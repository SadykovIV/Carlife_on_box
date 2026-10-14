"""Native CarLife USB adapter for the existing HU session, decoder and input.

Admission is explicit for either a reviewed P1 transaction or a P2 Magisk boot
attach with owner-confirmed manual recovery. Neither scope sets
RecoveryGate.verified or asserts automatic recovery or successful boot attach.
No ADB, forwarding, device shell, driver detach, reset or SET_CONFIGURATION.
"""
from dataclasses import asdict, dataclass
from collections import deque
import fcntl
import hashlib
import json
import os
from pathlib import Path
import socket
import threading
import time

from .usb import LibUSB, RecoveryBlocked, USBIdentity, select_exact

ACCESSORY_IDS = ((0x18d1, 0x2d00), (0x18d1, 0x2d01))
DEFAULT_STRINGS = ("Mac HU Lab", "CarLife HU", "CarLife video/input engineering pilot",
                   "1.0", "", "MacHULab-P1")


@dataclass(frozen=True)
class ScopedUSBAdmission:
    """Hashed operator admission, not a runtime/boot success assertion.

    P2 evidence mapping uses baseline_evidence for the installed module,
    transition_evidence for armed selection plus proven manual runtime, and
    rollback_evidence for controlled return/manual recovery. The legacy field
    network_adb_evidence records historical pre-boot access for P2; it does not
    claim that Wi-Fi ADB is live after reboot. P1 retains its existing contract.
    Callers must supply the P2 scope/kind pair explicitly; defaults stay P1.
    """
    physical_key: str
    usb_serial_sha256: str
    issued_unix: float
    expires_unix: float
    baseline_evidence: dict
    network_adb_evidence: dict
    transition_evidence: dict
    rollback_evidence: dict
    manual_recovery_route: str
    failure_boundaries: str
    scope: str = "P1_NATIVE_CARLIFE"
    recovery_kind: str = "OWNER_DECLARED_MANUAL_RECOVERY_WITH_LIVE_NETWORK_ADB"
    current_session_owner: str = "CarLife HU"

    def require(self, identity):
        now = time.time()
        allowed_contracts = (
            ("P1_NATIVE_CARLIFE", "OWNER_DECLARED_MANUAL_RECOVERY_WITH_LIVE_NETWORK_ADB"),
            ("P2_MAGISK_BOOT_ATTACH", "OWNER_CONFIRMED_MANUAL_RECOVERY_WITH_ARMED_MAGISK_RUNTIME"),
            ("BOOT_NATIVE_ATTACH", "OWNER_CONFIRMED_MANUAL_RECOVERY_WITH_EARLY_INIT_RUNTIME"),
        )
        if ((self.scope, self.recovery_kind) not in allowed_contracts or
            self.current_session_owner != "CarLife HU" or
            not self.issued_unix <= now <= self.expires_unix or
            not 0 < self.expires_unix - self.issued_unix <= 7200 or
            not identity.ports or self.physical_key != identity.physical_key or
            len(self.usb_serial_sha256) != 64 or self.usb_serial_sha256 != identity.serial_sha256 or
            not self.manual_recovery_route or not self.failure_boundaries):
            raise RecoveryBlocked("P1 transaction admission invalid, expired or mismatched; no USB action")
        for ref in (self.baseline_evidence, self.network_adb_evidence,
                    self.transition_evidence, self.rollback_evidence):
            try:
                path = Path(ref["path"])
                if not path.is_absolute() or not path.is_file() or path.stat().st_size > 16 * 1024 * 1024:
                    raise ValueError("evidence must be a bounded absolute file")
                if hashlib.sha256(path.read_bytes()).hexdigest() != ref["sha256"]:
                    raise ValueError("evidence hash mismatch")
            except (KeyError, OSError, TypeError, ValueError) as error:
                raise RecoveryBlocked(f"P1 admission evidence invalid: {error}") from error

    @classmethod
    def load(cls, path):
        return cls(**json.loads(Path(path).read_text()))


