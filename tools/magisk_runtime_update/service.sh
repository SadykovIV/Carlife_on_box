#!/system/bin/sh
# One pending data transaction only. Normal CarLife boot remains in early init.
MODDIR=${0%/*}
/data/adb/magisk/busybox sh "$MODDIR/manager.sh" apply "$MODDIR"
