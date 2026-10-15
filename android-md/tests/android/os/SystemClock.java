package android.os;
/** Host-test clock only. Never included in MD dex or stimulus APK. */
public final class SystemClock {
    public static volatile Runnable nextRead;
    public static volatile long fixedTime=-1;
    public static long elapsedRealtime(){return uptimeMillis();}
    public static long uptimeMillis(){Runnable hook=nextRead;if(hook!=null){nextRead=null;hook.run();}return fixedTime>=0?fixedTime:System.nanoTime()/1000000L;}
}
