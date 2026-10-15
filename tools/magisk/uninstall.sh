#!/system/bin/sh
# Magisk deletes its module even if this hook fails. Recovery lives outside it.
/data/adb/magisk/busybox sh /data/adb/carlife-on-box/manager.sh uninstall >> /data/adb/carlife-on-box/lifecycle.log 2>&1
