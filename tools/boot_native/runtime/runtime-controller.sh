#!/system/bin/sh
# BOOT_NATIVE_V1. Selected early by init; never launches/stops OEM projection.
# Only own children, existing configfs functions and a single selected USB owner.
set -u
umask 077
PATH=/system/bin:/system/xbin:/vendor/bin
export PATH
unset ASH_STANDALONE
ACTION=${1-}; P=${2-}; V=${3-}
G=/config/usb_gadget/g1
U=/sys/class/udc/a600000.dwc3
R=$P/runtime
STOP=0; PHASE=INITIALIZING; RUN_ID=; GENERATION=0; USB_OWNED=0
DETACH_ARMED=0; DETACH_SINCE=0; ATTACH_FAILURES=0; MD_EXIT_SINCE=0
FLOATING_NAV_ATTEMPTED=0
QCOM_MAIN_BOOTSTRAP_ATTEMPTED=0
RUN_STARTED_MS=unknown; PHASE_STARTED_MS=unknown
b() { timeout -k 1 3 "$@"; }

readf() {
    # Fixed kernel scalar attributes only. Keep arbitrary/identity files bounded.
    # Read on one fd: do not silently truncate a second line or a partial EOF.
    case "$1" in
        /proc/sys/kernel/random/boot_id|/config/usb_gadget/g1/UDC|/config/usb_gadget/g2/UDC|/config/usb_gadget/g1/idVendor|/config/usb_gadget/g1/idProduct|/sys/class/udc/a600000.dwc3/state|/sys/class/gpio/gpiochip1124/base|/sys/class/gpio/gpiochip1124/ngpio|/sys/class/gpio/gpiochip1124/label|/sys/class/gpio/gpio1132/direction|/sys/class/gpio/gpio1132/value)
            rf_scalar=; rf_tail=
            if {
                IFS= read -r rf_scalar && {
                    IFS= read -r rf_tail; rf_tail_rc=$?
                    [ "$rf_tail_rc" = 1 ] && [ -z "$rf_tail" ]
                }
            } 2>/dev/null < "$1"; then
                printf '%s\n' "$rf_scalar"; return 0
            fi
            ;;
    esac
    b cat "$1"
}

prop() { b getprop "$1"; }

regular() { [ -f "$1" ] && [ ! -L "$1" ]; }

meta() { b stat -c '%u:%g:%a' "$1"; }

owned_file() { regular "$1" && [ "$(meta "$1")" = "0:0:$2" ]; }

read_is() { ri=$(readf "$1") || return 1; [ "$ri" = "$2" ]; }

number() { case "$1" in ''|*[!0-9]*) return 1;; esac; }

now() { read up rest < /proc/uptime || return 1; printf '%s\n' "${up%%.*}"; }

now_cs() {
    read ct_up ct_rest < /proc/uptime || return 1
    ct_s=${ct_up%%.*}; ct_f=${ct_up#*.}00; ct_f=${ct_f%"${ct_f#??}"}
    number "$ct_s" && number "$ct_f" || return 1
    printf '%s\n' "$((ct_s * 100 + 1$ct_f - 100))"
}
now_ms() {
    nm_cs=$(now_cs) || return 1
    printf '%s\n' "$((nm_cs * 10))"
}
observe_udc() {
    # Monotonic centiseconds: crossing an integer second is not a full second.
    ou_state=$1; ou_time=$2; DETACH_CONFIRMED=0
    number "$ou_time" || return 1
    case "$ou_state" in
        powered|default|addressed|configured) DETACH_ARMED=1; DETACH_SINCE=0;;
        'not attached')
            if [ "$DETACH_ARMED" = 1 ]; then
                [ "$DETACH_SINCE" != 0 ] || DETACH_SINCE=$ou_time
                [ "$ou_time" -lt $((DETACH_SINCE+100)) ] || DETACH_CONFIRMED=1
            fi;;
        attached|reconnecting|unauthenticated|suspended) DETACH_SINCE=0;;
        *) DETACH_SINCE=0; return 1;;
    esac
}
atomic() { (set -C; printf '%s\n' "$2" > "$R/.$1.$$") && b chmod 600 "$R/.$1.$$" && b mv "$R/.$1.$$" "$R/$1"; }

