#!/system/bin/sh
# One download, existing transaction backends. This file never writes boot.
set -eu
umask 077
export ASH_STANDALONE=1
PATH=/data/adb/magisk:/system/bin:/system/xbin:/vendor/bin
export PATH
D=/data/adb/carlife-package
P=/data/local/carlife-native
S=/data/adb/carlife-boot
I=/data/adb/carlife-on-box
L=/data/adb/carlife-runtime-update
A=/data/adb/modules
U=/data/adb/modules_update
UI=carlife_oem_selector_v1
V=$P/versions/boot-native-v1-20261014
MAIN=system/app/MainAiBox/MainAiBox.apk
SETTINGS=system/app/SudingSettings_chelianyi_new/SudingSettings_chelianyi_new.apk
BB=/data/adb/magisk/busybox
MODE=${1:-status}
W=${2:-}
die() { printf 'CarLife package: ERROR: %s\n' "$*" >&2; exit 70; }
exists() { [ -e "$1" ] || [ -L "$1" ]; }
dir() { [ -d "$1" ] && [ ! -L "$1" ] && [ "$(readlink -f "$1")" = "$1" ]; }
file() { [ -f "$1" ] && [ ! -L "$1" ]; }
hash() { sha256sum "$1" | cut -d ' ' -f 1; }
field() { sed -n "s/^$2=//p" "$1"; }
exact() { file "$1" && [ "$(cat "$1"; printf .)" = "$(printf '%s\n.' "$2")" ]; }
atomic() {
    local f=$1 t=$1.tmp.$$
    ! exists "$t" || die 'temporary file already exists'
    (set -C; printf '%s\n' "$2" > "$t")
    chmod 600 "$t"; sync; mv "$t" "$f"; sync
}
index_tree() {
    (cd "$1" && find . -type f | LC_ALL=C sort | while IFS= read -r n; do
        n=${n#./}; printf '%s  %s\n' "$(hash "$n")" "$n"
    done)
}
copy_ui() {
    mkdir "$2"
    cp -a "$1/." "$2/"
    [ -z "$(find "$2" -type l -print)" ] || die 'UI backup contains a symlink'
}
package() {
    dir "$W" && file "$W/package.sha256" || die 'package workspace'
    [ -z "$(find "$W" -type l -print)" ] || die 'package symlink'
    (cd "$W" && sha256sum -c package.sha256 >/dev/null) || die 'package checksums'
    [ "$(field "$W/manifest.env" FORMAT)" = 1 ] || die 'package format'
    [ "$(field "$W/manifest.env" PROFILE)" = tbox-ultra-1-sm6350-v1 ] || die 'package profile'
}
legacy_root() {
    local n h
    for n in "$P" "$P/versions" "$V" "$P/logs" "$P/runtime" "$S"; do
        dir "$n" && [ "$(stat -c '%u:%g:%a' "$n")" = 0:0:700 ] || return 1
    done
    exact "$P/owner" org.carlife.boot-native.v1 && exact "$P/current" boot-native-v1-20261014 || return 1
    file "$P/runtime-control.sh" || return 1
    h=$(hash "$P/runtime-control.sh")
    case "$h" in b97b882340ec5e8f16f9e0cbd19aa18eb1cccc5e75bca1a278609d76f02d791a|6add4df4d3aeb321a813e9d2262d755c7d3615f5ff0883bca08c3cb70fe36481) ;; *) return 1;; esac
    for n in md.jar runtime-controller.sh payload.sha256 uevent-watch; do file "$V/$n" || return 1; done
    [ "$(hash "$V/uevent-watch")" = a109a2a1270a09e25c87199cb9fa027c90db61dc511d846cb2fc6b94806c814e ] || return 1
    exact "$V/payload.sha256" "$(printf '%s  md.jar\n%s  runtime-controller.sh\n%s  uevent-watch' "$(hash "$V/md.jar")" "$(hash "$V/runtime-controller.sh")" "$(hash "$V/uevent-watch")")" || return 1
    for n in "$P/.installer-owned" "$S/.installer-owned"; do ! exists "$n" || return 1; done
    # An APPLIED historical receipt stays pending by design. Do not replace an
    # interrupted transaction or infer success merely from the receipt's name.
    if exists "$L/pending"; then
        local opid action completed op
        opid=$(field "$L/pending" OP_ID)
        [ "${#opid}" = 64 ] || return 1
        case "$opid" in *[!0-9a-f]*) return 1;; esac
        op=$L/operations/$opid
        dir "$op" && file "$op/complete" && file "$op/descriptor" || return 1
        cmp -s "$L/pending" "$op/descriptor" || return 1
        action=$(field "$L/pending" PACKAGE_ACTION)
        case "$action" in update|rollback) ;; *) return 1;; esac
        completed=$(field "$op/complete" BOOT)
        [ "${#completed}" = 36 ] || return 1
        case "$completed" in *[!0-9a-f-]*) return 1;; esac
        exact "$op/complete" "$(printf 'OP_ID=%s\nBOOT=%s\nACTION=%s' "$opid" "$completed" "$action")" || return 1
        for n in md.jar runtime-controller.sh payload.sha256 uevent-watch; do
            file "$op/target/$n" && cmp -s "$V/$n" "$op/target/$n" || return 1
        done
    fi
}
ui_check() {
    local h1 h2 n
    file "/$MAIN" && file "/$SETTINGS" || die 'OEM Settings/MainAiBox APK missing'
    h1=$(hash "/$MAIN"); h2=$(hash "/$SETTINGS")
    grep -F -x "$h1 $h2" "$W/compatible-ui.txt" >/dev/null || die 'unreviewed OEM APK pair; nothing staged'
    if exists "$A/$UI"; then
        dir "$A/$UI" && [ -z "$(find "$A/$UI" -type l -print)" ] || die 'OEM module directory/symlink'
        [ "$(field "$A/$UI/module.prop" id)" = "$UI" ] || die 'OEM module identity'
        file "$A/$UI/$MAIN" && file "$A/$UI/$SETTINGS" || die 'OEM module APK missing'
        grep -F -x "$(hash "$A/$UI/$MAIN") $(hash "$A/$UI/$SETTINGS")" "$W/compatible-ui.txt" >/dev/null || die 'unreviewed existing OEM overlay'
        while IFS= read -r n; do
            n=${n#"$A/$UI/"}
            case "$n" in module.prop|service.sh|disable|remove|update|"$MAIN"|"$SETTINGS") ;; *) die 'unexpected OEM module file; preserve it';; esac
        done <<EOF
