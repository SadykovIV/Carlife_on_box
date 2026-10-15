#!/usr/bin/env python3
"""Offline exact-input Magisk overlay builder. Never opens a device or executes images.

Only Android boot v0/1/2 with gzip newc ramdisk is supported. Existing ramdisk
entries, kernel/second/recovery-dtbo/dtb and vbmeta bytes are retained. Header
ramdisk length, moved recovery offset and boot SHA1 ID are the only header edits.
The original AVB vbmeta is preserved; footer offsets are adjusted as in pinned
Magisk30.7 repack. It is NOT re-signed or called valid for new payload data.
"""
from __future__ import annotations

import gzip
import hashlib
import io
from pathlib import Path, PurePosixPath
import stat
import struct


LIMIT = 128 * 1024 * 1024


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def align(n: int, page: int) -> int:
    return (n + page - 1) // page * page


def require(ok, message):
    if not ok:
        raise ValueError(message)


def parse_boot(data: bytes):
    require(1660 <= len(data) <= LIMIT, 'bounded boot size')
    require(data[:8] == b'ANDROID!', 'Android boot magic')
    ks, ka, rs, ra, ss, sa, ta, page, version, osv = struct.unpack_from('<10I', data, 8)
    require(version in (0, 1, 2), 'only boot v0/v1/v2 reviewed')
    require(page in (2048, 4096, 8192, 16384), 'unsupported page size')
    require(ks > 0 and rs > 0 and page <= len(data), 'empty kernel/ramdisk')
    off = page
    components = {}
    for name, size in [('kernel', ks), ('ramdisk', rs), ('second', ss)]:
        require(off + size <= len(data), name + ' bounds')
        components[name] = dict(offset=off, size=size, data=data[off:off + size])
        require(not any(data[off+size:off+align(size, page)]), 'nonzero component padding')
        off += align(size, page)
    if version >= 1:
        recovery_size, recovery_offset, header_size = struct.unpack_from('<IQI', data, 1632)
        require(header_size == (1648 if version == 1 else 1660), 'header_size mismatch')
        require(recovery_offset in (0, off) if recovery_size == 0 else recovery_offset == off,
                'noncanonical recovery_dtbo offset')
        require(off + recovery_size <= len(data), 'recovery bounds')
        components['recovery_dtbo'] = dict(offset=off, size=recovery_size, data=data[off:off + recovery_size])
        require(not any(data[off+recovery_size:off+align(recovery_size, page)]), 'nonzero recovery padding')
        off += align(recovery_size, page)
    if version == 2:
        size, address = struct.unpack_from('<IQ', data, 1648)
        require(off + size <= len(data), 'dtb bounds')
        components['dtb'] = dict(offset=off, size=size, data=data[off:off + size])
        require(not any(data[off+size:off+align(size, page)]), 'nonzero dtb padding')
        off += align(size, page)
    require(off <= len(data), 'aligned component end beyond partition')
    # Preserve all vbmeta bytes; relocate only a canonical vbmeta+zero-pad+footer.
    # No footer removal, AVB flag edit, vbmeta rewrite or invented signature.
    nonzero = next((i for i in range(off, len(data)) if data[i]), len(data))
    footer = None
    if data[-64:-60] == b'AVBf':
        major, minor, original, vb_off, vb_size = struct.unpack_from('>IIQQQ', data, len(data)-60)
        require(vb_off >= off and vb_size > 0 and vb_off + vb_size <= len(data)-64, 'AVB footer bounds')
        require(data[vb_off:vb_off+4] == b'AVB0', 'AVB header absent')
        require(not any(data[off:vb_off]) and not any(data[vb_off+vb_size:-64]),
                'unknown data around AVB trailer')
        footer = dict(major=major, minor=minor, original_image_size=original,
                      vbmeta_offset=vb_off, vbmeta_size=vb_size,
                      vbmeta_sha256=sha(data[vb_off:vb_off+vb_size]))
        nonzero = min(nonzero, vb_off)
    return dict(version=version, page_size=page, components=components,
                component_end=off, trailer_start=nonzero, avb_footer=footer,
                header=data[:page], image_size=len(data))


def boot_id(components, version):
    h = hashlib.sha1()
    names = ['kernel', 'ramdisk', 'second']
    if version >= 1:
        names.append('recovery_dtbo')
    if version >= 2:
        names.append('dtb')
    for name in names:
        blob = components[name]['data']
        h.update(blob)
        h.update(struct.pack('<I', len(blob)))
    return h.digest() + b'\0' * 12