fields() {
    pf=$(readf "/proc/$1/stat") || return 1; pf=${pf##*) }; set -- $pf
    [ "$#" -ge 20 ] || return 1
    printf '%s %s %s %s\n' "${20}" "$4" "$3" "$1"
}
cmdhash() { ch=$(b sha256sum "/proc/$1/cmdline") || return 1; printf '%s\n' "${ch%% *}"; }

md_command_ok() {
    case "$1" in app_process\ *|app_process64\ *|/system/bin/app_process\ *|/system/bin/app_process64\ *) ;; *) return 1;; esac
    case "$1" in *' org.carlife.hulab.MdMain '*'--transport native-usb'*) return 0;; *) return 1;; esac
}
group_matches() {
    gm=$(readf "/proc/$1/cgroup") || return 1
    [ "$(printf '%s\n' "$gm" | sed -n 's/^0:://p')" = "/carlife-boot-native-$RUN_ID/$2" ] || return 1
    gv=$(readf "$R/cgroup_v1") || return 1
    [ "$(printf '%s\n' "$gm" | sed '/^0::/d')" = "$gv" ]
}
record_pid() {
    rp_role=$1; rp_pid=$2
    rf=$(fields "$rp_pid") || return 1; set -- $rf
    rh=$(cmdhash "$rp_pid") || return 1
    group_matches "$rp_pid" "$rp_role" || return 1
    atomic "$rp_role.identity" "$rp_pid $1 $rh"
}
identity() {
    id_role=$1
    owned_file "$R/$id_role.identity" 600 || return 1
    read ID_PID ID_START ID_HASH id_extra < "$R/$id_role.identity" || return 1
    number "$ID_PID" && number "$ID_START" && [ "$ID_PID" -gt 1 ] && [ -z "$id_extra" ] || return 1
    idf=$(fields "$ID_PID") || return 1; set -- $idf
    [ "$1" = "$ID_START" ] && group_matches "$ID_PID" "$id_role" || return 1
    # A zombie has no live descriptor owner and often no cmdline, but still needs reaping.
    [ "$4" = Z ] && return 2
    idh=$(cmdhash "$ID_PID") || return 1
    [ "$idh" = "$ID_HASH" ]
}
pending_pid() {
    # Exact child returned by our shell, pinned before exec. This marker is
    # removed only once final executable identity is recorded.
    pp_role=$1; pp_pid=$2
    pp_f=$(fields "$pp_pid") || return 1; set -- $pp_f
    atomic "$pp_role.pending" "$pp_pid $1"
}
pending_matches() {
    owned_file "$R/$1.pending" 600 || return 1
    read pp_pid pp_start pp_extra < "$R/$1.pending" || return 1
    number "$pp_pid" && number "$pp_start" && [ "$pp_pid" -gt 1 ] && [ -z "$pp_extra" ] || return 1
    pp_f=$(fields "$pp_pid") || return 1; set -- $pp_f
    [ "$1" = "$pp_start" ]
}
group_empty() {
    ge=$(readf "$CG/$1/cgroup.procs") || return 1; [ -z "$ge" ]
}
child_exited() {
    ce_role=$1
    owned_file "$R/$ce_role.identity" 600 || return 1
    read ce_pid ce_start ce_hash ce_extra < "$R/$ce_role.identity" || return 1
    number "$ce_pid" && number "$ce_start" && [ "$ce_pid" -gt 1 ] && [ -z "$ce_extra" ] || return 1
    [ -d "/proc/$ce_pid" ] || return 0
    cf=$(fields "$ce_pid") || { [ ! -d "/proc/$ce_pid" ]; return $?; }; set -- $cf
    [ "$1" = "$ce_start" ] && [ "$4" = Z ] && group_matches "$ce_pid" "$ce_role"
}
stop_child() {
    sc_role=$1
    sc_pending=0
    if [ -e "$R/$sc_role.pending" ]; then
        owned_file "$R/$sc_role.pending" 600 || return 1
        read sc_pid sc_start sc_extra < "$R/$sc_role.pending" || return 1
        sc_pending=1
    elif [ -e "$R/$sc_role.identity" ]; then
        owned_file "$R/$sc_role.identity" 600 || return 1
        read sc_pid sc_start sc_hash sc_extra < "$R/$sc_role.identity" || return 1
    else group_empty "$sc_role"; return $?; fi
    number "$sc_pid" && number "$sc_start" && [ "$sc_pid" -gt 1 ] && [ -z "$sc_extra" ] || return 1
    if [ -d "/proc/$sc_pid" ]; then
        if [ "$sc_pending" = 1 ]; then pending_matches "$sc_role"; else identity "$sc_role"; fi; sc_identity=$?
        if [ "$sc_identity" = 0 ]; then
            b kill -TERM "$sc_pid" || { [ ! -d "/proc/$sc_pid" ] || return 1; }
        elif [ "$sc_identity" != 2 ] && [ -d "/proc/$sc_pid" ]; then return 1; fi
        for sc_i in 1 2 3 4 5 6 7 8 9 10; do
            [ ! -d "/proc/$sc_pid" ] && break
            sf=$(fields "$sc_pid") || { [ ! -d "/proc/$sc_pid" ] && break; return 1; }; set -- $sf
            [ "$1" = "$sc_start" ] || return 1
            if [ "$4" = Z ]; then wait "$sc_pid" 2>/dev/null; break; fi
            sleep .25
        done
        if [ -d "/proc/$sc_pid" ]; then
            if [ "$sc_pending" = 1 ]; then pending_matches "$sc_role"; else identity "$sc_role"; fi; sc_identity=$?
            if [ "$sc_identity" = 0 ]; then b kill -KILL "$sc_pid" || { [ ! -d "/proc/$sc_pid" ] || return 1; };
            elif [ "$sc_identity" != 2 ] && [ -d "/proc/$sc_pid" ]; then return 1; fi
            # Never wait on a still-running or D-state process after SIGKILL.
            for sc_k in 1 2 3 4 5 6 7 8; do
                [ ! -d "/proc/$sc_pid" ] && break
                sf=$(fields "$sc_pid") || { [ ! -d "/proc/$sc_pid" ] && break; return 1; }; set -- $sf
                [ "$1" = "$sc_start" ] || return 1
                if [ "$4" = Z ]; then wait "$sc_pid" 2>/dev/null; break; fi
                sleep .25
            done
            [ ! -d "/proc/$sc_pid" ] || return 1
        fi
    fi
    for sc_i in 1 2 3 4 5 6 7 8; do
        if group_empty "$sc_role"; then
            for sc_file in "$R/$sc_role.identity" "$R/$sc_role.pending"; do
                [ ! -e "$sc_file" ] || { owned_file "$sc_file" 600 && b rm "$sc_file"; } || return 1
            done
            return 0
        fi
        sleep .25
    done
    return 1
}
observer_ready() {
    # argv proves exec, not netlink bind. READY is emitted after socket/bind.
    owned_file "$P/logs/uevents.log" 600 || return 1
    read or_tag or_nonce or_clock or_extra < "$P/logs/uevents.log" || return 1
    [ "$or_tag" = READY ] && [ "$or_nonce" = "nonce=$OBS_NONCE" ] && [ -z "$or_extra" ] || return 1
    case "$or_clock" in monotonic_ms=*) ;; *) return 1;; esac
    number "${or_clock#monotonic_ms=}"
}
wait_observer_ready() {
    for wr_i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
        identity observer || return 1
        if observer_ready; then return 0; fi
        sleep .05
    done
    return 1
}
start_observer() {
    stop_child observer || return 1
    [ ! -e "$R/aoa-start" ] || b rm "$R/aoa-start" || return 1
    if regular "$P/logs/uevents.log"; then b mv "$P/logs/uevents.log" "$P/logs/uevents.1.log" || return 1; fi
    on_uuid=$(readf /proc/sys/kernel/random/uuid) || return 1
    OBS_NONCE=$(printf '%s' "$on_uuid" | tr -d '-')
    case "$OBS_NONCE" in ''|*[!a-f0-9]*) return 1;; esac
    [ "${#OBS_NONCE}" = 32 ] && atomic observer.nonce "$OBS_NONCE" || return 1
    # Child moves itself before exec, so launch-shell/adbd teardown cannot own it.
    setsid sh -c 'printf "%s\n" "$$" > "$1/cgroup.procs" && exec "$2" "$3" "$4" 0 /devices/virtual/misc/mtp_usb' pilot-observer "$CG/observer" "$V/uevent-watch" "$R/aoa-start" "$OBS_NONCE" > "$P/logs/uevents.log" 2> "$P/logs/uevents.err" &
    OBS=$!
    pending_pid observer "$OBS" || { group_empty observer; return 1; }
    for oi in 1 2 3 4 5 6 7 8 9 10; do
        oc=$(b tr '\000' ' ' < "/proc/$OBS/cmdline") || return 1
        case "$oc" in "$V/uevent-watch "*)
            record_pid observer "$OBS" && b rm "$R/observer.pending" && wait_observer_ready
            return $?;; esac
        sleep .05
    done
    return 1
}
kernel_start_ok() {
    owned_file "$R/aoa-start" 600 || return 1
    read ev_nonce ev_ms ev_seq ev_pid ev_uid ev_path ev_extra < "$R/aoa-start" || return 1
    owned_file "$R/observer.nonce" 600 && read_is "$R/observer.nonce" "$ev_nonce" && [ "$ev_nonce" = "$OBS_NONCE" ] && number "$ev_ms" && number "$ev_seq" && [ "$ev_seq" -gt 0 ] &&
        [ "$ev_pid" = 0 ] && [ "$ev_uid" = 0 ] && [ "$ev_path" = /devices/virtual/misc/mtp_usb ] && [ -z "$ev_extra" ]
}
observer_completed() {
    # Marker is written before exit. Require its successful fsync/close/exit,
    # without sending a signal to interrupt the evidence writer.
    for oe_i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
        identity observer; oe_identity=$?
        if [ "$oe_identity" != 0 ]; then
            # Exit may occur during the identity read, not only before it.
            # Only a proven own child exit permits wait; read errors do not.
            child_exited observer || return 1
            wait "$OBS"; oe_rc=$?; [ "$oe_rc" = 0 ]; return $?
        fi
        sleep .05
    done
    return 1
}
advance_discovery() {
    DISCOVERY_FAILURE=AOA_TRANSITION
    if [ -e "$R/aoa-start" ] || [ -L "$R/aoa-start" ]; then enter_accessory; return $?; fi
    if identity observer; then return 0; fi
    DISCOVERY_FAILURE=OBSERVER_IDENTITY
    child_exited observer || return 1
    # The observer writes/fsyncs/closes the marker BEFORE its successful exit.
    # Re-read after proven exit: the earlier absence can race that publication.
    # enter_accessory still validates marker, exit status and exact USB ownership.
    DISCOVERY_FAILURE=AOA_TRANSITION
    if [ -e "$R/aoa-start" ] || [ -L "$R/aoa-start" ]; then enter_accessory; return $?; fi
    DISCOVERY_FAILURE=OBSERVER_EXIT
    return 1
}

