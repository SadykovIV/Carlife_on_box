#!/system/bin/sh
# The Magisk Action button is read-only; rollback is an explicit command.
/data/adb/magisk/busybox sh /data/adb/carlife-on-box/manager.sh status
printf '\nFull rollback / Полный откат:\n'
printf 'su -c "/data/adb/magisk/busybox sh /data/adb/carlife-on-box/manager.sh rollback"\n'
