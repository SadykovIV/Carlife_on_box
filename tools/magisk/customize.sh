# Sourced by Magisk after extracting this ZIP. No device reboot or USB switch.
[ "$BOOTMODE" = true ] || abort 'Install from the Magisk app in booted Android.'
[ "$ARCH" = arm64 ] || abort 'This package is for TBox Ultra 1 / ARM64.'
set_perm_recursive "$MODPATH" 0 0 0700 0600
for script in manager.sh post-fs-data.sh uninstall.sh action.sh; do
    set_perm "$MODPATH/$script" 0 0 0500
done
ui_print '- Checking TBox and saving the original boot before any boot change...'
/data/adb/magisk/busybox sh "$MODPATH/manager.sh" install "$MODPATH" || abort 'CarLife installation failed. Read the error; existing recovery files are retained.'
ui_print '- Done. Reboot manually when ready.'
