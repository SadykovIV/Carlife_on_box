"""Local projection policy; no device I/O and no CarLife wire extensions.

The legacy video message carries width, height and frameRate, not DPI. A plan
therefore separates requested video, measured Android layout, physical panel
data, and the encoder mode which must still be observed after negotiation.
"""
from dataclasses import dataclass, replace
import hashlib
import json
import math
from pathlib import Path


class ProjectionError(ValueError):
    pass


def _integer(value, low, high, name):
    if type(value) is not int or not low <= value <= high:
        raise ProjectionError(f'{name}: integer in {low}..{high} required')
    return value


def _size(value, name):
    if not isinstance(value, (tuple, list)) or len(value) != 2:
        raise ProjectionError(f'{name}: [width, height] required')
    return tuple(_integer(n, 1, 16384, name) for n in value)


def _density(value, name):
    if value in (None, 0) and type(value) is not bool:
        return None
    return _integer(value, 1, 640, name)


def _rotation(value):
    if type(value) is not int or value not in (0, 90, 180, 270):
        raise ProjectionError('rotation must be 0, 90, 180 or 270 degrees')
    return value


def _ppi(value):
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
        raise ProjectionError('physical PPI must be finite and positive, or unknown')
    return value


def _field(value, unit, source, evidence=None):
    return {'value': value, 'unit': unit, 'provenance': source if value is not None else 'unknown',
            'evidence': evidence}


@dataclass(frozen=True)
class HuCaps:
    identity: str | None = None
    version: str | None = None
    identity_kind: str | None = None
    identity_confirmed: bool = False
    panel_size: tuple | None = None
    physical_ppi: float | None = None
    viewport: tuple | None = None  # x, y, width, height in physical panel pixels
    insets: tuple | None = None  # left, top, right, bottom in panel pixels
    rotation: int | None = None
    supported_modes: tuple | None = None  # (width, height, fps); None means unknown
    provenance: str = 'unknown'
    evidence: str | None = None


@dataclass(frozen=True)
class AndroidDisplayState:
    display_id: int | None
    logical_size: tuple
    density_dpi: int | None
    rotation: int = 0
    source_crop: tuple | None = None
    provenance: str = 'runtime_measurement'
    evidence: str | None = None


@dataclass(frozen=True)
class EncoderCaps:
    name: str | None = None
    max_width: int | None = None
    max_height: int | None = None
    max_fps: float | None = None
    width_alignment: int | None = None
    height_alignment: int | None = None
    supported_modes: tuple | None = None
    provenance: str = 'unknown'
    evidence: str | None = None


@dataclass(frozen=True)
class UserProfile:
    identity: str
    version: str
    identity_kind: str
    panel_size: tuple
    source_size: tuple
    source_rotation: int
    video_mode: tuple | None = None
    isolated_density_dpi: int | None = None
    physical_ppi: float | None = None
    schema_version: int = 1
    sha256: str | None = None
    evidence: str = 'manual_profile_object'


