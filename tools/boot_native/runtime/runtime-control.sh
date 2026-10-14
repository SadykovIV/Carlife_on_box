#!/system/bin/sh
# BOOT_NATIVE data resolver. No late loader, OEM stop, ADB or property dependency.
set -u
umask 077
PATH=/system/bin:/system/xbin:/vendor/bin
export PATH
unset ASH_STANDALONE
P=/data/local/carlife-native
V=$P/versions/boot-native-v1-20261014
S=/data/adb/carlife-boot
MODE=${1-}
[ "$#" = 1 ] || exit 64
case "$MODE" in run-boot|status|stop) ;; *) exit 64;; esac
b() { timeout -k 1 4 "$@"; }
exact_lf() {
    # Sentinel preserves the LF; size rejects NUL bytes discarded by shell substitution.
    xl=$(b cat "$1" && printf '.') || return 1
    [ "$xl" = "$(printf '%s\n.' "$2")" ] && [ "$(b stat -c %s "$1")" = "$((${#2}+1))" ]
}
empty_file() { [ "$(b stat -c %s "$1")" = 0 ]; }
meta() { b stat -c '%u:%g:%a' "$1"; }
file() { [ -f "$1" ] && [ ! -L "$1" ] && [ "$(meta "$1")" = "0:0:$2" ]; }
dir() { [ -d "$1" ] && [ ! -L "$1" ] && [ "$(meta "$1")" = 0:0:700 ]; }
selection_ok() {
    dir "$S" || return 1
    [ ! -e "$S/.selector-lock" ] && [ ! -L "$S/.selector-lock" ] || return 1
    file "$S/schema" 600 && exact_lf "$S/schema" 1 || return 1
    file "$S/selected" 600 && exact_lf "$S/selected" carlife || return 1
    [ ! -e "$S/disabled" ] && [ ! -L "$S/disabled" ] || return 1
    # An interrupted/concurrent transaction is not a complete saved selection.
    [ ! -e "$S/.selector-lock" ] && [ ! -L "$S/.selector-lock" ]
}
[ "$(b id -u)" = 0 ] && [ "$(b id -g)" = 0 ] || exit 70
for d in "$P" "$P/versions" "$V" "$P/logs" "$P/runtime"; do dir "$d" || exit 70; done
file "$P/owner" 600 && exact_lf "$P/owner" org.carlife.boot-native.v1 || exit 70
file "$P/current" 600 && exact_lf "$P/current" boot-native-v1-20261014 || exit 70
file "$V/payload.sha256" 400 || exit 70
(cd "$V" && b sha256sum -c payload.sha256 >/dev/null) || exit 70
for f in runtime-controller.sh uevent-watch; do file "$V/$f" 500 || exit 70; done
file "$V/md.jar" 400 || exit 70
case "$MODE" in
 run-boot)
    selection_ok || exit 70
    # A single active loader: the earlier accepted pilot is retained but inhibited.
    file /data/adb/modules/carlife_native_pilot_v3/disable 644 && empty_file /data/adb/modules/carlife_native_pilot_v3/disable || exit 70
    exec /system/bin/sh "$V/runtime-controller.sh" run "$P" "$V";;
 status) exec /system/bin/sh "$V/runtime-controller.sh" status "$P" "$V";;
 stop) exec /system/bin/sh "$V/runtime-controller.sh" stop "$P" "$V";;
esac