class USBStream:
    """Socket-like bounded byte stream; there is no socket or proxy underneath."""
    def __init__(self, usb, handle, owner_lock, evidence, observer=None):
        self.usb, self.handle, self.owner_lock = usb, handle, owner_lock
        self.evidence = evidence
        self.timeout = .5
        self.closed = False
        self.close_lock = threading.Lock()
        self.send_lock = threading.Lock()
        self.first_write_complete = False
        self.write_failed = False
        self.observer = observer
        self.rx_bytes = self.tx_bytes = 0
        self.pending = bytearray()

    def settimeout(self, timeout):
        if not .01 <= timeout <= 10:
            raise ValueError("USB stream timeout must be 0.01..10 seconds")
        self.timeout = timeout

    def recv(self, size):
        if self.closed:
            return b""
        if not 1 <= size <= 4 * 1024 * 1024:
            raise ValueError("USB stream recv capacity outside 1..4MiB")
        if self.pending:
            data = bytes(self.pending[:size])
            del self.pending[:size]
            return data
        # USB bulk capacities must be max-packet aligned. Never trim a transfer.
        capacity = max(16384, ((size + 511) // 512) * 512)
        try:
            data = self.handle.read(capacity, self.timeout)
        except TimeoutError as error:
            raise socket.timeout(str(error)) from error
        self.rx_bytes += len(data)
        self.pending.extend(data[size:])
        return data[:size]

    def sendall(self, data):
        with self.send_lock:
            if self.closed or self.write_failed:
                raise OSError("USB stream closed or failed; write replay prohibited")
            if not data:
                return
            first = not self.first_write_complete
            # The gadget can enumerate before the MD queues its first read.
            # Keep two seconds for VERSION_MATCH inside the existing absolute
            # eight-second WAIT_VERSION bound; later commands retain two seconds.
            budget = 6 if first else 2
            started = time.monotonic_ns()
            if first and self.observer:
                self.observer("usb_first_write_begin", bytes=len(data), deadline_seconds=budget,
                              payload_sha256=hashlib.sha256(data).hexdigest(),
                              wait_version_deadline_seconds=8, subsequent_write_seconds=2)
            try:
                written = self.handle.write(data, budget)
                if written != len(data):
                    raise OSError(f"short USB stream write {written}/{len(data)}")
            except Exception as error:
                # A timeout may have sent a prefix. Never retry this logical
                # write or grant another startup window on this stream.
                self.write_failed = True
                self.handle.cancel.set()
                if first and self.observer:
                    self.observer("usb_first_write_failed", bytes=len(data), deadline_seconds=budget,
                                  elapsed_ms=(time.monotonic_ns()-started)/1e6,
                                  error=f"{type(error).__name__}: {error}", replay=False)
                raise
            self.tx_bytes += written
            self.first_write_complete = True
            if first and self.observer:
                self.observer("usb_first_write_complete", bytes=written, deadline_seconds=budget,
                              elapsed_ms=(time.monotonic_ns()-started)/1e6)

    def shutdown(self, _how=socket.SHUT_RDWR):
        self.handle.cancel.set()

    def close(self):
        with self.close_lock:
            if self.closed:
                return
            self.closed = True
            self.pending.clear()
            try:
                self.handle.close()
            finally:
                try:
                    self.usb.close()
                finally:
                    fcntl.flock(self.owner_lock.fileno(), fcntl.LOCK_UN)
                    self.owner_lock.close()


class NativeUSBTransportFactory:
    """Exact device acquisition, at most one AOA START, then accessory reconnect.

    An already-accessory acquisition is reported separately and is not proof
    that a normal host-initiated transition succeeded.
    """
    def __init__(self, identity, admission, output_dir, *, allow_aoa_start=False,
                 strings=DEFAULT_STRINGS, reenumeration_timeout=15, usb_factory=LibUSB):
        self.identity, self.admission = identity, admission
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.allow_aoa_start, self.strings = allow_aoa_start, tuple(strings)
        if not 1 <= reenumeration_timeout <= 60:
            raise ValueError("re-enumeration timeout must be 1..60 seconds")
        self.reenumeration_timeout = reenumeration_timeout
        self.usb_factory = usb_factory
        self.start_requested = False
        self.invocations = 0
        self.cancel = threading.Event()
        self.events = deque(maxlen=256)
        self.event_bytes = 0

    def event(self, stage, **details):
        event = {"unix": time.time(), "monotonic_ns": time.monotonic_ns(), "stage": stage, **details}
        self.events.append(event)
        line = json.dumps(event) + "\n"
        if self.event_bytes + len(line.encode()) <= 1024 * 1024:
            with (self.output_dir / "native-transport.jsonl").open("a") as log:
                log.write(line)
            self.event_bytes += len(line.encode())

    def _record_start_intent(self):
        # Root's transition evidence is the unique armed-result record, including
        # this trial's nonce and guard identity. Keep intent beside that record,
        # not in a per-GUI output: a restarted HU cannot replay the same trial.
        ref = self.admission.transition_evidence
        key = self.identity.physical_key.replace(":", "-")
        path = Path(ref["path"]).parent / f".native-aoa-start-{key}-{ref['sha256']}.json"
        record = {"physical_key": self.identity.physical_key,
                  "usb_serial_sha256": self.identity.serial_sha256,
                  "transition_evidence": ref, "requested_unix": time.time(),
                  "pid": os.getpid(), "state": "INTENT_BEFORE_FIRST_AOA_REQUEST_NO_AUTOMATIC_REPLAY"}
        try:
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        except FileExistsError as error:
            raise RecoveryBlocked("AOA START intent already exists for this armed trial; no replay after unknown outcome") from error
        with os.fdopen(fd, "w") as handle:
            json.dump(record, handle, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        self.event("aoa_start_intent_durable", path=str(path), transition_sha256=ref["sha256"])

    def __call__(self):
        self.admission.require(self.identity)
        # A shared physical-port lock survives address/VID/PID changes.
        key = self.identity.physical_key.replace(":", "-")
        owner = open(f"/tmp/carlife-usb-{key}.lock", "a+")
        usb = handle = None
        try:
            try:
                fcntl.flock(owner.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise RecoveryBlocked("Selected USB port already owned by another CarLife HU process") from error
            owner.seek(0)
            owner.truncate()
            owner.write(json.dumps({"pid": os.getpid(), "physical_key": self.identity.physical_key,
                                    "owner": "CarLife HU", "scope": self.admission.scope}))
            owner.flush()
            self.invocations += 1
            usb = self.usb_factory()
            devices = usb.discover()
            try:
                selected = select_exact(devices, self.identity)
            except ValueError:
                selected = select_exact(devices, self.identity, reenumerated=True)
            selected_identity = USBIdentity(**{**selected["identity"],
                "ports": tuple(selected["identity"]["ports"]), "serial_sha256": self.identity.serial_sha256})
            self.admission.require(selected_identity)
            handle = usb.open_exact(selected_identity)  # Verifies actual serial before any 51/52/53 or claim.
            self.event("selected_serial_verified", identity=asdict(selected_identity), invocation=self.invocations)
            started_here = False
            protocol = None
            if (selected_identity.vid, selected_identity.pid) not in ACCESSORY_IDS:
                if not self.allow_aoa_start or self.start_requested:
                    raise RecoveryBlocked("No repeat AOA START; new normal-state transition needs a new reviewed transaction")
                self._record_start_intent()
                self.start_requested = True  # Unknown outcome must never replay START automatically.
                self.event("aoa_start_requested", strings=self.strings, requests=[51, 52, 52, 52, 52, 52, 52, 53])
                protocol = handle.start_accessory(self.strings, self.admission, observer=self.event)
                self.event("aoa_control_complete", aoa_version=protocol)
                handle.close()
                handle = None
                selected = usb.wait_accessory(self.identity, self.cancel, self.reenumeration_timeout)
                selected_identity = USBIdentity(**{**selected["identity"],
                    "ports": tuple(selected["identity"]["ports"]), "serial_sha256": self.identity.serial_sha256})
                handle = usb.open_exact(selected_identity)
                self.event("accessory_reenumerated_serial_verified", identity=asdict(selected_identity))
                started_here = True
            handle.claim_accessory(self.admission)
            evidence = {"backend": "DIRECT_USB_AOA", "identity": asdict(selected_identity),
                        "bulk_pair": asdict(handle.pair), "aoa_version": protocol,
                        "entry": "HOST_INITIATED_51_52_53" if started_here else "ALREADY_ACCESSORY",
                        "admission_scope": self.admission.scope, "recovery_kind": self.admission.recovery_kind,
                        "universal_recovery_verified": False, "adb_data_plane": False}
            self.event("accessory_claimed", **evidence)
            return USBStream(usb, handle, owner, evidence, observer=self.event)
        except Exception as error:
            self.event("acquisition_failed", error=f"{type(error).__name__}: {error}",
                       aoa_start_requested=self.start_requested, automatic_usb_rollback=False)
            if handle is not None:
                handle.close()
            if usb is not None:
                usb.close()
            owner.close()
            raise