log() {
    for log_file in "$P/logs/controller.log" "$P/logs/controller.1.log"; do
        [ ! -e "$log_file" ] && [ ! -L "$log_file" ] || owned_file "$log_file" 600 || return 1
    done
    if regular "$P/logs/controller.log"; then
        log_size=$(b stat -c %s "$P/logs/controller.log") || return 1
        if [ "$log_size" -ge 131072 ]; then b mv "$P/logs/controller.log" "$P/logs/controller.1.log" || return 1; fi
    fi
    # /proc/uptime includes suspend; MD uptimeMillis does not. Label the clock
    # explicitly and compare boot stages only before any suspend (10 ms resolution).
    # Timing is diagnostic only: an unavailable clock must not add a new gate.
    log_ms=$(now_ms) || log_ms=unknown
    log_run_elapsed=unknown; log_phase_elapsed=unknown
    if number "$log_ms"; then
        log_seconds=$((log_ms / 1000))
        if number "$RUN_STARTED_MS" && [ "$log_ms" -ge "$RUN_STARTED_MS" ]; then log_run_elapsed=$((log_ms-RUN_STARTED_MS)); fi
        if number "$PHASE_STARTED_MS" && [ "$log_ms" -ge "$PHASE_STARTED_MS" ]; then log_phase_elapsed=$((log_ms-PHASE_STARTED_MS)); fi
    else log_seconds=$(now); fi
    printf 'boot=%s uptime=%s pid=%s run=%s generation=%s phase=%s clock=proc_uptime boot_elapsed_ms=%s run_elapsed_ms=%s phase_elapsed_ms=%s event=%s\n' "$BOOT" "$log_seconds" "$$" "$RUN_ID" "$GENERATION" "$PHASE" "$log_ms" "$log_run_elapsed" "$log_phase_elapsed" "$*" >> "$P/logs/controller.log"
}
phase() {
    phase_previous=$PHASE; phase_previous_elapsed=unknown
    phase_ms=$(now_ms) || phase_ms=unknown
    if number "$phase_ms" && number "$PHASE_STARTED_MS" && [ "$phase_ms" -ge "$PHASE_STARTED_MS" ]; then
        phase_previous_elapsed=$((phase_ms-PHASE_STARTED_MS))
    fi
    # Repeated WAIT_GADGET_DIRECTORIES must retain the start of that wait.
    [ "$PHASE" = "$1" ] || PHASE_STARTED_MS=$phase_ms
    PHASE=$1
    atomic status "$(printf 'contract=BOOT_NATIVE_RUNTIME_V1\nphase=%s\nboot_id=%s\nrun_id=%s\ncontroller_pid=%s\ngeneration=%s\neffective_backend=carlife\nselection_scope=PINNED_AT_BOOT_ADMISSION\norigin=BOOT_NATIVE_SERVICE_CANDIDATE\ntransport=DIRECT_USB_AOA\nmedia_validation=DISPLAY_PLUS_CODEC_CAPABILITIES_NOT_ENCODER_START\n' "$PHASE" "$BOOT" "$RUN_ID" "$$" "$GENERATION")" && log "PHASE previous_phase=$phase_previous previous_phase_elapsed_ms=$phase_previous_elapsed ${2-}"
}
bootstrap_qcom_main_once() {
    # sdAutoReverse used to request the common Qualcomm main services. Native
    # projection does not run it. Restore that one stock init trigger after our
    # /data owner claim, before any gadget/HU readiness wait. Never retry here.
    [ "$QCOM_MAIN_BOOTSTRAP_ATTEMPTED" = 0 ] || return 0
    QCOM_MAIN_BOOTSTRAP_ATTEMPTED=1
    qmb_before=$(prop vendor.qcom.main.start); qmb_rc=$?
    if [ "$qmb_rc" != 0 ]; then
        log "QCOM_MAIN_BOOTSTRAP_FAILED stage=read rc=$qmb_rc" || :
        return 0
    fi
    case "$qmb_before" in
        1) log QCOM_MAIN_BOOTSTRAP_ALREADY_REQUESTED || :; return 0;;
        ''|0) ;;
        *) log 'QCOM_MAIN_BOOTSTRAP_FAILED stage=unexpected_value' || :; return 0;;
    esac
    log QCOM_MAIN_BOOTSTRAP_REQUEST || :
    b setprop vendor.qcom.main.start 1; qmb_rc=$?
    if [ "$qmb_rc" != 0 ]; then
        log "QCOM_MAIN_BOOTSTRAP_FAILED stage=write rc=$qmb_rc" || :
        return 0
    fi
    qmb_after=$(prop vendor.qcom.main.start); qmb_rc=$?
    if [ "$qmb_rc" = 0 ] && [ "$qmb_after" = 1 ]; then
        # This is trigger readback, not radio/SIM/data readiness acceptance.
        log QCOM_MAIN_BOOTSTRAP_REQUESTED || :
    else
        log "QCOM_MAIN_BOOTSTRAP_FAILED stage=readback rc=$qmb_rc" || :
    fi
    return 0
}
same_boot() { read_is /proc/sys/kernel/random/boot_id "$BOOT"; }
oem_absent() {
    # sd-reverse is our parked dispatcher. Its init state may correctly be running.
    oa=$(b pidof sdAutoReverse sdCarplaySvc sd_mdnsd suding_mdnsd 2> "$R/oem-check.err"); oa_rc=$?
    [ "$oa_rc" = 1 ] && [ -z "$oa" ] && [ ! -s "$R/oem-check.err" ]
}
stop_requested() {
    [ "$STOP" = 1 ] && return 0
    # Saved protocol changes are for the next boot; never compare selected here.
    if [ -e "$R/stop.request" ]; then owned_file "$R/stop.request" 600 && read_is "$R/stop.request" "$RUN_ID $BOOT"; else return 1; fi
}
choose_accessory() {
    ca_unsorted=$(b find "$G/functions" -maxdepth 1 -name 'accessory.*') || return 1
    ca=$(printf '%s\n' "$ca_unsorted" | b sort) || return 1
    case "$ca" in
        "$G/functions/accessory.gs2") ACC_FUNCTION=accessory.gs2;;
        "$(printf '%s/functions/accessory.gs2\n%s/functions/accessory.usb0' "$G" "$G")") ACC_FUNCTION=accessory.usb0;;
        *) return 1;;
    esac
    [ -d "$G/functions/$ACC_FUNCTION" ] && [ ! -L "$G/functions/$ACC_FUNCTION" ] || return 1
    ACC_LAYOUT=$ca
    ACC_META=$(b stat -c '%u:%g:%a:%i' "$G/functions/$ACC_FUNCTION") || return 1
    atomic accessory.target "$ACC_FUNCTION"
}
accessory_unchanged() {
    au_names=$(b find "$G/functions" -maxdepth 1 -name 'accessory.*') || return 1
    au_layout=$(printf '%s\n' "$au_names" | b sort) || return 1
    [ "$au_layout" = "$ACC_LAYOUT" ] || return 1
    [ -d "$G/functions/$ACC_FUNCTION" ] && [ ! -L "$G/functions/$ACC_FUNCTION" ] &&
        [ "$(b stat -c '%u:%g:%a:%i' "$G/functions/$ACC_FUNCTION")" = "$ACC_META" ]
}
links_snapshot() {
    ls_names=$(b find "$G/configs" /config/usb_gadget/g2/configs -maxdepth 2 -type l) || return 1
    ls_rows=$(
        for ls_path in $ls_names; do
            [ "$(meta "$ls_path")" = 0:0:777 ] || exit 1
            ls_target=$(b readlink -f "$ls_path") || exit 1
            case "$ls_path:$ls_target" in
                ("$G/configs/b.1/f1:$G/functions/mtp.gs0"|"$G/configs/b.1/f1:$G/functions/$ACC_FUNCTION"|"$G/configs/b.1/f1:$G/functions/diag.diag"|"$G/configs/b.1/f2:$G/functions/cser.dun.0"|"$G/configs/b.1/f3:$G/functions/gsi.rmnet"|"$G/configs/b.1/f4:$G/functions/gsi.dpl"|"$G/configs/b.1/f5:$G/functions/qdss.qdss_mdm"|"$G/configs/b.1/f6:$G/functions/ffs.adb") ;;
                (*) exit 1;;
            esac
            printf '%s=%s\n' "$ls_path" "$ls_target"
        done
    ) || return 1
    printf '%s' "$ls_rows"
}
clear_known_links() {
    ck_rows=$(links_snapshot) || return 1
    for ck_n in 1 2 3 4 5 6; do
        ck_path=$G/configs/b.1/f$ck_n
        if [ -e "$ck_path" ] || [ -L "$ck_path" ]; then
            [ -L "$ck_path" ] || return 1
            ck_target=$(b readlink -f "$ck_path") || return 1
            printf '%s\n' "$ck_rows" | b grep -F -x "$ck_path=$ck_target" >/dev/null || return 1
            b rm "$ck_path" || return 1
        fi
    done
}
physical_profile() {
    pp=$1
    accessory_unchanged && read_is /config/usb_gadget/g2/UDC '' || return 1
    read_is "$G/idVendor" 0x18d1 && read_is "$G/UDC" a600000.dwc3 || return 1
    pp_rows=$(links_snapshot) || return 1
    case "$pp" in
        mtp) read_is "$G/idProduct" 0x4ee1 && [ "$pp_rows" = "$G/configs/b.1/f1=$G/functions/mtp.gs0" ];;
        accessory) read_is "$G/idProduct" 0x2d00 && [ "$pp_rows" = "$G/configs/b.1/f1=$G/functions/$ACC_FUNCTION" ];;
        *) return 1;;
    esac
}
read_md_ready() {
    identity md || return 1
    ready_pid=$ID_PID; ready_start=$ID_START
    owned_file "$R/md.boot-state" 600 || return 1
    ms_seen=; ms_contract=; ms_boot=; ms_nonce=; ms_pid=; ms_start=; ms_command=; ms_media=; ms_error=
    while IFS='=' read -r mk mv; do
        case " $ms_seen " in *" $mk "*) return 1;; esac
        ms_seen="$ms_seen $mk"
        case "$mk" in
            contract) ms_contract=$mv;; boot_id) ms_boot=$mv;; nonce) ms_nonce=$mv;;
            pid) ms_pid=$mv;; start) ms_start=$mv;; command_ready) ms_command=$mv;; media_ready) ms_media=$mv;; error_present) ms_error=$mv;;
            sequence|uptime_ms|stage|media_validation|source_display_id|source_width|source_height|source_density) ;;
            *) return 1;;
        esac
    done < "$R/md.boot-state"
    [ "$ms_contract" = BOOT_NATIVE_MD_V1 ] && [ "$ms_boot" = "$BOOT" ] && [ "$ms_nonce" = "$MD_NONCE" ] &&
        [ "$ms_pid" = "$ready_pid" ] && [ "$ms_start" = "$ready_start" ] && [ "$ms_command" = true ] && [ "$ms_media" = true ] && [ "$ms_error" = false ]
}
start_md() {
    stop_child md || return 1
    md_uuid=$(readf /proc/sys/kernel/random/uuid) || return 1
    MD_NONCE=$(printf '%s' "$md_uuid" | tr -d '-')
    case "$MD_NONCE" in ''|*[!a-f0-9]*) return 1;; esac
    [ "${#MD_NONCE}" = 32 ] && atomic md.nonce "$MD_NONCE" || return 1
    for md_log in "$P/logs/md.stderr"; do [ ! -e "$md_log" ] && [ ! -L "$md_log" ] || owned_file "$md_log" 600 || return 1; done
    : > "$P/logs/md.stderr"
    setsid sh -c 'printf "%s\n" "$$" > "$1/cgroup.procs" && export CLASSPATH="$2/md.jar" && exec /system/bin/app_process /system/bin org.carlife.hulab.MdMain --transport native-usb --seconds 0 --display-width 0 --display-height 0 --source-display 0 --video-source mediacodec --video-path egl --encoder c2.qti.avc.encoder --csd-source output-format-oem-guard --touch-profile recognized --log-dir "$3" --boot-native true --boot-state-file "$4/md.boot-state" --boot-nonce "$5"' boot-native-md "$CG/md" "$V" "$P/logs" "$R" "$MD_NONCE" > /dev/null 2>> "$P/logs/md.stderr" &
    MD=$!
    pending_pid md "$MD" || return 1
    md_deadline=$(( $(now) + 20 ))
    while [ "$(now)" -lt "$md_deadline" ]; do
        mc=$(b tr '\000' ' ' < "/proc/$MD/cmdline") || return 1
        if md_command_ok "$mc"; then
            mh=$(cmdhash "$MD") || return 1
            sleep .05
            [ "$(cmdhash "$MD")" = "$mh" ] && record_pid md "$MD" && b rm "$R/md.pending"
            return $?
        fi
        sleep .1
    done
    return 1
}
wait_md_ready() {
    phase MEDIA_WAIT || return 1
    wm_last=0
    while ! stop_requested; do
        identity md && same_boot || return 1
        if read_md_ready; then
            # Reject immediately failing/open-close loops; not proof of host bytes.
            sleep .1
            read_md_ready && return 0
        fi
        wm_now=$(now) || return 1
        if [ "$wm_now" -ge $((wm_last+10)) ]; then log WAIT_MD_COMMAND_AND_MEDIA || return 1; wm_last=$wm_now; fi
        sleep .25
    done
    return 1
}
vendor_usb_ready() {
    [ "$(prop sys.carlife.generic_usb_ready)" = 1 ] || return 1
    vu_vendor=$(prop vendor.usb.controller) && vu_sys=$(prop sys.usb.controller) && vu_ack=$(prop sys.carlife.usb_controller_ready) || return 1
    [ "$vu_vendor" = a600000.dwc3 ] && [ "$vu_sys" = "$vu_vendor" ] && [ "$vu_ack" = "$vu_vendor" ]
}

