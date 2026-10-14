"""Addressed libusb transport. Import/discovery never switches USB mode.

AOA mutations require a supplied admission gate. RecoveryGate retains the
historical independent-recovery requirement; P1 uses a separate explicitly
scoped network/manual admission. This module never resets/seizes a device, detaches drivers,
sets configuration, or stops adb. Transport chunks are not CarLife frames.
"""
from __future__ import annotations

import ctypes as C
import ctypes.util
import hashlib
from dataclasses import asdict, dataclass, field
from pathlib import Path
import threading
import time
from typing import Callable


class USBError(RuntimeError):
    def __init__(self, operation: str, code: int):
        self.operation, self.code = operation, code
        names = {-1: 'IO', -2: 'INVALID_PARAM', -3: 'ACCESS', -4: 'NO_DEVICE',
                 -5: 'NOT_FOUND', -6: 'BUSY', -7: 'TIMEOUT', -8: 'OVERFLOW',
                 -9: 'PIPE', -10: 'INTERRUPTED', -11: 'NO_MEM', -12: 'NOT_SUPPORTED'}
        super().__init__(f'{operation}: libusb {names.get(code, code)}')


class RecoveryBlocked(RuntimeError):
    pass


@dataclass(frozen=True)
class USBIdentity:
    bus: int
    ports: tuple[int, ...]
    vid: int
    pid: int
    address: int = 0
    serial_sha256: str = ''

    @property
    def physical_key(self) -> str:
        return f'{self.bus}:' + '.'.join(map(str, self.ports))


@dataclass(frozen=True)
class BulkPair:
    interface: int
    alternate: int
    endpoint_in: int
    endpoint_out: int
    max_packet_in: int
    max_packet_out: int


@dataclass(frozen=True)
class RecoveryGate:
    baseline_evidence: str = ''
    physical_key: str = ''
    oem_sequence_evidence: str = ''
    recovery_survives_adb_loss_evidence: str = ''
    heartbeat_deadline_cleanup_test_evidence: str = ''
    failure_boundaries: str = ''
    current_session_owner: str = ''
    verified: bool = False

    def require(self, identity: USBIdentity) -> None:
        required = (self.baseline_evidence, self.oem_sequence_evidence,
                    self.recovery_survives_adb_loss_evidence,
                    self.heartbeat_deadline_cleanup_test_evidence,
                    self.failure_boundaries)
        if (not self.verified or not all(required) or not identity.ports or
                self.physical_key != identity.physical_key or
                self.current_session_owner not in ('CarLife HU', 'DHU')):
            raise RecoveryBlocked('DIRECT_USB_AOA blocked: verified recovery independent of ADB loss, exact physical identity, and exclusive owner required')


class _DeviceDescriptor(C.Structure):
    _fields_ = [('bLength', C.c_uint8), ('bDescriptorType', C.c_uint8), ('bcdUSB', C.c_uint16),
                ('bDeviceClass', C.c_uint8), ('bDeviceSubClass', C.c_uint8), ('bDeviceProtocol', C.c_uint8),
                ('bMaxPacketSize0', C.c_uint8), ('idVendor', C.c_uint16), ('idProduct', C.c_uint16),
                ('bcdDevice', C.c_uint16), ('iManufacturer', C.c_uint8), ('iProduct', C.c_uint8),
                ('iSerialNumber', C.c_uint8), ('bNumConfigurations', C.c_uint8)]


class _Endpoint(C.Structure):
    _fields_ = [('bLength', C.c_uint8), ('bDescriptorType', C.c_uint8), ('bEndpointAddress', C.c_uint8),
                ('bmAttributes', C.c_uint8), ('wMaxPacketSize', C.c_uint16), ('bInterval', C.c_uint8),
                ('bRefresh', C.c_uint8), ('bSynchAddress', C.c_uint8), ('extra', C.c_void_p), ('extra_length', C.c_int)]


