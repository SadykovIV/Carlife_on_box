package org.carlife.hulab;

import android.content.Context;
import android.media.AudioFocusInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiopolicy.AudioPolicy.AudioPolicyFocusListener;
import android.os.Handler;
import android.os.Looper;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Passive focus follower on the existing microphone worker; never governs
 * focus, changes ducking or creates an audio mix. Yandex Navigator keeps MIC
 * open while idle and reuses it for Alice; its exclusive focus marks intent. */
final class NativeVoiceFocus implements AutoCloseable {
    interface Listener {void changed(int newlyGrantedUid);default void focusTurn(int uid,String pkg,long turn,boolean active){}}
    final State state=new State();
    private final Listener listener;
    private AudioManager manager;private Object policy;private Class<?> policyType;
    private AudioManager.AudioPlaybackCallback playbackCallback;private boolean playbackRegistered;
    private boolean attempted,registered;private volatile boolean closed;
    NativeVoiceFocus(Listener listener){this.listener=listener;}
    void start(AudioManager audio,Handler worker){
        if(attempted||closed)return;attempted=true;manager=audio;
        try{
            policyType=Class.forName("android.media.audiopolicy.AudioPolicy");
            Class<?> builderType=Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
            Object builder=builderType.getConstructor(Context.class).newInstance((Object)null);
            builderType.getMethod("setLooper",Looper.class).invoke(builder,worker.getLooper());
            builderType.getMethod("setAudioPolicyFocusListener",AudioPolicyFocusListener.class).invoke(builder,new AudioPolicyFocusListener(){
                public void onAudioFocusGrant(AudioFocusInfo value,int result){event(value,true,result);}
                public void onAudioFocusLoss(AudioFocusInfo value,boolean notified){event(value,false,0);}
            });
            policy=builderType.getMethod("build").invoke(builder);
            Object config=policyType.getMethod("getConfig").invoke(policy);
            int mixes=((List<?>)get(config,"getMixes")).size();
            if((Boolean)get(policy,"isFocusPolicy")||(Boolean)get(policy,"isTestFocusPolicy")||(Boolean)get(policy,"isVolumeController")||mixes!=0)
                throw new IllegalStateException("passive focus invariants failed");
            int result=(Integer)AudioManager.class.getMethod("registerAudioPolicy",policyType).invoke(manager,policy);
            if(result!=0)throw new IllegalStateException("focus follower registration="+result);
            registered=true;
            playbackCallback=new AudioManager.AudioPlaybackCallback(){public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> values){playback(values);}};
            manager.registerAudioPlaybackCallback(playbackCallback,worker);playbackRegistered=true;
            playback(manager.getActivePlaybackConfigurations());
            for(Object value:(List<?>)policyType.getMethod("getFocusStack").invoke(policy))if(number(value,"getLossReceived")==0)event(value,true,1);
            log("observer_ready","passive listener; zero mixes; focus_policy=false");
        }catch(Exception|LinkageError e){
            log("observer_unavailable",e.toString()+"; Yandex persistent MIC requires explicit HU START until focus observation is available");
            // A snapshot failure does not invalidate callbacks already registered.
        }
    }
    private static Object get(Object value,String name)throws Exception{return value.getClass().getMethod(name).invoke(value);}
    private static int number(Object value,String name)throws Exception{return ((Number)get(value,name)).intValue();}
    void captureReceived(int uid,long turn,long routeEpoch){if(!closed&&state.captureReceived(uid,turn,routeEpoch))log("listening_pcm_ready","uid="+uid+" turn="+turn+" route_epoch="+routeEpoch+"; startup MEDIA cannot close this route");}
    void captureEnded(long routeEpoch){state.captureEnded(routeEpoch);}
    private void playback(List<AudioPlaybackConfiguration> values){
        if(closed)return;
        try{
            HashSet<Integer> media=new HashSet<Integer>();
            for(AudioPlaybackConfiguration value:values)if((Boolean)get(value,"isActive")&&value.getAudioAttributes().getUsage()==1)media.add(number(value,"getClientUid"));
            if(state.media(media)){log(state.replying()?"application_reply_started":"application_reply_ended","exclusive voice focus + same UID MEDIA playback");listener.changed(-1);}
        }catch(Exception e){log("playback_snapshot_error",e.toString());}
    }
    private void event(Object value,boolean grant,int result){
        if(closed)return;
        try{
            int uid=number(value,"getClientUid"),gain=number(value,"getGainRequest"),loss=number(value,"getLossReceived");
            String pkg=String.valueOf(get(value,"getPackageName")),client=String.valueOf(get(value,"getClientId"));
            if(!State.navigator(pkg))return;
            long turn=state.turn(uid,client);
            boolean changed=grant?state.grant(uid,pkg,client,gain,result):state.loss(uid,client);
            if(changed){if(grant)turn=state.turn(uid,client);log(grant?"voice_focus_granted":"voice_focus_lost","uid="+uid+" gain="+gain+" loss="+loss+" result="+result+" package="+pkg+" turn="+turn);if(grant||loss==0||loss==-1)listener.focusTurn(uid,pkg,turn,grant);listener.changed(grant?uid:-1);}
        }catch(Exception e){log("focus_decode_error",e.toString());}
    }
    public void close(){
        closed=true;state.clear();
        if(playbackRegistered)try{manager.unregisterAudioPlaybackCallback(playbackCallback);}catch(RuntimeException e){log("playback_close_error",e.toString());}
        playbackRegistered=false;playbackCallback=null;
        if(registered)try{AudioManager.class.getMethod("unregisterAudioPolicy",policyType).invoke(manager,policy);log("observer_closed","passive policy removed");}catch(Exception e){log("observer_close_error",e.toString());}
        registered=false;policy=null;manager=null;
    }
    static final class State {
        private static final class Phase {long routeEpoch;boolean armed,reply;}
        private final Map<String,Integer> active=new HashMap<String,Integer>();private final Map<String,Long> turns=new HashMap<String,Long>();private final Map<String,Phase> phases=new HashMap<String,Phase>();private long nextTurn,retiredRouteEpoch;private final HashSet<String> stopped=new HashSet<String>();private final HashSet<Integer> mediaUids=new HashSet<Integer>();private boolean explicit;
        static boolean navigator(String pkg){return "ru.yandex.yandexnavi".equals(pkg);}
        static boolean gated(int source,String pkg){return source==1&&navigator(pkg);}
        private static String key(int uid,String client){return uid+":"+client;}
        synchronized boolean grant(int uid,String pkg,String client,int gain,int result){
            if(!navigator(pkg)||gain!=4||result!=1)return false;
            String key=key(uid,client);if(active.containsKey(key))return false;active.put(key,uid);turns.put(key,++nextTurn);phases.put(key,new Phase());return true;
        }
        synchronized long turn(int uid,String client){Long value=turns.get(key(uid,client));return value==null?0:value;}
        synchronized long currentTurn(int uid){long newest=0;for(Map.Entry<String,Integer> entry:active.entrySet())if(entry.getValue()==uid&&!stopped.contains(entry.getKey()))newest=Math.max(newest,turns.get(entry.getKey()));return newest;}
        synchronized boolean loss(int uid,String client){String key=key(uid,client);boolean changed=active.remove(key)!=null;turns.remove(key);phases.remove(key);stopped.remove(key);if(changed)explicit=false;return changed;}
        private boolean voiceUid(int uid){for(Map.Entry<String,Integer> entry:active.entrySet())if(entry.getValue()==uid&&!stopped.contains(entry.getKey()))return true;return false;}
        private boolean replyUid(int uid){for(Map.Entry<String,Integer> entry:active.entrySet())if(entry.getValue()==uid&&!stopped.contains(entry.getKey())&&phases.get(entry.getKey()).reply)return true;return false;}
        synchronized boolean permits(int uid,int source,String pkg){if(!gated(source,pkg))return true;return (explicit||voiceUid(uid))&&!replyUid(uid);}
        synchronized boolean replying(){for(int uid:active.values())if(replyUid(uid))return true;return false;}
        /** A startup sound is MEDIA too. Only a new MEDIA rising edge after a
         * verified route has received PCM belongs to the listening->reply phase. */
        synchronized boolean media(java.util.Set<Integer> uids){
            boolean before=replying();
            for(Map.Entry<String,Integer> entry:active.entrySet()){
                String key=entry.getKey();if(stopped.contains(key))continue;
                int uid=entry.getValue();Phase p=phases.get(key);boolean was=mediaUids.contains(uid),now=uids.contains(uid);
                if(!now){if(p.reply){p.reply=false;p.routeEpoch=0;p.armed=false;}else if(p.routeEpoch>0)p.armed=true;}
                else if(!was&&p.routeEpoch>0&&p.armed){p.reply=true;p.armed=false;}
            }
            mediaUids.clear();mediaUids.addAll(uids);return before!=replying();
        }
        synchronized boolean captureReceived(int uid,long turn,long routeEpoch){
            if(turn<=0||routeEpoch<=retiredRouteEpoch)return false;
            for(Map.Entry<String,Integer> entry:active.entrySet())if(entry.getValue()==uid&&!stopped.contains(entry.getKey())&&turns.get(entry.getKey())==turn){
                Phase p=phases.get(entry.getKey());if(p.reply||p.routeEpoch==routeEpoch)return false;
                p.routeEpoch=routeEpoch;p.armed=!mediaUids.contains(uid);return true;
            }return false;
        }
        synchronized void captureEnded(long routeEpoch){retiredRouteEpoch=Math.max(retiredRouteEpoch,routeEpoch);for(Phase p:phases.values())if(p.routeEpoch==routeEpoch){p.routeEpoch=0;p.armed=false;}}
        synchronized void explicitStart(){
            explicit=true;stopped.clear();
            // A new HU START may interrupt an answer. Do not inherit that
            // answer's reply latch or readiness; the new route must receive PCM.
            for(Phase p:phases.values())if(p.reply){retiredRouteEpoch=Math.max(retiredRouteEpoch,p.routeEpoch);p.reply=false;p.routeEpoch=0;p.armed=false;}
        }
        synchronized void explicitStop(){explicit=false;stopped.addAll(active.keySet());}
        synchronized void clear(){active.clear();turns.clear();phases.clear();stopped.clear();mediaUids.clear();explicit=false;}
    }
    private static void log(String state,String detail){MdMain.logFields("native_voice_focus","state",state,"detail",detail);}
}