write_node() { timeout -k 1 3 sh -c 'printf "%s\n" "$2" > "$1"' boot-usb-write "$1" "$2"; }
initialize_usb_gpio() {
    # Proven first-start wiring: chelianyi-led + sd_msm6350, TLMM8=1124+8.
    # The line-info cache is not the hardware direction/value. Read sysfs after
    # export; atomic direction=low avoids an intermediate output-high pulse.
    GPIO_FAILURE=GPIO_MAPPING
    [ "$(prop ro.board.reverse.custom)" = chelianyi-led ] &&
        b grep -F -x 'CHANNEL=sd_msm6350' /system/bin/sd-reverse.sh >/dev/null &&
        read_is /sys/class/gpio/gpiochip1124/base 1124 &&
        read_is /sys/class/gpio/gpiochip1124/ngpio 156 &&
        read_is /sys/class/gpio/gpiochip1124/label f100000.pinctrl || return 1
    GPIO_FAILURE=GPIO_PREPUBLICATION_OWNER
    same_boot && oem_absent && read_is "$G/UDC" '' && read_is /config/usb_gadget/g2/UDC '' || return 1
    GPIO_FAILURE=GPIO_LOG
    log GPIO1132_INIT_BEGIN_BOARD_CHELIANYI_LED_CHANNEL_SD_MSM6350 || return 1
    gi_line=/sys/class/gpio/gpio1132
    if [ ! -e "$gi_line" ] && [ ! -L "$gi_line" ]; then
        GPIO_FAILURE=GPIO_EXPORT
        write_node /sys/class/gpio/export 1132 || return 1
    fi
    GPIO_FAILURE=GPIO_READBACK
    [ -d "$gi_line" ] || return 1
    gi_direction=$(readf "$gi_line/direction") && gi_value=$(readf "$gi_line/value") || return 1
    case "$gi_direction:$gi_value" in in:0|in:1|out:0|out:1) ;; *) return 1;; esac
    if [ "$gi_direction:$gi_value" != out:0 ]; then
        GPIO_FAILURE=GPIO_PREPUBLICATION_OWNER
        same_boot && oem_absent && read_is "$G/UDC" '' && read_is /config/usb_gadget/g2/UDC '' || return 1
        GPIO_FAILURE=GPIO_DIRECTION_LOW
        write_node "$gi_line/direction" low || return 1
    fi
    GPIO_FAILURE=GPIO_READBACK
    read_is "$gi_line/direction" out && read_is "$gi_line/value" 0 || return 1
    GPIO_FAILURE=GPIO_LOG
    log GPIO1132_READY_OUT0 || return 1
    # No unexport on stop/failure: releasing sysfs ownership does not undo level.
    GPIO_FAILURE=
}

