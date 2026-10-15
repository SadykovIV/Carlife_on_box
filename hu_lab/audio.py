"""Independent CarLife PCM receiver, with optional bounded WAV evidence.

Wire IDs and INIT fields follow Baidu CarLife Vehicle Lib, not AAP audio.
No playback or microphone routing is claimed by this diagnostic sink.
"""
from collections import deque
from dataclasses import asdict, dataclass
from pathlib import Path
import threading
import time
import wave

from .protocol import MEDIA, TTS, ProtocolError, fields

MEDIA_INIT, MEDIA_STOP, MEDIA_PAUSE = 0x30001, 0x30002, 0x30003
MEDIA_RESUME, MEDIA_DATA = 0x30004, 0x30006
TTS_INIT, TTS_STOP, TTS_DATA = 0x40001, 0x40002, 0x40003
_NAMES = {MEDIA: "MEDIA", TTS: "TTS"}


@dataclass(frozen=True)
class PcmFormat:
    sample_rate: int
    channels: int
    bits: int

    @property
    def frame_bytes(self):
        return self.channels * (self.bits // 8)

    @property
    def bytes_per_second(self):
        return self.sample_rate * self.frame_bytes

    @classmethod
    def parse(cls, payload):
        if len(payload) > 1024:
            raise ProtocolError("audio INIT exceeds bound")
        value = fields(payload)
        rate, channels, bits = (value.get(i) for i in (1, 2, 3))
        if any(type(v) is not int for v in (rate, channels, bits)):
            raise ProtocolError("audio INIT requires three integer fields")
        if not 8000 <= rate <= 192000 or channels not in (1, 2) or bits not in (8, 16):
            raise ProtocolError("unsupported PCM format")
        return cls(rate, channels, bits)


class BoundedWavCapture:
    """Disk IO never runs on the USB reader. Expired PCM is counted and dropped.

    Saved WAV is the received prefix with any queue drops removed; counters are
    authoritative about gaps. A slow disk cannot build an unbounded backlog.
    """
    def __init__(self, directory, *, max_bytes=8 * 1024 * 1024,
                 max_queue_seconds=.2, max_age_seconds=.25, max_files=64,
                 clock=time.monotonic, start_worker=True):
        if max_bytes <= 0 or not 0 < max_queue_seconds <= 5 or not 0 < max_age_seconds <= 5 or max_files <= 0:
            raise ValueError("invalid audio capture limits")
        self.directory = Path(directory)
        self.max_bytes, self.max_queue_seconds = max_bytes, max_queue_seconds
        self.max_age_seconds, self.max_files = max_age_seconds, max_files
        self.clock = clock
        self.condition = threading.Condition()
        self.pending = deque()
        self.queue_seconds = 0.0
        self.high_water_seconds = 0.0
        self.dropped_queue_bytes = self.dropped_limit_bytes = self.written_bytes = 0
        self.records = []
        self.closed = False
        self.error = None
        self.worker = threading.Thread(target=self._run, name="carlife-audio-wav", daemon=True)
        if start_worker:
            self.worker.start()

    def _discard_first(self):
        _, _, _, fmt, data = self.pending.popleft()
        self.queue_seconds = max(0.0, self.queue_seconds - len(data) / fmt.bytes_per_second)
        self.dropped_queue_bytes += len(data)

    def submit(self, channel, generation, fmt, data):
        duration = len(data) / fmt.bytes_per_second
        with self.condition:
            if self.closed or self.error:
                self.dropped_queue_bytes += len(data)
                return False
            now = self.clock()
            while self.pending and now - self.pending[0][0] > self.max_age_seconds:
                self._discard_first()
            if duration > self.max_queue_seconds:
                self.dropped_queue_bytes += len(data)
                return False
            while self.pending and (self.queue_seconds + duration > self.max_queue_seconds + 1e-9 or len(self.pending) >= 64):
                self._discard_first()
            self.pending.append((now, channel, generation, fmt, data))
            self.queue_seconds += duration
            self.high_water_seconds = max(self.high_water_seconds, self.queue_seconds)
            self.condition.notify()
            return True

    def _run(self):
        handles = {}
        try:
            while True:
                with self.condition:
                    while not self.pending and not self.closed:
                        self.condition.wait(.2)
                    if not self.pending:
                        break
                    job = self.pending.popleft()
                    stamp, channel, generation, fmt, data = job
                    self.queue_seconds = max(0.0, self.queue_seconds - len(data) / fmt.bytes_per_second)
                    if self.clock() - stamp > self.max_age_seconds:
                        self.dropped_queue_bytes += len(data)
                        continue
                    allowed = min(len(data), max(0, self.max_bytes - self.written_bytes))
                    allowed -= allowed % fmt.frame_bytes
                    self.dropped_limit_bytes += len(data) - allowed
                    if not allowed:
                        continue
                key = (channel, generation)
                old = handles.get(channel)
                if old is None or old[0] != key:
                    if old is not None:
                        old[1].close()
                    with self.condition:
                        if len(self.records) >= self.max_files:
                            self.dropped_limit_bytes += allowed
                            continue
                    self.directory.mkdir(parents=True, exist_ok=True)
                    name = f"{_NAMES[channel].lower()}-{generation:04d}.wav"
                    out = wave.open(str(self.directory / name), "wb")
                    out.setnchannels(fmt.channels)
                    out.setsampwidth(fmt.bits // 8)
                    out.setframerate(fmt.sample_rate)
                    record = {"file": name, "channel": channel, "generation": generation,
                              "format": asdict(fmt), "pcm_bytes": 0}
                    handles[channel] = (key, out, record)
                    with self.condition:
                        self.records.append(record)
                _, out, record = handles[channel]
                # writeframes updates the header, so a live evidence file is readable.
                out.writeframes(data[:allowed])
                with self.condition:
                    self.written_bytes += allowed
                    record["pcm_bytes"] += allowed
        except Exception as error:
            with self.condition:
                self.error = f"{type(error).__name__}: {error}"
                while self.pending:
                    self._discard_first()
        finally:
            for _, out, _ in handles.values():
                try:
                    out.close()
                except Exception as error:
                    with self.condition:
                        self.error = self.error or f"{type(error).__name__}: {error}"

    def close(self):
        with self.condition:
            self.closed = True
            self.condition.notify_all()
        if self.worker.ident is not None:
            self.worker.join(timeout=3)
        else:
            with self.condition:
                while self.pending:
                    self._discard_first()

    def summary(self):
        with self.condition:
            return {"enabled": True, "maximum_pcm_bytes": self.max_bytes,
                    "written_pcm_bytes": self.written_bytes, "dropped_queue_bytes": self.dropped_queue_bytes,
                    "dropped_limit_bytes": self.dropped_limit_bytes, "queued_seconds": self.queue_seconds,
                    "maximum_queue_seconds": self.max_queue_seconds, "high_water_queue_seconds": self.high_water_seconds,
                    "max_age_seconds": self.max_age_seconds, "worker_alive": self.worker.is_alive(),
                    "error": self.error, "files": [dict(r, format=dict(r["format"])) for r in self.records],
                    "gaps": bool(self.dropped_queue_bytes or self.dropped_limit_bytes),
                    "scope": "PCM evidence, not playback or end-to-end latency"}


class AudioReceiver:
    """MEDIA/TTS state is isolated from video; a valid INIT recovers a bad lane."""
    def __init__(self, *, event=None, capture_directory=None, capture_limit_bytes=8 * 1024 * 1024):
        self.event = event or (lambda *args, **kwargs: None)
        self.lock = threading.RLock()
        self.closed = False
        self.capture = (BoundedWavCapture(capture_directory, max_bytes=capture_limit_bytes)
                        if capture_directory is not None else None)
        self.lanes = {channel: {"state": "IDLE", "format": None, "generation": 0,
            "packets": 0, "data_packets": 0, "pcm_frames": 0, "pcm_bytes": 0,
            "non_silent_bytes": 0, "dropped_pcm_bytes": 0, "partial_discarded_bytes": 0,
            "errors": 0, "last_error": None, "last_timestamp_raw": None, "pending": b"",
            "unknown_messages": 0} for channel in (MEDIA, TTS)}

    def _discard_partial(self, lane):
        lane["partial_discarded_bytes"] += len(lane["pending"])
        lane["pending"] = b""

    def receive(self, packet):
        if packet.channel not in self.lanes:
            return False
        with self.lock:
            lane = self.lanes[packet.channel]
            lane["packets"] += 1
            if self.closed:
                if packet.message in (MEDIA_DATA, TTS_DATA):
                    lane["dropped_pcm_bytes"] += len(packet.payload)
                return True
            try:
                self._receive(lane, packet)
            except (ProtocolError, ValueError) as error:
                self._discard_partial(lane)
                lane["state"] = "DISABLED"
                lane["errors"] += 1
                lane["last_error"] = str(error)
                self.event("audio_lane_error", channel=packet.channel, message=hex(packet.message), error=str(error),
                           effect="this audio lane disabled until valid INIT; video continues")
            return True

    def _receive(self, lane, packet):
        init = MEDIA_INIT if packet.channel == MEDIA else TTS_INIT
        data_id = MEDIA_DATA if packet.channel == MEDIA else TTS_DATA
        stop = MEDIA_STOP if packet.channel == MEDIA else TTS_STOP
        if packet.message == init:
            fmt = PcmFormat.parse(packet.payload)
            self._discard_partial(lane)
            lane.update(state="READY", format=fmt, generation=lane["generation"] + 1,
                        last_error=None, last_timestamp_raw=None)
            self.event("audio_init", channel=packet.channel, generation=lane["generation"], format=asdict(fmt))
        elif packet.message == data_id:
            lane["data_packets"] += 1
            lane["last_timestamp_raw"] = packet.timestamp
            maximum = 100 * 1024 if packet.channel == MEDIA else 50 * 1024
            if len(packet.payload) > maximum:
                lane["dropped_pcm_bytes"] += len(packet.payload)
                raise ProtocolError("audio DATA exceeds channel bound")
            if lane["state"] in ("DISABLED", "PAUSED", "STOPPED"):
                lane["dropped_pcm_bytes"] += len(packet.payload)
                return
            fmt = lane["format"]
            if fmt is None:
                lane["dropped_pcm_bytes"] += len(packet.payload)
                raise ProtocolError("audio DATA before INIT")
            joined = lane["pending"] + packet.payload
            aligned = len(joined) - len(joined) % fmt.frame_bytes
            pcm, lane["pending"] = joined[:aligned], joined[aligned:]
            lane["state"] = "PLAYING"
            lane["pcm_bytes"] += aligned
            lane["pcm_frames"] += aligned // fmt.frame_bytes
            silence = 128 if fmt.bits == 8 else 0
            lane["non_silent_bytes"] += len(pcm) - pcm.count(silence)
            if pcm and self.capture is not None:
                self.capture.submit(packet.channel, lane["generation"], fmt, pcm)
        elif packet.message in (stop, MEDIA_PAUSE, MEDIA_RESUME) and (packet.channel == MEDIA or packet.message == stop):
            if packet.payload:
                raise ProtocolError("audio control requires empty payload")
            self._discard_partial(lane)
            if lane["state"] == "DISABLED":
                return
            if packet.message == stop:
                lane["state"] = "STOPPED"
            elif packet.message == MEDIA_PAUSE:
                lane["state"] = "PAUSED"
            else:
                if lane["format"] is None or lane["state"] == "STOPPED":
                    raise ProtocolError("audio RESUME requires initialized, non-stopped lane")
                lane["state"] = "READY"
            self.event("audio_state", channel=packet.channel, state=lane["state"])
        else:
            lane["unknown_messages"] += 1
            if lane["unknown_messages"] <= 3:
                self.event("audio_unknown_message", channel=packet.channel, message=hex(packet.message))

    def close(self):
        with self.lock:
            if self.closed:
                return
            self.closed = True
            for lane in self.lanes.values():
                self._discard_partial(lane)
                lane["state"] = "CLOSED"
        if self.capture is not None:
            self.capture.close()

    def summary(self):
        with self.lock:
            lanes = {}
            for channel, lane in self.lanes.items():
                row = {key: value for key, value in lane.items() if key not in ("format", "pending")}
                row["format"] = asdict(lane["format"]) if lane["format"] else None
                row["pending_bytes"] = len(lane["pending"])
                lanes[_NAMES[channel]] = row
            return {"lanes": lanes, "capture": self.capture.summary() if self.capture else {"enabled": False},
                    "playback": "NOT_IMPLEMENTED", "timestamp_semantics": "peer raw 32-bit timestamp; not verified capture PTS"}
