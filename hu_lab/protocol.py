"""Strict, bounded CarLife wire codec, independently implemented for this lab.

Field schemas: pinned apollo-DuerOS/CarLife-Vehicle-Lib LibSource/protobuf/proto.
Inner headers/IDs: LibSource/include/CTranRecvPackageProcess.h.
AOA outer framing observation: pinned HeadSwap Protocol.kt, commit
6835e50dbc5cd753f8ecfd7e851ad2ac320577ad. The same framing over a development
socket does NOT demonstrate AOA or legacy six-socket TCP interoperability.
"""
from dataclasses import dataclass
import struct

CMD, VIDEO, MEDIA, TTS, VR, TOUCH = range(1, 7)
HU_VERSION, VERSION_MATCH, HU_INFO, MD_INFO = 0x18001, 0x10002, 0x18003, 0x10004
VIDEO_INIT, VIDEO_INIT_DONE = 0x18007, 0x10008
VIDEO_START, VIDEO_PAUSE, VIDEO_RESET, VIDEO_FPS = 0x18009, 0x1800A, 0x1800B, 0x1800C
PAUSE_MEDIA = 0x1800E
VIDEO_DATA, VIDEO_HEARTBEAT = 0x20001, 0x20002
TOUCH_ACTION, HARD_KEY, BACK_KEY = 0x68001, 0x68008, 14
# Pinned fgrcwp ServiceTypes.kt: CarLife key enum, not Android KeyEvent values.
HOME_KEY = 1
AUTH_IDS = frozenset((0x18048, 0x10049, 0x1804A, 0x1004B,
                      0x1006A, 0x1806B, 0x1006C, 0x1806D, 0x1006E, 0x18083))
MAX_INNER = 4 * 1024 * 1024
MEDIACODEC_AU_MARKER = "DEV_STREAM_OVER_ADB;source=MediaCodecDisplaySource;video=ANNEXB_AU;timestamp=ENCODER_PTS_MS"
NATIVE_MEDIACODEC_AU_MARKER = "DIRECT_USB_AOA;source=MediaCodecDisplaySource;video=ANNEXB_AU;timestamp=ENCODER_PTS_MS"


class ProtocolError(ValueError):
    pass


@dataclass(frozen=True)
class Packet:
    channel: int
    message: int
    payload: bytes = b""
    timestamp: int = 0

    def encode(self):
        if self.channel not in range(1, 7):
            raise ProtocolError("unsupported channel")
        if self.message >> 16 != self.channel:
            raise ProtocolError("message/channel mismatch")
        if self.channel in (CMD, TOUCH):
            if len(self.payload) > 65535:
                raise ProtocolError("command too large")
            inner = struct.pack(">HHI", len(self.payload), 0, self.message) + self.payload
        else:
            inner = struct.pack(">III", len(self.payload), self.timestamp & 0xFFFFFFFF,
                                self.message) + self.payload
        if len(inner) > MAX_INNER:
            raise ProtocolError("media too large")
        return struct.pack(">II", self.channel, len(inner)) + inner


class FrameParser:
    """Incremental outer parser; strict one-inner-per-outer development profile.

    Unknown message IDs on a known matching channel pass through for logging.
    Unknown channels, malformed lengths/reserved words and partial EOF fail.
    Vendor split-inner quirks are deliberately not guessed.
    """
    def __init__(self, maximum=MAX_INNER):
        self.buffer = bytearray()
        self.maximum = maximum

    def feed(self, data):
        # Callers receive <=64 KiB; avoid allocating an attacker-sized buffer.
        if len(data) > self.maximum + 8:
            raise ProtocolError("input chunk exceeds bound")
        self.buffer.extend(data)
        result = []
        while len(self.buffer) >= 8:
            channel, length = struct.unpack_from(">II", self.buffer)
            if channel not in range(1, 7):
                raise ProtocolError("unknown channel")
            header = 8 if channel in (CMD, TOUCH) else 12
            if not header <= length <= self.maximum:
                raise ProtocolError("invalid outer length")
            if len(self.buffer) < 8 + length:
                break
            inner = bytes(self.buffer[8:8 + length])
            del self.buffer[:8 + length]
            if channel in (CMD, TOUCH):
                declared, reserved, message = struct.unpack_from(">HHI", inner)
                if reserved:
                    raise ProtocolError("nonzero reserved field")
                timestamp = 0
            else:
                declared, timestamp, message = struct.unpack_from(">III", inner)
            if declared != length - header:
                raise ProtocolError("inner/outer length mismatch")
            if message >> 16 != channel:
                raise ProtocolError("message/channel mismatch")
            result.append(Packet(channel, message, inner[header:], timestamp))
        if len(self.buffer) > self.maximum + 8:
            raise ProtocolError("pending buffer exceeds bound")
        return result

    def eof(self):
        if self.buffer:
            raise ProtocolError(f"truncated EOF: {len(self.buffer)} pending bytes")


def varint(value):
    if not 0 <= value < 1 << 64:
        raise ProtocolError("varint out of range")
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def protobuf(**fields):
    """Tiny proto2 encoder: keyword fN=int or bytes/UTF-8 string."""
    result = bytearray()
    for key, value in sorted(fields.items(), key=lambda x: int(x[0][1:])):
        number = int(key[1:])
        if not 1 <= number < 1 << 29:
            raise ProtocolError("invalid field number")
        if isinstance(value, int):
            result += varint(number << 3) + varint(value)
        else:
            value = value.encode() if isinstance(value, str) else bytes(value)
            result += varint((number << 3) | 2) + varint(len(value)) + value
    return bytes(result)


