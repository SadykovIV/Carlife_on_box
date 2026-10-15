#!/system/bin/sh
# Delivery of three data files to an already-owned native CarLife installation.
# No boot writes, new loader, USB properties, security changes or selector writes.
set -u
umask 077
PATH=/data/adb/magisk:/system/bin:/system/xbin:/vendor/bin
export PATH
unset ASH_STANDALONE
LC_ALL=C; export LC_ALL
P=/data/local/carlife-native
VERSION=boot-native-v1-20261014
V=$P/versions/$VERSION
D=/data/adb/carlife-runtime-update
S=/data/adb/carlife-boot
OBSERVER=a109a2a1270a09e25c87199cb9fa027c90db61dc511d846cb2fc6b94806c814e
RESOLVER1=b97b882340ec5e8f16f9e0cbd19aa18eb1cccc5e75bca1a278609d76f02d791a
RESOLVER2=6add4df4d3aeb321a813e9d2262d755c7d3615f5ff0883bca08c3cb70fe36481
MODE=${1-}; M=${2-}; LOCK=; LOCKED=0; OP=; READY_LOG=0
[ "$#" = 2 ] || exit 64
case "$MODE" in stage|apply|status) ;; *) exit 64;; esac
b() { /data/adb/magisk/busybox timeout -k 1 5 "$@"; }
sha() { local x; x=$(b sha256sum "$1") || return 1; x=${x%% *}; hex "$x" || return 1; printf '%s\n' "$x"; }
hex() { [ "${#1}" = 64 ] || return 1; case "$1" in *[!a-f0-9]*) return 1;; esac; }
uuid() { [ "${#1}" = 36 ] || return 1; case "$1" in *[!a-f0-9-]*) return 1;; esac; }
file() { [ -f "$1" ] && [ ! -L "$1" ] && [ "$(b stat -c '%u:%g:%a' "$1")" = "0:0:$2" ]; }
dir() { [ -d "$1" ] && [ ! -L "$1" ] && [ "$(b stat -c '%u:%g:%a' "$1")" = 0:0:700 ]; }
exact() { local x; x=$(b cat "$1" && printf '.') || return 1; [ "$x" = "$(printf '%s\n.' "$2")" ] && [ "$(b stat -c %s "$1")" = "$((${#2}+1))" ]; }
log() {
    printf '%s\n' "$*"
    [ "$READY_LOG" = 1 ] || return 0
    # Bound the log without ever following a replaced file or symlink.
    file "$D/lifecycle.log" 600 || return 1
    if [ "$(b stat -c %s "$D/lifecycle.log")" -gt 65536 ]; then
        b mv "$D/lifecycle.log" "$D/lifecycle.previous.log" || return 1
        (set -C; : > "$D/lifecycle.log") || return 1
    fi
    printf '%s mode=%s %s\n' "$BOOT" "$MODE" "$*" >> "$D/lifecycle.log"
}
fail() { log "ERROR=$*" >&2; exit 70; }
cleanup() { [ "$LOCKED" = 1 ] || return 0; b rm "$LOCK/pid" && b rmdir "$LOCK"; }
trap cleanup EXIT
trap 'exit 71' HUP INT TERM
write() { (set -C; printf '%s\n' "$2" > "$1") && b chmod 600 "$1"; }
copy() { b cp "$1" "$2" && b chmod "$3" "$2" && b chown 0:0 "$2"; }
atomic() {
    local dest=$1 value=$2 tmp=$1.$$
    [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || return 1
    write "$tmp" "$value" && b sync && b mv "$tmp" "$dest" && b sync
}
field() { b sed -n "s/^$2=//p" "$1"; }
root_ok() {
    local n h
    for n in "$P" "$P/versions" "$V" "$P/logs" "$P/runtime"; do dir "$n" || return 1; done
    file "$P/owner" 600 && exact "$P/owner" org.carlife.boot-native.v1 || return 1
    file "$P/current" 600 && exact "$P/current" "$VERSION" || return 1
    file "$P/runtime-control.sh" 500 || return 1
    h=$(sha "$P/runtime-control.sh") || return 1
    [ "$h" = "$RESOLVER1" ] || [ "$h" = "$RESOLVER2" ] || return 1
    file "$V/md.jar" 400 && file "$V/runtime-controller.sh" 500 && file "$V/payload.sha256" 400 && file "$V/uevent-watch" 500 || return 1
    [ "$(sha "$V/uevent-watch")" = "$OBSERVER" ] || return 1
    # All ancestors which can redirect fixed paths must themselves be real.
    for n in /data /data/local /data/adb; do [ -d "$n" ] && [ ! -L "$n" ] || return 1; done
}
version_ok() {
    local d=$1 actual expected
    dir "$d" && file "$d/md.jar" 400 && file "$d/runtime-controller.sh" 500 && file "$d/uevent-watch" 500 && file "$d/payload.sha256" 400 || return 1
    [ "$(sha "$d/uevent-watch")" = "$OBSERVER" ] || return 1
    actual=$(b cat "$d/payload.sha256" && printf '.') || return 1
    expected=$(printf '%s  md.jar\n%s  runtime-controller.sh\n%s  uevent-watch\n.' "$(sha "$d/md.jar")" "$(sha "$d/runtime-controller.sh")" "$OBSERVER")
    [ "$actual" = "$expected" ] && [ "$(b stat -c %s "$d/payload.sha256")" = 240 ]
}
# Exact fixed filenames; checksums supplied by a ZIP never select a path to read.
package_ok() {
    local index names name mode actual expected=''
    dir "$M" && dir "$M/payload" || return 1
    case "$M" in /*) ;; *) return 1;; esac
    [ "$(b readlink -f "$M")" = "$M" ] || return 1
    names='INSTALL_EN.txt INSTALL_RU.txt LICENSE action.sh allowed-resolvers.txt manager.sh manifest.env module.prop observer.sha256 payload/md.jar payload/payload.sha256 payload/runtime-controller.sh service.sh skip_mount'
    if [ "$MODE" = stage ]; then index=files.sha256; names="$names customize.sh module.sha256"; else index=module.sha256; fi
    file "$M/$index" 600 || return 1
    for name in $(printf '%s\n' $names | b sort); do
        mode=600; case "$name" in manager.sh|service.sh|action.sh) mode=500;; esac
        file "$M/$name" "$mode" || return 1
        expected="$expected$(sha "$M/$name")  $name
"
    done
    actual=$(b cat "$M/$index" && printf '.') || return 1
    [ "$actual" = "$expected." ] && [ "$(b stat -c %s "$M/$index")" = "${#expected}" ] || return 1
    exact "$M/observer.sha256" "$OBSERVER" && exact "$M/allowed-resolvers.txt" "$(printf '%s\n%s' "$RESOLVER1" "$RESOLVER2")" || return 1
    ACTION=$(field "$M/manifest.env" PACKAGE_ACTION); PAYLOAD_ID=$(field "$M/manifest.env" PAYLOAD_ID)
    case "$ACTION" in update|rollback) ;; *) return 1;; esac
    hex "$PAYLOAD_ID" || return 1
    exact "$M/manifest.env" "$(printf 'FORMAT=1\nMODULE_ID=carlife_runtime_update\nPACKAGE_ACTION=%s\nRUNTIME_VERSION=%s\nPAYLOAD_ID=%s' "$ACTION" "$VERSION" "$PAYLOAD_ID")" || return 1
    [ "$(sha "$M/payload/payload.sha256")" = "$PAYLOAD_ID" ] || return 1
    # Package modes are set by Magisk; the target snapshot gets runtime modes.
    actual=$(b cat "$M/payload/payload.sha256" && printf '.') || return 1
    expected=$(printf '%s  md.jar\n%s  runtime-controller.sh\n%s  uevent-watch\n.' "$(sha "$M/payload/md.jar")" "$(sha "$M/payload/runtime-controller.sh")" "$OBSERVER")
    [ "$actual" = "$expected" ] && [ "$(b stat -c %s "$M/payload/payload.sha256")" = 240 ] || return 1
    MODULE_HASH=$(sha "$M/module.sha256") && hex "$MODULE_HASH"
}
lock() {
    local pid
    LOCK=$D/lock.$BOOT
    if ! b mkdir -m 700 "$LOCK" 2>/dev/null; then
        dir "$LOCK" && file "$LOCK/pid" 600 || fail incomplete_lock_preserved
        pid=$(b cat "$LOCK/pid") || fail lock_read
        case "$pid" in ''|*[!0-9]*) fail invalid_lock;; esac
        [ "$pid" -gt 1 ] || fail invalid_lock
        if kill -0 "$pid" 2>/dev/null; then fail another_manager_running; fi
        b rm "$LOCK/pid" && b rmdir "$LOCK" && b mkdir -m 700 "$LOCK" || fail stale_lock
    fi
    LOCKED=1; write "$LOCK/pid" "$$" || fail lock_owner
}
init_state() {
    if [ ! -e "$D" ] && [ ! -L "$D" ]; then b mkdir -m 700 "$D" || fail state_create; fi
    dir "$D" || fail state_metadata
    if [ ! -e "$D/lifecycle.log" ] && [ ! -L "$D/lifecycle.log" ]; then (set -C; : > "$D/lifecycle.log") || fail log_create; fi
    file "$D/lifecycle.log" 600 || fail log_metadata
    [ ! -L "$D/lifecycle.previous.log" ] || fail log_symlink
    READY_LOG=1
    lock
    if [ ! -e "$D/operations" ] && [ ! -L "$D/operations" ]; then b mkdir -m 700 "$D/operations" || fail operations_create; fi
    dir "$D/operations" || fail operations_metadata
}
copy_version() {
    local src=$1 dst=$2 n mode
    b mkdir -m 700 "$dst" || return 1
    for n in md.jar runtime-controller.sh payload.sha256 uevent-watch; do
        mode=400; case "$n" in runtime-controller.sh|uevent-watch) mode=500;; esac
        copy "$src/$n" "$dst/$n" "$mode" || return 1
    done
}
root_same() {
    local n
    root_ok || return 1
    for n in owner current runtime-control.sh; do
        [ "$(sha "$P/$n")" = "$(sha "$D/backup/root/$n")" ] || return 1
    done
}
backup_ok() {
    dir "$D/backup" && dir "$D/backup/root" && version_ok "$D/backup/version" || return 1
    file "$D/backup/root/owner" 600 && exact "$D/backup/root/owner" org.carlife.boot-native.v1 || return 1
    file "$D/backup/root/current" 600 && exact "$D/backup/root/current" "$VERSION" || return 1
    file "$D/backup/root/runtime-control.sh" 500 && file "$D/backup/choice.txt" 600 && file "$D/backup/created.boot" 600 || return 1
    root_same
}
ensure_backup() {
    local t n mode choice=ABSENT
    if [ -e "$D/backup" ] || [ -L "$D/backup" ]; then backup_ok || fail backup_or_root_changed; return; fi
    version_ok "$V" || fail original_payload_invalid
    t=$D/backup.new.$BOOT.$$
    b mkdir -m 700 "$t" && b mkdir -m 700 "$t/root" && copy_version "$V" "$t/version" || fail backup_copy
    for n in owner current runtime-control.sh; do
        mode=600; [ "$n" != runtime-control.sh ] || mode=500
        copy "$P/$n" "$t/root/$n" "$mode" || fail backup_root
    done
    if [ -e "$S/selected" ] || [ -L "$S/selected" ]; then
        dir "$S" && file "$S/selected" 600 || fail selector_snapshot_metadata
        choice=$(b cat "$S/selected") || fail selector_snapshot
        case "$choice" in carlife|android_auto|carplay) ;; *) fail selector_snapshot_unknown;; esac
        exact "$S/selected" "$choice" || fail selector_snapshot_format
    fi
    write "$t/choice.txt" "$choice" && write "$t/created.boot" "$BOOT" || fail backup_receipt
    version_ok "$t/version" && version_ok "$V" || fail changed_during_backup
    for n in owner current runtime-control.sh; do [ "$(sha "$P/$n")" = "$(sha "$t/root/$n")" ] || fail root_changed_during_backup; done
    for n in md.jar runtime-controller.sh payload.sha256 uevent-watch; do [ "$(sha "$V/$n")" = "$(sha "$t/version/$n")" ] || fail changed_during_backup; done
    b sync && b mv "$t" "$D/backup" && b sync || fail backup_publish
    backup_ok || fail backup_verify
}
# Four fixed names also make partially published snapshots independently verifiable.
snapshot_index() {
    local n
    for n in md.jar payload.sha256 runtime-controller.sh uevent-watch; do printf '%s  %s\n' "$(sha "$1/$n")" "$n"; done
}
operation_ok() {
    local q=$1 n expected actual mode
    dir "$q" && dir "$q/before" && version_ok "$q/target" && file "$q/before.sha256" 600 && file "$q/descriptor" 600 || return 1
    for n in md.jar runtime-controller.sh payload.sha256 uevent-watch; do
        mode=400; case "$n" in runtime-controller.sh|uevent-watch) mode=500;; esac
        file "$q/before/$n" "$mode" || return 1
    done
    [ "$(sha "$q/before/uevent-watch")" = "$OBSERVER" ] || return 1
    expected=$(snapshot_index "$q/before") || return 1
    exact "$q/before.sha256" "$expected" || return 1
}
load_pending() {
    file "$D/pending" 600 || return 1
    OP_ID=$(field "$D/pending" OP_ID); STAGED_BOOT=$(field "$D/pending" STAGED_BOOT)
    PENDING_ACTION=$(field "$D/pending" PACKAGE_ACTION); PENDING_PAYLOAD=$(field "$D/pending" PAYLOAD_ID); PENDING_MODULE=$(field "$D/pending" MODULE_HASH)
    hex "$OP_ID" && uuid "$STAGED_BOOT" && hex "$PENDING_PAYLOAD" && hex "$PENDING_MODULE" || return 1
    case "$PENDING_ACTION" in update|rollback) ;; *) return 1;; esac
    exact "$D/pending" "$(printf 'OP_ID=%s\nSTAGED_BOOT=%s\nPACKAGE_ACTION=%s\nPAYLOAD_ID=%s\nMODULE_HASH=%s' "$OP_ID" "$STAGED_BOOT" "$PENDING_ACTION" "$PENDING_PAYLOAD" "$PENDING_MODULE")" || return 1
    OP=$D/operations/$OP_ID
    operation_ok "$OP" && exact "$OP/descriptor" "$(b cat "$D/pending")"
}
combination_ok() {
    local n live a z
    PAYLOAD_REASON=NONE
    root_same || { PAYLOAD_REASON=ROOT_IDENTITY_OR_METADATA; return 1; }
    operation_ok "$OP" || { PAYLOAD_REASON=SAVED_OPERATION_CHANGED; return 1; }
    for n in md.jar runtime-controller.sh payload.sha256; do
        live=$(sha "$V/$n") && a=$(sha "$OP/before/$n") && z=$(sha "$OP/target/$n") || { PAYLOAD_REASON=HASH_READ_$n; return 1; }
        [ "$live" = "$a" ] || [ "$live" = "$z" ] || { PAYLOAD_REASON=UNRECOGNIZED_LIVE_$n; return 1; }
    done
}
completion_ok() {
    file "$OP/complete" 600 || return 1
    COMPLETED_BOOT=$(field "$OP/complete" BOOT)
    uuid "$COMPLETED_BOOT" || return 1
    exact "$OP/complete" "$(printf 'OP_ID=%s\nBOOT=%s\nACTION=%s' "$OP_ID" "$COMPLETED_BOOT" "$PENDING_ACTION")"
}
live_payload_matches() {
    local n
    for n in md.jar runtime-controller.sh payload.sha256; do [ "$(sha "$V/$n")" = "$(sha "$1/$n")" ] || return 1; done
}
live_matches() {
    local n
    for n in md.jar runtime-controller.sh payload.sha256 uevent-watch; do [ "$(sha "$V/$n")" = "$(sha "$1/$n")" ] || return 1; done
}
# A status=false result alone says nothing about a mismatched/foreign process.
quiet_fail() { QUIET_REASON=$1; QUIET_RETRY=${2-0}; QUIET_PID=${3-none}; return 1; }
# Classify executable/script/main-class positions, not arbitrary file arguments.
# A timeout watchdog may outlive sha256sum; mentioning a controller filename
# does not make that read-only utility a controller. Wrapped real owners count.
process_role() {
    local depth base option script
    PROCESS_ROLE=NONE
    for depth in 1 2 3 4 5 6; do
        [ "$#" -gt 0 ] || return 0
        base=${1##*/}
        case "$base" in
            busybox) shift;;
            timeout)
                shift
                while [ "$#" -gt 0 ]; do
                    case "$1" in
                        -k|-s|--kill-after|--signal) [ "$#" -ge 2 ] || return 0; shift 2;;
                        -k?*|-s?*|--kill-after=*|--signal=*|--preserve-status|--foreground|-v|--verbose) shift;;
                        --) shift; break;;
                        -*) return 0;;
                        *) break;;
                    esac
                done
                [ "$#" -gt 0 ] || return 0
                case "$1" in [0-9]*) shift;; *) return 0;; esac;;
            setsid)
                shift
                while [ "$#" -gt 0 ]; do
                    case "$1" in -f|-w|-c|--fork|--wait|--ctty|--) shift;; -*) return 0;; *) break;; esac
                done;;
            *) break;;
        esac
    done
    [ "$#" -gt 0 ] || return 0
    base=${1##*/}
    case "$base" in
        app_process|app_process32|app_process64)
            shift
            # app_process [VM options] cmd-dir [runtime options] Java-main args.
            while [ "$#" -gt 0 ]; do
                case "$1" in -cp|-classpath) [ "$#" -ge 2 ] || return 0; shift 2;; -*) shift;; *) break;; esac
            done
            [ "$#" -ge 2 ] || return 0
            shift # cmd-dir, not a Java class or an arbitrary trailing argument
            while [ "$#" -gt 0 ]; do
                case "$1" in --*) shift;; *) break;; esac
            done
            [ "${1-}" != org.carlife.hulab.MdMain ] || PROCESS_ROLE=LIVE_MD;;
        org.carlife.hulab.MdMain) PROCESS_ROLE=LIVE_MD;;
        sh|ash|mksh|bash)
            shift
            while [ "$#" -gt 0 ]; do
                case "$1" in
                    --) shift; break;;
                    -c|-[!-]*c*|--command) return 0;; # shell command text is not a script argv
                    -o|+o) [ "$#" -ge 2 ] || return 0; shift 2;;
                    -?*|+?*) shift;;
                    *) break;;
                esac
            done
            script=${1-}; [ "$#" -gt 0 ] && shift
            case "$script:${1-}" in
                "$V/runtime-controller.sh:run") PROCESS_ROLE=LIVE_CONTROLLER;;
                "$P/runtime-control.sh:run-boot") PROCESS_ROLE=LIVE_RESOLVER;;
            esac;;
        uevent-watch) [ "$1" != "$V/uevent-watch" ] && [ "$1" != uevent-watch ] || PROCESS_ROLE=LIVE_OBSERVER;;
        runtime-controller.sh) [ "$1" != "$V/runtime-controller.sh" ] || [ "${2-}" != run ] || PROCESS_ROLE=LIVE_CONTROLLER;;
        runtime-control.sh) [ "$1" != "$P/runtime-control.sh" ] || [ "${2-}" != run-boot ] || PROCESS_ROLE=LIVE_RESOLVER;;
    esac
    return 0
}
no_processes() {
    local p g f contents state arg rc scan_ok scan_start scan_now unused
    # Magisk ash can read NUL-delimited argv directly. The old per-PID `b tr`
    # created hundreds of Android timeout watchdogs on each quiescence check.
    # Use no process/subshell per PID and retain full argv (not truncated ps).
    QUIET_REASON=NONE; QUIET_RETRY=0; QUIET_PID=none
    IFS=' ' read -r scan_start unused < /proc/uptime || { quiet_fail UPTIME_READ 1; return 1; }
    scan_start=${scan_start%%.*}
    case "$scan_start" in ''|*[!0-9]*) quiet_fail UPTIME_FORMAT 1; return 1;; esac
    for p in /proc/[0-9]*; do
        [ -d "$p" ] || continue
        IFS=' ' read -r scan_now unused < /proc/uptime || { quiet_fail UPTIME_READ 1; return 1; }
        scan_now=${scan_now%%.*}
        case "$scan_now" in ''|*[!0-9]*) quiet_fail UPTIME_FORMAT 1; return 1;; esac
        [ "$scan_now" -le $((scan_start+5)) ] || { quiet_fail SCAN_DEADLINE 1; return 1; }
        if [ ! -f "$p/cmdline" ] || [ -L "$p/cmdline" ]; then
            [ ! -d "$p" ] && continue
            quiet_fail CMDLINE_TYPE 0 "${p##*/}"; return 1
        fi
        if [ ! -r "$p/cmdline" ]; then
            [ ! -d "$p" ] && continue
            quiet_fail CMDLINE_READ 1 "${p##*/}"; return 1
        fi
        set --; scan_ok=1
        if ! {
            while :; do
                arg=
                IFS= read -r -d '' arg; rc=$?
                case "$rc" in 0|1) ;; *) scan_ok=0; break;; esac
                # Keep a final non-NUL argument too; kernel threads and zombies
                # normally return immediate EOF with an empty command line.
                [ "$rc" = 1 ] && [ -z "$arg" ] && break
                set -- "$@" "$arg"
                [ "$rc" = 0 ] || break
            done
            [ "$scan_ok" = 1 ]
        } < "$p/cmdline" 2>/dev/null; then
            [ ! -d "$p" ] && continue
            quiet_fail CMDLINE_READ 1 "${p##*/}"; return 1
        fi
        process_role "$@"
        if [ "$PROCESS_ROLE" != NONE ]; then
            quiet_fail "$PROCESS_ROLE" 0 "${p##*/}"; return 1
        fi
    done
    for g in /sys/fs/cgroup/carlife-boot-native-*; do
        [ -e "$g" ] || [ -L "$g" ] || continue
        [ -d "$g" ] && [ ! -L "$g" ] || { quiet_fail CGROUP_TYPE; return 1; }
        for f in "$g/cgroup.procs" "$g/controller/cgroup.procs" "$g/md/cgroup.procs" "$g/observer/cgroup.procs"; do
            [ -e "$f" ] || continue
            [ ! -L "$f" ] || { quiet_fail CGROUP_TYPE; return 1; }
            contents=$(b cat "$f") || { quiet_fail CGROUP_READ 1; return 1; }
            [ -z "$contents" ] || { quiet_fail CGROUP_NONEMPTY; return 1; }
        done
    done
    if [ -e "$P/runtime/owner.lock" ] || [ -L "$P/runtime/owner.lock" ]; then
        dir "$P/runtime/owner.lock" && file "$P/runtime/boot" 600 || { quiet_fail OWNER_LOCK_METADATA; return 1; }
        state=$(b cat "$P/runtime/boot") || { quiet_fail OWNER_LOCK_READ 1; return 1; }
        uuid "$state" || { quiet_fail OWNER_LOCK_BOOT_FORMAT; return 1; }
        [ "$state" != "$BOOT" ] || { quiet_fail CURRENT_BOOT_OWNER_LOCK; return 1; }
        # Old-boot lock is left for the existing controller's own claim_owner.
    fi
}
quiet() {
    local service_state
    service_state=$(b getprop init.svc.carlife-native) || { quiet_fail INIT_STATE_READ 1; return 1; }
    [ "$service_state" = stopped ] || { quiet_fail INIT_NOT_STOPPED; return 1; }
    no_processes
}
publication_guard() {
    local where=$1 attempt=0
    while :; do
        # A retry is never permission to overwrite a foreign/modified payload.
        combination_ok || fail "$where PAYLOAD_REASON=$PAYLOAD_REASON"
        quiet && return 0
        log "QUIESCENCE_FAILED STAGE=$where REASON=$QUIET_REASON PID=$QUIET_PID ATTEMPT=$attempt"
        [ "$QUIET_RETRY" = 1 ] && [ "$attempt" -lt 2 ] || fail "$where QUIET_REASON=$QUIET_REASON PID=$QUIET_PID"
        attempt=$((attempt+1)); sleep .1
    done
}
stop_runtime() {
    local status live count=0
    if version_ok "$V"; then
        status=$(/data/adb/magisk/busybox timeout -k 1 10 /system/bin/sh "$P/runtime-control.sh" status) || fail owner_status_failed
        live=$(printf '%s\n' "$status" | b grep '^verified_alive=') || fail owner_status_missing
        case "$live" in
            verified_alive=true)
                /data/adb/magisk/busybox timeout -k 1 40 /system/bin/sh "$P/runtime-control.sh" stop > "$OP/owner-stop.log" 2>&1 || fail owner_stop_failed
                ;;
            verified_alive=false) no_processes || fail unverified_live_owner;;
            *) fail owner_status_ambiguous;;
        esac
    else
        # Interrupted publication cannot execute a resolver whose manifest is mixed.
        combination_ok && no_processes || fail partial_has_live_owner
    fi
    b stop carlife-native || fail init_stop_failed
    while ! quiet; do
        count=$((count+1)); [ "$count" -lt 40 ] || fail live_process_or_init_not_stopped
        sleep .25
    done
}
selected_carlife() {
    dir "$S" && file "$S/schema" 600 && exact "$S/schema" 1 && file "$S/selected" 600 && exact "$S/selected" carlife &&
        [ ! -e "$S/disabled" ] && [ ! -L "$S/disabled" ] && [ ! -e "$S/.selector-lock" ] && [ ! -L "$S/.selector-lock" ]
}
restart_selected() {
    if ! selected_carlife; then log 'INIT_START_REQUESTED=false SAVED_SELECTION_RETAINED=true'; return 0; fi
    case "$(b getprop init.svc.carlife-native)" in
        running|restarting) log 'INIT_ALREADY_RUNNING=true'; return 0;;
        stopped) no_processes || { log 'INIT_START_REFUSED_LIVE_PROCESS=true'; return 71; };;
        *) log 'INIT_START_REFUSED_UNKNOWN_STATE=true'; return 71;;
    esac
    if b start carlife-native; then log 'INIT_START_REQUESTED=true'; else log 'INIT_START_REQUEST_FAILED=true'; return 71; fi
}
# Preserve the existing label; this changes no policy and never labels a live file.
label_like() {
    local label got
    label=$(b ls -Zd "$1") || return 1; label=${label%% *}
    case "$label" in u:object_r:*:s0*) ;; *) return 1;; esac
    b chcon "$label" "$2" || return 1
    got=$(b ls -Zd "$2") || return 1; got=${got%% *}
    [ "$got" = "$label" ]
}
stage() {
    local target n mode new_id descriptor t old_op=
    root_ok || fail incompatible_runtime
    if [ -e "$D/pending" ] || [ -L "$D/pending" ]; then
        load_pending && combination_ok || fail previous_operation_or_live_payload_changed
        old_op=$OP
    else version_ok "$V" || fail payload_invalid; fi
    ensure_backup
    target=$M/payload
    [ "$ACTION" != rollback ] || target=$D/backup/version
    # A completed identical update/rollback never overwrites the first backup.
    if live_payload_matches "$target"; then
        if [ -n "$old_op" ] && { [ "$PENDING_ACTION" != "$ACTION" ] || [ "$PENDING_MODULE" != "$MODULE_HASH" ]; }; then
            # Cancel a staged update when its rollback target is already live;
            # also permit repackaging identical payload bytes. Preserve evidence.
            b mv "$D/pending" "$old_op/canceled-pending.$BOOT.$$" && b sync || fail cancel_pending
        fi
        log 'RESULT=ALREADY_INSTALLED'; return 0
    fi
    # Same staged package is idempotent. Keep its original staging boot identity.
    if [ -n "$old_op" ] && [ "$PENDING_ACTION" = "$ACTION" ] && [ "$PENDING_PAYLOAD" = "$PAYLOAD_ID" ] && [ "$PENDING_MODULE" = "$MODULE_HASH" ]; then
        log "RESULT=ALREADY_STAGED OP_ID=$OP_ID"; return 0
    fi
    new_id=$(printf '%s\n%s\n%s\n%s\n%s\n' "$BOOT" "$$" "$ACTION" "$MODULE_HASH" "$(sha "$V/payload.sha256")" | b sha256sum); new_id=${new_id%% *}
    hex "$new_id" || fail operation_id
    t=$D/operations/$new_id
    b mkdir -m 700 "$t" && copy_version "$V" "$t/before" && b mkdir -m 700 "$t/target" || fail stage_directory
    for n in md.jar runtime-controller.sh payload.sha256; do
        mode=400; [ "$n" != runtime-controller.sh ] || mode=500
        copy "$target/$n" "$t/target/$n" "$mode" || fail stage_payload
    done
    copy "$V/uevent-watch" "$t/target/uevent-watch" 500 || fail stage_observer
    write "$t/before.sha256" "$(snapshot_index "$t/before")" || fail before_index
    descriptor=$(printf 'OP_ID=%s\nSTAGED_BOOT=%s\nPACKAGE_ACTION=%s\nPAYLOAD_ID=%s\nMODULE_HASH=%s' "$new_id" "$BOOT" "$ACTION" "$PAYLOAD_ID" "$MODULE_HASH")
    write "$t/descriptor" "$descriptor" && operation_ok "$t" || fail operation_validation
    root_same && live_matches "$t/before" || fail changed_while_staging
    b sync && atomic "$D/pending" "$descriptor" || fail pending_publish
    log "RESULT=STAGED ACTION=$ACTION OP_ID=$new_id REBOOT_REQUIRED=true"
}
apply() {
    local n mode publish stamp
    if [ ! -e "$D/pending" ] && [ ! -L "$D/pending" ]; then log 'RESULT=NO_PENDING_OPERATION'; return 0; fi
    load_pending && backup_ok || fail pending_or_backup_invalid
    [ "$PENDING_ACTION" = "$ACTION" ] && [ "$PENDING_PAYLOAD" = "$PAYLOAD_ID" ] && [ "$PENDING_MODULE" = "$MODULE_HASH" ] || fail installed_module_does_not_match_pending
    if [ "$BOOT" = "$STAGED_BOOT" ]; then log 'RESULT=WAITING_FOR_REBOOT'; return 0; fi
    combination_ok || fail live_payload_changed_outside_transaction
    if [ -e "$OP/complete" ] || [ -L "$OP/complete" ]; then
        completion_ok && live_matches "$OP/target" || fail completed_payload_changed
        log "RESULT=ALREADY_APPLIED OP_ID=$OP_ID"
        # Retry a failed start only in the publication boot. On later boots this
        # delivery module must never become a late CarLife loader.
        if [ "$BOOT" = "$COMPLETED_BOOT" ]; then restart_selected; return $?; fi
        return 0
    fi
    stop_runtime
    publication_guard changed_after_stop
    # Durable intent before the first live rename. An interrupted intent is retained.
    if [ ! -e "$OP/receipt.intent" ] && [ ! -L "$OP/receipt.intent" ]; then
        write "$OP/receipt.intent" "$(printf 'OP_ID=%s\nBOOT=%s\nACTION=%s' "$OP_ID" "$BOOT" "$ACTION")" && b sync || fail intent_write
    else file "$OP/receipt.intent" 600 || fail intent_metadata; fi
    [ "$(b stat -c %d "$OP")" = "$(b stat -c %d "$V")" ] || fail non_atomic_filesystems
    for n in md.jar runtime-controller.sh payload.sha256; do
        publication_guard changed_before_publish
        [ "$(sha "$V/$n")" != "$(sha "$OP/target/$n")" ] || continue
        publish=$OP/publish.$n
        # Recreate an interrupted, disposable copy; the retained target and the
        # live old/new combination are independently verified. Never follow it.
        if [ -e "$publish" ] || [ -L "$publish" ]; then
            [ -f "$publish" ] && [ ! -L "$publish" ] || fail interrupted_publish_type
            case "$(b stat -c '%u:%g:%a' "$publish")" in 0:0:400|0:0:500|0:0:600) ;; *) fail interrupted_publish_owner;; esac
            b rm "$publish" || fail interrupted_publish_cleanup
        fi
        mode=400; [ "$n" != runtime-controller.sh ] || mode=500
        copy "$OP/target/$n" "$publish" "$mode" && [ "$(sha "$publish")" = "$(sha "$OP/target/$n")" ] || fail publish_copy
        label_like "$V/$n" "$publish" || fail publish_label
        publication_guard before_rename
        b sync && b mv "$publish" "$V/$n" && b sync || fail publish_failed
    done
    root_same && version_ok "$V" && live_matches "$OP/target" || fail final_payload_verify
    publication_guard final_quiescence
    atomic "$OP/complete" "$(printf 'OP_ID=%s\nBOOT=%s\nACTION=%s' "$OP_ID" "$BOOT" "$ACTION")" || fail completion_receipt
    atomic "$D/last-applied" "$(printf 'OP_ID=%s\nBOOT=%s\nACTION=%s' "$OP_ID" "$BOOT" "$ACTION")" || fail last_receipt
    log "RESULT=APPLIED ACTION=$ACTION OP_ID=$OP_ID"
    restart_selected
}
[ "$(b id -u)" = 0 ] && [ "$(b id -g)" = 0 ] || fail root_required
[ "$(b getprop ro.build.version.sdk)" = 35 ] || fail unsupported_sdk
case "$(b getprop ro.board.platform)" in sm6350|lito) ;; *) fail unsupported_platform;; esac
BOOT=$(b cat /proc/sys/kernel/random/boot_id) && uuid "$BOOT" || fail boot_identity
package_ok || fail package_invalid
if [ "$MODE" = status ]; then
    # Read-only: no mkdir, locks, logs, stop, start, or device property writes.
    if [ ! -e "$D" ] && [ ! -L "$D" ]; then printf 'RESULT=NOT_STAGED\n'; exit 0; fi
    dir "$D" || fail state_metadata
    if load_pending; then
        printf 'ACTION=%s\nOP_ID=%s\nSTAGED_BOOT=%s\n' "$PENDING_ACTION" "$OP_ID" "$STAGED_BOOT"
        if [ -f "$OP/complete" ] && [ ! -L "$OP/complete" ]; then
            if completion_ok && root_same && live_matches "$OP/target"; then printf 'RESULT=APPLIED\n'; else printf 'RESULT=RECEIPT_PRESENT_LIVE_CHANGED\n'; fi
        else printf 'RESULT=PENDING_OR_INTERRUPTED\n'; fi
    else printf 'RESULT=NO_VALID_PENDING_OPERATION\n'; fi
    exit 0
fi
init_state
case "$MODE" in stage) stage;; apply) apply;; esac