def load_hu_profile(value):
    """Load a bounded local JSON profile. Loading alone does not confirm its HU.

    Schema 1 example (values are illustrative, never discovered capabilities)::

        {"schema_version": 1,
         "identity": {"id": "confirmed-model", "version": "firmware-version",
                      "kind": "vendor_model_firmware"},
         "panel_native_pixels": [1280, 720],
         "source_geometry": {"logical_pixels": [1280, 720], "rotation_degrees": 0},
         "video_mode": [1280, 720, 20], "isolated_density_dpi": 213}

    source_geometry binds the effective capture display: the requested owned
    display when isolated, or the measured existing source otherwise. To apply:
    build_projection_config(..., hu_profile=Path('hu.json'), hu_caps=verified_caps,
                            isolated_size=(1280, 720)).to_dict()
    verified_caps must come from an independent confirmed identity/version and
    panel measurement; do not construct it by copying fields from this file.
    """
    if isinstance(value, UserProfile):
        profile = value
    else:
        digest, evidence = None, 'manual_profile_object'
        if isinstance(value, (str, Path)):
            path = Path(value).expanduser().resolve()
            if path.stat().st_size > 65536:
                raise ProjectionError('HU_PROFILE_TOO_LARGE')
            raw = path.read_bytes()
            if len(raw) > 65536:
                raise ProjectionError('HU_PROFILE_TOO_LARGE')
            digest, evidence = hashlib.sha256(raw).hexdigest(), str(path)
            try:
                value = json.loads(raw)
            except (ValueError, UnicodeError) as exc:
                raise ProjectionError('HU_PROFILE_INVALID_JSON') from exc
        if not isinstance(value, dict):
            raise ProjectionError('HU_PROFILE_OBJECT_REQUIRED')
        allowed = {'schema_version', 'identity', 'panel_native_pixels', 'source_geometry',
                   'video_mode', 'isolated_density_dpi', 'physical_ppi'}
        if set(value) - allowed:
            raise ProjectionError('HU_PROFILE_UNKNOWN_FIELDS: ' + ','.join(sorted(set(value) - allowed)))
        identity, source = value.get('identity'), value.get('source_geometry')
        if not isinstance(identity, dict) or set(identity) != {'id', 'version', 'kind'}:
            raise ProjectionError('HU_PROFILE_IDENTITY_REQUIRED: id, version, kind')
        if not isinstance(source, dict) or set(source) != {'logical_pixels', 'rotation_degrees'}:
            raise ProjectionError('HU_PROFILE_SOURCE_GEOMETRY_REQUIRED')
        profile = UserProfile(identity['id'], identity['version'], identity['kind'],
            value.get('panel_native_pixels'), source['logical_pixels'], source['rotation_degrees'],
            value.get('video_mode'), value.get('isolated_density_dpi'), value.get('physical_ppi'),
            value.get('schema_version'), digest, evidence)
    if type(profile.schema_version) is not int or profile.schema_version != 1:
        raise ProjectionError('HU_PROFILE_UNSUPPORTED_SCHEMA_VERSION')
    if any(not isinstance(v, str) or not v.strip() or len(v) > 256 for v in (profile.identity, profile.version)):
        raise ProjectionError('HU_PROFILE_STABLE_IDENTITY_AND_VERSION_REQUIRED')
    if profile.identity_kind not in ('vendor_model_firmware', 'usb_identity', 'lab_software_build', 'manual_confirmed'):
        raise ProjectionError('HU_PROFILE_IDENTITY_KIND_UNSUPPORTED: Bluetooth/display name is insufficient')
    panel, source = _size(profile.panel_size, 'profile panel'), _size(profile.source_size, 'profile source')
    rotation = _rotation(profile.source_rotation)
    mode = _mode(profile.video_mode) if profile.video_mode is not None else None
    density = _density(profile.isolated_density_dpi, 'profile isolated density')
    if density is not None:
        _integer(density, 120, 640, 'profile isolated density')
    return replace(profile, panel_size=panel, source_size=source, source_rotation=rotation,
                   video_mode=mode, isolated_density_dpi=density, physical_ppi=_ppi(profile.physical_ppi))


def _mode(value):
    if not isinstance(value, (tuple, list)) or len(value) != 3:
        raise ProjectionError('video mode requires [width, height, fps]')
    return (_integer(value[0], 320, 1920, 'video width'),
            _integer(value[1], 180, 1080, 'video height'),
            _integer(value[2], 1, 60, 'video fps'))


def _check_modes(mode, available, name):
    if available is None:
        return
    modes = []
    for value in available:
        if not isinstance(value, (tuple, list)) or len(value) != 3:
            raise ProjectionError(name + '_INVALID_CAPABILITY_MODE')
        # Observed hardware may support modes outside the lab's request bounds.
        modes.append((*_size(value[:2], name + ' mode'), _integer(value[2], 1, 1000, name + ' fps')))
    if mode not in modes:
        raise ProjectionError(name + '_MODE_NOT_CONFIRMED: no automatic fallback')


