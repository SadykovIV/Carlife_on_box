"""Independent CarLife touch implementation from pinned sender observations.

Reference: fgrcwp/apollo-DuerOS fddb0be12a24b501bee435a56be3f564a158fd39,
RemoteControlManager.kt + BitConverter.kt; see touch-geometry-reference.md.
Profiles are explicitly selected SELF contracts, never guessed from coordinates.
"""
from dataclasses import dataclass, asdict
import math
import struct
import time
from .protocol import Packet, ProtocolError, TOUCH, TOUCH_ACTION, protobuf, fields

ACTION3 = 0x6800E
DOWN, UP, MOVE, CANCEL, POINTER_DOWN, POINTER_UP = 0, 1, 2, 3, 5, 6
PROFILES = ("LEGACY_SINGLE", "LEGACY_DUAL", "ACTION3_MULTI")


def profile_name(value):
    value = value.upper().replace("-", "_")
    if value not in PROFILES:
        raise ProtocolError("unknown explicit touch profile")
    return value


@dataclass(frozen=True)
class Pointer:
    id: int
    x: float
    y: float


@dataclass(frozen=True)
class TouchFrame:
    action: int
    pointers: tuple[Pointer, ...]
    action_index: int = 0
    generation: int = 0
    display_id: int = 0
    event_time_ns: int = 0
    source: str = "explicit_lab"

    @property
    def full_action(self):
        return self.action | (self.action_index << 8)

    def validate(self, maximum=10):
        if self.action not in (DOWN, UP, MOVE, CANCEL, POINTER_DOWN, POINTER_UP):
            raise ProtocolError("unsupported touch action")
        if not 1 <= len(self.pointers) <= maximum:
            raise ProtocolError("invalid pointer count")
        if len({p.id for p in self.pointers}) != len(self.pointers):
            raise ProtocolError("duplicate pointer IDs")
        if any(not 0 <= p.id <= 31 for p in self.pointers):
            raise ProtocolError("pointer ID outside Android lab range 0..31")
        if self.action in (POINTER_DOWN, POINTER_UP):
            if len(self.pointers) < 2 or not 0 <= self.action_index < len(self.pointers):
                raise ProtocolError("invalid pointer action index")
        elif self.action_index != 0:
            raise ProtocolError("index only valid for POINTER_DOWN/UP")
        if self.action in (DOWN, UP) and len(self.pointers) != 1:
            raise ProtocolError("DOWN/UP must have one pointer")
        if any(not math.isfinite(p.x) or not math.isfinite(p.y) or
               not 0 <= p.x <= 65535 or not 0 <= p.y <= 65535 for p in self.pointers):
            raise ProtocolError("touch coordinates outside uint16 video space")
        return self

    def record(self):
        return asdict(self)


class TouchTracker:
    """Validate the Android contact lifecycle before serializing or injecting.

    IDs survive array compaction. Legacy wire omits IDs, so its decoder can only
    reconstruct this lifecycle from an unbroken stream beginning with DOWN.
    """
    def __init__(self):
        self.active = ()
        self.generation = None

    def accept(self, frame):
        frame.validate()
        ids = tuple(p.id for p in frame.pointers)
        old = tuple(p.id for p in self.active)
        if self.active and frame.generation != self.generation:
            raise ProtocolError("active pointers belong to another generation")
        if frame.action == DOWN:
            if old:
                raise ProtocolError("DOWN while contacts active")
        elif frame.action == CANCEL:
            if not old or ids != old:
                raise ProtocolError("CANCEL contacts do not match active contacts")
        elif frame.action == POINTER_DOWN:
            remain = ids[:frame.action_index] + ids[frame.action_index + 1:]
            if remain != old or ids[frame.action_index] in old:
                raise ProtocolError("POINTER_DOWN must add one new ID at action index")
        elif ids != old or not old:
            raise ProtocolError("touch IDs/order differ from active contacts")
        if frame.action in (UP, CANCEL):
            self.active = ()
        elif frame.action == POINTER_UP:
            self.active = frame.pointers[:frame.action_index] + frame.pointers[frame.action_index + 1:]
        else:
            self.active = frame.pointers
        self.generation = frame.generation


