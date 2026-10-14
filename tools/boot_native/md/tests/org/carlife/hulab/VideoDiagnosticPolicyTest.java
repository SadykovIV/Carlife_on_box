package org.carlife.hulab;
/** Exercises the actual proposed policy methods; no Android runtime or device. */
public final class VideoDiagnosticPolicyTest {
    static int n;
    static void ok(boolean b){n++;if(!b)throw new AssertionError("case "+n);}
    public static void main(String[] args){
        ok(MediaCodecDisplaySource.fullVideoTraceDefault);
        MediaCodecDisplaySource.configureVideoDiagnostics(false);
        ok(!MediaCodecDisplaySource.fullVideoTraceDefault);
        for(int i=1;i<=3;i++)ok(MediaCodecDisplaySource.retainAuDiagnostic(false,i,false));
        ok(!MediaCodecDisplaySource.retainAuDiagnostic(false,4,false));
        ok(!MediaCodecDisplaySource.retainAuDiagnostic(false,30000,false));
        ok(MediaCodecDisplaySource.retainAuDiagnostic(false,30000,true));
        ok(MediaCodecDisplaySource.retainAuDiagnostic(true,30000,false));
        for(int i=1;i<=3;i++)ok(MediaCodecDisplaySource.retainBufferDiagnostic(false,i,0));
        ok(!MediaCodecDisplaySource.retainBufferDiagnostic(false,4,0));
        ok(!MediaCodecDisplaySource.retainBufferDiagnostic(false,4,1));
        ok(MediaCodecDisplaySource.retainBufferDiagnostic(false,4,2));
        ok(MediaCodecDisplaySource.retainBufferDiagnostic(false,4,4));
        ok(MediaCodecDisplaySource.retainBufferDiagnostic(true,4,0));
        MediaCodecDisplaySource.configureVideoDiagnostics(true);
        ok(MediaCodecDisplaySource.fullVideoTraceDefault);
        System.out.println("VideoDiagnosticPolicyTest PASS "+n);
    }
}
