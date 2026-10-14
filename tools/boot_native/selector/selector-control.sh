#!/system/bin/sh
# BOOT_SELECTOR_V1. Fixed root-only storage; never changes USB, properties or processes.
PATH=/system/bin:/system/xbin:/vendor/bin
export PATH
unset ENV BASH_ENV ASH_STANDALONE
umask 077
B=/data/adb/carlife-boot
CP=/sys/devices/platform/soc/soc:qpdata_driver/cp_aa
LOCKED=0
TMP=
die() { echo "ERROR:$1" >&2; exit 71; }
meta() { stat -c '%u:%g:%a' "$1" 2>/dev/null; }
regular() { [ -f "$1" ] && [ ! -L "$1" ] && [ "$(meta "$1")" = 0:0:600 ]; }
enum() { case "$1" in android_auto|carplay|carlife) return 0;; *) return 1;; esac; }
exact() { regular "$1" && [ "$(wc -c < "$1")" -eq "$((${#2}+1))" ] && [ "$(cat "$1"; printf x)" = "$2
x" ]; }
base() { [ -d "$B" ] && [ ! -L "$B" ] && [ "$(readlink -f "$B")" = "$B" ] && [ "$(meta "$B")" = 0:0:700 ]; }
cleanup() {
    [ -z "$TMP" ] || { [ ! -L "$TMP" ] && [ -f "$TMP" ] && rm "$TMP"; }
    [ "$LOCKED" = 0 ] || rmdir "$B/.selector-lock"
}
trap cleanup EXIT
trap 'exit 72' HUP INT TERM
[ "$(id -u)" = 0 ] || die root_required
case "$#:$1" in 1:get|1:status|1:guard|2:bootstrap|2:select-next-boot) ;; *) die usage;; esac
[ -d /data/adb ] && [ ! -L /data/adb ] && [ "$(readlink -f /data/adb)" = /data/adb ] || die data_not_ready
case "$1" in
bootstrap)
    enum "$2" || die enum
    if [ ! -e "$B" ] && [ ! -L "$B" ]; then mkdir "$B" || die mkdir; chmod 700 "$B" || die chmod; fi
    ;;
esac
base || die base_metadata
write_atomic() {
    # Caller holds a mkdir lock in a root-only directory. No attacker-controlled filename.
    TMP="$B/.selector-tmp.$$"
    [ ! -e "$TMP" ] && [ ! -L "$TMP" ] || die temp_exists
    (set -C; printf '%s\n' "$2" > "$TMP") || die temp_write
    regular "$TMP" || die temp_metadata
    sync || die sync_before_rename
    if [ -e "$B/$1" ] || [ -L "$B/$1" ]; then regular "$B/$1" || die target_metadata; fi
    mv -f "$TMP" "$B/$1" || die rename
    TMP=
    sync || die sync_after_rename
    exact "$B/$1" "$2" || die write_readback
}
observe_oem() {
    # One already-issued AT request; observation only, never another command.
    ATTEMPTS=0
    while [ "$ATTEMPTS" -lt 20 ]; do
        ACTUAL_CP=$(cat "$CP") || return 1
        [ "$ACTUAL_CP" = "$1" ] && return 0
        ATTEMPTS=$((ATTEMPTS+1))
        sleep 0.25 || return 1
    done
    return 1
}
case "$1" in
bootstrap|select-next-boot)
    enum "$2" || die enum
    mkdir "$B/.selector-lock" || die busy_or_abandoned_lock
    LOCKED=1
    if [ "$1" = bootstrap ]; then
        [ ! -e "$B/selected" ] && [ ! -L "$B/selected" ] || die already_bootstrapped
        if [ -e "$B/schema" ] || [ -L "$B/schema" ]; then exact "$B/schema" 1 || die schema; else write_atomic schema 1; fi
    else
        exact "$B/schema" 1 || die schema
        regular "$B/selected" || die selected_metadata
        OLD=$(cat "$B/selected") || die selected_read
        enum "$OLD" && exact "$B/selected" "$OLD" || die selected_bytes
    fi
    # OEM requests must use OEM AT#CS first; its return alone is not proof.
    # A pending/mismatched OEM request cannot replace a valid CarLife selection.
    case "$2" in
      android_auto) observe_oem 2000 || die oem_aa_not_observed_selection_unchanged;;
      carplay) observe_oem 1000 || die oem_carplay_not_observed_selection_unchanged;;
    esac
    write_atomic selected "$2"
    printf 'selected=%s\neffective=UNCHANGED\nrequires_reboot=1\n' "$2"
    exit 0
    ;;
esac
[ ! -e "$B/.selector-lock" ] && [ ! -L "$B/.selector-lock" ] || die selection_busy
exact "$B/schema" 1 || die schema
regular "$B/selected" || die selected_metadata
SELECTED=$(cat "$B/selected") || die selected_read
enum "$SELECTED" && exact "$B/selected" "$SELECTED" || die selected_bytes
INHIBIT=0
if [ -e "$B/disabled" ] || [ -L "$B/disabled" ]; then
    regular "$B/disabled" && [ "$(wc -c < "$B/disabled")" -eq 0 ] || die disabled_metadata
    INHIBIT=1
fi
case "$1" in
get) printf '%s\n' "$SELECTED";;
status) printf 'schema=1\nselected=%s\ndiagnostic_disabled=%s\neffective=NOT_OBSERVED\n' "$SELECTED" "$INHIBIT";;
guard)
    [ "$INHIBIT" = 0 ] || die diagnostic_disabled
    case "$SELECTED" in
      android_auto) [ "$(cat "$CP")" = 2000 ] || die oem_aa_mismatch;;
      carplay) [ "$(cat "$CP")" = 1000 ] || die oem_carplay_mismatch;;
    esac
    # Re-read after the external predicate. No fallback/default on concurrent update.
    [ ! -e "$B/.selector-lock" ] && exact "$B/selected" "$SELECTED" || die selection_changed
    printf '%s\n' "$SELECTED"
    ;;
esac