class _Alt(C.Structure):
    _fields_ = [('bLength', C.c_uint8), ('bDescriptorType', C.c_uint8), ('bInterfaceNumber', C.c_uint8),
                ('bAlternateSetting', C.c_uint8), ('bNumEndpoints', C.c_uint8), ('bInterfaceClass', C.c_uint8),
                ('bInterfaceSubClass', C.c_uint8), ('bInterfaceProtocol', C.c_uint8), ('iInterface', C.c_uint8),
                ('endpoint', C.POINTER(_Endpoint)), ('extra', C.c_void_p), ('extra_length', C.c_int)]


class _Interface(C.Structure):
    _fields_ = [('altsetting', C.POINTER(_Alt)), ('num_altsetting', C.c_int)]


class _Config(C.Structure):
    _fields_ = [('bLength', C.c_uint8), ('bDescriptorType', C.c_uint8), ('wTotalLength', C.c_uint16),
                ('bNumInterfaces', C.c_uint8), ('bConfigurationValue', C.c_uint8), ('iConfiguration', C.c_uint8),
                ('bmAttributes', C.c_uint8), ('MaxPower', C.c_uint8), ('interface', C.POINTER(_Interface)),
                ('extra', C.c_void_p), ('extra_length', C.c_int)]


def select_exact(devices: list[dict], identity: USBIdentity, *, reenumerated: bool = False) -> dict:
    if not identity.ports:
        raise ValueError('No physical port path: cannot safely select by mutable address/VID/PID alone')
    matches = []
    for item in devices:
        d = item['identity']
        if d['bus'] != identity.bus or tuple(d['ports']) != identity.ports:
            continue
        if reenumerated:
            if (d['vid'], d['pid']) not in ((0x18d1, 0x2d00), (0x18d1, 0x2d01)):
                continue
        elif (d['vid'], d['pid']) != (identity.vid, identity.pid):
            continue
        matches.append(item)
    if len(matches) != 1:
        raise ValueError(f'Expected exactly one selected physical device; found {len(matches)}')
    return matches[0]


def select_bulk_pair(device: dict) -> BulkPair:
    ident = device['identity']
    if (ident['vid'], ident['pid']) not in ((0x18d1, 0x2d00), (0x18d1, 0x2d01)):
        raise ValueError('Selected device is not an AOA accessory VID/PID')
    pairs = []
    for interface in device.get('interfaces', []):
        # AOA 1.0 interface 0 is accessory; interface 1 may be adb. Never claim adb.
        if interface['number'] != 0 or interface['alternate'] != 0:
            continue
        if (interface['class'], interface['subclass'], interface['protocol']) == (255, 66, 1):
            continue
        ins = [e for e in interface['endpoints'] if e['attributes'] & 3 == 2 and e['address'] & 0x80]
        outs = [e for e in interface['endpoints'] if e['attributes'] & 3 == 2 and not e['address'] & 0x80]
        if len(ins) == len(outs) == 1:
            pairs.append(BulkPair(0, 0, ins[0]['address'], outs[0]['address'], ins[0]['max_packet'], outs[0]['max_packet']))
    if len(pairs) != 1:
        raise ValueError('AOA accessory interface must expose one unambiguous bulk IN/OUT pair')
    return pairs[0]


def aoa_string(value: str) -> bytes:
    if '\0' in value:
        raise ValueError('Embedded NUL in AOA string')
    data = value.encode('utf-8') + b'\0'
    if len(data) > 256:
        raise ValueError('AOA string exceeds 256 bytes including NUL')
    return data


def write_all(transfer: Callable[[bytes, int], tuple[int, int]], data: bytes,
              cancel: threading.Event, deadline: float, chunk_size: int = 16384) -> int:
    """Continue partial writes, including transferred bytes reported on timeout."""
    if not 1 <= chunk_size <= 1024 * 1024:
        raise ValueError('Invalid write chunk size')
    offset = 0
    while offset < len(data):
        if cancel.is_set():
            raise InterruptedError('USB write cancelled')
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError(f'USB write deadline after {offset}/{len(data)} bytes')
        part = data[offset:offset + chunk_size]
        rc, transferred = transfer(part, max(1, min(200, int(remaining * 1000))))
        if not 0 <= transferred <= len(part):
            raise USBError('invalid transferred count', -1)
        offset += transferred
        if rc not in (0, -7):
            raise USBError(f'bulk write after {offset} bytes', rc)
        if rc == 0 and transferred == 0:
            raise USBError('bulk write made no progress', -1)
    return offset