configure_discovery() {
    # No init autoMtp trigger: all publication happens after our two readiness checks.
    same_boot && oem_absent && accessory_unchanged && read_is "$G/UDC" '' && read_is /config/usb_gadget/g2/UDC '' && read_md_ready && observer_ready || return 1
    [ "$(prop sys.usb.mtp.device_type)" = 3 ] || return 1
    serial=$(prop ro.serialno) || return 1
    case "$serial" in ''|*[!a-zA-Z0-9._-]*) return 1;; esac
    [ "${#serial}" -le 126 ] || return 1
    serial_hash=$(printf '%s' "$serial" | b sha256sum) || return 1
    atomic serial.provenance "source=ro.serialno sha256=${serial_hash%% *}" || return 1
    clear_known_links && b setprop sys.usb.configfs 2 || return 1
    write_node "$G/idVendor" 0x18d1 && write_node "$G/idProduct" 0x4ee1 && write_node "$G/bcdDevice" 0x1001 || return 1
    for node in bDeviceClass bDeviceSubClass bDeviceProtocol; do write_node "$G/$node" 0x00 || return 1; done
    write_node "$G/strings/0x409/serialnumber" "$serial" && write_node "$G/strings/0x409/manufacturer" CarLife && write_node "$G/strings/0x409/product" AndroidBox || return 1
    serial=
    write_node "$G/configs/b.1/bmAttributes" 0xc0 && b ln -s "$G/functions/mtp.gs0" "$G/configs/b.1/f1" || return 1
    # Last pre-bind check. External init/configfs is not a CAS; runtime logs drift.
    same_boot && oem_absent && read_md_ready && observer_ready && read_is "$G/UDC" '' || return 1
    atomic usb.intent "$RUN_ID $BOOT DISCOVERY" && log DISCOVERY_BIND_INTENT || return 1
    discovery_bind_start_ms=$(now_ms) || discovery_bind_start_ms=unknown
    write_node "$G/UDC" a600000.dwc3 || return 1
    discovery_bind_return_ms=$(now_ms) || discovery_bind_return_ms=unknown
    physical_profile mtp || return 1
    USB_OWNED=1
    phase USB_DISCOVERY "bind_start_boot_elapsed_ms=$discovery_bind_start_ms bind_return_boot_elapsed_ms=$discovery_bind_return_ms"
}
enter_accessory() {
    observer_completed && kernel_start_ok && physical_profile mtp && oem_absent && identity md || return 1
    stop_child observer && atomic usb.intent "$RUN_ID $BOOT ACCESSORY" && log ACCESSORY_BIND_INTENT || return 1
    # Keep MD + native reader open through transition; first HU bytes have an owner.
    write_node "$G/UDC" '' && read_is "$G/UDC" '' && accessory_unchanged && clear_known_links || return 1
    write_node "$G/idProduct" 0x2d00 && b ln -s "$G/functions/$ACC_FUNCTION" "$G/configs/b.1/f1" || return 1
    accessory_bind_start_ms=$(now_ms) || accessory_bind_start_ms=unknown
    write_node "$G/UDC" a600000.dwc3 || return 1
    accessory_bind_return_ms=$(now_ms) || accessory_bind_return_ms=unknown
    physical_profile accessory || return 1
    USB_OWNED=1
    # The verified START already proves a host connection. Reenumeration can
    # lose it before polling observes configured; still require a full detach interval.
    DETACH_ARMED=1; DETACH_SINCE=0; ATTACH_FAILURES=0
    phase ACCESSORY_CONNECTED "bind_start_boot_elapsed_ms=$accessory_bind_start_ms bind_return_boot_elapsed_ms=$accessory_bind_return_ms"
}
recovery_reason() {
    RECOVERY_REASON=NONE
    if [ "$DETACH_CONFIRMED" = 1 ]; then RECOVERY_REASON=DETACH; return 0; fi
    [ "$PHASE" = ACCESSORY_CONNECTED ] || return 0
    if identity md; then MD_EXIT_SINCE=0; return 0; fi
    child_exited md || return 1
    [ "$MD_EXIT_SINCE" != 0 ] || MD_EXIT_SINCE=$tick
    if [ "$tick" -ge $((MD_EXIT_SINCE+150)) ]; then RECOVERY_REASON=MD_EXIT; fi
}