@dataclass(frozen=True)
class ProjectionConfig:
    hu: HuCaps
    android: AndroidDisplayState
    encoder_caps: EncoderCaps
    user_profile: UserProfile | None
    requested_mode: tuple
    generation: int
    isolated: bool
    source_density_before: int | None
    source_display_before: int = 0
    accepted_encoder: dict | None = None

    def to_dict(self):
        h, a, e, p = self.hu, self.android, self.encoder_caps, self.user_profile
        mode = self.requested_mode
        profile_evidence = p.evidence if p else None
        panel_ppi = p.physical_ppi if p and p.physical_ppi is not None else h.physical_ppi
        ppi_source = 'confirmed_per_hu_profile' if p and p.physical_ppi is not None else h.provenance
        return {
            'schema_version': 1, 'status': 'PLANNED_NOT_NEGOTIATED' if self.accepted_encoder is None else 'ENCODER_OBSERVED',
            'generation': self.generation, 'interoperability': 'SELF_UNTIL_TARGET_HU_VALIDATION',
            'hu': {'identity': _field(h.identity, 'stable_identifier', h.provenance, h.evidence),
                'version': _field(h.version, 'version_string', h.provenance, h.evidence),
                'panel_native_pixels': _field(h.panel_size, 'pixels', h.provenance, h.evidence),
                'physical_ppi': _field(panel_ppi, 'physical_pixels_per_inch', ppi_source, profile_evidence or h.evidence),
                'viewport': _field(h.viewport, 'panel_pixels_xywh', h.provenance, h.evidence),
                'insets': _field(h.insets, 'panel_pixels_ltrb', h.provenance, h.evidence),
                'rotation': _field(h.rotation, 'degrees', h.provenance, h.evidence),
                'decoder_modes': _field(h.supported_modes, 'width_px_height_px_fps', h.provenance, h.evidence)},
            'requested_video': {'width': _field(mode[0], 'pixels', 'manual_setting'),
                'height': _field(mode[1], 'pixels', 'manual_setting'),
                'fps': _field(mode[2], 'frames_per_second', 'manual_setting'),
                'wire_fields': ['width', 'height', 'frameRate'], 'wire_density_field': None,
                'acceptance': 'INIT_DONE and observed encoder/SPS required; request is not acceptance'},
            'encoder_caps': {name: _field(getattr(e, name), unit, e.provenance, e.evidence)
                for name, unit in (('name', 'codec_name'), ('max_width', 'pixels'), ('max_height', 'pixels'),
                    ('max_fps', 'frames_per_second'), ('width_alignment', 'pixels'), ('height_alignment', 'pixels'),
                    ('supported_modes', 'width_px_height_px_fps'))},
            'accepted_encoder': self.accepted_encoder,
            'android_source': {'display_id': _field(a.display_id, 'Android_display_id', a.provenance, a.evidence),
                'logical_pixels': _field(a.logical_size, 'pixels', a.provenance, a.evidence),
                'density_dpi': _field(a.density_dpi, 'Android_logical_density_dpi', a.provenance, a.evidence),
                'rotation': _field(a.rotation, 'degrees', a.provenance, a.evidence),
                'source_crop': _field(a.source_crop, 'source_pixels_xywh', a.provenance, a.evidence)},
            'ui_layout': {'policy': 'owned_isolated_display' if self.isolated else 'preserve_source_display',
                'primary_density_change': False,
                'baseline_source_density': _field(self.source_density_before, 'Android_logical_density_dpi', 'runtime_measurement'),
                'primary_density_before': _field(self.source_density_before if self.source_display_before == 0 else None,
                                                 'Android_logical_density_dpi', 'runtime_measurement'),
                'density_to_apply_to_owned_display': a.density_dpi if self.isolated else None,
                'auto_density': 'UNSUPPORTED_NO_CONFIRMED_HU_LAYOUT_CONTRACT',
                'note': 'Pixel scaling alone does not relayout Android applications; isolated Activity success is not full Android desktop support'},
            'coordinate_contract': {'touch_wire_space': 'video_pixels',
                'touch_ranges': {'x': [0, mode[0] - 1], 'y': [0, mode[1] - 1]},
                'source_mapping': 'SourceDisplayTransform', 'renderer_mapping': 'ViewportTransform',
                'policy': 'fit', 'outside': 'reject DOWN; CANCEL active gesture',
                'mac_viewport': _field(None, 'Qt_logical_pixels_xywh', 'unknown'),
                'mac_backing_scale': _field(None, 'device_pixels_per_Qt_logical_pixel', 'unknown'),
                'mac_geometry_evidence': 'recorded by the live viewport_geometry event; never used as HU DPI'},
            'manual_profile': None if p is None else {'schema_version': p.schema_version,
                'sha256': p.sha256, 'evidence': p.evidence, 'validation': 'IDENTITY_VERSION_PANEL_SOURCE_MATCH'},
        }

    def with_encoder_observation(self, *, width, height, fps, coded_size, visible_crop,
                                 profile, level, evidence):
        """Attach measured output; never manufacture INIT_DONE from the request."""
        mode = _mode((width, height, fps))
        if mode != self.requested_mode:
            raise ProjectionError('ENCODER_ACCEPTED_MODE_MISMATCH: explicit renegotiation required')
        coded = _size(coded_size, 'coded size')
        crop = _crop(visible_crop, coded, 'visible crop')
        if tuple(crop[2:]) != mode[:2] or not evidence or profile is None or level is None:
            raise ProjectionError('ENCODER_OBSERVATION_INCOMPLETE_OR_CROP_MISMATCH')
        fields = {name: _field(value, unit, 'runtime_measurement', evidence) for name, value, unit in (
            ('width', width, 'pixels'), ('height', height, 'pixels'), ('fps', fps, 'frames_per_second'),
            ('coded_size', coded, 'pixels'), ('visible_crop', crop, 'coded_pixels_xywh'),
            ('profile', profile, 'codec_profile'), ('level', level, 'codec_level'))}
        return replace(self, accepted_encoder=fields)