class LibUSB:
    def __init__(self, library: str | None = None):
        path = library or ('/opt/homebrew/opt/libusb/lib/libusb-1.0.dylib' if Path('/opt/homebrew/opt/libusb/lib/libusb-1.0.dylib').exists() else C.util.find_library('usb-1.0'))
        if not path:
            raise RuntimeError('libusb 1.0 library unavailable')
        self.library = str(path)
        self.lib = C.CDLL(path)
        signatures = {
            'libusb_init': ([C.POINTER(C.c_void_p)], C.c_int),
            'libusb_exit': ([C.c_void_p], None),
            'libusb_get_device_list': ([C.c_void_p, C.POINTER(C.POINTER(C.c_void_p))], C.c_ssize_t),
            'libusb_free_device_list': ([C.POINTER(C.c_void_p), C.c_int], None),
            'libusb_get_device_descriptor': ([C.c_void_p, C.POINTER(_DeviceDescriptor)], C.c_int),
            'libusb_get_bus_number': ([C.c_void_p], C.c_uint8),
            'libusb_get_device_address': ([C.c_void_p], C.c_uint8),
            'libusb_get_port_numbers': ([C.c_void_p, C.POINTER(C.c_uint8), C.c_int], C.c_int),
            'libusb_get_active_config_descriptor': ([C.c_void_p, C.POINTER(C.POINTER(_Config))], C.c_int),
            'libusb_free_config_descriptor': ([C.POINTER(_Config)], None),
            'libusb_open': ([C.c_void_p, C.POINTER(C.c_void_p)], C.c_int),
            'libusb_close': ([C.c_void_p], None),
            'libusb_claim_interface': ([C.c_void_p, C.c_int], C.c_int),
            'libusb_release_interface': ([C.c_void_p, C.c_int], C.c_int),
            'libusb_control_transfer': ([C.c_void_p, C.c_uint8, C.c_uint8, C.c_uint16, C.c_uint16, C.POINTER(C.c_ubyte), C.c_uint16, C.c_uint], C.c_int),
            'libusb_get_string_descriptor_ascii': ([C.c_void_p, C.c_uint8, C.POINTER(C.c_ubyte), C.c_int], C.c_int),
            'libusb_bulk_transfer': ([C.c_void_p, C.c_ubyte, C.POINTER(C.c_ubyte), C.c_int, C.POINTER(C.c_int), C.c_uint], C.c_int),
        }
        for name, (args, result) in signatures.items():
            function = getattr(self.lib, name)
            function.argtypes, function.restype = args, result
        self.context = C.c_void_p()
        rc = self.lib.libusb_init(C.byref(self.context))
        if rc < 0:
            raise USBError('init', rc)

    def close(self):
        if self.context:
            self.lib.libusb_exit(self.context)
            self.context = C.c_void_p()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def _list(self):
        array = C.POINTER(C.c_void_p)()
        count = self.lib.libusb_get_device_list(self.context, C.byref(array))
        if count < 0:
            raise USBError('device list', count)
        return array, count

    def _describe(self, device):
        desc = _DeviceDescriptor()
        rc = self.lib.libusb_get_device_descriptor(device, C.byref(desc))
        if rc < 0:
            raise USBError('device descriptor', rc)
        ports = (C.c_uint8 * 16)()
        count = self.lib.libusb_get_port_numbers(device, ports, len(ports))
        ident = USBIdentity(self.lib.libusb_get_bus_number(device), tuple(ports[:max(0, count)]), desc.idVendor, desc.idProduct, self.lib.libusb_get_device_address(device))
        result = {'identity': asdict(ident), 'physical_key': ident.physical_key, 'usb_bcd': desc.bcdUSB, 'device_bcd': desc.bcdDevice,
                  'serial_index': desc.iSerialNumber, 'interfaces': []}
        config = C.POINTER(_Config)()
        rc = self.lib.libusb_get_active_config_descriptor(device, C.byref(config))
        if rc < 0:
            result['config_error'] = str(USBError('active config descriptor', rc))
            return result
        try:
            result['configuration'] = config.contents.bConfigurationValue
            for n in range(config.contents.bNumInterfaces):
                interface = config.contents.interface[n]
                for a in range(interface.num_altsetting):
                    alt = interface.altsetting[a]
                    item = {'number': alt.bInterfaceNumber, 'alternate': alt.bAlternateSetting, 'class': alt.bInterfaceClass, 'subclass': alt.bInterfaceSubClass, 'protocol': alt.bInterfaceProtocol, 'endpoints': []}
                    for e in range(alt.bNumEndpoints):
                        ep = alt.endpoint[e]
                        item['endpoints'].append({'address': ep.bEndpointAddress, 'attributes': ep.bmAttributes, 'max_packet': ep.wMaxPacketSize, 'interval': ep.bInterval})
                    result['interfaces'].append(item)
        finally:
            self.lib.libusb_free_config_descriptor(config)
        return result

    def discover(self) -> list[dict]:
        """Cached descriptors only: no open, claim, request51, reset or mode action."""
        array, count = self._list()
        try:
            return [self._describe(array[n]) for n in range(count)]
        finally:
            self.lib.libusb_free_device_list(array, 1)

    def open_exact(self, identity: USBIdentity):
        array, count = self._list()
        try:
            described = [self._describe(array[n]) for n in range(count)]
            selected = select_exact(described, identity)
            n = described.index(selected)
            handle = C.c_void_p()
            rc = self.lib.libusb_open(array[n], C.byref(handle))
            if rc < 0:
                raise USBError('open selected device', rc)
            opened = USBHandle(self, handle, identity, selected)
            if identity.serial_sha256:
                try:
                    if opened.serial_sha256() != identity.serial_sha256:
                        raise RecoveryBlocked('Selected physical USB device serial mismatch')
                except Exception:
                    opened.close()
                    raise
            return opened
        finally:
            self.lib.libusb_free_device_list(array, 1)

    def wait_accessory(self, original: USBIdentity, cancel: threading.Event, timeout: float = 10) -> dict:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if cancel.is_set():
                raise InterruptedError('AOA re-enumeration cancelled')
            try:
                return select_exact(self.discover(), original, reenumerated=True)
            except ValueError:
                cancel.wait(.1)
        raise TimeoutError('No accessory VID/PID at selected physical port; no automatic recovery action issued')


