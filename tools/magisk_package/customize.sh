# Magisk v30.7 sources this script; finalization uses the selected MODID/MODPATH.
SKIPUNZIP=1
[ "$BOOTMODE" = true ] || abort 'Install from Magisk in booted Android.'
[ "$ARCH" = arm64 ] || abort 'This package requires compatible TBox Ultra / ARM64.'
CP_OUTER=$MODPATH
CP_WORK=$TMPDIR/carlife-package
mkdir -p "$CP_WORK" || abort 'Cannot create package workspace.'
unzip -o "$ZIPFILE" 'package/*' -d "$CP_WORK" >&2 || abort 'Cannot extract package.'
CP_PACKAGE=$CP_WORK/package
CP_BB=/data/adb/magisk/busybox
"$CP_BB" sh "$CP_PACKAGE/control.sh" preflight "$CP_PACKAGE" || abort 'CarLife preflight failed; existing installation preserved.'
CP_ROUTE=$(cat "$CP_PACKAGE/route")
case "$CP_ROUTE" in
    managed|fresh) CP_ID=carlife_on_box; CP_ZIP=universal.zip;;
    legacy) CP_ID=carlife_runtime_update; CP_ZIP=legacy-update.zip;;
    *) abort 'Unknown installation route.';;
esac
ui_print "- CarLife: $CP_ROUTE; preparing exact rollback and settings..."
"$CP_BB" sh "$CP_PACKAGE/control.sh" prepare "$CP_PACKAGE" || abort 'Cannot prepare CarLife rollback/settings; keep the installation log.'
# Existing pending paths were rejected before this point. No rm -rf of another
# backend/module is used: the transient outer ID prevents Magisk doing that too.
CP_TARGET=/data/adb/modules_update/$CP_ID
mkdir "$CP_TARGET" || abort 'Backend staging appeared concurrently; preserved.'
if ! unzip -o "$CP_PACKAGE/payload/$CP_ZIP" -d "$CP_TARGET" >&2; then
    # This path was created exclusively by the preceding mkdir, never adopted.
    rm -rf "$CP_TARGET"
    abort 'Backend extraction failed; recovery files retained.'
fi
MODID=$CP_ID
MODPATH=$CP_TARGET
# The backend's own checksum/compatibility/ownership/boot logic is unchanged.
if ! (
    # Do not let the generic Magisk abort erase a package after the backend has
    # durably written a boot/pending receipt. Its recovery manager needs it.
    abort() { ui_print "$*"; exit 1; }
    . "$MODPATH/customize.sh"
); then
    : > "$CP_TARGET/disable"
    MODPATH=$CP_OUTER
    abort 'Backend refused or interrupted. Matching package and rollback are retained; do not reinstall over pending state.'
fi
if ! "$CP_BB" sh "$CP_PACKAGE/control.sh" commit "$CP_PACKAGE"; then
    : > "$CP_TARGET/disable"
    MODPATH=$CP_OUTER
    abort 'CarLife backend prepared; settings finalization needs recovery. Package and backups retained.'
fi
rm "$CP_OUTER/customize.sh" || ui_print '- Could not remove transient installer script; backend remains staged.'
rmdir "$CP_OUTER" || ui_print '- Unexpected transient installer files preserved; backend remains staged.'
ui_print '- Installed/staged. Reboot once. The same ZIP handles future updates.'
ui_print '- Rollback: su -c "/data/adb/magisk/busybox sh /data/adb/carlife-package/control.sh rollback"'