$(find "$A/$UI" -type f)
EOF
        ! exists "$A/$UI/remove" && ! exists "$A/$UI/update" || die 'OEM module has a pending action'
    fi
}
preflight() {
    package
    [ "$(id -u)" = 0 ] || die 'root required'
    [ "$(getprop ro.build.version.sdk)" = 35 ] || die 'Android 15 required'
    case "$(getprop ro.board.platform)" in sm6350|lito) ;; *) die 'SM6350 platform required';; esac
    for n in /data /data/adb /data/local "$A" "$U"; do dir "$n" || die 'noncanonical data/module parent'; done
    for n in carlife_on_box carlife_runtime_update "$UI"; do
        ! exists "$U/$n" || die "pending module preserved: $n"
        if exists "$A/$n"; then
            dir "$A/$n" || die 'module path is not a directory'
            ! exists "$A/$n/remove" && ! exists "$A/$n/update" || die "pending active module action: $n"
        fi
    done
    if exact "$I/phase" rollback && exact "$I/cleanup" complete &&
       ! exists "$P" && ! exists "$S" && ! exists "$A/carlife_on_box"; then
        # A complete managed rollback retains its original recovery receipt.
        # The existing backend explicitly supports reinstall from that receipt.
        route=fresh
    elif exists "$I" || exists "$P/.installer-owned" || exists "$S/.installer-owned"; then
        dir "$I" && exact "$I/phase" installed && file "$I/original.env" || die 'incomplete managed installation preserved'
        [ "$(field "$I/original.env" MODULE_ID)" = carlife_on_box ] &&
        [ "$(field "$I/original.env" BOOT_PROFILE_ID)" = "$(field "$W/manifest.env" BOOT_PROFILE_ID)" ] || die 'managed boot profile differs'
        exact "$P/.installer-owned" carlife_on_box && exact "$S/.installer-owned" carlife_on_box || die 'managed ownership differs'
        ! exists "$I/pending" || die 'managed transaction pending; preserved'
        ! exists "$A/carlife_runtime_update" || die 'two active runtime families; preserved'
        route=managed
    elif exists "$P" || exists "$S" || exists "$L" || exists "$A/carlife_runtime_update"; then
        legacy_root || die 'unsupported or incomplete legacy runtime; preserved'
        ! exists "$A/carlife_on_box" || die 'managed module without receipt; preserved'
        route=legacy
    else
        ! exists "$A/carlife_on_box" && ! exists "$A/carlife_native_pilot_v3" || die 'existing module without runtime; preserved'
        route=fresh
    fi
    if [ "$route" = fresh ]; then
        [ "$(getprop ro.board.reverse.custom)" = chelianyi-led ] || die 'OEM board differs'
        [ "$(getprop ro.boot.vbmeta.device_state)" = unlocked ] || die 'bootloader must already be unlocked'
        [ "$(getenforce)" = Permissive ] || die 'this firmware profile requires its existing Permissive mode'
        [ "$(magisk -V)" -ge 30700 ] || die 'Magisk 30.7 or newer required'
        [ "$(hash /system/etc/init/hw/init.rc)" = "$(field "$W/manifest.env" INIT_SHA256)" ] || die 'OEM init differs'
        [ "$(hash /system/bin/sd-reverse.sh)" = "$(field "$W/manifest.env" OEM_SHA256)" ] || die 'OEM projection differs'
    fi
    if exists "$D"; then
        dir "$D" && [ "$(stat -c '%u:%g:%a' "$D")" = 0:0:700 ] || die 'package backup directory differs'
        file "$D/branch" || die 'saved package branch missing'
        case "$(cat "$D/branch"):$route" in legacy:legacy|managed:managed|managed:fresh) ;; *) die 'installation family changed';; esac
        backups_ok || die 'original combined backup changed'
        if exact "$D/phase" rollback; then
            rollback_complete || die 'rollback has not completed; reboot before reinstall'
        else exact "$D/phase" installed || die 'previous combined transaction incomplete; use saved control.sh status/rollback'; fi
    fi
    ui_check
    printf '%s\n' "$route" > "$W/route"
    printf 'PREFLIGHT=PASS ROUTE=%s\n' "$route"
}
reindex_legacy() {
    local p=$1 n
    atomic "$p/manifest.env" "$(printf 'FORMAT=1\nMODULE_ID=carlife_runtime_update\nPACKAGE_ACTION=update\nRUNTIME_VERSION=boot-native-v1-20261014\nPAYLOAD_ID=%s' "$(hash "$p/payload/payload.sha256")")"
    (cd "$p" && for n in INSTALL_EN.txt INSTALL_RU.txt LICENSE action.sh allowed-resolvers.txt manager.sh manifest.env module.prop observer.sha256 payload/md.jar payload/payload.sha256 payload/runtime-controller.sh service.sh skip_mount; do
        printf '%s  %s\n' "$(hash "$n")" "$n"
    done | LC_ALL=C sort -k2) > "$p/module.sha256"
    (cd "$p" && for n in INSTALL_EN.txt INSTALL_RU.txt LICENSE action.sh allowed-resolvers.txt customize.sh manager.sh manifest.env module.prop module.sha256 observer.sha256 payload/md.jar payload/payload.sha256 payload/runtime-controller.sh service.sh skip_mount; do
        printf '%s  %s\n' "$(hash "$n")" "$n"
    done | LC_ALL=C sort -k2) > "$p/files.sha256"
}
backups_ok() {
    file "$D/backup.sha256" && (cd "$D" && sha256sum -c backup.sha256 >/dev/null)
}
rollback_complete() {
    local n
    case "$(cat "$D/branch")" in
        legacy)
            for n in md.jar runtime-controller.sh payload.sha256; do
                cmp -s "$V/$n" "$D/legacy-before/payload/$n" || return 1
            done;;
        managed)
            exact "$I/phase" rollback && exact "$I/cleanup" complete &&
            ! exists "$P" && ! exists "$S" && ! exists "$A/carlife_on_box" || return 1;;
        *) return 1;;
    esac
    case "$(cat "$D/ui-before.state")" in
        ABSENT) ! exists "$A/$UI";;
        PRESENT) dir "$A/$UI" && [ "$(index_tree "$A/$UI")" = "$(index_tree "$D/ui-before")" ];;
        *) return 1;;
    esac
}
prepare() {
    preflight
    route=$(cat "$W/route")
    if exact "$D/phase" rollback; then
        old=$D.rollback.$(cat /proc/sys/kernel/random/boot_id)
        ! exists "$old" || die 'previous rollback archive already exists; preserved'
        mv "$D" "$old"; sync
    fi
    if ! exists "$D"; then
        mkdir -m 700 "$D"
        atomic "$D/phase" preparing
        case "$route" in fresh|managed) atomic "$D/branch" managed;; legacy) atomic "$D/branch" legacy;; esac
        cp "$W/control.sh" "$D/control.sh"; chmod 500 "$D/control.sh"
        if exists "$A/$UI"; then
            copy_ui "$A/$UI" "$D/ui-before"
            atomic "$D/ui-before.state" PRESENT
        else atomic "$D/ui-before.state" ABSENT; fi
        if [ "$route" = legacy ]; then
            mkdir "$D/legacy-before"
            unzip -o "$W/payload/legacy-update.zip" -d "$D/legacy-before" >/dev/null
            for n in md.jar runtime-controller.sh payload.sha256; do cp "$V/$n" "$D/legacy-before/payload/$n"; done
            if file "$A/carlife_runtime_update/module.prop"; then
                cp "$A/carlife_runtime_update/module.prop" "$D/legacy-before/module.prop"
            fi
            reindex_legacy "$D/legacy-before"
        fi
        # Recovery survives Magisk deleting an aborted/removed module.
        cp "$W/payload/legacy-rollback.zip" "$D/legacy-first-backup-rollback.zip"
        (cd "$D" && { printf '%s  %s\n' "$(hash branch)" branch; printf '%s  %s\n' "$(hash ui-before.state)" ui-before.state;
            for sub in ui-before legacy-before; do
                if [ -d "$sub" ]; then find "$sub" -type f | LC_ALL=C sort | while IFS= read -r n; do printf '%s  %s\n' "$(hash "$n")" "$n"; done; fi
            done
        }) > "$D/backup.sha256"
        chmod 600 "$D/backup.sha256"; sync
        backups_ok || die 'rollback snapshot readback failed'
    fi
    # A failed install retains this explicit phase and its original snapshots.
    atomic "$D/phase" prepared
    atomic "$D/last-release" "$(field "$W/manifest.env" RELEASE)"
    case "$route" in
        legacy) atomic "$D/last-backend-id" carlife_runtime_update; backend_zip=legacy-update.zip;;
        *) atomic "$D/last-backend-id" carlife_on_box; backend_zip=universal.zip;;
    esac
    unzip -p "$W/payload/$backend_zip" module.sha256 > "$D/last-backend.module.sha256"
    cp "$W/control.sh" "$D/control.sh.new"; chmod 500 "$D/control.sh.new"; sync
    mv "$D/control.sh.new" "$D/control.sh"
    ! exists "$D/ui-next" || die 'previous UI staging preserved'
    mkdir "$D/ui-next"
    unzip -o "$W/payload/oem-overlay.zip" -d "$D/ui-next" >/dev/null
    (cd "$D/ui-next" && sha256sum -c "$W/oem-files.sha256" >/dev/null) || die 'UI staging checksum'
    cp "$W/oem-files.sha256" "$D/ui-next.sha256"
    sync
    printf 'PREPARED=true ROUTE=%s\n' "$route"
}
ui_permissions() {
    find "$1" -type d -exec chmod 755 '{}' \;
    find "$1" -type f -exec chmod 644 '{}' \;
    [ ! -f "$1/service.sh" ] || chmod 755 "$1/service.sh"
    chown -R 0:0 "$1"
    chcon -R u:object_r:system_file:s0 "$1"
}
commit() {
    package
    exact "$D/phase" prepared && backups_ok || die 'prepared state differs'
    dir "$D/ui-next" && ! exists "$U/$UI" || die 'UI pending directory changed; preserved'
    (cd "$D/ui-next" && sha256sum -c "$D/ui-next.sha256" >/dev/null) || die 'prepared UI changed'
    ui_permissions "$D/ui-next"
    mv "$D/ui-next" "$U/$UI"; sync
    if ! exists "$A/$UI"; then mkdir "$A/$UI"; fi
    cp "$U/$UI/module.prop" "$A/$UI/module.prop"
    : > "$A/$UI/update"; chmod 644 "$A/$UI/update"
    # The installer deliberately activates its UI; previous disabled state is
    # in the exact UI backup and restored on rollback.
    [ ! -f "$A/$UI/disable" ] || rm "$A/$UI/disable"
    cp "$D/ui-next.sha256" "$D/last-ui.sha256"
    rm "$D/ui-next.sha256"
    atomic "$D/phase" installed
    printf 'PACKAGE=INSTALLED_REBOOT_REQUIRED\n'
}
restore_ui() {
    ! exists "$U/$UI" || die 'pending UI action preserved; reboot before rollback'
    case "$(cat "$D/ui-before.state")" in
        PRESENT)
            copy_ui "$D/ui-before" "$U/$UI"
            ui_permissions "$U/$UI"
            [ -d "$A/$UI" ] || mkdir "$A/$UI"
            cp "$U/$UI/module.prop" "$A/$UI/module.prop"
            : > "$A/$UI/update"
            # Magisk migrates the saved disable flag along with the directory.
            ;;
        ABSENT)
            if exists "$A/$UI"; then
                dir "$A/$UI" && [ "$(field "$A/$UI/module.prop" id)" = "$UI" ] || die 'foreign UI preserved'
                : > "$A/$UI/remove"
            fi;;
        *) die 'UI backup state';;
    esac
    sync
}
rollback_preflight() {
    # Validate every destination before changing boot, pending runtime or UI.
    # Own staged data can be canceled by exact retained indexes; a different
    # package's pending action is never deleted to make rollback proceed.
    local id n idx
    dir "$D" && backups_ok || die 'rollback backup missing/damaged'
    case "$(cat "$D/branch")" in
        legacy) id=carlife_runtime_update; dir "$D/legacy-before" || die 'legacy rollback package missing';;
        managed) id=carlife_on_box;;
        *) die 'unknown saved branch';;
    esac
    if exists "$U/$UI"; then
        dir "$U/$UI" || die 'pending UI path preserved'
        idx=$D/last-ui.sha256
        [ ! -f "$D/ui-next.sha256" ] || idx=$D/ui-next.sha256
        file "$idx" && (cd "$U/$UI" && sha256sum -c "$idx" >/dev/null) || die 'foreign pending UI preserved'
        [ "$(find "$U/$UI" -type f | wc -l | tr -d ' ')" = 3 ] || die 'unexpected pending UI files preserved'
    fi
    if exists "$A/$UI"; then
        dir "$A/$UI" && file "$A/$UI/module.prop" &&
        [ "$(field "$A/$UI/module.prop" id)" = "$UI" ] &&
        [ -z "$(find "$A/$UI" -type l -print)" ] || die 'foreign active UI preserved'
        # A successful install may have changed the visible module.prop while
        # the old APKs remain active until reboot. Accept only this exact mixed
        # metadata case, the installed generation, or the original snapshot.
        local ui_ok=0 prop_ok=0 source
        for source in "$D/last-ui.sha256" "$D/ui-next.sha256"; do
            if file "$source"; then
                if (cd "$A/$UI" && grep '  system/app/' "$source" | sha256sum -c - >/dev/null 2>&1); then ui_ok=1; fi
                if (cd "$A/$UI" && grep '  module.prop$' "$source" | sha256sum -c - >/dev/null 2>&1); then prop_ok=1; fi
            fi
        done
        if dir "$D/ui-before"; then
            if cmp -s "$A/$UI/$MAIN" "$D/ui-before/$MAIN" && cmp -s "$A/$UI/$SETTINGS" "$D/ui-before/$SETTINGS"; then ui_ok=1; fi
            cmp -s "$A/$UI/module.prop" "$D/ui-before/module.prop" && prop_ok=1
        fi
        if [ "$ui_ok" != 1 ] && exists "$U/$UI" &&
           ! exists "$A/$UI/$MAIN" && ! exists "$A/$UI/$SETTINGS"; then
            # Magisk's first-install metadata stub is not a mounted overlay.
            ui_ok=1
        fi
        [ "$ui_ok:$prop_ok" = 1:1 ] || die 'active UI changed outside package; preserved'
        while IFS= read -r n; do
            n=${n#"$A/$UI/"}
            case "$n" in module.prop|disable|update|"$MAIN"|"$SETTINGS") ;;
                service.sh) file "$D/ui-before/service.sh" && cmp -s "$A/$UI/service.sh" "$D/ui-before/service.sh" || die 'active UI service changed; preserved';;
                *) die 'unexpected active UI file/action; preserved';;
            esac
        done <<EOF
