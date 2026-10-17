package org.carlife.hulab;

/** Generation-scoped evidence for a HU-owned voice request; no Android calls. */
final class VoiceUiState {
    static final long START_TIMEOUT_MS=2800, RUN_TIMEOUT_MS=45000;
    enum Phase { IDLE, LAUNCHING, WAIT_ROUTE, RUNNING }
    private long generation,started,runningSince,focusTurn,focusGeneration;
    private int targetUid=-1,currentUid=-1,currentSession=-1,runningSession=-1;
    private final java.util.HashSet<Long> preexisting=new java.util.HashSet<Long>();
    private String targetPackage="",currentPackage="";
    private boolean launched;
    private Phase phase=Phase.IDLE;

    synchronized long begin(long now,int priorUid,int priorSession){return begin(now,new int[][]{{priorUid,priorSession}});}
    synchronized long begin(long now,int[][] prior){
        if(phase!=Phase.IDLE)return 0;
        ++generation;started=now;runningSince=0;focusTurn=0;focusGeneration=0;preexisting.clear();
        if(prior!=null)for(int[] pair:prior)if(pair!=null&&pair.length==2&&pair[0]>=1000&&pair[1]>0)preexisting.add(identity(pair[0],pair[1]));
        targetUid=-1;targetPackage="";currentUid=-1;currentSession=-1;runningSession=-1;currentPackage="";launched=false;
        phase=Phase.LAUNCHING;return generation;
    }
    synchronized boolean current(long token){return token==generation&&phase!=Phase.IDLE;}
    synchronized boolean target(long token,int uid,String pkg){
        if(!current(token)||uid<1000||pkg==null||pkg.isEmpty())return false;
        targetUid=uid;targetPackage=pkg;return true;
    }
    synchronized boolean launched(long token,long now){
        if(!current(token)||expired(now)||targetUid<1000)return false;
        launched=true;phase=Phase.WAIT_ROUTE;return consider(now);
    }
    synchronized boolean route(long token,int uid,int session,String pkg,long now){
        if(!current(token))return false;
        currentUid=uid;currentSession=session;currentPackage=pkg==null?"":pkg;
        return consider(now);
    }
    private boolean consider(long now){
        if(phase==Phase.RUNNING||!launched||expired(now))return false;
        if(currentUid!=targetUid||currentSession<=0||!targetPackage.equals(currentPackage))return false;
        // A pre-existing hotword recorder plus a successful/no-op Activity launch
        // is not evidence that the assistant opened its actual listening input.
        if(preexisting.contains(identity(currentUid,currentSession)))return false;
        phase=Phase.RUNNING;runningSince=now;runningSession=currentSession;return true;
    }
    private static long identity(int uid,int session){return ((long)uid<<32)|(session&0xffffffffL);}
    synchronized boolean expired(long now){
        if(phase==Phase.IDLE)return false;
        return phase==Phase.RUNNING?now-runningSince>=RUN_TIMEOUT_MS:now-started>=START_TIMEOUT_MS;
    }
    synchronized long remaining(long token,long now){
        if(!current(token))return 0;
        return Math.max(0,(phase==Phase.RUNNING?runningSince+RUN_TIMEOUT_MS:started+START_TIMEOUT_MS)-now);
    }
    synchronized boolean finish(long token){
        if(!current(token))return false;
        ++generation;phase=Phase.IDLE;launched=false;return true;
    }
    synchronized long token(){return generation;}
    synchronized Phase phase(){return phase;}
    synchronized String targetPackage(){return targetPackage;}
    synchronized boolean matches(int uid,String pkg){return uid==targetUid&&targetPackage.equals(pkg);}
    synchronized void focusGranted(int uid,String pkg,long turn){if(turn>0&&phase!=Phase.IDLE&&matches(uid,pkg)){focusTurn=turn;focusGeneration=generation;}}
    /** The focus turn is bound to the UI generation present at grant time.
     * An old terminal callback must not finish a newer explicit HU request. */
    synchronized long focusEnded(int uid,String pkg,long turn){if(turn<=0||turn!=focusTurn||focusGeneration!=generation||phase==Phase.IDLE||!matches(uid,pkg))return 0;focusTurn=0;return generation;}
    synchronized boolean ownsRecorder(int uid,int session,String pkg){return phase==Phase.RUNNING&&matches(uid,pkg)&&session==runningSession;}
    /** New recorder attributable to this request, including route-before-am-return. */
    synchronized boolean ownsPendingOrRunningRecorder(long token,int uid,int session,String pkg){
        if(!current(token)||!matches(uid,pkg)||session<=0||preexisting.contains(identity(uid,session)))return false;
        return phase!=Phase.RUNNING||session==runningSession;
    }
}
