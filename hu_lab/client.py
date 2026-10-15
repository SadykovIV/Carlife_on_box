"""Threaded CarLife HU over an injected byte stream or development socket.

This module never invokes ADB. Media originates and is packetized on Android.
Raw traces are bounded and local; counters distinguish chunks, access units,
decoded frames, host arrival and peer timestamps (the latter are not PTS).
"""
from collections import deque, Counter, OrderedDict
from contextlib import contextmanager, nullcontext
from dataclasses import dataclass
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import queue
import re
import socket
import threading
import time
import traceback
import uuid

from .protocol import *
from .audio import AudioReceiver
from .touch import TouchFrame, Pointer, TouchTracker, encode_touch, profile_name, two_pointer_gesture, CANCEL
from .geometry import ViewportTransform, SourceDisplayTransform, Rect


@dataclass(frozen=True)
class MediaChunk:
    generation: int
    data: bytes
    complete_au: bool = False
    pts_ms: int | None = None
    received_ns: int | None = None
    au_index: int | None = None

    def __len__(self):
        return len(self.data)


def annexb_nals(data):
    markers = list(re.finditer(b"\x00\x00(?:\x00)?\x01", data))
    if not markers or markers[0].start() != 0:
        raise ProtocolError("explicit Annex B AU has missing/leading non-start-code bytes")
    result = []
    for index, marker in enumerate(markers):
        end = markers[index + 1].start() if index + 1 < len(markers) else len(data)
        if marker.end() >= end or data[marker.end()] & 0x80:
            raise ProtocolError("invalid Annex B NAL")
        result.append((data[marker.end()] & 31, bytes(data[marker.end() + 1:end])))
    return result


def percentile(values, fraction):
    values = sorted(values)
    return values[min(len(values) - 1, int((len(values) - 1) * fraction))] if values else None


def timing_stats(values):
    return {"count": len(values), "min_ms": min(values) if values else None,
            "p50_ms": percentile(values, .5), "p95_ms": percentile(values, .95),
            "p99_ms": percentile(values, .99), "max_ms": max(values) if values else None}


class ReferenceStartGate:
    """Buffer fragmented Annex B until complete SPS/PPS plus an IDR start.

    Never pass a dependent slice from the prior encoder into a new decoder.
    NAL boundaries are used only for parameter sets/IDR detection, not frames.
    """
    def __init__(self, maximum=4 * 1024 * 1024):
        self.buffer = bytearray()
        self.maximum = maximum
        self.ready = False

    def feed(self, data):
        if self.ready:
            return data
        self.buffer.extend(data)
        if len(self.buffer) > self.maximum:
            raise ProtocolError("fresh SPS/PPS/IDR sequence not found within bound")
        markers = list(re.finditer(b"\x00\x00(?:\x00)?\x01", self.buffer))
        sps = pps = None
        for index, marker in enumerate(markers):
            if marker.end() >= len(self.buffer):
                continue
            kind = self.buffer[marker.end()] & 31
            end = markers[index + 1].start() if index + 1 < len(markers) else None
            if kind == 7 and end is not None:
                sps = bytes(self.buffer[marker.start():end])
                pps = None
            elif kind == 8 and end is not None and sps is not None:
                pps = bytes(self.buffer[marker.start():end])
            elif kind == 5 and sps is not None and pps is not None:
                result = sps + pps + bytes(self.buffer[marker.start():])
                self.buffer.clear()
                self.ready = True
                return result
        return None


class MediaQueueError(ProtocolError):
    def __init__(self, message, evidence):
        super().__init__(message)
        self.evidence = evidence


class BoundedMedia:
    def __init__(self, max_bytes=4 * 1024 * 1024, max_age=1.5):
        self.maximum, self.max_age = max_bytes, max_age
        self.items = deque()
        self.size = 0
        self.condition = threading.Condition()
        self.closed = False
        self.high_water_bytes = self.high_water_items = 0
        self.high_water_age_ms = 0
        self.last_put_ns = self.last_get_ns = None

    def put(self, item):
        now = time.monotonic()
        with self.condition:
            if self.closed:
                return
            byte_limit = self.size + len(item) > self.maximum
            age_limit = bool(self.items and now - self.items[0][0] > self.max_age)
            if byte_limit or age_limit:
                raise MediaQueueError("MEDIA_BACKPRESSURE: reset/reconnect required before decoding dependents",
                    {**self.snapshot(now), "reason": "bytes" if byte_limit else "age",
                     "incoming_bytes": len(item), "incoming_generation": getattr(item, "generation", None)})
            self.items.append((now, item))
            self.size += len(item)
            self.high_water_bytes = max(self.high_water_bytes, self.size)
            self.high_water_items = max(self.high_water_items, len(self.items))
            self.last_put_ns = time.monotonic_ns()
            self.condition.notify()

    def get(self):
        with self.condition:
            while not self.items and not self.closed:
                self.condition.wait(.2)
            if not self.items:
                return None
            timestamp, item = self.items.popleft()
            self.size -= len(item)
            self.last_get_ns = time.monotonic_ns()
            self.high_water_age_ms = max(self.high_water_age_ms, (time.monotonic() - timestamp) * 1000)
            if time.monotonic() - timestamp > self.max_age:
                raise MediaQueueError("MEDIA_STALL: stale reference sequence requires reconnect",
                    {**self.snapshot(), "reason": "dequeued_age",
                     "dequeued_age_ms": (time.monotonic() - timestamp) * 1000,
                     "dequeued_generation": getattr(item, "generation", None)})
            return item

    def snapshot(self, now=None):
        with self.condition:
            now = time.monotonic() if now is None else now
            self.high_water_age_ms = max(self.high_water_age_ms, (now - self.items[0][0]) * 1000 if self.items else 0)
            return {"queued_bytes": self.size, "queued_items": len(self.items),
                    "oldest_age_ms": (now - self.items[0][0]) * 1000 if self.items else 0,
                    "maximum_bytes": self.maximum, "maximum_age_ms": self.max_age * 1000,
                    "closed": self.closed, "high_water_bytes": self.high_water_bytes,
                    "high_water_items": self.high_water_items, "high_water_age_ms": self.high_water_age_ms,
                    "last_put_ns": self.last_put_ns, "last_get_ns": self.last_get_ns}

    def close(self):
        with self.condition:
            self.closed = True
            self.condition.notify_all()

    def clear(self):
        with self.condition:
            removed = self.size
            self.items.clear()
            self.size = 0
            return removed


