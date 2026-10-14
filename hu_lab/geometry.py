"""One invertible geometry for Qt rendering and touchscreen mapping.

Qt input and renderer both use logical coordinates. Device pixel ratio is
recorded, never multiplied a second time. Wire space is video pixels, not dp.
"""
from dataclasses import dataclass
import math
from .protocol import ProtocolError


@dataclass(frozen=True)
class Rect:
    x: float
    y: float
    width: float
    height: float

    def contains(self, x, y):
        return self.x <= x < self.x + self.width and self.y <= y < self.y + self.height


class ViewportTransform:
    def __init__(self, width, height, viewport, policy="fit", rotation=0, crop=None, backing_scale=1):
        if min(width, height, viewport.width, viewport.height, backing_scale) <= 0:
            raise ProtocolError("non-positive geometry")
        if policy not in ("fit", "crop") or rotation not in (0, 90, 180, 270):
            raise ProtocolError("unsupported geometry policy/rotation")
        self.width, self.height, self.viewport = width, height, viewport
        self.policy, self.rotation, self.backing_scale = policy, rotation, backing_scale
        self.crop = crop or Rect(0, 0, width, height)
        c = self.crop
        if min(c.width, c.height) <= 0 or c.x < 0 or c.y < 0 or c.x + c.width > width or c.y + c.height > height:
            raise ProtocolError("source crop outside image")
        rw, rh = (c.height, c.width) if rotation in (90, 270) else (c.width, c.height)
        self.scale = (min if policy == "fit" else max)(viewport.width / rw, viewport.height / rh)
        w, h = rw * self.scale, rh * self.scale
        self.rectangle = Rect(viewport.x + (viewport.width - w) / 2, viewport.y + (viewport.height - h) / 2, w, h)

    def _edge_to_view(self, x, y):
        c, r, s = self.crop, self.rectangle, self.scale
        x, y = x - c.x, y - c.y
        x, y = {0: (x, y), 90: (c.height - y, x), 180: (c.width - x, c.height - y), 270: (y, c.width - x)}[self.rotation]
        return r.x + x * s, r.y + y * s

    def video_to_view(self, x, y):
        """Map a video pixel centre (0 is first pixel) into Qt logical space."""
        return self._edge_to_view(x + .5, y + .5)

    def view_to_video(self, x, y, quantize=True):
        if not self.viewport.contains(x, y) or not self.rectangle.contains(x, y):
            return None
        c, r, s = self.crop, self.rectangle, self.scale
        x, y = (x - r.x) / s, (y - r.y) / s
        x, y = {0: (x, y), 90: (y, c.height - x), 180: (c.width - x, c.height - y), 270: (c.width - y, x)}[self.rotation]
        x, y = x + c.x - .5, y + c.y - .5
        # Rotated boundary can map exactly to the far edge. It is an image edge,
        # not a letterbox point; map to the last pixel without accepting outside.
        x, y = min(self.width - 1, max(0, x)), min(self.height - 1, max(0, y))
        return (min(self.width - 1, round(x)), min(self.height - 1, round(y))) if quantize else (x, y)

    @property
    def affine(self):
        # QPainter addresses image edges, whereas touch addresses pixel centres.
        x0, y0 = self._edge_to_view(0, 0)
        x1, y1 = self._edge_to_view(1, 0)
        x2, y2 = self._edge_to_view(0, 1)
        return x1 - x0, y1 - y0, x2 - x0, y2 - y0, x0, y0

    def describe(self):
        return {"video": [self.width, self.height], "viewport": vars(self.viewport),
                "image_rectangle": vars(self.rectangle), "source_crop": vars(self.crop),
                "policy": self.policy, "rotation": self.rotation, "qt_coordinate_space": "logical",
                "backing_scale_recorded_only": self.backing_scale, "affine": self.affine,
                "wire_space": "video_pixels", "outside_policy": "reject DOWN, CANCEL active gesture"}


class SourceDisplayTransform:
    """Explicit lab profile matching MD EGL integer viewport and pixel centres.

    This is not a CarLife wire extension. Source dimensions require measured or
    manually confirmed metadata. Qt transforms operate on the already encoded
    video; this transform belongs only between that video and Android display.
    """
    def __init__(self, source_width, source_height, video_width, video_height, rotation=0):
        if min(source_width, source_height, video_width, video_height) <= 0 or rotation not in (0, 90, 180, 270):
            raise ProtocolError("invalid source display geometry")
        self.sw, self.sh, self.vw, self.vh, self.rotation = source_width, source_height, video_width, video_height, rotation
        rw, rh = (source_height, source_width) if rotation % 180 else (source_width, source_height)
        scale = min(video_width / rw, video_height / rh)
        cw, ch = min(video_width, math.floor(rw * scale + .5)), min(video_height, math.floor(rh * scale + .5))
        self.content = Rect((video_width - cw) // 2, (video_height - ch) // 2, cw, ch)

    def source_to_video(self, x, y):
        u, v = (x + .5) / self.sw, (y + .5) / self.sh
        u, v = {0: (u, v), 90: (1-v, u), 180: (1-u, 1-v), 270: (v, 1-u)}[self.rotation]
        c = self.content
        return c.x + u * c.width - .5, c.y + v * c.height - .5

    def video_to_source(self, x, y):
        c = self.content
        if not c.contains(x, y):
            return None
        u, v = (x - c.x + .5) / c.width, (y - c.y + .5) / c.height
        u, v = {0: (u, v), 90: (v, 1-u), 180: (1-u, 1-v), 270: (1-v, u)}[self.rotation]
        return max(0, min(self.sw - 1, u * self.sw - .5)), max(0, min(self.sh - 1, v * self.sh - .5))