def parse_cpio(data: bytes):
    require(len(data) <= LIMIT, 'ramdisk too large')
    entries = []
    seen = set()
    offset = 0
    while offset < len(data):
        start = offset
        require(data[offset:offset+6] in (b'070701', b'070702'), 'newc magic')
        require(offset + 110 <= len(data), 'newc header bounds')
        fields = [int(data[offset+6+i*8:offset+14+i*8], 16) for i in range(13)]
        ino, mode, uid, gid, links, mtime, size, dm, dn, rm, rn, ns, check = fields
        require(1 <= ns <= 4096 and size <= LIMIT, 'newc entry bounds')
        name_bytes = data[offset+110:offset+110+ns]
        require(len(name_bytes) == ns and name_bytes[-1:] == b'\0', 'newc name termination')
        name = name_bytes[:-1].decode('utf-8')
        require('\0' not in name, 'embedded name NUL')
        pos = align(offset + 110 + ns, 4)
        end = align(pos + size, 4)
        require(end <= len(data), 'newc data bounds')
        if name == 'TRAILER!!!':
            require(size == 0 and not any(data[end:]), 'nonzero after newc trailer')
            return entries, start
        p = PurePosixPath(name)
        require(not p.is_absolute() and '..' not in p.parts and str(p) == name,
                'unsafe or noncanonical cpio name')
        require(name not in seen, 'duplicate cpio name')
        seen.add(name)
        payload = data[pos:pos+size]
        if data[offset:offset+6] == b'070702':
            require(sum(payload) & 0xffffffff == check, 'newc checksum mismatch')
        entries.append(dict(name=name, fields=fields, mode=mode, uid=uid, gid=gid,
                            data=payload, raw=data[start:end]))
        require(len(entries) <= 10000, 'too many cpio entries')
        offset = end
    raise ValueError('missing newc trailer')


def newc_entry(name, data, mode, ino):
    raw_name = name.encode() + b'\0'
    fields = [ino, mode, 0, 0, 2 if stat.S_ISDIR(mode) else 1, 0, len(data), 0, 0, 0, 0, len(raw_name), 0]
    header = b'070701' + b''.join(f'{x:08x}'.encode() for x in fields)
    out = header + raw_name
    out += b'\0' * (align(len(out), 4) - len(out))
    out += data
    return out + b'\0' * (align(len(out), 4) - len(out))


def patch_cpio(data, additions):
    entries, trailer = parse_cpio(data)
    existing = {x['name']: x for x in entries}
    require('init' in existing and stat.S_ISREG(existing['init']['mode']), 'Magisk init absent')
    require('.backup/.magisk' in existing, 'current ramdisk is not recognized Magisk patched')
    for x in entries:
        if x['name'].startswith('overlay.d/') and x['name'].endswith('.rc'):
            require(b'sd-reverse' not in x['data'] and b'carlife-native' not in x['data'],
                    'conflicting existing overlay service')
    raw = data[:trailer]
    next_ino = max(x['fields'][0] for x in entries) + 1
    for name, payload, mode in additions:
        if name in existing:
            require(stat.S_ISDIR(mode) and stat.S_ISDIR(existing[name]['mode']) and
                    existing[name]['uid'] == 0 and existing[name]['gid'] == 0,
                    'overlay target already exists')
            continue
        parent = str(PurePosixPath(name).parent)
        if parent not in ('.', ''):
            e = existing.get(parent)
            require(e and stat.S_ISDIR(e['mode']), 'overlay parent missing/not directory')
        raw += newc_entry(name, payload, mode, next_ino)
        existing[name] = dict(mode=mode, uid=0, gid=0)
        next_ino += 1
    raw += newc_entry('TRAILER!!!', b'', 0, next_ino)
    raw += b'\0' * (align(len(raw), 512) - len(raw))
    patched, _ = parse_cpio(raw)
    patched_by_name = {x['name']: x for x in patched}
    require(all(patched_by_name[x['name']]['raw'] == x['raw'] for x in entries),
            'original cpio entry changed')
    return raw, entries, patched


def gzip_deterministic(data):
    out = io.BytesIO()
    with gzip.GzipFile(fileobj=out, mode='wb', filename='', mtime=0, compresslevel=9) as f:
        f.write(data)
    return out.getvalue()


