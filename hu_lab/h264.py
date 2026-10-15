"""Local H.264 SPS parser; no device or filesystem access."""
from fractions import Fraction


class Bits:
    def __init__(self, data):
        self.data, self.pos = data, 0

    def u(self, n):
        if n < 0 or self.pos + n > len(self.data) * 8:
            raise ValueError("truncated RBSP")
        result = 0
        for _ in range(n):
            result = (result << 1) | ((self.data[self.pos // 8] >> (7 - self.pos % 8)) & 1)
            self.pos += 1
        return result

    def ue(self):
        zeros = 0
        while self.u(1) == 0:
            zeros += 1
            if zeros > 31:
                raise ValueError("oversized Exp-Golomb code")
        return (1 << zeros) - 1 + self.u(zeros)

    def se(self):
        code = self.ue()
        return (code + 1) // 2 if code & 1 else -(code // 2)


def rbsp(ebsp):
    """Remove valid emulation-prevention bytes from an H.264 NAL payload."""
    result = bytearray()
    zeros = 0
    for i, value in enumerate(ebsp):
        if zeros >= 2 and value == 3 and i + 1 < len(ebsp) and ebsp[i + 1] <= 3:
            zeros = 0
            continue
        result.append(value)
        zeros = zeros + 1 if value == 0 else 0
    return bytes(result)


def skip_scaling_list(bits, size):
    last = nxt = 8
    for _ in range(size):
        if nxt:
            nxt = (last + bits.se() + 256) % 256
        last = nxt or last


def parse_sps(payload):
    bits = Bits(rbsp(payload))
    profile, constraints, level, sps_id = bits.u(8), bits.u(8), bits.u(8), bits.ue()
    chroma, separate = 1, 0
    result = dict(profile_idc=profile, constraint_flags_byte=constraints, level_idc=level,
                  seq_parameter_set_id=sps_id)
    if profile in {100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135}:
        chroma = bits.ue()
        if chroma > 3:
            raise ValueError("invalid chroma_format_idc")
        if chroma == 3:
            separate = bits.u(1)
        result["bit_depth_luma"] = bits.ue() + 8
        result["bit_depth_chroma"] = bits.ue() + 8
        bits.u(1)
        if bits.u(1):
            for i in range(8 if chroma != 3 else 12):
                if bits.u(1):
                    skip_scaling_list(bits, 16 if i < 6 else 64)
    result.update(chroma_format_idc=chroma, separate_colour_plane_flag=separate)
    result["log2_max_frame_num"] = bits.ue() + 4
    poc = bits.ue()
    result["pic_order_cnt_type"] = poc
    if poc == 0:
        result["log2_max_pic_order_cnt_lsb"] = bits.ue() + 4
    elif poc == 1:
        bits.u(1)
        bits.se()
        bits.se()
        count = bits.ue()
        if count > 255:
            raise ValueError("invalid num_ref_frames_in_pic_order_cnt_cycle")
        for _ in range(count):
            bits.se()
    elif poc != 2:
        raise ValueError("invalid pic_order_cnt_type")
    result["max_num_ref_frames"] = bits.ue()
    bits.u(1)
    width_mbs, height_map = bits.ue() + 1, bits.ue() + 1
    frame_only = bits.u(1)
    result["frame_mbs_only_flag"] = frame_only
    if not frame_only:
        bits.u(1)
    bits.u(1)
    crop = [bits.ue() for _ in range(4)] if bits.u(1) else [0, 0, 0, 0]
    chroma_array = 0 if separate else chroma
    subwidth = {0: 1, 1: 2, 2: 2, 3: 1}[chroma_array]
    subheight = {0: 1, 1: 2, 2: 1, 3: 1}[chroma_array]
    crop_x, crop_y = subwidth, subheight * (2 - frame_only)
    coded_width, coded_height = width_mbs * 16, height_map * 16 * (2 - frame_only)
    width = coded_width - crop_x * (crop[0] + crop[1])
    height = coded_height - crop_y * (crop[2] + crop[3])
    if width <= 0 or height <= 0:
        raise ValueError("invalid SPS crop")
    result.update(coded_width=coded_width, coded_height=coded_height,
                  display_width=width, display_height=height,
                  crop_offsets=dict(zip(("left", "right", "top", "bottom"), crop)),
                  crop_unit_x=crop_x, crop_unit_y=crop_y)
    result["vui_parameters_present_flag"] = bits.u(1)
    vui = {}
    if result["vui_parameters_present_flag"]:
        if bits.u(1):
            vui["aspect_ratio_idc"] = bits.u(8)
            if vui["aspect_ratio_idc"] == 255:
                vui["sar_width"], vui["sar_height"] = bits.u(16), bits.u(16)
        if bits.u(1):
            vui["overscan_appropriate_flag"] = bits.u(1)
        if bits.u(1):
            vui["video_format"], vui["video_full_range_flag"] = bits.u(3), bits.u(1)
            if bits.u(1):
                vui["colour_primaries"], vui["transfer_characteristics"], vui["matrix_coefficients"] = bits.u(8), bits.u(8), bits.u(8)
        if bits.u(1):
            vui["chroma_sample_loc_type_top_field"] = bits.ue()
            vui["chroma_sample_loc_type_bottom_field"] = bits.ue()
        vui["timing_info_present_flag"] = bits.u(1)
        if vui["timing_info_present_flag"]:
            tick, scale, fixed = bits.u(32), bits.u(32), bits.u(1)
            vui.update(num_units_in_tick=tick, time_scale=scale, fixed_frame_rate_flag=fixed)
            if tick and scale:
                rate = Fraction(scale, 2 * tick)
                vui["time_scale_over_2_ticks"] = str(rate)
                vui["nominal_fps_for_frame_picture_sequence"] = float(rate)
                vui["timing_note"] = "SPS VUI signalling, not measured timestamps or achieved capture FPS"
    result["vui"] = vui
    return result


# H.264 Annex A / Android MediaCodecInfo AVC level limits:
# level_idc -> (MaxFS, MaxMBPS, MaxDpbMbs). Keep measurements distinct from signalling.
AVC_LEVEL_LIMITS = {
    10: (99, 1485, 396),
    31: (3600, 108000, 18000),
    32: (5120, 216000, 20480),
    40: (8192, 245760, 32768),
    41: (8192, 245760, 32768),
    42: (8704, 522240, 34816),
}


def validate_sps_mode(sps, accepted=None, expected_baseline=False):
    """Check actual SPS against its level and accepted INIT_DONE mode, not a fixed 720p.

    Accepted FPS is an encoder contract. The separate PTS analysis measures achieved
    output rate; this function never substitutes VUI signalling for that measurement.
    """
    mbs = (sps["coded_width"] // 16) * (sps["coded_height"] // 16)
    level, profile = sps["level_idc"], sps["profile_idc"]
    limits = AVC_LEVEL_LIMITS.get(level)
    fs, mbps, dpb = limits if limits is not None else (None, None, None)
    failures = []
    if expected_baseline and profile != 66:
        failures.append("FAIL_REQUESTED_BASELINE")
    if fs is not None and mbs > fs:
        failures.append("FAIL_LEVEL_MAXFS")
    references = sps["max_num_ref_frames"]
    if references > 16 or (dpb is not None and references * mbs > dpb):
        failures.append("FAIL_LEVEL_MAXDPB")
    mode = None
    if accepted is not None:
        mode = {name: accepted[name] for name in ("width", "height", "fps")}
        if (sps["display_width"], sps["display_height"]) != (mode["width"], mode["height"]):
            failures.append("FAIL_ACCEPTED_GEOMETRY")
        if mbps is not None and mbs * mode["fps"] > mbps:
            failures.append("FAIL_LEVEL_MAXMBPS")
    if failures:
        status = failures[0]
    elif limits is None:
        status = "PARTIAL_UNKNOWN_LEVEL"
    elif expected_baseline:
        # Preserve the historical 720p summary name; extra checks are explicit below.
        suffix = "MAXFS" if level == 31 or mode is None else "MODE_LIMITS"
        status = f"PASS_BASELINE_L{level}_{suffix}"
    else:
        status = "PARTIAL_CHECK_ONLY"
    return {
        "status": status, "failures": failures,
        "coded_macroblocks": mbs, "level_max_fs": fs,
        "level_max_mbps": mbps, "level_max_dpb_macroblocks": dpb,
        "sps_reference_macroblocks": references * mbs,
        "accepted_mode_checked": mode,
        "accepted_mode_macroblocks_per_second": mbs * mode["fps"] if mode else None,
        "decoder_policy": "permissive diagnostic decode; not automotive acceptance",
        "remaining_checks": "Achieved MBPS assessed separately from actual quantized PTS; HRD/bitrate and target HU acceptance not fully validated",
    }
