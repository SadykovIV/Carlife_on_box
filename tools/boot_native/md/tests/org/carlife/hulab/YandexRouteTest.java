package org.carlife.hulab;

import java.util.Map;

/** Pure parsing/lifecycle regressions, not a claim that an app emitted a fixture. */
public final class YandexRouteTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static YandexNotificationRoute.Turn parse(String value){return YandexNotificationRoute.parse("ru.yandex.yandexnavi",value,null,null);}
    private static int field(NativeServiceState.Output output,int field)throws Exception {check(output!=null&&output.id==NativeServiceState.TURN,"navigation output");return MdMain.Proto.number(MdMain.Proto.parse(output.payload),field,-1);}
    private static class Action15 {private int mViewId=73;}
    private static final class Reflection15 extends Action15 {private String mMethodName="setImageResource";private int mValue=19;}
    private static final class ReflectionOld {private int viewId=31;private String methodName="setText";private String value="20 м";}
    private static final class ViewTree {private java.util.List<Object> mActions=new java.util.ArrayList<Object>();}
    private static final class NestedAction {private Object mNestedViews;NestedAction(Object value){mNestedViews=value;}}
    public static void main(String[] args)throws Exception {
        ViewTree template=new ViewTree(),custom=new ViewTree();template.mActions.add(new NestedAction(custom));custom.mActions.add(new NestedAction(template));
        java.util.List<Object> views=YandexNotificationData.viewTree(template,null,template);
        check(views.size()==2&&views.get(1)==custom,"observed Android template wrapper exposes nested app views once; cycles bounded");
        check(YandexNotificationData.field(new Reflection15(),"mViewId","viewId").equals(73),"Android 15 action superclass field");
        check(YandexNotificationData.field(new ReflectionOld(),"mValue","value").equals("20 м"),"older action field fallback");
        check(YandexNotificationData.field(new Object(),"mValue","value")==null,"unknown private shape is optional");
        String[] text={"Continue straight in 500 m","Slight right in 500 m","Turn right in 500 m","Sharp right in 500 m","Make a U-turn in 500 m","Резко налево через 500 м","Поверните налево через 500 м","Плавно налево через 500 м","Через 500 м въезд на круговое движение","Через 500 м покиньте круговое движение","Через 500 м съезд налево","Через 500 м съезд направо","Merge left in 500 m","Keep left in 500 m","Merge right in 500 m","Keep right in 500 m","Take the ramp left in 500 m","Take the ramp right in 500 m","Fork left in 500 m","Fork right in 500 m"};
        int[] codes={1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,18,19,20,21};
        for(int i=0;i<text.length;i++){YandexNotificationRoute.Turn t=parse(text[i]);check(t!=null&&t.nextTurn==codes[i]&&t.meters==500,"explicit maneuver "+text[i]);}
        check(YandexNotificationRoute.parse("ru.yandex.yandexnavi","300 м","Поверните направо",null,true).nextTurn==3,"same navigation model split title/text");
        // Actual background-guidance notification on the box after owner-authorized
        // background location: navigation/category, title=20 м, text=Налево.
        YandexNotificationRoute.Turn observed=YandexNotificationRoute.parse("ru.yandex.yandexnavi","20 м","Налево",null,true);
        check(observed!=null&&observed.nextTurn==7&&observed.meters==20,"observed Navi 31.1.3 background payload");
        check(YandexNotificationRoute.parse("ru.yandex.yandexmaps","Поверните направо","1,2 км",null,true).meters==1200,"reverse field order");
        check(YandexNotificationRoute.parse("ru.yandex.yandexnavi","300 м","Поверните направо",null,false)==null,"no generic notification mixing");
        check(YandexNotificationRoute.parse("ru.yandex.yandexnavi","Осталось 300 м","Поверните направо",null,true)==null,"route total cannot substitute maneuver distance");
        check(parse("Через 500 м направо, затем через 30 м налево")==null,"multiple maneuvers are not guessed");
        check(parse("Навигатор запущен")==null,"foreground service is not route data");
        check(parse("Через 1\u202fкм направо").meters==1000,"narrow no-break space");
        check(YandexNotificationRoute.fromResource("ru.yandex.yandexnavi","notification_hard_left_sdl","20 м","Улица").nextTurn==6,"actual drawable name maps without invented text");
        check(YandexNotificationRoute.fromResource("ru.yandex.yandexnavi","ic_launcher","20 м",null)==null,"app logo is not maneuver");
        check(YandexNotificationRoute.fromResource("ru.yandex.yandexnavi","notification_right_sdl","Осталось 20 м",null)==null,"drawable plus route summary not guessed");
        check(YandexNotificationRoute.fromResource("other.app","notification_right_sdl","20 м",null)==null,"resource adapter scoped to source");
        YandexNotificationRoute.Turn first=parse("Через 500 м поверните направо").withSource("routeA").withIcon(new byte[]{1,2,3});
        NativeServiceState state=new NativeServiceState();state.capabilities(false,true);state.command(0x18043,NativeServicesTest.subscription(0));state.poll();state.route(first);
        check(state.routeNaviWanted(),"Honda activity advertised before START");check(state.poll(0)==null,"TBT payload still requires START");
        state.command(0x18045,NativeServicesTest.subscription(0));NativeServiceState.Output show=state.poll(0);check(field(show,1)==1&&field(show,4)==500,"first SHOW initial maneuver distance");
        Map<Integer,Object> fields=MdMain.Proto.parse(show.payload);check(((byte[])fields.get(6)).length==3,"real icon passed to bytes schema");
        state.route(first);check(state.poll(1000)==null,"duplicate notification emits nothing");
        for(int distance=499;distance>=400;distance--)state.route(parse("Через "+distance+" м поверните направо").withSource("routeA").withIcon(new byte[]{1,2,3}));
        check(state.poll(500)==null,"distance updates limited to 1Hz");state.control(0x10024,new byte[0]);check(state.poll(500).id==0x10024,"route throttle never blocks microphone lifecycle");
        NativeServiceState.Output update=state.poll(1000);check(field(update,1)==2&&field(update,4)==500&&field(update,5)==400,"coalesced latest distance with initial maneuver total");
        state.route(parse("Через 100 м поверните налево").withSource("routeA"));check(field(state.poll(1001),4)==100,"new maneuver bypasses distance throttle");
        state.command(0x18046,NativeServicesTest.subscription(0));check(state.routeNaviWanted(),"subscription STOP does not end application route");check(state.poll(2000)==null,"STOP stops TBT");
        state.command(0x18045,NativeServicesTest.subscription(0));check(field(state.poll(2001),1)==1,"resubscription restores SHOW");
        state.route(null);check(!state.routeNaviWanted(),"route end always releases NAVI_RUNNING");check(field(state.poll(2002),1)==3,"HIDE not throttled");state.route(null);check(state.poll(4000)==null,"no duplicate HIDE");
        NativeServiceState reconnect=new NativeServiceState();reconnect.capabilities(false,true);reconnect.route(first);reconnect.command(0x18045,NativeServicesTest.subscription(0));check(field(reconnect.poll(0),1)==1,"new USB session replays snapshot as SHOW");
        StringBuilder unicode=new StringBuilder();for(int i=0;i<200;i++)unicode.append("Я😀");
        String limited=NativeServiceState.utf8Limit(unicode.toString(),255);
        check(limited.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=255&&!Character.isHighSurrogate(limited.charAt(limited.length()-1)),"Honda metadata buffer bounded on UTF8/codepoint boundary");
        check(NativeServiceState.utf8Limit("short title",255).equals("short title"),"ordinary title preserved");
        state.close();state.route(first);check(!state.routeNaviWanted()&&state.poll(9000)==null,"late callbacks cannot revive old route");
        System.out.println("YandexRouteTest PASS "+checks+" checks; synthetic regressions plus one observed Navi 31.1.3 payload; no claim of physical HU acceptance");
    }
}