def fields(payload):
    result = {}
    pos = 0

    def integer():
        nonlocal pos
        value = 0
        for shift in range(0, 70, 7):
            if pos >= len(payload):
                raise ProtocolError("truncated protobuf varint")
            byte = payload[pos]
            pos += 1
            if shift == 63 and byte > 1:
                raise ProtocolError("protobuf varint overflow")
            value |= (byte & 127) << shift
            if not byte & 128:
                return value
        raise ProtocolError("protobuf varint overflow")

    while pos < len(payload):
        key = integer()
        number, wire = key >> 3, key & 7
        if number == 0:
            raise ProtocolError("invalid protobuf field zero")
        if wire == 0:
            value = integer()
        elif wire in (1, 2, 5):
            size = integer() if wire == 2 else (8 if wire == 1 else 4)
            if size > len(payload) - pos:
                raise ProtocolError("truncated protobuf body")
            value = payload[pos:pos + size]
            pos += size
        else:
            raise ProtocolError("unsupported protobuf wire type")
        result[number] = value
    return result


class AuthProvider:
    """Explicit extension point. No borrowed private keys or fabricated ACK."""
    def handle(self, packet):
        raise ProtocolError("BLOCKED_AUTH_PROVIDER_REQUIRED")


class HUSession:
    profile = "SYNTHETIC_NO_AUTH_V1"
    interoperability = "SELF"

    def __init__(self, width=1280, height=720, fps=30, auth_provider=None, allow_mode_fallback=False):
        if not (0 < width <= 4096 and 0 < height <= 4096 and 0 < fps <= 120):
            raise ProtocolError("invalid requested video mode")
        self.width, self.height, self.fps = width, height, fps
        self.requested = {"width": width, "height": height, "fps": fps, "provenance": "explicit_HU_request"}
        self.accepted = None
        self.allow_mode_fallback = allow_mode_fallback
        self.state = "NEW"
        self.auth_provider = auth_provider or AuthProvider()
        self.unknown = 0
        self.duplicates = 0
        self.md_info = {}
        self.reset_pending = False

    def start(self):
        if self.state != "NEW":
            raise ProtocolError("duplicate local start")
        self.state = "WAIT_VERSION"
        return [Packet(CMD, HU_VERSION, protobuf(f1=1, f2=0))]

    def receive(self, packet):
        if packet.message in AUTH_IDS:
            self.state = "BLOCKED_AUTH"
            return self.auth_provider.handle(packet)
        if packet.message == VERSION_MATCH:
            if self.state != "WAIT_VERSION":
                self.duplicates += 1
                return []
            if fields(packet.payload).get(1) != 1:
                self.state = "VERSION_REJECTED"
                raise ProtocolError("MD rejected version")
            self.state = "WAIT_INIT"
            return [Packet(CMD, HU_INFO, protobuf(f1="macOS", f4="Mac HU Lab",
                                                  f14="Development HU", f19="1")),
                    Packet(CMD, VIDEO_INIT, protobuf(f1=self.width, f2=self.height, f3=self.fps))]
        if packet.message == MD_INFO:
            self.md_info = {str(k): v.decode("utf-8", "replace") if isinstance(v, bytes) else v
                            for k, v in fields(packet.payload).items() if k not in (9, 16, 22, 23)}
            return []
        if packet.message == VIDEO_INIT_DONE:
            if self.state != "WAIT_INIT":
                self.duplicates += 1
                return []
            info = fields(packet.payload)
            if not all(isinstance(info.get(field), int) for field in (1, 2, 3)):
                raise ProtocolError("INIT_DONE missing required int32 width/height/frameRate")
            if not (0 < info[1] <= 4096 and 0 < info[2] <= 4096 and 0 < info[3] <= 120):
                raise ProtocolError("invalid accepted video mode")
            if not self.allow_mode_fallback and (info.get(1), info.get(2)) != (self.width, self.height):
                raise ProtocolError("MD encoder dimensions differ from request")
            self.width, self.height, self.fps = info[1], info[2], info[3]
            self.accepted = {"width": self.width, "height": self.height, "fps": self.fps,
                             "provenance": "MD_VIDEO_INIT_DONE", "actual_SPS_check": "pending"}
            self.state = "STREAMING"
            self.reset_pending = False
            return [Packet(CMD, VIDEO_START)]
        if packet.channel == VIDEO and packet.message in (VIDEO_DATA, VIDEO_HEARTBEAT):
            if self.state == "WAIT_INIT" and self.reset_pending:
                return []  # In-flight prior-generation bytes, caller must discard.
            if self.state not in ("STREAMING", "PAUSED"):
                raise ProtocolError("video before negotiation")
            return []
        self.unknown += 1
        return []

    def pause(self):
        if self.state != "STREAMING":
            raise ProtocolError("pause outside streaming")
        self.state = "PAUSED"
        return Packet(CMD, VIDEO_PAUSE)

    def resume(self):
        if self.state != "PAUSED":
            raise ProtocolError("resume outside pause")
        self.state = "STREAMING"
        return Packet(CMD, VIDEO_START)

    def reset(self):
        if self.state not in ("STREAMING", "PAUSED"):
            raise ProtocolError("reset outside established session")
        self.state = "WAIT_INIT"
        self.reset_pending = True
        return [Packet(CMD, VIDEO_RESET),
                Packet(CMD, VIDEO_INIT, protobuf(f1=self.width, f2=self.height, f3=self.fps))]