rearm_discovery() {
    # A terminal negotiated boot-native session exits 0 only after its source,
    # writer and native fd owner have stopped. PAUSE/RESET keep that process alive.
    # Reopen of the same accessory endpoints is not a new USB session.
    rd_reason=$1
    RECOVERY_FAILURE=RECOVERY_REASON
    case "$rd_reason" in
        DETACH) ;;
        MD_EXIT)
            RECOVERY_FAILURE=MD_EXIT_STATUS
            [ "$PHASE" = ACCESSORY_CONNECTED ] && child_exited md && [ "$ce_pid" = "$MD" ] || return 1
            wait "$MD"; rd_status=$?
            [ "$rd_status" = 0 ] || return 1
            ;;
        *) return 1;;
    esac
    RECOVERY_FAILURE=RECOVERY_OWNER
    stop_requested && return 1
    same_boot && oem_absent && physical_profile accessory || return 1
    RECOVERY_FAILURE=RECOVERY_CLEANUP
    stop_child md && stop_child observer || return 1
    # Recheck after cleanup; never publish while an old owner is still present.
    RECOVERY_FAILURE=RECOVERY_OWNER
    stop_requested && return 1
    same_boot && oem_absent && physical_profile accessory || return 1
    RECOVERY_FAILURE=RECOVERY_STATUS
    phase "${rd_reason}_REARM" || return 1
    RECOVERY_FAILURE=RECOVERY_UNBIND
    write_node "$G/UDC" '' && read_is "$G/UDC" '' || return 1
    GENERATION=$((GENERATION+1)); DETACH_ARMED=0; DETACH_SINCE=0; MD_EXIT_SINCE=0
    if [ "$rd_reason" = DETACH ]; then ATTACH_FAILURES=0;
    else ATTACH_FAILURES=$((ATTACH_FAILURES+1)); fi
    RECOVERY_FAILURE=RECOVERY_REARM
    stop_requested && return 1
    start_md && start_observer && wait_md_ready && configure_discovery
    # configure_discovery stops at MTP. Only a new authenticated kernel START
    # can enter accessory and reset the per-attempt counter; no AOA is replayed.
}

