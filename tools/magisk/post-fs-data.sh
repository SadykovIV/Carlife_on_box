#!/system/bin/sh
# Commit staged data before the early dispatcher releases native USB ownership.
MODDIR=${0%/*}
/data/adb/magisk/busybox sh "$MODDIR/manager.sh" activate "$MODDIR" >> /data/adb/carlife-on-box/lifecycle.log 2>&1
