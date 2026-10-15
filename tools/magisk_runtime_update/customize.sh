# Sourced by Magisk in booted Android. Staging keeps the current image alive.
[ "$BOOTMODE" = true ] || abort 'Install this update from the Magisk app in Android.'
[ "$ARCH" = arm64 ] || abort 'This update is for an existing SM6350 ARM64 CarLife runtime.'
set_perm_recursive "$MODPATH" 0 0 0700 0600
for script in manager.sh service.sh action.sh; do
    set_perm "$MODPATH/$script" 0 0 0500
done
ui_print '- Checking existing CarLife and saving its runtime backup...'
/data/adb/magisk/busybox sh "$MODPATH/manager.sh" stage "$MODPATH" || abort 'CarLife update was not staged. Keep the log; existing backup is retained.'
ui_print '- Staged. Current CarLife stays running. Reboot once to apply.'
ui_print '- Boot, saved selection and ADB settings are unchanged.'