$(find "$A/$UI" -type f)
EOF
    fi
    for n in carlife_on_box carlife_runtime_update; do
        if exists "$U/$n"; then
            [ "$n" = "$id" ] && dir "$U/$n" && file "$D/last-backend.module.sha256" || die 'foreign pending runtime preserved'
            cmp -s "$U/$n/module.sha256" "$D/last-backend.module.sha256" || die 'pending runtime belongs to another package; preserved'
            (cd "$U/$n" && sha256sum -c module.sha256 >/dev/null) || die 'pending runtime changed; preserved'
        fi
    done
    if [ "$id" = carlife_on_box ]; then
        if ! file "$I/original.env"; then
            ! exists "$P" && ! exists "$S" || die 'managed installation has no recovery receipt; data preserved'
        else
            file "$I/manager.sh" || die 'managed recovery manager missing'
        fi
    fi
}
cancel_owned_pending() {
    local n
    # Only called after rollback_preflight validated ALL pending directories.
    for n in carlife_on_box carlife_runtime_update "$UI"; do
        if exists "$U/$n"; then
            rm -rf "$U/$n"
            [ ! -f "$A/$n/update" ] || rm "$A/$n/update"
        fi
    done
    sync
}
rollback() {
    rollback_preflight
    cancel_owned_pending
    case "$(cat "$D/branch")" in
        legacy)
            ! exists "$U/carlife_runtime_update" || die 'pending runtime action preserved; reboot before rollback'
            # Reuse the original updater with an exact saved pre-combined
            # payload. Its own first-ever backup remains completely untouched.
            copy_ui "$D/legacy-before" "$U/carlife_runtime_update"
            find "$U/carlife_runtime_update" -type d -exec chmod 700 '{}' \;
            find "$U/carlife_runtime_update" -type f -exec chmod 600 '{}' \;
            for n in manager.sh service.sh action.sh; do chmod 500 "$U/carlife_runtime_update/$n"; done
            "$BB" sh "$U/carlife_runtime_update/manager.sh" stage "$U/carlife_runtime_update"
            [ -d "$A/carlife_runtime_update" ] || mkdir "$A/carlife_runtime_update"
            cp "$U/carlife_runtime_update/module.prop" "$A/carlife_runtime_update/module.prop"
            : > "$A/carlife_runtime_update/update"
            [ ! -f "$A/carlife_runtime_update/disable" ] || rm "$A/carlife_runtime_update/disable"
            restore_ui
            atomic "$D/phase" rollback
            printf 'Exact pre-combined runtime/UI staged. Reboot once. Earlier CarLife remains.\n';;
        managed)
            if file "$I/original.env"; then
                "$BB" sh "$I/manager.sh" rollback
            else
                printf 'Fresh installation never reached boot preparation; original boot was not changed.\n'
            fi
            restore_ui
            atomic "$D/phase" rollback
            printf 'Original boot restored and UI restoration staged. Reboot.\n';;
        *) die 'unknown saved branch';;
    esac
}
[ "$(id -u)" = 0 ] || die 'root required'
case "$MODE" in
    preflight) preflight;;
    prepare) prepare;;
    commit) commit;;
    rollback) rollback;;
    status)
        if ! exists "$D"; then printf 'NOT_INSTALLED\n'; exit 0; fi
        dir "$D" || die 'state path'
        for n in branch phase last-release; do [ ! -f "$D/$n" ] || printf '%s=%s\n' "$n" "$(cat "$D/$n")"; done
        printf 'backup=%s\n' "$D"
        if backups_ok; then printf 'BACKUP=VERIFIED\n'; else printf 'BACKUP=INCOMPLETE_OR_CHANGED\n'; fi
        printf 'rollback: su -c "%s sh %s/control.sh rollback"\n' "$BB" "$D"
        ;;
    *) die 'usage: control.sh status|rollback or internal preflight|prepare|commit package-dir';;
esac