claim_owner() {
    if [ -e "$R/owner.lock" ] || [ -L "$R/owner.lock" ]; then
        [ -d "$R/owner.lock" ] && [ ! -L "$R/owner.lock" ] && [ "$(meta "$R/owner.lock")" = 0:0:700 ] && owned_file "$R/boot" 600 || return 1
        oldboot=$(readf "$R/boot") || return 1
        case "$oldboot" in ''|*[!a-f0-9-]*) return 1;; esac
        [ "${#oldboot}" = 36 ] && [ "$oldboot" != "$BOOT" ] || return 1
        # Empty own lock only, old boot is retained in rotated controller/MD logs.
        b rmdir "$R/owner.lock" || return 1
    fi
    b mkdir -m 700 "$R/owner.lock" || return 1
    ru=$(readf /proc/sys/kernel/random/uuid) || return 1
    RUN_ID=$(printf '%s' "$ru" | tr -d '-')
    case "$RUN_ID" in ''|*[!a-f0-9]*) return 1;; esac
    [ "${#RUN_ID}" = 32 ] || return 1
    CG=/sys/fs/cgroup/carlife-boot-native-$RUN_ID
    [ ! -e "$CG" ] && b mkdir -m 700 "$CG" || return 1
    for role in controller md observer; do b mkdir -m 700 "$CG/$role" || return 1; done
    cg_all=$(readf /proc/self/cgroup) || return 1
    cg1=$(printf '%s\n' "$cg_all" | sed '/^0::/d')
    CG_PARENT=$(printf '%s\n' "$cg_all" | sed -n 's/^0:://p')
    case "$CG_PARENT" in /*) ;; *) return 1;; esac
    case "$CG_PARENT" in *..*|*[!a-zA-Z0-9_./-]*) return 1;; esac
    atomic cgroup_v1 "$cg1" && atomic boot "$BOOT" && atomic run_id "$RUN_ID" || return 1
    for old in md.identity observer.identity md.pending observer.pending observer.nonce md.nonce md.boot-state stop.request aoa-start usb.intent; do
        if [ -e "$R/$old" ] || [ -L "$R/$old" ]; then owned_file "$R/$old" 600 && b rm "$R/$old" || return 1; fi
    done
    printf '%s\n' "$$" > "$CG/controller/cgroup.procs" || return 1
    record_pid controller "$$"
}
load_owner() {
    owned_file "$R/boot" 600 && owned_file "$R/run_id" 600 || return 1
    BOOT=$(readf "$R/boot") && RUN_ID=$(readf "$R/run_id") || return 1
    case "$RUN_ID" in ''|*[!a-f0-9]*) return 1;; esac
    [ "${#RUN_ID}" = 32 ] || return 1
    CG=/sys/fs/cgroup/carlife-boot-native-$RUN_ID
}
cleanup_groups() {
    group_empty md && group_empty observer || return 1
    [ -d "/sys/fs/cgroup$CG_PARENT" ] && [ ! -L "/sys/fs/cgroup$CG_PARENT" ] || return 1
    printf '%s\n' "$$" > "/sys/fs/cgroup$CG_PARENT/cgroup.procs" || return 1
    for cleanup_role in md observer controller; do
        group_empty "$cleanup_role" && b rmdir "$CG/$cleanup_role" || return 1
    done
    b rmdir "$CG" && b rmdir "$R/owner.lock"
}
stop_own() {
    phase STOPPING || return 1
    stop_child md && stop_child observer || { phase STOP_FAILED_OWN_CHILD_ALIVE; return 1; }
    # Do not restore AA, unbind attached USB or change persisted selection.
    cleanup_groups || { phase STOP_FAILED_CGROUP_CLEANUP; return 1; }
    phase STOPPED
}
start_floating_nav_once() {
    # Optional OEM navigation UI. Never start during discovery/recovery, never
    # retry within this owner invocation, and never make UI failure a USB fault.
    [ "$PHASE" = ACCESSORY_CONNECTED ] && [ "$RECOVERY_REASON" = NONE ] &&
        [ "$udc_state" = configured ] && [ "$FLOATING_NAV_ATTEMPTED" = 0 ] || return 0
    FLOATING_NAV_ATTEMPTED=1
    log FLOATING_NAV_START_ATTEMPT || :
    fnav_output=$(b am startservice --user 0 -n com.sd.ball/.WindowService -a com.sd.intent.action.WindowService 2>&1)
    fnav_rc=$?
    fnav_result=COMMAND_ACCEPTED
    if [ "$fnav_rc" != 0 ]; then
        fnav_result=COMMAND_FAILED
    else
        case "$fnav_output" in
            *'Error:'*|*'Exception:'*|*'Exception occurred'*) fnav_result=ERROR_RESPONSE;;
        esac
    fi
    log "FLOATING_NAV_START_RESULT rc=$fnav_rc result=$fnav_result" || :
    fnav_output=
    return 0
}

fail() {
    log "FAIL_$1"; phase "FAULT_$1"
    stop_child md && stop_child observer || { phase FAULT_CHILD_STILL_OWNS_FD; exit 71; }
    # Own lock stays as evidence, no silent retry/AA switch/reboot loop.
    exit 71
}

[ "$#" = 3 ] && [ "$P" = /data/local/carlife-native ] && [ "$V" = "$P/versions/boot-native-v1-20261014" ] || exit 64
case "$ACTION" in run|status|stop) ;; *) exit 64;; esac
[ "$(b id -u)" = 0 ] && [ "$(b id -g)" = 0 ] || exit 70
if [ "$ACTION" = status ]; then
    if load_owner && same_boot && identity controller; then readf "$R/status"; printf 'verified_alive=true\n';
    elif [ -e "$R/status" ]; then readf "$R/status"; printf 'verified_alive=false\n';
    else printf 'phase=NOT_RUNNING\nverified_alive=false\n'; fi
    exit 0
fi
if [ "$ACTION" = stop ]; then
    load_owner && same_boot && identity controller || exit 70
    atomic stop.request "$RUN_ID $BOOT" || exit 70
    sd=$(( $(now) + 30 ))
    while [ "$(now)" -lt "$sd" ]; do
        if b grep -F -x phase=STOPPED "$R/status" >/dev/null; then
            readf "$R/status"; exit 0
        fi
        sleep .25
    done
    printf 'REFUSED=stop_timeout_no_blind_usb_restore\n' >&2; exit 71
fi
BOOT=$(readf /proc/sys/kernel/random/boot_id) || exit 70
RUN_STARTED_MS=$(now_ms) || RUN_STARTED_MS=unknown
PHASE_STARTED_MS=$RUN_STARTED_MS
claim_owner || { printf 'REFUSED=owner_claim\n' >&2; exit 70; }
trap 'STOP=1' TERM INT
phase BOOTSTRAP || fail STATUS
bootstrap_qcom_main_once
# Device boot readiness only; never sys.boot_completed / network / adbd.
while ! stop_requested; do
    if [ -d "$G/functions/mtp.gs0" ] && [ -d "$G/functions/accessory.gs2" ] && [ -d "$G/configs/b.1" ] && [ -d "$G/strings/0x409" ] && [ -e /dev/usb_accessory ]; then break; fi
    phase WAIT_GADGET_DIRECTORIES || fail STATUS
    sleep 1
done
stop_requested && { stop_own; exit $?; }
same_boot && oem_absent && choose_accessory && read_is /config/usb_gadget/g2/UDC '' && links_snapshot >/dev/null || fail PREPUBLICATION_OWNER
# If AOA was already completed by common discovery, adopt it without MTP rewind.
ADOPT=0
if physical_profile accessory; then ADOPT=1; phase ADOPT_EXISTING_ACCESSORY || fail STATUS;
else read_is "$G/UDC" '' || fail UNEXPECTED_BOUND_DISCOVERY; fi
start_md || fail MD_START
if [ "$ADOPT" = 1 ]; then
    USB_OWNED=1; phase ACCESSORY_CONNECTED || fail STATUS
else
    start_observer || fail OBSERVER_START
    if ! wait_md_ready; then stop_requested && { stop_own; exit $?; }; fail MD_READINESS; fi
    # Marker supplied by pinned init boot action after vendor USB setup; no OEM gate.
    phase WAIT_VENDOR_USB_ACTIONS || fail STATUS
    while ! stop_requested; do
        vendor_usb_ready && break
        identity md && oem_absent && read_is "$G/UDC" '' || fail UNEXPECTED_EARLY_OWNER
        sleep .25
    done
    stop_requested && { stop_own; exit $?; }
    initialize_usb_gpio || fail "$GPIO_FAILURE"
    configure_discovery || fail DISCOVERY_PUBLICATION
fi
while ! stop_requested; do
    same_boot && oem_absent && read_is /config/usb_gadget/g2/UDC '' && accessory_unchanged || fail OWNER_DRIFT
    [ "$(prop sys.usb.configfs)" = 2 ] || fail CONFIGFS_OWNER_DRIFT
    udc_state=$(readf "$U/state") || fail UDC_READ
    tick=$(now_cs) || fail CLOCK
    observe_udc "$udc_state" "$tick" || fail UDC_STATE
    case "$PHASE" in
        USB_DISCOVERY)
            identity md || fail DISCOVERY_MD_EXIT
            advance_discovery || fail "$DISCOVERY_FAILURE"
            ;;
        ACCESSORY_CONNECTED)
            recovery_reason || fail MD_IDENTITY
            if [ "$RECOVERY_REASON" != NONE ]; then
                if ! rearm_discovery "$RECOVERY_REASON"; then
                    stop_requested && { stop_own; exit $?; }
                    fail "$RECOVERY_FAILURE"
                fi
            else
                start_floating_nav_once
            fi
            ;;
        *) fail UNKNOWN_PHASE;;
    esac
    if regular "$P/logs/md.stderr"; then
        stderr_size=$(b stat -c %s "$P/logs/md.stderr") || fail STDERR_STAT
        [ "$stderr_size" -lt 65536 ] || { : > "$P/logs/md.stderr"; log MD_STDERR_TRUNCATED; }
    fi
    sleep .1
done
stop_own