class USBHandle:
    def __init__(self, usb: LibUSB, handle, identity: USBIdentity, descriptor: dict):
        self.usb, self.handle, self.identity, self.descriptor = usb, handle, identity, descriptor
        self.pair = None
        self.cancel = threading.Event()
        self.read_lock, self.write_lock = threading.Lock(), threading.Lock()

    def close(self):
        self.cancel.set()
        # Synchronous transfers have <=200ms timeouts; wait for workers before closing.
        with self.read_lock, self.write_lock:
            if self.handle:
                if self.pair:
                    self.usb.lib.libusb_release_interface(self.handle, self.pair.interface)
                self.usb.lib.libusb_close(self.handle)
                self.handle = None
                self.pair = None

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def serial_sha256(self) -> str:
        """Read only the addressed USB serial descriptor; never expose the serial."""
        index = self.descriptor.get('serial_index', 0)
        if not index or not self.handle:
            raise RecoveryBlocked('Selected device has no readable USB serial descriptor')
        data = (C.c_ubyte * 256)()
        rc = self.usb.lib.libusb_get_string_descriptor_ascii(self.handle, index, data, len(data))
        if rc <= 0:
            raise USBError('read selected USB serial descriptor', rc)
        return hashlib.sha256(bytes(data[:rc])).hexdigest()

    def _control(self, request_type, request, index=0, data=b'', length=0, timeout_ms=500):
        if not self.handle:
            raise RuntimeError('USB handle closed')
        if not 1 <= timeout_ms <= 2000:
            raise ValueError('control timeout must be 1..2000ms')
        length = length or len(data)
        array = (C.c_ubyte * max(1, length))()
        if data:
            array[:len(data)] = data
        rc = self.usb.lib.libusb_control_transfer(self.handle, request_type, request, 0, index, array, length, timeout_ms)
        if rc < 0:
            raise USBError(f'control request {request}', rc)
        if rc != length:
            raise USBError(f'short control request {request}: {rc}/{length}', -1)
        return bytes(array[:rc])

    def get_protocol(self) -> int:
        """Explicit addressed AOA51 read, separate from state-changing 52/53."""
        return int.from_bytes(self._control(0xc0, 51, length=2), 'little')

    def start_accessory(self, strings: tuple[str, str, str, str, str, str], gate: RecoveryGate,
                        observer=None):
        gate.require(self.identity)
        if len(strings) != 6 or not strings[0] or not strings[1] or not strings[3]:
            raise ValueError('Six identification strings with manufacturer/model/version required')
        values = [aoa_string(s) for s in strings]  # Validate every string before any mutation.
        protocol = self.get_protocol()
        if observer:
            observer('aoa_request_complete', request=51, aoa_version=protocol)
        if protocol not in (1, 2):
            raise ValueError(f'Unsupported AOA protocol version {protocol}')
        for index, data in enumerate(values):
            self._control(0x40, 52, index=index, data=data)
            if observer:
                observer('aoa_request_complete', request=52, index=index, bytes=len(data))
        self._control(0x40, 53)
        if observer:
            observer('aoa_request_complete', request=53)
        return protocol

    def claim_accessory(self, gate: RecoveryGate):
        gate.require(self.identity)
        pair = select_bulk_pair(self.descriptor)
        if self.descriptor.get('configuration') != 1:
            raise RecoveryBlocked('Configuration is not 1; automatic SET_CONFIGURATION prohibited')
        rc = self.usb.lib.libusb_claim_interface(self.handle, pair.interface)
        if rc < 0:
            raise USBError('claim accessory interface (no detach/seize fallback)', rc)
        self.pair = pair

    def read(self, max_bytes: int = 1024 * 1024, timeout: float = 2) -> bytes:
        # Large bounded reads avoid tiny-reader loss; the stream parser retains
        # framing. Overflow is fatal, never silently discarded.
        if max_bytes < 16384 or max_bytes > 4 * 1024 * 1024 or max_bytes % 512:
            raise ValueError('USB read capacity must be 16KiB..4MiB and a multiple of 512')
        if not self.pair:
            raise RuntimeError('Accessory interface not claimed')
        deadline = time.monotonic() + timeout
        with self.read_lock:
            while not self.cancel.is_set():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError('USB read deadline')
                array, transferred = (C.c_ubyte * max_bytes)(), C.c_int()
                rc = self.usb.lib.libusb_bulk_transfer(self.handle, self.pair.endpoint_in, array, max_bytes, C.byref(transferred), max(1, min(200, int(remaining * 1000))))
                if rc not in (0, -7):
                    raise USBError('bulk read', rc)
                if not 0 <= transferred.value <= max_bytes:
                    raise USBError('bulk read invalid transferred count', -1)
                if transferred.value:
                    return bytes(array[:transferred.value])
            raise InterruptedError('USB read cancelled')

    def write(self, data: bytes, timeout: float = 2) -> int:
        if not self.pair:
            raise RuntimeError('Accessory interface not claimed')
        if len(data) > 4 * 1024 * 1024:
            raise ValueError('Write exceeds 4MiB bounded buffer')
        def transfer(part, timeout_ms):
            array = (C.c_ubyte * len(part)).from_buffer_copy(part)
            transferred = C.c_int()
            rc = self.usb.lib.libusb_bulk_transfer(self.handle, self.pair.endpoint_out, array, len(part), C.byref(transferred), timeout_ms)
            return rc, transferred.value
        with self.write_lock:
            return write_all(transfer, data, self.cancel, time.monotonic() + timeout)
