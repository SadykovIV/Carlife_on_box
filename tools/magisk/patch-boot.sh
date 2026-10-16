#!/system/bin/sh
# Add the CarLife init overlay and the recognized TBox serial-console fix to
# THIS device's saved Magisk boot. Unknown console profiles remain unchanged.
# Contract: patch-boot.sh STATE_DIR MODULE_DIR BOOT_SIZE. No block-device I/O.
# Magisk v30.7: native/src/boot/{cli.rs,cpio.rs,bootimg.cpp}.
set -eu
umask 077
export ASH_STANDALONE=1
PATH=/data/adb/magisk:/system/bin:/system/xbin:/vendor/bin
export PATH
# repack's only vbmeta flag switch. Never inherit a Magisk installer setting.
PATCHVBMETAFLAG=false
export PATCHVBMETAFLAG
D=${1:?state directory}; M=${2:?module directory}; SIZE=${3:?partition size}
MB=/data/adb/magisk/magiskboot
fail() { printf 'CarLife boot patch: %s\n' "$*" >&2; exit 1; }
regular() { [ -f "$1" ] && [ ! -L "$1" ]; }
directory() { [ -d "$1" ] && [ ! -L "$1" ] && [ "$(readlink -f "$1")" = "$1" ]; }
hash() { sha256sum "$1" | cut -d ' ' -f 1; }
bytes() { stat -c '%s' "$1"; }
case "$D:$M" in *[!a-zA-Z0-9_./:-]*) fail 'unsupported directory characters';; esac
case "$D:$M" in /*:/*) ;; *) fail 'absolute directories required';; esac
case "$SIZE" in ''|*[!0-9]*) fail 'partition size';; esac
[ "$SIZE" -ge 4096 ] && [ "$SIZE" -le 134217728 ] || fail 'partition size bound'
directory "$D" && directory "$M" && directory "$M/hooks" || fail 'directory identity'
regular "$D/original.img" && [ "$(bytes "$D/original.img")" = "$SIZE" ] || fail 'saved boot size/type'
[ -x "$MB" ] && [ ! -L "$MB" ] || fail 'installed Magisk magiskboot missing'
[ ! -e "$D/candidate.img" ] && [ ! -L "$D/candidate.img" ] || fail 'existing candidate preserved'
W=$(mktemp -d "$D/patch-work.XXXXXX") || fail 'patch workspace'
mkdir -m 700 "$W/before" "$W/repack" "$W/after"
exec 3> "$W/audit.txt"
printf 'FORMAT=1\nORIGINAL_SHA256=%s\nMAGISKBOOT_SHA256=%s\nPATCHVBMETAFLAG=false\n' "$(hash "$D/original.img")" "$(hash "$MB")" >&3
# All reads and writes below are regular files. Keep failed evidence for diagnosis.
run() { "$MB" "$@" >> "$W/magiskboot.log" 2>&1; }
slice() {
    # tail/head avoid millions of one-byte dd reads; all callers check bounds.
    tail -c "+$(($2 + 1))" "$1" | head -c "$3" > "$4"
    [ "$(bytes "$4")" = "$3" ] || fail 'short binary slice'
}
u32() { od -An -tu4 -j "$2" -N 4 "$1" | tr -d ' \n'; }
be64() {
    hex=$(od -An -tx1 -j "$2" -N 8 "$1" | tr -d ' \n')
    case "$hex" in 00000000????????) printf '%s\n' "$((0x$hex))";; *) fail 'AVB integer bounds';; esac
}
zero_range() {
    [ "$3" -ge 0 ] || fail 'negative range'
    [ "$3" = 0 ] && return 0
    slice "$1" "$2" "$3" "$W/range"
    head -c "$3" /dev/zero > "$W/zeros"
    cmp -s "$W/range" "$W/zeros" || fail 'unknown nonzero boot padding/trailer'
}
# Read the Android v0/1/2 layout independently of magiskboot. Restrict to the
# supported TBox boot family; do not let unpack scan arbitrary embedded images.
layout() {
    image=$1; out=$2
    [ "$(head -c 8 "$image")" = 'ANDROID!' ] || fail 'Android boot header absent'
    ver=$(u32 "$image" 40); page=$(u32 "$image" 36)
    case "$ver" in 0|1|2) ;; *) fail 'unsupported boot header version';; esac
    case "$page" in 2048|4096|8192|16384) ;; *) fail 'unsupported boot page size';; esac
    [ "$ver" = 0 ] || [ "$(u32 "$image" 1644)" = "$((1648 + (ver - 1) * 12))" ] || fail 'boot header size'
    ks=$(u32 "$image" 8); rs=$(u32 "$image" 16); ss=$(u32 "$image" 24)
    [ "$ks" -gt 0 ] && [ "$rs" -gt 0 ] || fail 'empty kernel or ramdisk'
    end=$page
    for length in "$ks" "$rs" "$ss"; do
        [ "$length" -le "$SIZE" ] || fail 'component size'
        aligned=$(((length + page - 1) / page * page))
        [ "$((end + aligned))" -le "$SIZE" ] || fail 'component bounds'
        zero_range "$image" "$((end + length))" "$((aligned - length))"
        end=$((end + aligned))
    done
    if [ "$ver" -ge 1 ]; then
        recovery=$(u32 "$image" 1632)
        low=$(u32 "$image" 1636); high=$(u32 "$image" 1640)
        [ "$high" = 0 ] || fail 'recovery offset overflow'
        if [ "$recovery" = 0 ]; then
            [ "$low" = 0 ] || [ "$low" = "$end" ] || fail 'empty recovery offset'
        else [ "$low" = "$end" ] || fail 'recovery offset'; fi
        aligned=$(((recovery + page - 1) / page * page))
        [ "$recovery" -le "$SIZE" ] && [ "$((end + aligned))" -le "$SIZE" ] || fail 'recovery bounds'
        zero_range "$image" "$((end + recovery))" "$((aligned - recovery))"
        end=$((end + aligned))
    fi
    if [ "$ver" = 2 ]; then
        dtb=$(u32 "$image" 1648); aligned=$(((dtb + page - 1) / page * page))
        [ "$dtb" -le "$SIZE" ] && [ "$((end + aligned))" -le "$SIZE" ] || fail 'dtb bounds'
        zero_range "$image" "$((end + dtb))" "$((aligned - dtb))"
        end=$((end + aligned))
    fi
    slice "$image" 0 "$page" "$out/header.raw"
    # Only fields magiskboot must recompute may differ. Addresses, flags,
    # cmdline, OS metadata, and all other header bytes must remain exact.
    cp "$out/header.raw" "$out/header.masked"
    dd if=/dev/zero of="$out/header.masked" bs=1 seek=16 count=4 conv=notrunc 2>/dev/null
    dd if=/dev/zero of="$out/header.masked" bs=1 seek=576 count=32 conv=notrunc 2>/dev/null
    if [ "$ver" -ge 1 ]; then dd if=/dev/zero of="$out/header.masked" bs=1 seek=1636 count=8 conv=notrunc 2>/dev/null; fi
    slice "$image" "$((SIZE - 64))" 64 "$out/footer"
    if [ "$(head -c 4 "$out/footer")" = AVBf ]; then
        vb_offset=$(be64 "$out/footer" 20); vb_size=$(be64 "$out/footer" 28)
        original_end=$(be64 "$out/footer" 12)
        [ "$original_end" = "$end" ] || fail 'AVB original image size'
        [ "$vb_offset" -ge "$end" ] && [ "$vb_size" -ge 256 ] && [ "$((vb_offset + vb_size))" -le "$((SIZE - 64))" ] || fail 'AVB bounds'
        slice "$image" "$vb_offset" "$vb_size" "$out/vbmeta"
        [ "$(head -c 4 "$out/vbmeta")" = AVB0 ] || fail 'AVB metadata absent'
        zero_range "$image" "$end" "$((vb_offset - end))"
        zero_range "$image" "$((vb_offset + vb_size))" "$((SIZE - 64 - vb_offset - vb_size))"
        cp "$out/footer" "$out/footer.masked"
        dd if=/dev/zero of="$out/footer.masked" bs=1 seek=12 count=16 conv=notrunc 2>/dev/null
    else
        zero_range "$image" "$end" "$((SIZE - end))"
        : > "$out/footer.masked"; : > "$out/vbmeta"
    fi
    printf 'HEADER_VERSION=%s\nPAGE_SIZE=%s\nCOMPONENT_END=%s\n' "$ver" "$page" "$end" > "$out/layout.txt"
}
# BEGIN_REVIEWED_CONSOLE_PROFILE
# The whole kernel and DTB hashes identify the already accepted TBox profile.
# Header/DTB are checked as a pair before changing either. In particular, never
# remove arbitrary console/earlycon arguments from another vendor's boot image.
prepare_console_profile() {
    CONSOLE_RESULT=CONSOLE_UNCHANGED_UNRECOGNIZED
    REPACK_SOURCE=$D/original.img
    cp "$W/before/header.masked" "$W/expected.header.masked"
    if [ -e "$W/before/dtb" ]; then cp "$W/before/dtb" "$W/expected.dtb"; fi
    regular "$W/before/kernel" && regular "$W/before/dtb" || return 0
    [ "$(u32 "$W/before/header.raw" 40)" = 2 ] || return 0
    [ "$(hash "$W/before/kernel")" = 12f4f4aa1df602972e47fcb159354c32c83aca96d714ba702e7ed355fa4ddac0 ] || return 0
    cp_dtb_sha=$(hash "$W/before/dtb")
    case "$cp_dtb_sha" in
        7a49a4bb2c7d392af572ec00c582ceb1e200f24d832153b7b9781866bbce8210) cp_dtb_state=old;;
        38c946579a47a5b8b4e6b4237b16aaa6ffe0fac0e5a079c4ff4161f6aefee89a) cp_dtb_state=new;;
        *) return 0;;
    esac
    cp_old_head='console=ttyMSM0,115200,n8 earlycon=msm_geni_serial,0x888000 androidboot.hardware=qcom androidboot.console=ttyMSM0 androidboot.memcg=1 lpm_levels.sleep_disabled=1 video=vfb:640x400,bpp=32,memsize=3072000 msm_rtb.filter=0x237 service_locator.enable=1 androidboot.usbcontroller=a600000.dwc3 swiotlb=2048 cgroup.memory=nokmem,nosocket loop.max_part=7 buildvariant=user'
    cp_new_head='console=null androidboot.hardware=qcom androidboot.serialconsole=0 androidboot.memcg=1 lpm_levels.sleep_disabled=1 video=vfb:640x400,bpp=32,memsize=3072000 msm_rtb.filter=0x237 service_locator.enable=1 androidboot.usbcontroller=a600000.dwc3 swiotlb=2048 cgroup.memory=nokmem,nosocket loop.max_part=7 buildvariant=user'
    { printf '%s' "$cp_old_head"; head -c "$((512 - ${#cp_old_head}))" /dev/zero; } > "$W/console-head.old"
    { printf '%s' "$cp_new_head"; head -c "$((512 - ${#cp_new_head}))" /dev/zero; } > "$W/console-head.new"
    slice "$W/before/header.raw" 64 512 "$W/console-head.actual"
    slice "$W/before/header.raw" 608 1024 "$W/console-extra.actual"
    head -c 1024 /dev/zero > "$W/console-extra.zero"
    cmp -s "$W/console-extra.actual" "$W/console-extra.zero" || return 0
    if cmp -s "$W/console-head.actual" "$W/console-head.old"; then cp_head_state=old
    elif cmp -s "$W/console-head.actual" "$W/console-head.new"; then cp_head_state=new
    else return 0; fi
    if [ "$cp_dtb_state" = old ]; then
        # These offsets are within the full-hash-pinned DTB component, not the
        # boot image. Replace only the two reviewed /chosen/bootargs values.
        cp_old_dt='rcupdate.rcu_expedited=1 rcu_nocbs=0-7 earlycon=msm_geni_serial,0x98c000 kpti=off'
        cp_new_dt='rcupdate.rcu_expedited=1 rcu_nocbs=0-7                                   kpti=off'
        printf '%s\000' "$cp_old_dt" > "$W/console-dt.old"
        printf '%s\000' "$cp_new_dt" > "$W/console-dt.new"
        [ "$(bytes "$W/console-dt.old")" = 82 ] && [ "$(bytes "$W/console-dt.new")" = 82 ] || fail 'console DT replacement size'
        for cp_offset in 5352 398805; do
            slice "$W/before/dtb" "$cp_offset" 82 "$W/console-dt.actual"
            cmp -s "$W/console-dt.actual" "$W/console-dt.old" || fail 'pinned console DT bytes differ'
            dd if="$W/console-dt.new" of="$W/expected.dtb" bs=1 seek="$cp_offset" count=82 conv=notrunc 2>/dev/null
        done
        [ "$(hash "$W/expected.dtb")" = 38c946579a47a5b8b4e6b4237b16aaa6ffe0fac0e5a079c4ff4161f6aefee89a ] || fail 'console DT result hash'
    fi
    # Give repack a private template with the normalized header. Magiskboot
    # recomputes the boot SHA1 from the changed ramdisk/DTB. original.img, kernel,
    # vbmeta, AVB flags and every unrelated command-line byte remain untouched.
    if [ "$cp_head_state" = old ]; then
        cp "$D/original.img" "$W/console-template.img"
        dd if="$W/console-head.new" of="$W/console-template.img" bs=1 seek=64 count=512 conv=notrunc 2>/dev/null
        dd if="$W/console-head.new" of="$W/expected.header.masked" bs=1 seek=64 count=512 conv=notrunc 2>/dev/null
        REPACK_SOURCE=$W/console-template.img
    fi
    cp "$W/expected.dtb" "$W/repack/dtb"
    if [ "$cp_head_state:$cp_dtb_state" = new:new ]; then CONSOLE_RESULT=CONSOLE_OFF_ALREADY_APPLIED
    else CONSOLE_RESULT=CONSOLE_OFF_APPLIED; fi
}
# END_REVIEWED_CONSOLE_PROFILE
layout "$D/original.img" "$W/before"
(cd "$W/before" && run unpack -n "$D/original.img") || fail 'unpack original'
regular "$W/before/ramdisk.cpio" || fail 'ramdisk absent'
if [ "$(head -c 6 "$W/before/ramdisk.cpio")" = 070701 ]; then
    cp "$W/before/ramdisk.cpio" "$W/original.cpio"
else
    run decompress "$W/before/ramdisk.cpio" "$W/original.cpio" || fail 'ramdisk decompression'
fi
# Magisk canonicalizes inode metadata. Reject actual hard-linked regular
# files and duplicate names first, so normalization cannot conceal data loss.
pos=0; cpio_size=$(bytes "$W/original.cpio"); count=0
: > "$W/cpio-names"
while :; do
    [ "$((pos + 110))" -le "$cpio_size" ] || fail 'cpio header bounds'
    hdr=$(dd if="$W/original.cpio" bs=1 skip="$pos" count=110 2>/dev/null)
    [ "${#hdr}" = 110 ] || fail 'cpio header length'
    case "$hdr" in 070701*) ;; *) fail 'cpio newc format';; esac
    case "$hdr" in *[!0-9a-fA-F]*) fail 'cpio header encoding';; esac
    mode=$((0x$(printf '%s' "$hdr" | cut -c 15-22)))
    links=$((0x$(printf '%s' "$hdr" | cut -c 39-46)))
    length=$((0x$(printf '%s' "$hdr" | cut -c 55-62)))
    namesize=$((0x$(printf '%s' "$hdr" | cut -c 95-102)))
    [ "$namesize" -gt 1 ] && [ "$namesize" -le 4096 ] || fail 'cpio name size'
    data_pos=$(((pos + 110 + namesize + 3) / 4 * 4))
    next=$(((data_pos + length + 3) / 4 * 4))
    [ "$next" -le "$cpio_size" ] || fail 'cpio entry bounds'
    slice "$W/original.cpio" "$((pos + 110))" "$((namesize - 1))" "$W/entry-name"
    [ "$(od -An -tu1 -j "$((pos + 110 + namesize - 1))" -N 1 "$W/original.cpio" | tr -d ' \n')" = 0 ] || fail 'cpio name terminator'
    name=$(cat "$W/entry-name")
    [ "${#name}" = "$((namesize - 1))" ] || fail 'cpio binary name'
    case "$name" in *[!a-zA-Z0-9_./!-]*) fail 'cpio path characters';; esac
    case "$name" in .|..|/*|*/./*|*/../*|*/.|*/..|*//*) fail 'noncanonical cpio path';; esac
    if [ "$name" = 'TRAILER!!!' ]; then
        [ "$length" = 0 ] || fail 'cpio trailer size'
        zero_range "$W/original.cpio" "$next" "$((cpio_size - next))"
        break
    fi
    if [ "$((mode & 61440))" = 32768 ] && [ "$links" -gt 1 ]; then fail 'hard-linked cpio file unsupported'; fi
    if grep -F -x -- "$name" "$W/cpio-names" >/dev/null; then fail 'duplicate cpio name'; fi
    printf '%s\n' "$name" >> "$W/cpio-names"
    count=$((count + 1)); [ "$count" -le 10000 ] || fail 'cpio entry count'
    pos=$next
