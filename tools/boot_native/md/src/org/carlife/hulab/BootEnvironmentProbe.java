package org.carlife.hulab;

/** Read-only app_process probe: no accessory open, no codec creation, no display mirror. */
public final class BootEnvironmentProbe {
    public static void main(String[] args)throws Exception {
        if(args.length!=0)throw new IllegalArgumentException("probe accepts no arguments");
        System.out.println("contract=BOOT_NATIVE_ENV_PROBE_V1");
        System.out.println("pid="+android.os.Process.myPid());
        System.out.println("uid="+android.os.Process.myUid());
        System.out.println("uptime_ms="+android.os.SystemClock.uptimeMillis());
        System.out.println("boot_id="+BootNativeReadiness.readLine("/proc/sys/kernel/random/boot_id"));
        for(String path:new String[]{"/apex/com.android.art","/system/bin/app_process","/dev/usb_accessory"})System.out.println("path_present="+path+":"+new java.io.File(path).exists());
        for(String service:new String[]{"display","SurfaceFlinger","media.player","media.codec","input"}){
            try{System.out.println("service="+service+":"+BootNativeReadiness.servicePresent(service));}
            catch(Exception e){System.out.println("service_error="+service+":"+e.getClass().getName());}
        }
        System.out.println("accessory_opened=false\ncodec_created=false\ndisplay_mirror_created=false");
    }
}
