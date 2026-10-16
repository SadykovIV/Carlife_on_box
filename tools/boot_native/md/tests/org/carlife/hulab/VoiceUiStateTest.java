package org.carlife.hulab;

public final class VoiceUiStateTest {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        VoiceUiState s=new VoiceUiState();long a=s.begin(100,10060,10);
        check(a>0&&s.phase()==VoiceUiState.Phase.LAUNCHING,"new request admitted");
        check(s.begin(101,10060,10)==0,"duplicate START cannot launch another UI");
        check(s.target(a,10060,"ru.yandex.yandexnavi"),"target pinned to resolved application UID/package");
        check(!s.route(a,10060,10,"ru.yandex.yandexnavi",110),"old hotword route cannot assert listening before launch");
        check(!s.launched(a,120)&&s.phase()==VoiceUiState.Phase.WAIT_ROUTE,"successful no-op launch plus old recorder is insufficient");
        check(!s.route(a,10061,11,"ru.yandex.yandexnavi",130),"wrong UID rejected");
        check(!s.route(a,10060,11,"other.app",140),"wrong package rejected even at matching UID");
        check(!s.route(a,10060,0,"ru.yandex.yandexnavi",145),"invalid recorder session rejected");
        check(s.route(a,10060,11,"ru.yandex.yandexnavi",150),"new verified target recorder starts voice");
        check(s.phase()==VoiceUiState.Phase.RUNNING,"VR running phase");
        check(s.ownsRecorder(10060,11,"ru.yandex.yandexnavi")&&!s.ownsRecorder(10060,12,"ru.yandex.yandexnavi"),"return to a new hotword recorder cannot prolong the completed voice request");
        check(!s.route(a,10060,11,"ru.yandex.yandexnavi",160),"same route event does not emit repeated VR1");
        check(s.remaining(a,200)==44950,"running timeout starts at actual voice readiness");
        check(!s.expired(45149)&&s.expired(45150),"running timeout bounded exactly");
        check(s.finish(a)&&!s.current(a),"STOP invalidates generation");
        check(!s.finish(a),"repeated STOP idempotent");
        check(!s.launched(a,170)&&!s.route(a,10060,12,"ru.yandex.yandexnavi",180),"late launch/route callbacks cannot resurrect stopped voice");

        long b=s.begin(1000,-1,-1);check(b>a,"new session has new generation");
        check(s.target(b,10060,"ru.yandex.yandexnavi"),"second target");
        check(!s.route(b,10060,20,"ru.yandex.yandexnavi",1050),"route arriving during am wait remains pending");
        check(s.ownsPendingOrRunningRecorder(b,10060,20,"ru.yandex.yandexnavi"),"STOP owns new verified route even before am returns");
        check(!s.ownsPendingOrRunningRecorder(a,10060,20,"ru.yandex.yandexnavi"),"stale STOP cannot suppress later request recorder");
        check(!s.ownsPendingOrRunningRecorder(b,10070,20,"ru.yandex.yandexnavi"),"pending STOP preserves another UID");
        check(s.launched(b,1100),"am success can immediately consume already verified new route");
        check(!s.finish(a)&&s.current(b),"old timeout cannot stop later request");
        check(s.finish(b),"close cancels pending/active generation");

        long c=s.begin(2000,-1,-1);s.target(c,10060,"ru.yandex.yandexnavi");
        check(s.remaining(c,2000)==2800,"deadline includes Activity launch time");
        check(!s.launched(c,4799),"no route no VR1 even just before deadline");
        check(s.expired(4800),"start deadline under Honda's three-second gate");
        check(!s.route(c,10060,21,"ru.yandex.yandexnavi",4800),"late recorder cannot falsely acknowledge timed-out start");
        check(s.finish(c),"timed-out request retires");
        long d=s.begin(5000,10060,30);s.target(d,10062,"voice.app");s.route(d,10062,30,"voice.app",5050);
        check(s.launched(d,5100),"same numeric session on a different UID is a distinct recorder");
        check(s.matches(10062,"voice.app")&&!s.matches(10060,"voice.app"),"preemption identity includes target UID");
        s.finish(d);
        int[][] observed=new int[][]{{10060,40},{10060,41},{10070,50}};
        long e=s.begin(6000,observed);observed[0][1]=999;s.target(e,10060,"voice.app");s.launched(e,6010);
        check(!s.ownsPendingOrRunningRecorder(e,10060,40,"voice.app"),"pending STOP preserves preexisting hotword recorder");
        check(!s.route(e,10060,40,"voice.app",6020),"pre-existing unverified recorder cannot become a new session when injection verification completes");
        check(!s.route(e,10060,41,"voice.app",6030),"all prior target sessions retained, not only selected route");
        check(s.route(e,10060,42,"voice.app",6040),"genuinely new session still starts after immutable baseline snapshot");s.finish(e);
        System.out.println("VoiceUiStateTest PASS "+checks+" checks; actual assistant UI acceptance requires Android/HU");
    }
}