def repack(original, ramdisk):
    p = parse_boot(original)
    require(original[576:608] == boot_id(p['components'], p['version']),
            'input boot ID is not reviewed SHA1 form')
    comp = {name: dict(item) for name, item in p['components'].items()}
    comp['ramdisk']['data'] = ramdisk
    comp['ramdisk']['size'] = len(ramdisk)
    header = bytearray(p['header'])
    struct.pack_into('<I', header, 16, len(ramdisk))
    out = bytearray(header)
    for name, item in comp.items():
        item['offset'] = len(out)
        if name == 'recovery_dtbo' and item['size']:
            struct.pack_into('<Q', header, 1636, item['offset'])
        out.extend(item['data'])
        out.extend(b'\0' * (align(len(out), p['page_size']) - len(out)))
    payload_end = len(out)
    header[576:608] = boot_id(comp, p['version'])
    out[:p['page_size']] = header
    if p['avb_footer']:
        old_avb = p['avb_footer']
        new_vb_offset = align(len(out), 4096)
        vbmeta = original[old_avb['vbmeta_offset']:old_avb['vbmeta_offset']+old_avb['vbmeta_size']]
        require(new_vb_offset + len(vbmeta) <= len(original)-64, 'AVB exceeds partition size')
        out.extend(b'\0' * (new_vb_offset - len(out)))
        out.extend(vbmeta)
        out.extend(b'\0' * (len(original)-64-len(out)))
        footer = bytearray(original[-64:])
        struct.pack_into('>QQ', footer, 12, payload_end, new_vb_offset)
        out.extend(footer)
    else:
        require(len(out) <= p['trailer_start'], 'new ramdisk overlaps unknown retained trailer')
        out.extend(b'\0' * (p['trailer_start'] - len(out)))
        out.extend(original[p['trailer_start']:])
    require(len(out) == len(original), 'partition-sized output changed length')
    new = parse_boot(bytes(out))
    for name in comp:
        if name != 'ramdisk':
            require(new['components'][name]['data'] == p['components'][name]['data'],
                    'non-ramdisk component changed: ' + name)
    if p['avb_footer']:
        require(new['avb_footer']['vbmeta_sha256'] == p['avb_footer']['vbmeta_sha256'], 'vbmeta changed')
        changed_footer = {i for i in range(64) if out[-64+i] != original[-64+i]}
        require(changed_footer <= set(range(12, 28)), 'unexpected AVB footer mutation')
    else:
        require(out[p['trailer_start']:] == original[p['trailer_start']:], 'unknown trailer changed')
    changed_header = [i for i in range(p['page_size']) if original[i] != out[i]]
    allowed = set(range(16, 20)) | set(range(576, 608))
    if p['version'] >= 1:
        allowed |= set(range(1636, 1644))
    require(set(changed_header) <= allowed, 'unexpected header mutation')
    return bytes(out), p, new, changed_header



def overlay_image(original: bytes, payloads: dict[str, bytes]):
    """Add the four init hooks, preserving the caller's exact Magisk image.

    This does not execute an image, invoke magiskboot, resign AVB, or flash.
    """
    expected = {'carlife-owner.rc', 'carlife-early-dispatch.sh',
                'carlife-native-entry.sh', 'carlife-selector-entry.sh'}
    require(set(payloads) == expected, 'exact boot overlay payload set')
    parsed = parse_boot(original)
    ramdisk = parsed['components']['ramdisk']['data']
    require(ramdisk[:3] == b'\x1f\x8b\x08', 'only gzip ramdisk reviewed')
    with gzip.GzipFile(fileobj=io.BytesIO(ramdisk)) as stream:
        cpio = stream.read(LIMIT + 1)
    require(len(cpio) <= LIMIT, 'gzip expansion limit')
    additions = [('overlay.d', b'', stat.S_IFDIR | 0o755),
                 ('overlay.d/sbin', b'', stat.S_IFDIR | 0o755),
                 ('overlay.d/carlife-owner.rc', payloads['carlife-owner.rc'], stat.S_IFREG | 0o644)]
    additions += [('overlay.d/sbin/' + name, payloads[name], stat.S_IFREG | 0o500)
                  for name in sorted(expected - {'carlife-owner.rc'})]
    patched, before, after = patch_cpio(cpio, additions)
    candidate, old, new, changed_header = repack(original, gzip_deterministic(patched))
    summary = lambda image: {name: dict(offset=value['offset'], size=value['size'],
                                      sha256=sha(value['data']))
                             for name, value in image['components'].items()}
    return candidate, dict(header_version=old['version'], page_size=old['page_size'],
        changed_header_offsets=changed_header, before_components=summary(old),
        after_components=summary(new), existing_cpio_entries_preserved=len(before),
        added_paths=[name for name, _, _ in additions if name not in {e['name'] for e in before}],
        ramdisk_before_sha256=sha(cpio), ramdisk_after_sha256=sha(patched),
        avb_footer_before=old['avb_footer'], avb_footer_after=new['avb_footer'],
        avb='VBMETA_PRESERVED_FOOTER_OFFSETS_UPDATED_NO_FLAGS_CHANGED_NOT_RESIGNED')