def _crop(value, size, name):
    if not isinstance(value, (tuple, list)) or len(value) != 4:
        raise ProjectionError(name + ': [x,y,width,height] required')
    x, y, w, h = (_integer(v, 0, 16384, name) for v in value)
    if w == 0 or h == 0 or x + w > size[0] or y + h > size[1]:
        raise ProjectionError(name + ': outside display')
    return x, y, w, h


def build_projection_config(requested_width, requested_height, fps, source_size, source_density,
                            *, isolated_density=0, isolated_size=None, hu_profile=None,
                            hu_caps=None, encoder_caps=None, source_display=0, rotation=0,
                            generation=0, ui_scale='keep', primary_density=None,
                            primary_recovery_verified=False, source_evidence=None):
    """Build a bounded plan before install. Unknown capabilities stay unknown.

    ``source_size``/``source_density`` are the measured existing Android display.
    ``isolated_size``/``isolated_density`` describe a new owned display. Its ID is
    unknown until creation. ``hu_caps`` must independently identify a profile's
    HU; profile metadata cannot confirm itself. This module never mutates state.
    """
    mode = _mode((requested_width, requested_height, fps))
    size = _size(source_size, 'source size')
    density = _density(source_density, 'source density')
    rotation = _rotation(rotation)
    _integer(source_display, 0, 1000, 'source display ID')
    _integer(generation, 0, 255, 'generation')
    if ui_scale == 'auto':
        raise ProjectionError('AUTO_DENSITY_UNSUPPORTED: no proven CarLife HU DPI/layout contract')
    if ui_scale not in ('keep', 'manual-isolated-density'):
        raise ProjectionError('UNKNOWN_UI_SCALE_POLICY')
    if primary_density not in (None, 0):
        if not primary_recovery_verified:
            raise ProjectionError('PRIMARY_DENSITY_RECOVERY_NOT_VERIFIED')
        raise ProjectionError('PRIMARY_DENSITY_APPLY_UNIMPLEMENTED: only owned isolated display changes are implemented')
    h, e = hu_caps or HuCaps(), encoder_caps or EncoderCaps()
    if not isinstance(h, HuCaps) or not isinstance(e, EncoderCaps):
        raise ProjectionError('HuCaps and EncoderCaps instances required')
    h = replace(h, panel_size=_size(h.panel_size, 'HU panel') if h.panel_size is not None else None,
                physical_ppi=_ppi(h.physical_ppi), rotation=_rotation(h.rotation) if h.rotation is not None else None)
    if h.viewport is not None:
        if h.panel_size is None:
            raise ProjectionError('HU_VIEWPORT_REQUIRES_PANEL_GEOMETRY')
        h = replace(h, viewport=_crop(h.viewport, h.panel_size, 'HU viewport'))
    if h.insets is not None:
        if h.panel_size is None or len(h.insets) != 4:
            raise ProjectionError('HU_INSETS_REQUIRE_PANEL_GEOMETRY')
        insets = tuple(_integer(n, 0, 16384, 'HU inset') for n in h.insets)
        if insets[0] + insets[2] >= h.panel_size[0] or insets[1] + insets[3] >= h.panel_size[1]:
            raise ProjectionError('HU_INSETS_CONSUME_VIEWPORT')
        h = replace(h, insets=insets)
    _check_modes(mode, h.supported_modes, 'HU_DECODER')
    _check_modes(mode, e.supported_modes, 'ENCODER')
    for requested, maximum, alignment, label in ((mode[0], e.max_width, e.width_alignment, 'width'),
                                                (mode[1], e.max_height, e.height_alignment, 'height')):
        if maximum is not None and requested > _integer(maximum, 1, 16384, 'encoder maximum ' + label):
            raise ProjectionError('ENCODER_' + label.upper() + '_EXCEEDED')
        if alignment is not None and requested % _integer(alignment, 1, 16384, 'encoder alignment'):
            raise ProjectionError('ENCODER_ALIGNMENT_MISMATCH')
    if e.max_fps is not None:
        if isinstance(e.max_fps, bool) or not isinstance(e.max_fps, (int, float)) or not math.isfinite(e.max_fps) or e.max_fps <= 0:
            raise ProjectionError('ENCODER_MAX_FPS_INVALID')
        if fps > e.max_fps:
            raise ProjectionError('ENCODER_FPS_EXCEEDED')
    p = load_hu_profile(hu_profile) if hu_profile is not None else None
    if p:
        if not h.identity_confirmed or h.provenance == 'unknown':
            raise ProjectionError('HU_PROFILE_IDENTITY_NOT_INDEPENDENTLY_CONFIRMED')
        if (h.identity, h.version, h.identity_kind) != (p.identity, p.version, p.identity_kind):
            raise ProjectionError('HU_PROFILE_IDENTITY_VERSION_MISMATCH')
        effective_size = _size(isolated_size, 'isolated size') if isolated_size is not None else size
        effective_rotation = 0 if isolated_size is not None else rotation
        if h.panel_size != p.panel_size or effective_size != p.source_size or effective_rotation != p.source_rotation:
            raise ProjectionError('HU_PROFILE_PANEL_SOURCE_GEOMETRY_MISMATCH')
        if p.video_mode is not None and mode != p.video_mode:
            raise ProjectionError('HU_PROFILE_VIDEO_MODE_MISMATCH')
        if h.physical_ppi is not None and p.physical_ppi is not None and h.physical_ppi != p.physical_ppi:
            raise ProjectionError('HU_PROFILE_PPI_CONFLICT')
        if p.isolated_density_dpi is not None:
            if isolated_density not in (0, None, p.isolated_density_dpi):
                raise ProjectionError('HU_PROFILE_DENSITY_CONFLICT')
            isolated_density = p.isolated_density_dpi
    isolated = isolated_size is not None
    target_density = _density(isolated_density, 'isolated density')
    if isolated:
        target_size = _size(isolated_size, 'isolated size')
        _integer(target_size[0], 320, 1920, 'isolated width')
        _integer(target_size[1], 180, 1080, 'isolated height')
        _integer(target_density, 120, 640, 'isolated density')
        if source_display != 0 or rotation != 0:
            raise ProjectionError('ISOLATED_CREATION_REQUIRES_PRIMARY_BASELINE_ROTATION_ZERO')
        a = AndroidDisplayState(None, target_size, target_density, 0, (0, 0, *target_size),
                                'manual_setting', p.evidence if p else 'owned_display_creation_request')
    else:
        if target_density is not None or ui_scale == 'manual-isolated-density':
            raise ProjectionError('ISOLATED_DENSITY_REQUIRES_OWNED_DISPLAY_GEOMETRY')
        a = AndroidDisplayState(source_display, size, density, rotation, (0, 0, *size),
                                'runtime_measurement', source_evidence)
    return ProjectionConfig(h, a, e, p, mode, generation, isolated, density, source_display)