done
status=0; run cpio "$W/original.cpio" test || status=$?
[ "$status" = 1 ] || fail 'requires recognized Magisk ramdisk'
run cpio "$W/original.cpio" 'exists .backup/.magisk' || fail 'Magisk backup marker absent'
run cpio "$W/original.cpio" 'exists init' || fail 'Magisk init absent'
# Canonical compare preserves the semantic fields kept by Magisk newc:
# path, mode, uid, gid, rdev, data. No extract-all, mknod or symlink traversal.
run cpio "$W/original.cpio" || fail 'cpio normalization'
"$MB" cpio "$W/original.cpio" 'ls -r' > "$W/entries.before" 2>> "$W/magiskboot.log" || fail 'cpio inventory'
awk -F '\t' 'NF != 6 || $6 !~ /^[A-Za-z0-9_.\/-]+$/ || $6 ~ /(^|\/)\.\.?($|\/)/ || $6 ~ /^\// {exit 1}' "$W/entries.before" || fail 'unsupported cpio path'
awk -F '\t' '($6 == "init" || $6 == ".backup/.magisk") && substr($1,1,1) == "-" {ok++} END {exit ok != 2}' "$W/entries.before" || fail 'Magisk init/marker must be regular'
while IFS="$(printf '\t')" read -r mode uid gid size dev name; do
    case "$name" in overlay.d/*.rc)
        case "$mode" in -*) ;; *) fail 'overlay rc must be regular';; esac
        run cpio "$W/original.cpio" "extract $name $W/existing.rc" || fail 'overlay inspection'
        if grep -E 'sd-reverse|carlife-native|carlife-boot|carlife-on-box' "$W/existing.rc" >/dev/null; then fail 'conflicting CarLife/OEM overlay'; fi;;
    esac
done < "$W/entries.before"
cp "$W/original.cpio" "$W/repack/ramdisk.cpio"
: > "$W/new-directories"
for name in overlay.d overlay.d/sbin; do
    if run cpio "$W/original.cpio" "exists $name"; then
        awk -F '\t' -v p="$name" '$6 == p && substr($1,1,1) == "d" && $2 == 0 && $3 == 0 {ok=1} END {exit !ok}' "$W/entries.before" || fail 'overlay parent type/owner'
    else
        run cpio "$W/repack/ramdisk.cpio" "mkdir 0755 $name" || fail 'add overlay directory'
        printf '%s\n' "$name" >> "$W/new-directories"
    fi
done
for file in carlife-owner.rc carlife-early-dispatch.sh carlife-native-entry.sh carlife-selector-entry.sh; do
    regular "$M/hooks/$file" || fail 'hook type'
    case "$file" in *.rc) target=overlay.d/$file; mode=0644;; *) target=overlay.d/sbin/$file; mode=0500;; esac
    if run cpio "$W/original.cpio" "exists $target"; then fail 'CarLife overlay target already exists'; fi
    run cpio "$W/repack/ramdisk.cpio" "add $mode $target $M/hooks/$file" || fail 'add hook'
done
# Retain raw compressed kernel and all other original components. The optional
# exact console profile below normalizes only the reviewed header/DTB fields.
for component in kernel kernel_dtb second extra recovery_dtbo dtb bootconfig; do
    if [ -e "$W/before/$component" ]; then cp "$W/before/$component" "$W/repack/$component"; fi
done
prepare_console_profile
printf 'CONSOLE=%s\n' "$CONSOLE_RESULT" >&3
(cd "$W/repack" && run repack "$REPACK_SOURCE" "$W/candidate.img") || fail 'repack'
regular "$W/candidate.img" && [ "$(bytes "$W/candidate.img")" = "$SIZE" ] || fail 'candidate partition size'
layout "$W/candidate.img" "$W/after"
(cd "$W/after" && run unpack -n "$W/candidate.img") || fail 'unpack candidate'
cmp -s "$W/expected.header.masked" "$W/after/header.masked" || fail 'changed header.masked beyond reviewed console profile'
if [ -e "$W/expected.dtb" ]; then
    cmp -s "$W/expected.dtb" "$W/after/dtb" || fail 'changed dtb beyond reviewed console profile'
else [ ! -e "$W/after/dtb" ] || fail 'added dtb'; fi
for component in footer.masked vbmeta kernel kernel_dtb second extra recovery_dtbo bootconfig; do
    if [ -e "$W/before/$component" ]; then
        cmp -s "$W/before/$component" "$W/after/$component" || fail "changed $component"
    else [ ! -e "$W/after/$component" ] || fail "added $component"; fi
done
if [ "$(head -c 6 "$W/after/ramdisk.cpio")" = 070701 ]; then cp "$W/after/ramdisk.cpio" "$W/verified.cpio";
else run decompress "$W/after/ramdisk.cpio" "$W/verified.cpio" || fail 'candidate ramdisk decompression'; fi
run cpio "$W/verified.cpio" || fail 'candidate canonicalization'
cmp -s "$W/repack/ramdisk.cpio" "$W/verified.cpio" || fail 'repacked cpio differs from prepared overlay'
for file in carlife-owner.rc carlife-early-dispatch.sh carlife-native-entry.sh carlife-selector-entry.sh; do
    case "$file" in *.rc) target=overlay.d/$file;; *) target=overlay.d/sbin/$file;; esac
    run cpio "$W/verified.cpio" "extract $target $W/verified-hook" || fail 'hook readback'
    cmp -s "$W/verified-hook" "$M/hooks/$file" || fail 'hook differs after repack'
    run cpio "$W/verified.cpio" "rm $target" || fail 'remove audit hook'
done
# Nonrecursive removals leave any unexpected entries behind, failing cmp.
while read -r name; do run cpio "$W/verified.cpio" "rm $name" || fail 'remove audit directory'; done < "$W/new-directories"
run cpio "$W/verified.cpio" || fail 'candidate normalization'
cmp -s "$W/original.cpio" "$W/verified.cpio" || fail 'existing cpio entries changed or unexpected additions'
printf 'CANDIDATE_SHA256=%s\nBOOT_SIZE=%s\nORIGINAL_CPIO_SHA256=%s\nPATCHED_CPIO_SHA256=%s\nEXISTING_CPIO_ENTRIES=%s\nADDED_HOOK_FILES=4\nPRESERVATION=PASS\nAVB=unchanged_vbmeta_and_flags_not_resigned\n' "$(hash "$W/candidate.img")" "$SIZE" "$(hash "$W/original.cpio")" "$(hash "$W/repack/ramdisk.cpio")" "$count" >&3
exec 3>&-
cp "$W/audit.txt" "$D/patch-audit.txt"
chmod 400 "$W/candidate.img" "$D/patch-audit.txt"
sync
mv "$W/candidate.img" "$D/candidate.img"
sync
printf 'CarLife boot overlay prepared and verified; no partition written.\n'
