package org.carlife.hulab;

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.io.*;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;

/** Explicit HU microphone -> Android speech recognizer -> Android web search. */
final class NativeVoice {
    private final NativeServiceState wire;private final String component;
    private Context context;private Handler main;private SpeechRecognizer recognizer;
    private volatile boolean available,closed,active,availabilityBlocked,availabilityPending;
    private boolean prepared;
    private long epoch;private Pipe pipe;
    NativeVoice(NativeServiceState wire,String component){this.wire=wire;this.component=component;}
    synchronized void prepare(Context c,Handler h){if(prepared||closed)return;prepared=true;context=c;main=h;refreshAvailability();}
    void refreshAvailability(){
        Handler h;
        synchronized(this){if(closed||available||availabilityBlocked||availabilityPending||main==null)return;availabilityPending=true;h=main;}
        h.post(new Runnable(){public void run(){
            try{if(closed)return;if(android.os.Build.VERSION.SDK_INT<33){availabilityBlocked=true;diagnostic("external_audio_api_unavailable");return;}
                available=SpeechRecognizer.isRecognitionAvailable(context);diagnostic(available?"ready":"recognizer_not_ready");
            }catch(SecurityException e){availabilityBlocked=true;diagnostic("recognizer_permission_denied");}
            catch(RuntimeException e){diagnostic("recognizer_readiness_error "+e.getClass().getSimpleName());}
            finally{availabilityPending=false;}
        }});
    }
    boolean retryAvailability(){return !closed&&!available&&!availabilityBlocked;}
    boolean start(){Handler h=main;if(closed||h==null)return false;if(!available){refreshAvailability();return false;}h.post(new Runnable(){public void run(){begin();}});return true;}
    void stop(){Handler h=main;if(h!=null)h.post(new Runnable(){public void run(){finish(epoch,false,null);}});}
    void onMic(byte[] pcm,int timestamp){Pipe p; synchronized(this){p=pipe;}if(p!=null&&!p.offer(pcm)){final long token=p.token;main.post(new Runnable(){public void run(){finish(token,false,"mic_backpressure");}});}}
    private void begin(){
        if(closed||!available||pipe!=null)return;
        final long token=++epoch;
        try {
            final Pipe p=new Pipe(token);synchronized(this){pipe=p;}
            ComponentName selected=component==null||component.isEmpty()?null:ComponentName.unflattenFromString(component);
            if(component!=null&&!component.isEmpty()&&selected==null)throw new IOException("invalid recognizer component");
            recognizer=selected==null?SpeechRecognizer.createSpeechRecognizer(context):SpeechRecognizer.createSpeechRecognizer(context,selected);
            recognizer.setRecognitionListener(new RecognitionListener(){
                public void onReadyForSpeech(Bundle b){if(token!=epoch||closed)return;active=true;try{wire.control(0x10026,module(1));wire.control(0x10024,new byte[0]);}catch(IOException e){finish(token,false,"wire_error");}}
                public void onBeginningOfSpeech(){}public void onRmsChanged(float v){}public void onBufferReceived(byte[] b){}
                public void onEndOfSpeech(){endAudio(token);}
                public void onError(int error){finish(token,false,"recognizer_error_"+error);}
                public void onResults(Bundle b){results(token,b);}
                public void onPartialResults(Bundle b){}public void onEvent(int type,Bundle b){}
            });
            Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE,p.read)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,16000)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false);
            recognizer.startListening(intent);p.start();
            main.postDelayed(new Runnable(){public void run(){finish(token,false,"recognition_timeout");}},15000);
            diagnostic("start_external_pcm16_mono_16000 provider_support_requires_runtime_test");
        }catch(Exception e){finish(token,false,e.getClass().getSimpleName());}
    }
    private void results(long token,Bundle b){
        if(token!=epoch||closed)return;String query=null;
        try{ArrayList<String> list=b==null?null:b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);query=list==null||list.isEmpty()?null:list.get(0);}catch(RuntimeException e){diagnostic("recognizer_invalid_result");}
        finish(token,false,null);
        if(query!=null&&!query.trim().isEmpty()&&!closed){try{context.startActivity(new Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY,query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));diagnostic("search_dispatched");}catch(RuntimeException e){diagnostic("search_handler_unavailable "+e.getClass().getSimpleName());}}
    }
    private void endAudio(long token){if(token!=epoch)return;Pipe p;synchronized(this){p=pipe;}if(p!=null)p.end();wire.control(0x10023,new byte[0]);}
    private void finish(long token,boolean unused,String reason){
        if(token!=epoch)return;++epoch;active=false;Pipe p;synchronized(this){p=pipe;pipe=null;}if(p!=null)p.close();
        SpeechRecognizer old=recognizer;recognizer=null;
        if(old!=null){try{old.cancel();}catch(RuntimeException e){diagnostic("recognizer_cancel_failed "+e.getClass().getSimpleName());}try{old.destroy();}catch(RuntimeException e){diagnostic("recognizer_destroy_failed "+e.getClass().getSimpleName());}}
        wire.control(0x10023,new byte[0]);try{wire.control(0x10026,module(0));}catch(IOException ignored){}
        if(reason!=null)diagnostic(reason);
    }
    boolean active(){return active&&!closed;}
    void close(){closed=true;available=false;active=false;Handler h=main;if(h!=null)h.post(new Runnable(){public void run(){finish(epoch,false,null);}});Pipe p;synchronized(this){p=pipe;pipe=null;}if(p!=null)p.close();}
    private static byte[] module(int status)throws IOException {ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(MdMain.Proto.ints(1,1));NativeSessionProtocol.bytes(b,2,MdMain.Proto.ints(1,4,2,status));return b.toByteArray();}
    private static void diagnostic(String detail){MdMain.logFields("native_voice","state",detail);}
    private final class Pipe {
        final long token;final ParcelFileDescriptor read,write;final MicQueue chunks=new MicQueue();
        volatile boolean disposed;Thread writer;
        Pipe(long t)throws IOException{token=t;ParcelFileDescriptor[] p=ParcelFileDescriptor.createPipe();read=p[0];write=p[1];}
        boolean offer(byte[] p){return chunks.offer(p);}
        void start(){writer=new Thread(new Runnable(){public void run(){try(OutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(write)){while(!disposed){byte[] b=chunks.poll();if(b!=null){out.write(b);}else if(chunks.ended())break;}}catch(Exception e){if(!disposed)main.post(new Runnable(){public void run(){finish(token,false,"mic_pipe_closed");}});}}},"carlife-hu-mic");writer.setDaemon(true);writer.start();}
        void end(){chunks.end();}
        void close(){disposed=true;chunks.close();try{read.close();}catch(IOException ignored){}try{write.close();}catch(IOException ignored){}if(writer!=null)writer.interrupt();}
    }
    /** At most one second of PCM; USB ingestion never waits on the recognizer pipe. */
    static final class MicQueue {
        private final ArrayBlockingQueue<byte[]> chunks=new ArrayBlockingQueue<byte[]>(64);
        private int queued;private boolean ended,closed;
        synchronized boolean offer(byte[] p){if(ended||closed)return true;if(p==null||p.length==0)return true;if((p.length&1)!=0||p.length>32000||queued+p.length>32000)return false;byte[] copy=p.clone();if(!chunks.offer(copy))return false;queued+=copy.length;return true;}
        byte[] poll()throws InterruptedException{byte[] b=chunks.poll(200,java.util.concurrent.TimeUnit.MILLISECONDS);if(b!=null)synchronized(this){queued=Math.max(0,queued-b.length);}return b;}
        synchronized boolean ended(){return ended;}
        synchronized void end(){ended=true;}
        synchronized void close(){closed=true;ended=true;chunks.clear();queued=0;}
        synchronized int queuedBytes(){return queued;}
    }

}
