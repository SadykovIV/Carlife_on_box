#!/system/bin/sh
# Read-only status. Install the rollback ZIP to restore the saved runtime.
MODDIR=${0%/*}
/data/adb/magisk/busybox sh "$MODDIR/manager.sh" status "$MODDIR"
