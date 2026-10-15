#!/system/bin/sh
# CarLife installer transactions. No USB writes, reboot, or security changes.
set -eu
umask 077
export ASH_STANDALONE=1
PATH=/data/adb/magisk:/system/bin:/system/xbin:/vendor/bin
export PATH
D=/data/adb/carlife-on-box
P=/data/local/carlife-native
S=/data/adb/carlife-boot
ACTIVE=/data/adb/modules/carlife_on_box
STAGED=/data/adb/modules_update/carlife_on_box
ACTION=${1:-status}
M=${2:-$ACTIVE}
LOCKED=0
say() { printf '%s\n' "$*"; }
die() { say "CarLife: ERROR: $*" >&2; exit 1; }
exists() { [ -e "$1" ] || [ -L "$1" ]; }
digest() { sha256sum "$1" | cut -d ' ' -f 1; }
hash_is() { [ -f "$1" ] && [ ! -L "$1" ] && [ "$(digest "$1")" = "$2" ]; }
directory() { [ -d "$1" ] && [ ! -L "$1" ] && [ "$(readlink -f "$1")" = "$1" ]; }
atomic() {
    printf '%s\n' "$2" > "$1.tmp.$$"
    chmod 600 "$1.tmp.$$"
    sync
    mv -f "$1.tmp.$$" "$1"
    sync
}
atomic_copy() {
    cp "$1" "$2.tmp.$$"
    chmod "$3" "$2.tmp.$$"
    sync
    mv -f "$2.tmp.$$" "$2"
    sync
}
phase() { atomic "$D/phase" "$1"; }
lock() {
    boot_id=$(cat /proc/sys/kernel/random/boot_id)
    case "$boot_id" in ''|*[!a-zA-Z0-9-]*) die 'boot identity';; esac
    # Atomic mkdir needs no second metadata write. A power cut cannot leave an
    # empty stale lock blocking the next boot, which has a different boot ID.
    LOCK=$D/lock.$boot_id
    mkdir "$LOCK" 2>/dev/null || die 'another transaction in this boot; reboot after an interrupted process'
    LOCKED=1
}
cleanup() {
    if [ "$LOCKED" = 1 ]; then rmdir "$LOCK"; fi
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
read_boot_receipt() {
    [ -f "$D/boot.env" ] && [ ! -L "$D/boot.env" ] || die 'device boot receipt missing'
    # Only four device-derived values may override the portable manifest.
    # In particular, never replace the incoming package/profile identity.
    [ "$(wc -l < "$D/boot.env" | tr -d ' ')" = 4 ] || die 'invalid boot receipt'
    SLOT_SUFFIX=$(sed -n 's/^SLOT_SUFFIX=//p' "$D/boot.env")
    BOOT_SIZE=$(sed -n 's/^BOOT_SIZE=//p' "$D/boot.env")
    BOOT_ORIGINAL_SHA256=$(sed -n 's/^BOOT_ORIGINAL_SHA256=//p' "$D/boot.env")
    BOOT_CANDIDATE_SHA256=$(sed -n 's/^BOOT_CANDIDATE_SHA256=//p' "$D/boot.env")
    case "$BOOT_SIZE" in ''|*[!0-9]*) die 'boot receipt size';; esac
    for value in "$BOOT_ORIGINAL_SHA256" "$BOOT_CANDIDATE_SHA256"; do
        [ "${#value}" = 64 ] || die 'boot receipt hash length'
        case "$value" in *[!0-9a-f]*) die 'boot receipt hash';; esac
    done
}
load_env() {
    [ -f "$1" ] && [ ! -L "$1" ] || die 'missing manifest'
    BOOT_STRATEGY=prebuilt
    BOOT_PROFILE_ID=none
    . "$1"
    [ "$FORMAT:$MODULE_ID:$PROFILE" = '1:carlife_on_box:tbox-ultra-1-sm6350-v1' ] || die 'unsupported package'
    case "$BOOT_STRATEGY" in
        prebuilt) ;;
        on-device)
            [ "${#BOOT_PROFILE_ID}" = 64 ] || die 'boot profile'
            case "$BOOT_PROFILE_ID" in *[!0-9a-f]*) die 'boot profile';; esac
            if exists "$D/boot.env"; then
                read_boot_receipt
            else
                [ "$ACTION:$PACKAGE_KIND" = install:universal ] || die 'first universal install required'
                SLOT_SUFFIX=$(getprop ro.boot.slot_suffix)
            fi;;
        *) die 'boot strategy';;
    esac
    case "$SLOT_SUFFIX" in _a|_b) ;; *) die 'slot';; esac
    BOOT=/dev/block/by-name/boot$SLOT_SUFFIX
    V=$P/versions/$RUNTIME_VERSION
}
verify_package() {
    case "$M" in "$ACTIVE"|"$STAGED") ;; *) die 'unexpected module path';; esac
    directory "$M" || die 'module directory'
    [ -z "$(find "$M" -type l -print)" ] || die 'module contains symlinks'
    checks=module.sha256
    [ "$ACTION" != install ] || checks=payload.sha256
    (cd "$M" && sha256sum -c "$checks" >/dev/null) || die 'package checksum'
    load_env "$M/manifest.env"
}
device() {
    [ "$(id -u)" = 0 ] || die 'root required'
    [ "$(getprop ro.boot.slot_suffix)" = "$SLOT_SUFFIX" ] || die 'active slot differs from package'
    if [ "$BOOT_STRATEGY" = prebuilt ]; then
        [ "$(getprop ro.build.fingerprint)" = "$EXPECTED_FINGERPRINT" ] || die 'firmware differs from package'
    fi
    [ "$(getprop ro.build.version.sdk)" = "$SDK" ] || die 'Android SDK mismatch'
    [ "$(getprop ro.board.reverse.custom)" = "$BOARD" ] || die 'board mismatch'
    grep -F -x "CHANNEL=$CHANNEL" /system/bin/sd-reverse.sh >/dev/null || die 'projection channel mismatch'
    [ "$(magisk -V)" -ge "$MAGISK_VERSION_CODE" ] || die 'Magisk version is too old for this installer'
    [ "$(getprop ro.boot.vbmeta.device_state)" = unlocked ] || die 'bootloader must already be unlocked'
    [ "$(getenforce)" = Permissive ] || die 'this profile has no verified enforcing policy; security left unchanged'
    [ -b "$BOOT" ] || die 'boot partition type'
    if [ "$BOOT_STRATEGY:$BOOT_SIZE" = on-device:auto ]; then
        BOOT_SIZE=$(blockdev --getsize64 "$BOOT")
        case "$BOOT_SIZE" in ''|*[!0-9]*) die 'boot partition size';; esac
        BOOT_ORIGINAL_SHA256=$(digest "$BOOT")
    fi
    [ "$(blockdev --getsize64 "$BOOT")" = "$BOOT_SIZE" ] || die 'boot partition size/type'
    [ "$(digest /system/etc/init/hw/init.rc)" = "$INIT_SHA256" ] || die 'OEM init mismatch'
    [ "$(digest /system/bin/sd-reverse.sh)" = "$OEM_SHA256" ] || die 'OEM projection mismatch'
}
boot_is() { [ "$(digest "$BOOT")" = "$1" ]; }
owned() { directory "$1" && hash_is "$1/.installer-owned" "$(printf 'carlife_on_box\n' | sha256sum | cut -d ' ' -f 1)"; }
seed() {
    # The builder validates archive names and modes; only these two roots exist.
    while IFS="$(printf '\t')" read -r mode path; do
        case "$path" in seed/runtime*) dest=$P${path#seed/runtime};; seed/selector*) dest=$S${path#seed/selector};; *) die 'seed directory';; esac
        mkdir -p "$dest"; chmod "$mode" "$dest"; chown 0:0 "$dest"
        case "$dest" in "$P"|"$S") printf 'carlife_on_box\n' > "$dest/.installer-owned";; esac
    done < "$M/payload-dirs.tsv"
    while IFS="$(printf '\t')" read -r expected mode path; do
        case "$path" in seed/runtime/*) dest=$P/${path#seed/runtime/};; seed/selector/*) dest=$S/${path#seed/selector/};; *) die 'seed file';; esac
        hash_is "$M/$path" "$expected" || die 'seed hash'
        cp "$M/$path" "$dest"; chmod "$mode" "$dest"; chown 0:0 "$dest"
        hash_is "$dest" "$expected" || die 'seed readback'
    done < "$M/payload-files.tsv"
    sync
}
check_owned() { owned "$P" && owned "$S" || die 'installation ownership differs; data preserved'; }
check_payload() {
    [ -d "$1" ] && [ ! -L "$1" ] || return 1
    [ "$(stat -c '%u:%g:%a' "$1")" = 0:0:700 ] || return 1
    for executable in runtime-controller.sh uevent-watch; do
        [ -f "$1/$executable" ] && [ ! -L "$1/$executable" ] && [ "$(stat -c '%u:%g:%a' "$1/$executable")" = 0:0:500 ] || return 1
    done
    for data_file in md.jar payload.sha256; do
        [ -f "$1/$data_file" ] && [ ! -L "$1/$data_file" ] && [ "$(stat -c '%u:%g:%a' "$1/$data_file")" = 0:0:400 ] || return 1
    done
    (cd "$1" && sha256sum -c payload.sha256 >/dev/null) || return 1
}
remember_tools() {
    atomic_copy "$M/manager.sh" "$D/manager.sh" 500
    atomic_copy "$M/manifest.env" "$D/current.env" 400
}
fresh_install() {
    boot_is "$BOOT_ORIGINAL_SHA256" || die 'boot differs from the saved source; nothing flashed'
    directory /data/local && directory /data/adb || die 'data parent paths'
    ! exists "$P" && ! exists "$S" || die 'existing CarLife roots preserved; use their current updater'
    ! exists /data/adb/modules/carlife_native_pilot_v3 || die 'historical pilot present; preserve the existing installation'
    if exists "$D/original.img"; then
        hash_is "$D/original.img" "$BOOT_ORIGINAL_SHA256" || die 'existing original backup differs'
    else
        dd if="$BOOT" of="$D/original.img" bs=1048576
        chmod 400 "$D/original.img"; sync
        hash_is "$D/original.img" "$BOOT_ORIGINAL_SHA256" || die 'original backup readback'
    fi
    [ "$(stat -c '%s' "$D/original.img")" = "$BOOT_SIZE" ] || die 'original backup size'
    if [ "$BOOT_STRATEGY" = on-device ]; then
        if exists "$D/candidate.img"; then
            # Retry is safe here only because boot still equals the saved
            # original and both live roots are absent (checked above). This
            # also covers interruption after patch publication but before the
            # receipt/phase commit. Retain the previous attempt as evidence.
            [ -f "$D/candidate.img" ] && [ ! -L "$D/candidate.img" ] || die 'previous candidate type'
            previous_candidate=$(digest "$D/candidate.img")
            mv "$D/candidate.img" "$D/candidate.$previous_candidate.img"
            sync
        fi
        sh "$M/patch-boot.sh" "$D" "$M" "$BOOT_SIZE" || die 'local boot patch failed; nothing flashed'
        [ -f "$D/candidate.img" ] && [ ! -L "$D/candidate.img" ] || die 'local candidate missing'
        [ "$(stat -c '%s' "$D/candidate.img")" = "$BOOT_SIZE" ] || die 'local candidate size'
        BOOT_CANDIDATE_SHA256=$(digest "$D/candidate.img")
        [ "$BOOT_CANDIDATE_SHA256" != "$BOOT_ORIGINAL_SHA256" ] || die 'local boot patch made no change'
        chmod 400 "$D/candidate.img"; sync
        atomic "$D/boot.env" "SLOT_SUFFIX=$SLOT_SUFFIX
BOOT_SIZE=$BOOT_SIZE
BOOT_ORIGINAL_SHA256=$BOOT_ORIGINAL_SHA256
BOOT_CANDIDATE_SHA256=$BOOT_CANDIDATE_SHA256"
    else
        cp "$M/boot/candidate.img" "$D/candidate.img"; chmod 400 "$D/candidate.img"
        hash_is "$D/candidate.img" "$BOOT_CANDIDATE_SHA256" || die 'candidate backup readback'
    fi
    atomic_copy "$M/manifest.env" "$D/original.env" 400
    remember_tools
    rm -f "$D/rollback-boot-id" "$D/cleanup"
    phase prepared
    seed
    check_owned
    check_payload "$V" || die 'installed runtime verification'
    # Everything needed by the new boot exists durably before the sole write.
    atomic "$D/boot-write.intent" "$BOOT_ORIGINAL_SHA256 $BOOT_CANDIDATE_SHA256"
    boot_is "$BOOT_ORIGINAL_SHA256" || die 'boot changed during preparation'
    dd if="$D/candidate.img" of="$BOOT" bs=1048576 conv=fsync
    sync
    boot_is "$BOOT_CANDIDATE_SHA256" || die 'BOOT WRITE READBACK FAILED; keep power and original backup'
    phase installed
    say 'CarLife installed. Reboot once to activate. Original boot is saved outside the module.'
}
update_origin() {
    if [ "$BOOT_STRATEGY" = on-device ]; then
        original_strategy=$(sed -n 's/^BOOT_STRATEGY=//p' "$D/original.env")
        original_profile=$(sed -n 's/^BOOT_PROFILE_ID=//p' "$D/original.env")
        [ "$original_strategy" = on-device ] && [ "$BOOT_PROFILE_ID" = "$original_profile" ] || die 'update boot profile differs'
        case "$PACKAGE_KIND" in
            universal) ;;
            update) [ "$BASE_PAYLOAD_ID" = profile ] || die 'update origin mismatch';;
            *) die 'on-device update package kind';;
        esac
    else
        install_id=$(sed -n 's/^PAYLOAD_ID=//p' "$D/original.env")
        [ "$BASE_PAYLOAD_ID" = "$install_id" ] || die 'update belongs to another installation'
    fi
}
stage_update() {
    check_owned
    directory "$P/versions" || die 'runtime versions path'
    [ -f "$D/original.env" ] && [ -f "$D/current.env" ] || die 'first install required'
    update_origin
    boot_is "$BOOT_CANDIDATE_SHA256" || die 'boot changed; update preserved existing files'
    [ "$(cat "$D/phase")" = installed ] || die 'finish the previous transaction first'
    check_payload "$V" || die 'current runtime damaged'
    hash_is "$P/runtime-control.sh" "$RUNTIME_ENTRY_SHA256" || die 'resolver changed'
    hash_is "$S/selector-daemon.sh" "$SELECTOR_ENTRY_SHA256" || die 'selector changed'
    atomic "$D/pending" "$PAYLOAD_ID"
    say 'CarLife update staged. Current session is unchanged; reboot to apply.'
}
activate() {
    [ -f "$D/pending" ] || return 0
    pending=$(cat "$D/pending")
    if [ "$pending" != "$PAYLOAD_ID" ]; then
        # Magisk may abort a later ZIP and discard modules_update. Keep the
        # still-valid active generation, rather than leaving a stale boot gate.
        [ "$(cat "$D/phase")" = installed ] && check_payload "$V" || die 'pending update mismatch; recovery required'
        rm "$D/pending"; sync
        say 'Cancelled an unactivated update; current runtime retained.'
        return 0
    fi
    check_owned
    directory "$P/versions" || die 'runtime versions path'
    case "$PACKAGE_KIND" in update|universal) ;; *) die 'pending package is not an update';; esac
    update_origin
    boot_is "$BOOT_CANDIDATE_SHA256" || die 'boot changed before update activation'
    # A validated staged directory and a previous directory make interrupted
    # renames resumable. No live native process can pass the pending boot gate.
    next=$P/versions/.installer-next
    previous=$D/previous-version
    if [ "$(cat "$D/phase")" = installed ]; then
        if exists "$next"; then
            directory "$next" || die 'update staging path'
            # A copy interrupted before any rename only touches owned staging.
            rm -rf "$next"
        fi
        mkdir -m 700 "$next"
        for name in md.jar payload.sha256 runtime-controller.sh uevent-watch; do
            cp "$M/seed/runtime/versions/$RUNTIME_VERSION/$name" "$next/$name"
            case "$name" in *.sh|uevent-watch) chmod 500 "$next/$name";; *) chmod 400 "$next/$name";; esac
            chown 0:0 "$next/$name"
        done
        check_payload "$next" || die 'update staging verification'
        if exists "$previous"; then
            check_payload "$previous" || die 'previous update backup changed'
            rm -rf "$previous"
        fi
        cp "$D/current.env" "$D/previous.env"
        sync; phase updating
    fi
    [ "$(cat "$D/phase")" = updating ] || die 'unexpected update phase'
    if exists "$next"; then
        check_payload "$next" || die 'staged update damaged'
        if exists "$V"; then
            ! exists "$previous" || die 'ambiguous update directories'
            check_payload "$V" || die 'old runtime damaged'
            mv "$V" "$previous"; sync
        fi
        [ -d "$previous" ] || die 'previous runtime backup missing'
        mv "$next" "$V"; sync
    fi
    check_payload "$V" || die 'activated runtime damaged'
    [ "$(digest "$V/payload.sha256")" = "$(digest "$M/seed/runtime/versions/$RUNTIME_VERSION/payload.sha256")" ] || die 'activated generation mismatch'
    remember_tools
    # Remove pending first: phase=updating still holds the boot gate closed.
    # On power loss the post-fs-data hook completes from the active package.
    rm "$D/pending"; sync
    phase installed
    say 'CarLife update activated. Original boot backup retained.'
}
rollback() {
    load_env "$D/original.env"
    [ "$(getprop ro.boot.slot_suffix)" = "$SLOT_SUFFIX" ] || die 'slot changed; original backup preserved'
    [ -b "$BOOT" ] && [ "$(blockdev --getsize64 "$BOOT")" = "$BOOT_SIZE" ] || die 'boot partition mismatch'
    hash_is "$D/original.img" "$BOOT_ORIGINAL_SHA256" || die 'original backup damaged'
    boot_is "$BOOT_ORIGINAL_SHA256" || boot_is "$BOOT_CANDIDATE_SHA256" || die 'boot changed outside this installer; will not overwrite it'
    if exists "$STAGED"; then
        directory "$STAGED" && [ "$(sed -n 's/^MODULE_ID=//p' "$STAGED/manifest.env")" = carlife_on_box ] || die 'foreign pending module preserved'
        rm -rf "$STAGED"
        sync
    fi
    if ! boot_is "$BOOT_ORIGINAL_SHA256"; then
        boot_is "$BOOT_CANDIDATE_SHA256" || die 'boot changed outside this installer; will not overwrite it'
        atomic "$D/boot-write.intent" "$BOOT_CANDIDATE_SHA256 $BOOT_ORIGINAL_SHA256"
        dd if="$D/original.img" of="$BOOT" bs=1048576 conv=fsync
        sync
        boot_is "$BOOT_ORIGINAL_SHA256" || die 'ROLLBACK READBACK FAILED; original backup retained'
    fi
    # Boot gate switches to the original OEM branch. Live cleanup is deferred
    # until a reboot; never remove JARs under a running native owner.
    if [ ! -f "$D/rollback-boot-id" ]; then
        atomic "$D/rollback-boot-id" "$(cat /proc/sys/kernel/random/boot_id)"
    fi
    phase rollback
    [ ! -f "$D/pending" ] || rm "$D/pending"
    if [ -d "$ACTIVE" ]; then : > "$ACTIVE/remove"; fi
    # The external command also completes cleanup after a reboot when Magisk
    # aborted initial installation and there is no module hook left to run.
    if [ "$ACTION" = uninstall ] || [ "$ACTION" = activate ] ||
       [ "$(cat "$D/rollback-boot-id")" != "$(cat /proc/sys/kernel/random/boot_id)" ]; then
        for target in "$P" "$S"; do
            if exists "$target"; then owned "$target" || die 'foreign data retained'; rm -rf "$target"; fi
        done
        for target in "$D/previous-version" "$P/versions/.installer-next"; do
            if exists "$target"; then directory "$target" || die 'backup path changed'; rm -rf "$target"; fi
        done
        atomic "$D/cleanup" complete
    fi
    sync
    say 'Original boot restored. Reboot to finish rollback. Recovery backup is retained.'
}
[ "$(id -u)" = 0 ] || die 'root required'
case "$ACTION" in install|activate|status|rollback|uninstall) ;; *) die 'usage: manager.sh install|activate|status|rollback [module-directory]';; esac
if [ "$ACTION" = status ]; then
    if [ -f "$D/phase" ]; then say "phase=$(cat "$D/phase")"; else say 'not installed'; fi
    [ ! -f "$D/pending" ] || say 'update=pending reboot'
    [ ! -f "$D/original.env" ] || { load_env "$D/original.env"; say "slot=$SLOT_SUFFIX"; say "original_boot=$BOOT_ORIGINAL_SHA256"; say "backup=$D/original.img"; }
    exit 0
fi
if [ "$ACTION" = install ]; then
    verify_package; device
    if ! exists "$D"; then mkdir -m 700 "$D"; fi
else
    [ -f "$D/original.env" ] || die 'installation receipt missing'
fi
directory "$D" && [ "$(stat -c '%u:%g:%a' "$D")" = 0:0:700 ] || die 'state directory ownership'
lock
case "$ACTION" in
 install) case "$PACKAGE_KIND" in
     install) fresh_install;;
     update) stage_update;;
     universal)
         [ "$BOOT_STRATEGY" = on-device ] || die 'universal boot strategy'
         if [ -f "$D/phase" ] && [ "$(cat "$D/phase")" = installed ]; then stage_update
         else fresh_install; fi;;
     *) die 'package kind';; esac;;
 activate)
    if [ "$(cat "$D/phase")" = rollback ]; then rollback
    elif [ "$(cat "$D/phase")" = prepared ]; then
        verify_package
        boot_is "$BOOT_CANDIDATE_SHA256" || die 'initial installation incomplete; use saved recovery manager'
        check_owned; check_payload "$V" || die 'initial payload incomplete'
        hash_is "$P/runtime-control.sh" "$RUNTIME_ENTRY_SHA256" && hash_is "$S/selector-daemon.sh" "$SELECTOR_ENTRY_SHA256" || die 'initial entry points incomplete'
        phase installed
        say 'Completed interrupted initial commit.'
    elif [ -f "$D/pending" ] || [ "$(cat "$D/phase")" = updating ]; then
        verify_package
        if [ ! -f "$D/pending" ]; then atomic "$D/pending" "$PAYLOAD_ID"; fi
        activate
    fi;;
 rollback|uninstall) rollback;;
esac