def encode_touch(frame, profile):
    profile = profile_name(profile)
    frame.validate(1 if profile == "LEGACY_SINGLE" else 2 if profile == "LEGACY_DUAL" else 10)
    if profile == "ACTION3_MULTI":
        payload = struct.pack(">I", frame.full_action)
        payload += b"".join(struct.pack(">BHH", p.id, int(p.x), int(p.y)) for p in frame.pointers)
        return Packet(TOUCH, ACTION3, payload)
    p = frame.pointers[0]
    values = dict(f1=frame.full_action, f2=int(p.x), f3=int(p.y))
    if profile == "LEGACY_DUAL":
        if any(p.id not in (0, 1) for p in frame.pointers):
            raise ProtocolError("legacy dual IDs are synthetic 0/1 only")
        q = frame.pointers[1] if len(frame.pointers) > 1 else Pointer(0, 0, 0)
        values.update(f4=int(q.x), f5=int(q.y))
    return Packet(TOUCH, TOUCH_ACTION, protobuf(**values))


class TouchDecoder:
    """Strict offline oracle/state model, independently bounded from packet header.

    Legacy IDs are synthetic: slot0 starts as ID0, second contact as ID1; retained
    until release. We never infer a missing pointer from zero coordinates/fields.
    """
    def __init__(self, profile):
        self.profile = profile_name(profile)
        self.tracker = TouchTracker()

    def decode(self, packet, generation=0):
        if self.profile == "ACTION3_MULTI":
            if packet.message != ACTION3 or len(packet.payload) < 9 or (len(packet.payload) - 4) % 5:
                raise ProtocolError("malformed ACTION3 length/opcode")
            count = (len(packet.payload) - 4) // 5
            if count > 10:
                raise ProtocolError("ACTION3 pointer count exceeds lab limit")
            action = struct.unpack_from(">I", packet.payload)[0]
            points = tuple(Pointer(*struct.unpack_from(">BHH", packet.payload, 4 + n * 5)) for n in range(count))
        else:
            if packet.message != TOUCH_ACTION:
                raise ProtocolError("opcode does not match explicit legacy profile")
            f = fields(packet.payload)
            if not all(isinstance(f.get(k), int) for k in (1, 2, 3)):
                raise ProtocolError("missing required legacy touch fields")
            action = f[1]
            masked, index = action & 255, (action >> 8) & 255
            ids = tuple(p.id for p in self.tracker.active)
            if masked == DOWN:
                ids = (0,)
            elif masked == POINTER_DOWN:
                if self.profile != "LEGACY_DUAL" or len(ids) != 1 or index not in (0, 1):
                    raise ProtocolError("legacy dual POINTER_DOWN cannot be reconstructed")
                ids = ids[:index] + (1 - ids[0],) + ids[index:]
            if not ids:
                raise ProtocolError("legacy event lacks DOWN history")
            if self.profile == "LEGACY_SINGLE" and len(ids) > 1:
                raise ProtocolError("legacy single cannot carry two pointers")
            if len(ids) == 2 and not all(isinstance(f.get(k), int) for k in (4, 5)):
                raise ProtocolError("dual lifecycle requires pointer coordinates")
            points = (Pointer(ids[0], f[2], f[3]),)
            if len(ids) == 2:
                points += (Pointer(ids[1], f[4], f[5]),)
        if action & ~0xffff:
            raise ProtocolError("unsupported high touch action bits")
        frame = TouchFrame(action & 255, points, (action >> 8) & 255, generation)
        self.tracker.accept(frame)
        return frame


def two_pointer_gesture(width, height, kind="pinch-out", release_first=0, steps=12, generation=0):
    """Explicit synthetic absolute-pointer path, not native trackpad fingers."""
    if kind not in ("pinch-out", "pinch-in", "pan", "rotate") or release_first not in (0, 1) or not 1 <= steps <= 120:
        raise ProtocolError("unsupported scripted gesture options")
    cx, cy = width * .5, height * .5
    def positions(t):
        distance = min(width, height) * ((.10 + .15 * t) if kind != "pinch-in" else (.25 - .15 * t))
        angle = t * math.pi / 2 if kind == "rotate" else 0
        if kind in ("pan", "rotate"):
            distance = min(width, height) * .15
        dx = width * .1 * t if kind == "pan" else 0
        dy = height * .1 * t if kind == "pan" else 0
        return (Pointer(0, cx + dx - distance * math.cos(angle), cy + dy - distance * math.sin(angle)),
                Pointer(1, cx + dx + distance * math.cos(angle), cy + dy + distance * math.sin(angle)))
    def f(action, pts, index=0):
        return TouchFrame(action, pts, index, generation, event_time_ns=time.monotonic_ns(), source="synthetic_two_pointer")
    points = positions(0)
    yield f(DOWN, points[:1])
    yield f(POINTER_DOWN, points, 1)
    for n in range(1, steps + 1):
        points = positions(n / steps)
        yield f(MOVE, points)
    yield f(POINTER_UP, points, release_first)
    yield f(UP, (points[1 - release_first],))