class Trace:
    """Bounded asynchronous evidence IO; saturation never stalls CarLife."""
    def __init__(self, directory, maximum=128 * 1024 * 1024, mode="full"):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.logfile = (self.directory / "trace.jsonl").open("w")
        self.raw = {direction: (self.directory / (direction + ".bin")).open("wb") for direction in ("rx", "tx")}
        self.video_file = (self.directory / "video.h264").open("wb")
        self.bytes = {"rx": 0, "tx": 0}
        self.reserved = {"rx": 0, "tx": 0}
        self.maximum, self.mode = maximum, mode
        self.started = time.monotonic()
        self.event_counts, self.omitted = Counter(), Counter()
        self.raw_truncated = set()
        self.json_bytes = self.json_written_bytes = self.video_written_bytes = 0
        self.lock = threading.RLock()
        self.condition = threading.Condition(self.lock)
        self.jobs = deque()
        self.queued_bytes = self.high_water_bytes = self.high_water_items = 0
        self.queue_limit = 1024 * 1024
        self.item_limit = 1024
        self.active_io = None
        self.high_water_age_ms = 0
        self.last_progress_ns = None
        self.io_error = None
        self.closed = False
        self.writer = threading.Thread(target=self._write_loop, name="carlife-diagnostics", daemon=True)
        self.writer.start()

    def _enqueue(self, kind, data):
        size = len(data.encode()) if isinstance(data, str) else len(data)
        if self.closed or self.io_error or self.queued_bytes + size > self.queue_limit or len(self.jobs) >= self.item_limit:
            return False
        self.jobs.append((time.monotonic_ns(), kind, data, size))
        self.queued_bytes += size
        self.high_water_bytes = max(self.high_water_bytes, self.queued_bytes)
        self.high_water_items = max(self.high_water_items, len(self.jobs))
        self.condition.notify()
        return True

    def event(self, event, **kwargs):
        # Capture caller time before enqueue, never substitute disk-writer time.
        now_ns = time.monotonic_ns()
        with self.condition:
            if self.closed:
                return
            self.event_counts[event] += 1
            hot = (event == "packet" and kwargs.get("direction") != "tx") or event in (
                "received_access_unit", "decoded_frame", "qt_frame_painted")
            if self.mode == "sampled" and hot and time.monotonic() - self.started > 5 and self.event_counts[event] % 120:
                self.omitted[event] += 1
                return
            line = json.dumps({"monotonic_ns": now_ns, "event": event, **kwargs}) + "\n"
            size = len(line.encode())
            if (self.json_bytes + size > min(self.maximum // 4, 4 * 1024 * 1024) and event != "stopped") or not self._enqueue("json", line):
                self.omitted[event] += 1
                return
            self.json_bytes += size

    def wire(self, direction, wire):
        with self.condition:
            if direction in self.raw_truncated:
                return
            if self.reserved[direction] + len(wire) > self.maximum // 4 or not self._enqueue(direction, wire):
                self.raw_truncated.add(direction)
                return
            self.reserved[direction] += len(wire)

    def video(self, data):
        with self.condition:
            return self._enqueue("video", data)

    def packet(self, direction, packet):
        self.event("packet", direction=direction, channel=packet.channel,
                   message=f"0x{packet.message:08x}", size=len(packet.payload), timestamp=packet.timestamp,
                   payload_sha256=hashlib.sha256(packet.payload).hexdigest())

    def _write_loop(self):
        try:
            while True:
                with self.condition:
                    while not self.jobs and not self.closed:
                        self.condition.wait(.2)
                    if not self.jobs:
                        break
                    queued_ns, kind, data, size = self.jobs.popleft()
                    self.queued_bytes -= size
                    self.high_water_age_ms = max(self.high_water_age_ms, (time.monotonic_ns() - queued_ns) / 1e6)
                    self.active_io = {"kind": kind, "started_ns": time.monotonic_ns(), "queued_ns": queued_ns, "bytes": size}
                # All filesystem calls are outside the shared trace/transport locks.
                target = self.logfile if kind == "json" else self.video_file if kind == "video" else self.raw[kind]
                target.write(data)
                if kind == "json":
                    target.flush()
                with self.condition:
                    if kind == "json": self.json_written_bytes += size
                    elif kind == "video": self.video_written_bytes += size
                    else: self.bytes[kind] += size
                    self.last_progress_ns = time.monotonic_ns()
                    self.active_io = None
        except Exception as error:
            with self.condition:
                self.io_error = f"{type(error).__name__}: {error}"
        finally:
            for handle in [self.logfile, self.video_file, *self.raw.values()]:
                try: handle.close()
                except Exception as error:
                    self.io_error = self.io_error or str(error)

    def progress_snapshot(self):
        with self.condition:
            now = time.monotonic_ns()
            return {"queued_bytes": self.queued_bytes, "queued_items": len(self.jobs),
                    "oldest_age_ms": (now - self.jobs[0][0]) / 1e6 if self.jobs else 0,
                    "high_water_bytes": self.high_water_bytes, "high_water_items": self.high_water_items,
                    "high_water_age_ms": max(self.high_water_age_ms, (now-self.jobs[0][0])/1e6 if self.jobs else 0),
                    "maximum_bytes": self.queue_limit, "maximum_items": self.item_limit,
                    "active_io": dict(self.active_io) if self.active_io else None,
                    "last_progress_ns": self.last_progress_ns, "io_error": self.io_error,
                    "writer_alive": self.writer.is_alive()}

    def close(self):
        with self.condition:
            self.closed = True
            self.condition.notify_all()
        self.writer.join(timeout=3)

    def summary(self):
        with self.condition:
            return {"mode": self.mode, "combined_capture_budget_bytes": self.maximum,
                    "retained_wire_bytes": self.bytes.copy(), "truncated_prefixes": sorted(self.raw_truncated),
                    "trace_json_bytes": self.json_bytes, "trace_json_written_bytes": self.json_written_bytes,
                    "video_written_bytes": self.video_written_bytes,
                    "event_counts": dict(self.event_counts), "omitted_events": dict(self.omitted),
                    "diagnostic_io": self.progress_snapshot(),
                    "retention_note": "one quarter each for rx/tx/video/JSON, plus bounded terminal summary; diagnostic queue saturation drops evidence only; counters cover entire session"}


class HUClient:
    backend = "DEV_STREAM_OVER_ADB"

    def __init__(self, host, port, output_dir, width=1280, height=720, fps=30, timeout=8,
                 touch_profile="legacy-single", capture_limit_mib=None, trace_mode="full",
                 display_policy="fit", rotation=0, source_size=None, source_policy="fit",
                 allow_mode_fallback=False, on_decoded_frame=None,
                 transport_factory=None, backend="DEV_STREAM_OVER_ADB",
                 audio_capture=False, audio_capture_limit_mib=8):
        if backend not in ("DEV_STREAM_OVER_ADB", "DIRECT_USB_AOA"):
            raise ValueError("unsupported transport backend")
        if (backend == "DIRECT_USB_AOA") != (transport_factory is not None):
            raise ValueError("native USB requires an explicit transport factory")
        if transport_factory is None and host not in ("127.0.0.1", "localhost", "::1"):
            raise ValueError("development transport must remain loopback")
        self.backend, self.transport_factory = backend, transport_factory
        self.io_stage = "usb_bulk" if backend == "DIRECT_USB_AOA" else "socket"
        self.host, self.port, self.timeout = host, (0 if transport_factory is not None else int(port)), timeout
        self.output_dir = Path(output_dir)
        self.session = HUSession(width, height, fps, allow_mode_fallback=allow_mode_fallback)
        self.touch_profile = profile_name(touch_profile)
        self.touch_tracker = TouchTracker()
        self.display_policy, self.rotation = display_policy, rotation
        self.source_size, self.source_policy = source_size, source_policy
        self.on_decoded_frame = on_decoded_frame
        self.capture_limit = int((capture_limit_mib if capture_limit_mib is not None else 128) * 1024 * 1024)
        if self.capture_limit < 1024 or trace_mode not in ("full", "sampled"):
            raise ValueError("invalid capture budget or trace mode")
        if audio_capture and audio_capture_limit_mib <= 0:
            raise ValueError("invalid audio capture budget")
        self.session_id = uuid.uuid4().hex
        self.trace = Trace(output_dir, self.capture_limit, trace_mode)
        self.audio = AudioReceiver(event=self.trace.event,
            capture_directory=self.output_dir / "audio" if audio_capture else None,
            capture_limit_bytes=int(audio_capture_limit_mib * 1024 * 1024))
        self.video_retained_bytes = 0
        self.video_capture_truncated = False
        self.trace.event("configuration", session_id=self.session_id, backend=self.backend, profile=self.session.profile,
                         interoperability="SELF", requested_width=width, requested_height=height,
                         requested_fps=fps, touch_profile=self.touch_profile, touch_profile_provenance="explicit_SELF_override",
                         timestamp_semantics="pending MD source negotiation; bootstrap default unless explicit AU marker")
        self.commands = queue.Queue(maxsize=256)
        self.media = BoundedMedia()
        self.halt = threading.Event()
        self.threads = []
        self.sock = None
        self.error = None
        self.failure_lock = threading.Lock()
        self.start_time = None
        self.end_time = None
        self.decoded_frames = 0
        self.access_units = 0
        self.video_chunks = 0
        self.video_bytes = 0
        self.input_packets = 0
        self.audio_packets = {"MEDIA": 0, "TTS": 0, "VR": 0}
        self.frame_times = deque(maxlen=4096)
        self.first_frame_time = self.last_frame_time = None
        self.frame_hashes = set()
        self.frame_hashes_saturated = False
        self.last_pixel_hash = None
        self.consecutive_identical_frames = 0
        self.frames_painted = 0
        self.latest_frame = None
        self.frame_lock = threading.Lock()
        self.stopped = False
        self.decoder_generation = 0
        self.stream_generation = 0
        self.pending_touch = False
        self.last_touch_time = 0
        self.dimensions = None
        self.sps = None
        self.sps_validation = {"status": "NOT_YET_SEEN", "decoder_policy": "permissive diagnostic decode"}
        self._sps_prefix = bytearray()
        self.state_lock = threading.RLock()
        self.reference_gate = ReferenceStartGate()
        self.old_generation_chunks_dropped = 0
        self.generation_frames = {}
        self.video_packetization = "ANNEXB_STREAM_CHUNKS"
        self.source_name = "BootstrapDisplaySource"
        self.timestamp_semantics = "Android uptime at bootstrap chunk arrival, NOT encoder PTS"
        self.pts_epochs = {}
        self.au_records = deque(maxlen=4096)
        self.total_aus = 0
        self.pts_aggregates = {}
        self.frame_records = OrderedDict()
        self.timing_samples = {name: deque(maxlen=2048) for name in ("receive_to_decode", "receive_to_qt_paint", "decode_to_qt_paint")}
        self.timing_totals = {name: {"count": 0, "sum_ms": 0, "min_ms": None, "max_ms": None} for name in self.timing_samples}
        self.metrics_lock = threading.RLock()
        self.pipeline_stages = {}
        self.pipeline_samples = {}
        self.pipeline_totals = {}
        self.png_condition = threading.Condition()
        self.png_pending = {}
        self.png_closed = False
        self.png_thread = None
        self.png_counts = Counter()
        self.png_high_water_bytes = self.png_high_water_items = 0
        self.png_high_water_age_ms = 0
        self.first_frame_before_eof = None
        self.peer_eof_seen = False
        self.progress_counts = Counter()
        self.last_progress_ns = {}
        self.progress_history = deque(maxlen=65)
        self.last_snapshot_ns = 0
        self.framing_pending_bytes = 0
        self.command_high_water_items = 0
        self.native_handshake_state = None
        self.native_handshake_since = None
        self.native_stale_media_packets = 0
        self.native_stale_media_bytes = 0
        self.native_drain_since = None
        self.native_rx_quiescent = threading.Event()
        self.native_shutdown_drain = None

    def _check_native_handshake_deadline(self):
        if self.backend != "DIRECT_USB_AOA":
            return
        state = self.session.state if self.session.state in ("WAIT_VERSION", "WAIT_INIT") else None
        now = time.monotonic()
        if state != self.native_handshake_state:
            self.native_handshake_state, self.native_handshake_since = state, now if state else None
        elif state and now - self.native_handshake_since > self.timeout:
            raise ProtocolError("native USB absolute handshake timeout in " + state)

    def _progress(self, name, amount=1):
        self.progress_counts[name] += amount
        self.last_progress_ns[name] = time.monotonic_ns()

    def progress_snapshot(self, record=True):
        """Call about once a second; no state lock, no filesystem IO on caller.

        Host-local session_id is diagnostic metadata, never a wire extension.
        The ring covers at most 65 snapshots; timing clocks stay local to Mac.
        """
        now = time.monotonic_ns()
        with self.png_condition:
            pending = list(self.png_pending.values())
        latest = self.latest_frame
        snapshot = {"host_monotonic_ns": now, "session_id": self.session_id,
                    "generation": self.decoder_generation, "state": self.session.state, "error": self.error,
                    "counts": dict(self.progress_counts), "last_progress_ns": dict(self.last_progress_ns),
                    "last_progress_age_ms": {k: (now-v)/1e6 for k,v in self.last_progress_ns.copy().items()},
                    "framing_pending_bytes": self.framing_pending_bytes,
                    "received_aus": self.total_aus, "decoded_frames": self.decoded_frames,
                    "latest_frame_index": latest[-1] if latest else None,
                    "painted_frames": self.frames_painted, "media_queue": self.media.snapshot(),
                    "command_queue": {"items": self.commands.qsize(), "maximum_items": self.commands.maxsize,
                                      "high_water_items": self.command_high_water_items},
                    "png_queue": {"items": len(pending), "maximum_pending_items": 2,
                                  "bytes_estimate": sum(v[0].width*v[0].height*3 for v in pending),
                                  "oldest_age_ms": max([(now-v[3])/1e6 for v in pending] or [0]),
                                  "high_water_bytes_estimate": self.png_high_water_bytes,
                                  "high_water_items": self.png_high_water_items,
                                  "high_water_age_ms": max(self.png_high_water_age_ms, max([(now-v[3])/1e6 for v in pending] or [0])),
                                  "counts": dict(self.png_counts)},
                    "active_pipeline_stages": {k: {**v, "elapsed_ms": (now-v["started_ns"])/1e6}
                                               for k,v in self.pipeline_stages.copy().items()},
                    "logging": self.trace.progress_snapshot()}
        if record and now - self.last_snapshot_ns >= 1_000_000_000:
            self.last_snapshot_ns = now
            self.progress_history.append(snapshot)
            self.trace.event("progress_snapshot", snapshot=snapshot)
        return snapshot

    def start(self):
        self.sock = (self.transport_factory() if self.transport_factory is not None else
                     socket.create_connection((self.host, self.port), timeout=self.timeout))
        self.sock.settimeout(.5)
        self.start_time = time.monotonic()
        self.trace.event("connected", backend=self.backend,
                         endpoint=getattr(self.sock, "evidence", {"host": self.host, "port": self.port}))
        self.threads = [threading.Thread(target=self._guard, args=(fn,), name=name, daemon=True)
                        for name, fn in (("carlife-writer", self._writer), ("carlife-decoder", self._decoder),
                                         ("carlife-reader", self._reader))]
        for thread in self.threads:
            thread.start()
        for packet in self.session.start():
            self._check_native_handshake_deadline()
            self.send(packet)
        return self

    def _guard(self, fn):
        try:
            fn()
        except Exception as error:
            with self.failure_lock:
                first_failure = not self.halt.is_set()
                if first_failure:
                    self.error = f"{type(error).__name__}: {error}"
                    if self.session.state != "BLOCKED_AUTH":
                        self.session.state = "ERROR"
                    self.halt.set()
                    # Failure must wake/close the peer immediately. Leaving an
                    # open unread socket fills MD's queues while GUI stays open.
                    if self.sock:
                        try:
                            self.sock.shutdown(socket.SHUT_RDWR)
                        except OSError:
                            pass
                        self.sock.close()
                    self.media.close()
            if first_failure:
                self.trace.event("error", worker=threading.current_thread().name, error=self.error,
                                 queue_evidence=getattr(error, "evidence", self.media.snapshot()),
                                 active_pipeline_stages=dict(self.pipeline_stages), socket_shutdown=True,
                                 recent_progress=list(self.progress_history),
                                 traceback=traceback.format_exc())

    def send(self, packet):
        if self.halt.is_set():
            raise ProtocolError("connection is closed")
        try:
            self.commands.put_nowait(packet)
            self.command_high_water_items = max(self.command_high_water_items, self.commands.qsize())
        except queue.Full:
            raise ProtocolError("command queue full")

    def _writer(self):
        while not self.halt.is_set():
            try:
                packet = self.commands.get(timeout=.2)
            except queue.Empty:
                continue
            wire = packet.encode()
            with self._pipeline_stage(self.io_stage + "_write"):
                self.sock.sendall(wire)
            self._progress(self.io_stage + "_write")
            self.trace.wire("tx", wire)
            self.trace.packet("tx", packet)
            self.commands.task_done()

    def _reader(self):
        parser = FrameParser()
        last_received = time.monotonic()
        with nullcontext(None) as video:
            while not self.halt.is_set():
                self._check_native_handshake_deadline()
                if self._native_rx_boundary_ready(len(parser.buffer)):
                    # Signal only between reads at a complete frame boundary.
                    # Returning prevents a new partial read racing host close.
                    self.native_rx_quiescent.set()
                    return
                if self.pending_touch and time.monotonic() - self.last_touch_time > 5:
                    self.cancel_touch("idle_timeout")
                try:
                    with self._pipeline_stage(self.io_stage + "_read"):
                        data = self.sock.recv(65536)
                except socket.timeout:
                    if self.pending_touch and time.monotonic() - self.last_touch_time > 5:
                        self.cancel_touch("idle_timeout")
                    # Static display can legitimately yield no new frames.
                    if self.session.state in ("WAIT_VERSION", "WAIT_INIT") and time.monotonic() - last_received > self.timeout:
                        raise ProtocolError("handshake timeout")
                    continue
                if not data:
                    parser.eof()
                    self.peer_eof_seen = True
                    self.trace.event("peer_eof")
                    if not self.error:
                        self.session.state = "DISCONNECTED"
                    self.halt.set()
                    self.media.close()
                    break
                last_received = time.monotonic()
                self._progress(self.io_stage + "_read_bytes", len(data))
                with self._pipeline_stage("framing"):
                    packets = parser.feed(data)
                self.framing_pending_bytes = len(parser.buffer)
                self._progress("framed_packets", len(packets))
                for packet in packets:
                    # Retain complete packet prefixes even when the byte budget ends.
                    self.trace.wire("rx", packet.encode())
                    self.trace.packet("rx", packet)
                    self._receive_packet(packet, video, received_ns=time.monotonic_ns())

    def _receive_packet(self, packet, video, received_ns=None):
        with self.state_lock:
            self._check_native_handshake_deadline()
            received_ns = received_ns or time.monotonic_ns()
            if (self.backend == "DIRECT_USB_AOA" and self.session.state == "WAIT_VERSION" and
                    ((packet.channel == VIDEO and packet.message in (VIDEO_DATA, VIDEO_HEARTBEAT))
                     or packet.channel in (MEDIA, TTS))):
                # Releasing a libusb interface is not a USB detach. A prior
                # PAUSE/closed HU may leave complete old media in the endpoint.
                # Discard only framed media before the fresh VERSION_MATCH.
                # Control/auth still enters the normal session validator.
                self.native_stale_media_packets += 1
                self.native_stale_media_bytes += len(packet.payload)
                if self.native_stale_media_bytes > 2 * MAX_INNER:
                    raise ProtocolError("native pre-handshake media exceeds bounded endpoint tail")
                self.trace.event("native_pre_handshake_media_discarded", message=packet.message,
                                 packets=self.native_stale_media_packets, bytes=self.native_stale_media_bytes,
                                 reason="accessory endpoint tail before fresh VERSION_MATCH")
                return
            discard = (packet.channel == VIDEO and
                       ((self.session.state == "WAIT_INIT" and self.session.reset_pending)
                        or self.session.state == "PAUSED"))
            responses = self.session.receive(packet)
            self._check_native_handshake_deadline()
            for response in responses:
                self.send(response)
            if packet.message == MD_INFO:
                marker = self.session.md_info.get("15", "")
                expected_marker = (NATIVE_MEDIACODEC_AU_MARKER if self.backend == "DIRECT_USB_AOA"
                                   else MEDIACODEC_AU_MARKER)
                if marker == expected_marker:
                    if self.video_chunks:
                        raise ProtocolError("packetization profile changed after video began")
                    self.video_packetization = "ANNEXB_COMPLETE_AU"
                    self.source_name = "MediaCodecDisplaySource"
                    self.timestamp_semantics = "Actual encoder PTS_us // 1000 modulo 2^32; millisecond quantization"
                    self.trace.event("video_profile_negotiated", marker=marker,
                                     packetization=self.video_packetization, timestamp_semantics=self.timestamp_semantics)
                elif "video=ANNEXB_" in marker:
                    raise ProtocolError("unsupported explicit video packetization profile")
            if packet.channel == VIDEO and packet.message == VIDEO_DATA:
                self.video_chunks += 1
                self.video_bytes += len(packet.payload)
                if not self.video_capture_truncated and self.video_retained_bytes + len(packet.payload) <= self.capture_limit // 4:
                    accepted = self.trace.video(packet.payload) if video is None else video.write(packet.payload) is not None
                    if accepted:
                        self.video_retained_bytes += len(packet.payload)
                    else:
                        self.video_capture_truncated = True
                else:
                    self.video_capture_truncated = True
                if discard:
                    self.old_generation_chunks_dropped += 1
                    self.trace.event("old_generation_video_discarded", state=self.session.state,
                                     bytes=len(packet.payload), generation=self.decoder_generation)
                    return
                if self.video_packetization == "ANNEXB_COMPLETE_AU":
                    nals = annexb_nals(packet.payload)
                    kinds = [kind for kind, body in nals]
                    if not any(kind in (1, 5) for kind in kinds):
                        raise ProtocolError("negotiated complete AU contains no VCL picture")
                    if not self.reference_gate.ready:
                        if not all(kind in kinds for kind in (7, 8, 5)):
                            raise ProtocolError("first complete AU requires unaltered SPS/PPS and IDR")
                        self.reference_gate.ready = True
                    self._inspect_sps(packet.payload)
                    raw = packet.timestamp
                    previous = self.pts_epochs.get(self.decoder_generation)
                    unwrapped = raw if previous is None else previous + ((raw - (previous & 0xFFFFFFFF)) & 0xFFFFFFFF)
                    if previous is not None and unwrapped - previous > 0x7FFFFFFF:
                        raise ProtocolError("encoder PTS moved backwards in an AU generation")
                    self.pts_epochs[self.decoder_generation] = unwrapped
                    with self.metrics_lock:
                        self.total_aus += 1
                        index = self.total_aus
                        record = {"session_id": self.session_id, "au_index": index, "generation": self.decoder_generation,
                                  "pts_ms": unwrapped, "pts_ms_raw": raw,
                                  "host_receive_ns": received_ns, "bytes": len(packet.payload),
                                  "nal_types": kinds, "idr": 5 in kinds,
                                  "payload_sha256": hashlib.sha256(packet.payload).hexdigest()}
                        self.au_records.append(record)
                        self._record_pts(record)
                    self._progress("received_au")
                    self.trace.event("received_access_unit", **record)
                    self.media.put(MediaChunk(self.decoder_generation, packet.payload, True, unwrapped, received_ns, index))
                else:
                    data = self.reference_gate.feed(packet.payload)
                    if data is not None:
                        self._inspect_sps(data)
                        self.media.put(MediaChunk(self.decoder_generation, data, received_ns=received_ns))
            elif packet.channel in (MEDIA, TTS, VR):
                self.audio_packets[{MEDIA: "MEDIA", TTS: "TTS", VR: "VR"}[packet.channel]] += 1
                self.audio.receive(packet)

    def _inspect_sps(self, data):
        if self.sps is not None:
            return
        self._sps_prefix.extend(data)
        if len(self._sps_prefix) > 1024 * 1024:
            raise ProtocolError("no complete SPS in bounded bootstrap prefix")
        starts = list(re.finditer(b"\x00\x00(?:\x00)?\x01", self._sps_prefix))
        for marker, nxt in zip(starts, starts[1:]):
            offset = marker.end()
            if self._sps_prefix[offset] & 31 == 7:
                from .h264 import parse_sps
                self.sps = parse_sps(bytes(self._sps_prefix[offset + 1:nxt.start()]))
                mbs = (self.sps["coded_width"] // 16) * (self.sps["coded_height"] // 16)
                level, profile = self.sps["level_idc"], self.sps["profile_idc"]
                fs_limit = {10: 99, 31: 3600}.get(level)
                violation = fs_limit is not None and mbs > fs_limit
                status = "FAIL_LEVEL_MAXFS" if violation else "PARTIAL_CHECK_ONLY"
                if self.source_name == "MediaCodecDisplaySource":
                    status = ("PASS_BASELINE_L31_MAXFS" if profile == 66 and level == 31 and mbs <= 3600
                              else "FAIL_REQUESTED_BASELINE_L31")
                self.sps_validation = {"status": status,
                    "coded_macroblocks": mbs, "level_max_fs": fs_limit,
                    "level_max_mbps": 108000 if level == 31 else None,
                    "decoder_policy": "permissive diagnostic decode; not automotive acceptance",
                    "remaining_checks": "MBPS assessed separately from actual quantized PTS; DPB/HRD/bitrate and target HU acceptance not fully validated"}
                self.trace.event("sps", parsed=self.sps, validation=self.sps_validation)
                self._sps_prefix.clear()
                break

    def _decoder(self):
        import av
        context = av.CodecContext.create("h264", "r")
        context.thread_count = 1
        local_generation = -1
        while True:
            data = self.media.get()
            if data is None:
                # Flush the last complete parser packet on orderly disconnect.
                with self.state_lock:
                    if local_generation == self.decoder_generation:
                        try:
                            if self.video_packetization != "ANNEXB_COMPLETE_AU":
                                for packet in context.parse(b""):
                                    self.access_units += 1
                                    for frame in context.decode(packet):
                                        self._frame(frame)
                            for frame in context.decode(None):
                                self._frame(frame)
                        except av.error.EOFError:
                            pass
                break
            with self.state_lock:
                if data.generation != self.decoder_generation:
                    self.trace.event("queued_generation_discarded", generation=data.generation)
                    continue
                if local_generation != data.generation:
                    context = av.CodecContext.create("h264", "r")
                    context.thread_count = 1
                    local_generation = data.generation
                    self.trace.event("decoder_new_reference_sequence", generation=local_generation)
                if data.complete_au:
                    packet = av.Packet(data.data)
                    packet.pts = data.pts_ms
                    packet.dts = data.pts_ms
                    packet.time_base = Fraction(1, 1000)
                    packets = [packet]
                else:
                    packets = context.parse(data.data)
                for packet in packets:
                    self.access_units += 1
                    with self._pipeline_stage("codec_decode"):
                        frames = context.decode(packet)
                    for frame in frames:
                        self._frame(frame, unit=data if data.complete_au else None)

    def _frame(self, frame, unit=None):
        if (frame.width, frame.height) != (self.session.width, self.session.height):
            raise ProtocolError("decoded SPS dimensions differ from negotiated encoder dimensions")
        received = time.monotonic()
        with self._pipeline_stage("rgb_conversion_and_hash"):
            rgb = frame.reformat(format="rgb24")
            pixels = bytes(rgb.planes[0])
            pixel_sha256 = hashlib.sha256(pixels).hexdigest()
        self.decoded_frames += 1
        self._progress("decoded_frame")
        if self.first_frame_before_eof is None:
            self.first_frame_before_eof = not self.peer_eof_seen and not self.halt.is_set()
        self.generation_frames[self.decoder_generation] = self.generation_frames.get(self.decoder_generation, 0) + 1
        self.frame_times.append(received)
        if self.first_frame_time is None:
            self.first_frame_time = received
        self.last_frame_time = received
        if pixel_sha256 == self.last_pixel_hash:
            self.consecutive_identical_frames += 1
        self.last_pixel_hash = pixel_sha256
        if len(self.frame_hashes) < 4096:
            self.frame_hashes.add(pixel_sha256)
        elif pixel_sha256 not in self.frame_hashes:
            self.frame_hashes_saturated = True
        self.dimensions = (frame.width, frame.height)
        record = {"session_id": self.session_id, "frame_index": self.decoded_frames, "generation": self.decoder_generation,
                  "host_decode_ns": time.monotonic_ns(), "encoder_pts_ms": frame.pts if unit else None,
                  "host_receive_ns": unit.received_ns if unit else None,
                  "au_index": unit.au_index if unit else None, "host_paint_ns": None,
                  "key_frame": frame.key_frame, "pixel_sha256": pixel_sha256}
        with self.metrics_lock:
            self.frame_records[self.decoded_frames] = record
            if len(self.frame_records) > 2048:
                self.frame_records.popitem(last=False)
            if record["host_receive_ns"]:
                self._timing_add("receive_to_decode", (record["host_decode_ns"] - record["host_receive_ns"]) / 1e6)
        if self.on_decoded_frame:
            with self._pipeline_stage("decoded_observer"):
                self.on_decoded_frame(record.copy(), pixels, frame.width, frame.height, rgb.planes[0].line_size)
        # Observer correlation must be ready before Qt can publish/paint this
        # frame. A callback failure remains an explicit worker error, not a
        # silently missing measurement or successful presentation claim.
        with self.frame_lock:
            self.latest_frame = (pixels, frame.width, frame.height, rgb.planes[0].line_size, self.decoded_frames)
        self._progress("latest_frame_published")
        if self.decoded_frames == 1:
            self._queue_png("first-frame.png", frame, record)
        if self.decoded_frames % 30 == 0:
            self._queue_png("last-frame.png", frame, record)
        self.trace.event("decoded_frame", index=self.decoded_frames, width=frame.width,
                         height=frame.height, key_frame=frame.key_frame, pts=frame.pts,
                         source_clock="host decoder output", source_monotonic=received,
                         generation=self.decoder_generation, timing=record.copy(),
                         before_eof=not self.peer_eof_seen and not self.halt.is_set())

    @contextmanager
    def _pipeline_stage(self, name):
        thread = threading.current_thread().name
        start = time.monotonic_ns()
        self.pipeline_stages[thread] = {"stage": name, "started_ns": start}
        try:
            yield
        finally:
            elapsed = (time.monotonic_ns() - start) / 1e6
            self.pipeline_stages.pop(thread, None)
            with self.metrics_lock:
                self.pipeline_samples.setdefault(name, deque(maxlen=2048)).append(elapsed)
                total = self.pipeline_totals.setdefault(name, {"count": 0, "sum_ms": 0, "max_ms": 0})
                total["count"] += 1
                total["sum_ms"] += elapsed
                total["max_ms"] = max(total["max_ms"], elapsed)
            if elapsed > 100:
                self.trace.event("pipeline_slow", stage=name, elapsed_ms=elapsed,
                                 queue_evidence=self.media.snapshot())

    def _queue_png(self, filename, frame, record):
        """At most one first and one latest pending image, plus an active write.

        Evidence PNG encoding/disk IO never holds the decoder or state lock.
        Coalescing affects optional PNG snapshots only, never CarLife AUs.
        """
        with self.png_condition:
            if self.png_closed:
                raise ProtocolError("PNG evidence queue already closed")
            self.png_counts["submitted"] += 1
            if filename in self.png_pending:
                self.png_counts["coalesced"] += 1
            self.png_pending[filename] = (frame, record["frame_index"], record["generation"], time.monotonic_ns())
            self.png_high_water_items = max(self.png_high_water_items, len(self.png_pending))
            self.png_high_water_bytes = max(self.png_high_water_bytes, sum(v[0].width*v[0].height*3 for v in self.png_pending.values()))
            if self.png_thread is None:
                self.png_thread = threading.Thread(target=self._guard, args=(self._png_writer,),
                                                   name="carlife-evidence", daemon=True)
                self.png_thread.start()
            self.png_condition.notify()

    def _png_writer(self):
        while True:
            with self.png_condition:
                while not self.png_pending and not self.png_closed:
                    self.png_condition.wait(.2)
                if not self.png_pending:
                    return
                filename = "first-frame.png" if "first-frame.png" in self.png_pending else "last-frame.png"
                frame, index, generation, queued_ns = self.png_pending.pop(filename)
                self.png_high_water_age_ms = max(self.png_high_water_age_ms, (time.monotonic_ns()-queued_ns)/1e6)
            try:
                with self._pipeline_stage("evidence_png_write"):
                    frame.to_image().save(self.output_dir / filename)
            except Exception as error:
                self.png_counts["failed"] += 1
                self.trace.event("evidence_png_error", filename=filename, frame_index=index,
                                 generation=generation, error=str(error))
                raise
            self.png_counts["written"] += 1
            self._progress("png_written")
            self.trace.event("evidence_png", filename=filename, frame_index=index, generation=generation)

    def mark_presented(self, frame_index, host_paint_ns=None):
        """Called after Qt paints this frame; not an assertion of physical scan-out."""
        with self.metrics_lock:
            record = self.frame_records.get(frame_index)
            if record is None or record["host_paint_ns"] is not None:
                return
            record["host_paint_ns"] = host_paint_ns or time.monotonic_ns()
            self.frames_painted += 1
            self._progress("qt_painted")
            if record["host_receive_ns"]:
                self._timing_add("receive_to_qt_paint", (record["host_paint_ns"] - record["host_receive_ns"]) / 1e6)
            self._timing_add("decode_to_qt_paint", (record["host_paint_ns"] - record["host_decode_ns"]) / 1e6)
            self.trace.event("qt_frame_painted", **record,
                             measurement="Qt paint completion in host monotonic clock; not physical scan-out")

    def _timing_add(self, name, value):
        self.timing_samples[name].append(value)
        total = self.timing_totals[name]
        total["count"] += 1
        total["sum_ms"] += value
        total["min_ms"] = value if total["min_ms"] is None else min(total["min_ms"], value)
        total["max_ms"] = value if total["max_ms"] is None else max(total["max_ms"], value)

    def _record_pts(self, record):
        generation, pts = record["generation"], record["pts_ms"]
        if generation not in self.pts_aggregates:
            if len(self.pts_aggregates) >= 256:
                raise ProtocolError("session generation limit 256 reached")
            self.pts_aggregates[generation] = {"count": 0, "first": pts, "last": pts,
                "idr_count": 0, "idrs": deque(maxlen=256), "gaps": deque(maxlen=2048),
                "repeats": 0, "backwards": 0, "large_gaps": 0}
        a = self.pts_aggregates[generation]
        if a["count"]:
            gap = pts - a["last"]
            a["gaps"].append(gap)
            a["repeats"] += gap == 0
            a["backwards"] += gap < 0
            a["large_gaps"] += gap > 1500 / self.session.fps
        a["count"] += 1
        a["last"] = pts
        if record["idr"]:
            a["idr_count"] += 1
            a["idrs"].append(pts)

    def pts_metrics(self):
        with self.metrics_lock:
            result = []
            for generation, a in self.pts_aggregates.items():
                duration = a["last"] - a["first"]
                rate = (a["count"] - 1) * 1000 / duration if duration > 0 else None
                lower = (a["count"] - 1) * 1000 / (duration + 1) if duration > 0 else None
                upper = (a["count"] - 1) * 1000 / (duration - 1) if duration > 1 else None
                mbs = self.sps_validation.get("coded_macroblocks")
                limit = self.sps_validation.get("level_max_mbps")
                check = "NOT_ENOUGH_PTS_OR_UNCHECKED_LEVEL"
                if lower is not None and upper is not None and mbs and limit:
                    check = ("FAIL" if lower * mbs > limit else "PASS" if upper * mbs <= limit
                             else "BORDERLINE_1MS_QUANTIZATION_USE_MD_PTS_US")
                result.append({"generation": generation, "access_units": a["count"],
                    "idr_access_units": a["idr_count"], "idr_pts_ms": list(a["idrs"]),
                    "idr_history_truncated": a["idr_count"] > len(a["idrs"]),
                    "pts_first_ms": a["first"], "pts_last_ms": a["last"], "pts_active_interval_ms": duration,
                    "strictly_increasing": not (a["repeats"] or a["backwards"]), "repeated_pts": a["repeats"],
                    "encoder_output_fps_from_pts_ms": rate,
                    "fps_bounds_due_to_ms_quantization": [lower, upper], "gap_distribution": timing_stats(a["gaps"]),
                    "gap_distribution_window": "most recent 2048 intervals; totals/rate cover entire generation",
                    "gaps_above_1_5_requested_period": a["large_gaps"],
                    "estimated_mbps_from_pts": rate * mbs if rate and mbs else None,
                    "max_mbps_for_level": limit, "mbps_check": check})
            return result

    def host_timing_metrics(self):
        with self.metrics_lock:
            stats = {name: {**timing_stats(values), "all_session": self.timing_totals[name],
                           "percentile_window": "most recent 2048 samples"} for name, values in self.timing_samples.items()}
            return {**stats, "unique_frames_painted": self.frames_painted,
                    "decoded_frames_not_painted": self.decoded_frames - self.frames_painted,
                    "semantics": "same-host monotonic clock; Qt paint completion, not scan-out; no Android-host clock subtraction"}

    def touch(self, action, x, y):
        if action not in (0, 1, 2, 3):
            raise ValueError("touch action must be down/up/move/cancel")
        if action == CANCEL:
            return self.cancel_touch("legacy_api_cancel")
        return self.send_touch_frame(TouchFrame(action, (Pointer(0, x, y),),
                    generation=self.decoder_generation, event_time_ns=time.monotonic_ns(), source="mouse_or_single_script"))

    @property
    def geometry(self):
        return {"requested_video": self.session.requested, "accepted_video": self.session.accepted,
                "wire_space": "video_pixels", "generation": self.decoder_generation,
                "source_size": self.source_size, "source_size_provenance": "explicit_runtime_or_manual_profile" if self.source_size else "unknown",
                "source_policy": self.source_policy, "display_policy": self.display_policy,
                "display_rotation": self.rotation, "density_dpi": None, "density_provenance": "not_carried_by_video_INIT",
                "physical_panel_ppi": None}

    def input_content_contains(self, x, y):
        if not (0 <= x < self.session.width and 0 <= y < self.session.height):
            return False
        if self.source_size:
            if self.source_policy != "fit":
                raise ProtocolError("source mapping profile only implements confirmed MD aspect-fit")
            t = SourceDisplayTransform(*self.source_size, self.session.width, self.session.height)
            return t.video_to_source(x, y) is not None
        return True

    def send_touch_frame(self, frame):
        with self.state_lock:
            if self.session.state != "STREAMING":
                raise ProtocolError("input requires streaming session")
            if frame.generation != self.decoder_generation:
                raise ProtocolError("touch generation differs from displayed video")
            if frame.display_id != 0:
                raise ProtocolError("wire has no display ID; this profile targets display 0")
            try:
                frame.validate()
            except ProtocolError:
                self.cancel_touch("invalid_frame")
                raise
            if frame.action != CANCEL and any(not self.input_content_contains(p.x, p.y) for p in frame.pointers):
                self.cancel_touch("outside_content")
                raise ProtocolError("touch outside active content")
            packet = encode_touch(frame, self.touch_profile)
            # Validate without mutating state until the command has been queued.
            tracker = TouchTracker()
            tracker.active, tracker.generation = self.touch_tracker.active, self.touch_tracker.generation
            tracker.accept(frame)
            self.send(packet)
            self.touch_tracker = tracker
            self.pending_touch = bool(tracker.active)
            self.last_touch_time = time.monotonic()
            self.input_packets += 1
            self.trace.event("touch_frame", profile=self.touch_profile, frame=frame.record(),
                             payload_hex=packet.payload.hex(), wire_space="video_pixels")
            return frame

    def cancel_touch(self, reason="explicit"):
        with self.state_lock:
            if not self.touch_tracker.active:
                return False
            if self.session.state == "STREAMING" and not self.halt.is_set():
                frame = TouchFrame(CANCEL, self.touch_tracker.active, generation=self.decoder_generation,
                                   event_time_ns=time.monotonic_ns(), source="cancel:" + reason)
                self.send_touch_frame(frame)
            else:
                # MD independently releases on disconnect/timeout; no fake packet.
                self.touch_tracker = TouchTracker()
                self.pending_touch = False
                self.trace.event("local_touch_released_without_wire", reason=reason)
            return True

    def gesture(self, kind="pinch-out", release_first=0, steps=12, interval=.04):
        if self.touch_profile == "LEGACY_SINGLE":
            raise ProtocolError("two-pointer gesture requires explicit multi profile")
        if not 0 <= interval <= .25:
            raise ProtocolError("gesture interval outside lab bound")
        frames = list(two_pointer_gesture(self.session.width, self.session.height, kind, release_first, steps, self.decoder_generation))
        for frame in frames:
            self.send_touch_frame(frame)
            if interval:
                time.sleep(interval)
        return len(frames)

    def tap(self, x, y):
        self.touch(0, x, y)
        self.touch(1, x, y)

    def back(self):
        if self.session.state != "STREAMING":
            raise ProtocolError("back requires streaming session")
        self.send(Packet(TOUCH, HARD_KEY, protobuf(f1=BACK_KEY)))
        self.input_packets += 1

    def home(self):
        if self.session.state != "STREAMING":
            raise ProtocolError("home requires streaming session")
        self.cancel_touch("home")
        self.send(Packet(TOUCH, HARD_KEY, protobuf(f1=HOME_KEY)))
        self.input_packets += 1
        self.trace.event("hard_key", key="HOME", carlife_code=HOME_KEY)

    def pause(self):
        with self.state_lock:
            if self.pending_touch:
                self.cancel_touch("pause")
            self.send(self.session.pause())
            self.media.clear()

    def _new_generation(self, reason):
        self.decoder_generation += 1
        discarded = self.media.clear()
        self.reference_gate = ReferenceStartGate()
        with self.frame_lock:
            self.latest_frame = None
        self.sps = None
        self._sps_prefix.clear()
        self.sps_validation = {"status": "NOT_YET_SEEN", "decoder_policy": "permissive diagnostic decode"}
        self.trace.event("generation_barrier", reason=reason, generation=self.decoder_generation,
                         queued_bytes_discarded=discarded)

    def resume(self):
        with self.state_lock:
            packet = self.session.resume()
            self._new_generation("resume")
            self.send(packet)

    def reset(self):
        with self.state_lock:
            if self.pending_touch:
                self.cancel_touch("reset")
            packets = self.session.reset()
            self._new_generation("reset")
            for packet in packets:
                self.send(packet)

    def summary(self):
        active = self.last_frame_time - self.first_frame_time if self.first_frame_time is not None and self.last_frame_time is not None else 0
        with self.metrics_lock:
            pipeline = {name: {**timing_stats(values), "all_session": self.pipeline_totals[name].copy(),
                              "percentile_window": "most recent 2048 stage samples"}
                        for name, values in self.pipeline_samples.items()}
        return {"session_id": self.session_id, "backend": self.backend, "profile": self.session.profile, "interoperability": "SELF",
                "transport": getattr(self.sock, "evidence", None),
                "transport_byte_counts": {"rx": getattr(self.sock, "rx_bytes", None), "tx": getattr(self.sock, "tx_bytes", None)},
                "native_pre_handshake_stale_media": {"packets": self.native_stale_media_packets,
                                                     "payload_bytes": self.native_stale_media_bytes},
                "native_shutdown_drain": self.native_shutdown_drain,
                "independent_interop": "NOT_RUN", "state": self.session.state, "error": self.error,
                "decoded_frames": self.decoded_frames, "distinct_frame_hashes": len(self.frame_hashes),
                "distinct_frame_hashes_exact": not self.frame_hashes_saturated,
                "distinct_hash_note": "exact until 4096 unique hashes, then lower bound; no source redraw inference",
                "consecutive_identical_frames": self.consecutive_identical_frames,
                "parsed_access_units": self.access_units, "video_chunks": self.video_chunks,
                "video_bytes": self.video_bytes, "decoded_dimensions": self.dimensions,
                "video_retained_bytes": self.video_retained_bytes, "video_capture_truncated": self.video_capture_truncated,
                "trace_retention": self.trace.summary(), "geometry": self.geometry,
                "touch_profile": self.touch_profile, "active_pointer_ids": [p.id for p in self.touch_tracker.active],
                "host_decode_active_interval_seconds": active,
                "host_decode_delivery_fps": (self.decoded_frames - 1) / active if active else None,
                "fps_semantics": "host decode delivery rate across active interval, NOT encoder FPS",
                "peer_timestamp_semantics": self.timestamp_semantics,
                "source": self.source_name, "video_packetization": self.video_packetization,
                "first_frame_decoded_before_eof": self.first_frame_before_eof,
                "encoder_pts_metrics": self.pts_metrics(), "host_timing_metrics": self.host_timing_metrics(),
                "pipeline_timing_metrics": pipeline,
                "progress_snapshot": self.progress_snapshot(record=False),
                "recent_progress": list(self.progress_history),
                "evidence_png": {**dict(self.png_counts), "pending": len(self.png_pending),
                                 "worker_alive": bool(self.png_thread and self.png_thread.is_alive())},
                "input_packets_sent": self.input_packets, "audio_packet_counts": self.audio_packets,
                "audio": self.audio.summary(),
                "unknown_messages": self.session.unknown, "duplicate_messages": self.session.duplicates,
                "md_info": self.session.md_info, "sps": self.sps, "sps_validation": self.sps_validation,
                "decoder_generation": self.decoder_generation,
                "generation_frames": self.generation_frames,
                "old_generation_chunks_dropped": self.old_generation_chunks_dropped,
                "workers_alive": [t.name for t in self.threads + ([self.png_thread] if self.png_thread else []) + [self.trace.writer] if t.is_alive()]}

    def _native_rx_boundary_ready(self, pending_bytes):
        if self.backend != "DIRECT_USB_AOA" or self.native_drain_since is None or pending_bytes:
            return False
        last_rx_ns = self.last_progress_ns.get("usb_bulk_read_bytes")
        since = max(self.native_drain_since, (last_rx_ns / 1e9) if last_rx_ns else self.native_drain_since)
        return time.monotonic() - since >= .2

    def _drain_native_before_close(self):
        if self.backend != "DIRECT_USB_AOA" or not self.sock or self.halt.is_set():
            return
        started = time.monotonic()
        self.native_drain_since = started
        deadline = started + 3.5
        while not self.halt.is_set() and not self.native_rx_quiescent.is_set():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            self.native_rx_quiescent.wait(min(.05, remaining))
        self.native_shutdown_drain = {"quiescent": self.native_rx_quiescent.is_set(),
            "elapsed_seconds": time.monotonic() - started, "deadline_seconds": 3.5,
            "quiet_minimum_ms": 200, "framing_pending_bytes": self.framing_pending_bytes,
            "scope": "logical HU disconnect, not USB detach"}
        self.trace.event("native_shutdown_rx_boundary", **self.native_shutdown_drain)
        if not self.native_rx_quiescent.is_set() and not self.error:
            self.error = "NATIVE_RX_DRAIN_NOT_QUIESCENT: no safe complete frame boundary before close"

    def stop(self):
        if self.stopped:
            return
        self.stopped = True
        if not self.halt.is_set():
            try:
                if self.pending_touch:
                    self.cancel_touch("disconnect")
                if self.backend == "DIRECT_USB_AOA" and self.session.state in ("STREAMING", "PAUSED"):
                    # Video pause leaves MEDIA running. Stop PCM before waiting
                    # for the reader's quiet, complete packet boundary.
                    self.send(Packet(CMD, PAUSE_MEDIA))
                if self.session.state == "STREAMING":
                    self.send(self.session.pause())
                deadline = time.monotonic() + .5
                while self.commands.unfinished_tasks and time.monotonic() < deadline:
                    time.sleep(.01)
            except (OSError, ProtocolError):
                pass
        self._drain_native_before_close()
        self.halt.set()
        if self.sock:
            try:
                self.sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            self.sock.close()
        self.media.close()
        for thread in self.threads:
            thread.join(timeout=3)
        self.audio.close()
        with self.png_condition:
            self.png_closed = True
            self.png_condition.notify_all()
        if self.png_thread:
            self.png_thread.join(timeout=3)
        self.end_time = time.monotonic()
        if not self.error:
            self.session.state = "DISCONNECTED"
        result = self.summary()
        self.trace.event("stopped", **result)
        self.trace.close()
        # Persist final retained-byte/worker state after bounded diagnostic drain.
        result = self.summary()
        (self.output_dir / "summary.json").write_text(json.dumps(result, indent=2) + "\n")


def replay(path):
    """Offline framing replay. Never opens a socket or contacts a device."""
    parser = FrameParser()
    counts = {}
    with Path(path).open("rb") as handle:
        while chunk := handle.read(32768):
            for packet in parser.feed(chunk):
                key = f"{packet.channel}:0x{packet.message:08x}"
                counts[key] = counts.get(key, 0) + 1
    parser.eof()
    return counts
