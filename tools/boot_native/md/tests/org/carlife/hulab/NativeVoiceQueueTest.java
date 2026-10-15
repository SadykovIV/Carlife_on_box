package org.carlife.hulab;
public final class NativeVoiceQueueTest {
    static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception {
        NativeVoice.MicQueue q=new NativeVoice.MicQueue();byte[] pcm=new byte[]{1,2,3,4};check(q.offer(pcm),"PCM accepted");pcm[0]=99;check(q.poll()[0]==1,"USB buffer not retained");check(q.queuedBytes()==0,"drain accounted");check(!q.offer(new byte[3]),"unaligned PCM rejected");check(!q.offer(new byte[32002]),"oversized chunk rejected");
        for(int i=0;i<50;i++)check(q.offer(new byte[640]),"one second standard PCM packets");check(q.queuedBytes()==32000,"exact one-second bound");long t=System.nanoTime();check(!q.offer(new byte[2]),"full queue signals cancellation");check(System.nanoTime()-t<100000000L,"ingress does not block");check(q.poll().length==640&&q.offer(new byte[640]),"capacity restored by actual drain");q.end();check(q.offer(new byte[4])&&q.queuedBytes()==32000,"late microphone packet after end discarded");
        q.close();check(q.ended()&&q.queuedBytes()==0,"close drops all pending PCM");check(q.offer(new byte[10])&&q.queuedBytes()==0,"closed callback cannot retain audio");check(q.poll()==null,"closed queue empty");
        System.out.println("NativeVoiceQueueTest PASS "+checks+" checks; recognizer/provider acceptance requires Android device");
    }
}
