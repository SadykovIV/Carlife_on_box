package org.carlife.hulab;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A maneuver must come from the app's notification, never from its package/category alone. */
final class YandexNotificationRoute {
    static final class Turn {
        final int nextTurn,meters;final String instruction,source;final byte[] icon;
        Turn(int nextTurn,int meters,String instruction){this(nextTurn,meters,instruction,"",new byte[0]);}
        private Turn(int nextTurn,int meters,String instruction,String source,byte[] icon){this.nextTurn=nextTurn;this.meters=meters;this.instruction=instruction;this.source=source;this.icon=icon==null||icon.length>30000?new byte[0]:Arrays.copyOf(icon,icon.length);}
        Turn withIcon(byte[] value){return new Turn(nextTurn,meters,instruction,source,value);}
        Turn withSource(String value){return new Turn(nextTurn,meters,instruction,value,icon);}
        String maneuverKey(){return source+"|"+nextTurn+"|"+DISTANCE.matcher(normalize(instruction)).replaceAll("#");}
    }
    private static final Pattern DISTANCE=Pattern.compile("(?<![\\d.,])(\\d{1,5}(?:[.,]\\d{1,2})?)\\s*(км|km|метров|метра|метр|meters|metres|м|m)(?![\\p{L}])");
    private static final Pattern DISTANCE_ONLY=Pattern.compile("^(?:(?:через|in)\\s+)?(?:\\d{1,5}(?:[.,]\\d{1,2})?)\\s*(?:км|km|метров|метра|метр|meters|metres|м|m)[.!]?$",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    static boolean supports(String pkg){return "ru.yandex.yandexnavi".equals(pkg)||"ru.yandex.yandexmaps".equals(pkg);}
    static Turn parse(String pkg,String title,String text,String bigText){return parse(pkg,title,text,bigText,false);}
    static Turn parse(String pkg,String title,String text,String bigText,boolean navigationNotification){
        if(!supports(pkg))return null;
        String[] parts={safe(title),safe(text),safe(bigText)};
        for(String part:parts){Turn t=single(part);if(t!=null)return t;}
        // Navi 31.1.3 GuidanceNotificationBuilderImpl.build fills title/text from ONE
        // hhk0 navigation model. Only a standalone distance may cross that field boundary;
        // never combine ETA/route summaries, separate notifications, or arbitrary bigText.
        if(navigationNotification){
            String a=normalize(title),b=normalize(text);
            if(DISTANCE_ONLY.matcher(a).matches()&&!DISTANCE.matcher(b).find())return single(parts[0]+" "+parts[1]);
            if(DISTANCE_ONLY.matcher(b).matches()&&!DISTANCE.matcher(a).find())return single(parts[1]+" "+parts[0]);
        }
        return null;
    }
    static Turn fromResource(String pkg,String resource,String title,String text){
        if(!supports(pkg))return null;int turn=resourceDirection(resource);if(turn==0)return null;
        for(String part:new String[]{safe(title),safe(text)}){
            String s=normalize(part);if(!DISTANCE_ONLY.matcher(s).matches())continue;
            Matcher m=DISTANCE.matcher(s);if(!m.find())continue;
            double meters=Double.parseDouble(m.group(1).replace(',','.'));if(m.group(2).equals("км")||m.group(2).equals("km"))meters*=1000;
            if(meters<=100000)return new Turn(turn,(int)Math.round(meters),resource+"|"+safe(title)+"|"+safe(text));
        }
        return null;
    }
    static int resourceDirection(String name){
        // Actual Navi 31.1.3 notification drawables. IDs change between builds;
        // resolve names inside the source package, never hard-code numeric IDs.
        if(name==null)return 0;
        switch(name){
            case "notification_straight_sdl":return 1;
            case "notification_slight_right_sdl":return 2;
            case "notification_right_sdl":return 3;
            case "notification_hard_right_sdl":return 4;
            case "notification_uturn_left_sdl":case "notification_uturn_right_sdl":return 5;
            case "notification_hard_left_sdl":return 6;
            case "notification_left_sdl":return 7;
            case "notification_slight_left_sdl":return 8;
            case "notification_enter_roundabout_sdl":return 9;
            case "notification_leave_roundabout_sdl":return 10;
            case "notification_exit_left_sdl":return 11;
            case "notification_exit_right_sdl":return 12;
            case "notification_fork_left_sdl":return 20;
            case "notification_fork_right_sdl":return 21;
            default:return 0;
        }
    }
    private static Turn single(String part){
        String s=normalize(part);Matcher m=DISTANCE.matcher(s);if(!m.find())return null;
        String amount=m.group(1),unit=m.group(2);if(m.find())return null;
        int direction=direction(s);if(direction==0)return null;
        double value=Double.parseDouble(amount.replace(',','.'));if(unit.equals("км")||unit.equals("km"))value*=1000;
        if(value<0||value>100000)return null;
        return new Turn(direction,(int)Math.round(value),part.length()>256?part.substring(0,256):part);
    }
    static int direction(String value){
        String s=" "+normalize(value);
        if(has(s,"затем","после этого"," then "))return 0;
        // Primary Baidu RouteGuideParams.gTurnIconName (8.9.5): 1..21. Same mapping
        // used by HeadSwap AaMeta; do not turn an unknown instruction into straight.
        boolean left=has(s,"←","↰","↖","↙","налево","левее","слева","влево"," left");
        boolean right=has(s,"→","↱","↗","↘","направо","правее","справа","вправо"," right");
        if(left&&right)return 0;
        if(has(s,"кругов","кольц","roundabout"))return has(s,"съез","съед","покин","exit","leave")?10:9;
        if(has(s,"↶","↷","развернит","разворот","u-turn","u turn"))return 5;
        if(left||right){
            if(has(s,"держит","keep ","stay ","придерж"))return left?14:16;
            if(has(s,"съезд","съезж","съед","off-ramp","off ramp","exit ","take the exit"))return left?11:12;
            if(has(s,"въезд","въезж","on-ramp","on ramp","take the ramp"))return left?18:19;
            if(has(s,"развил","fork"))return left?20:21;
            if(has(s,"слиян","merge"))return left?13:15;
            if(has(s,"резко","крут","sharp","↙","↘"))return left?6:4;
            if(has(s,"плавно","слегка","левее","правее","slight","↖","↗"))return left?8:2;
            if(has(s,"налево","направо","turn left","turn right","поверн","поворот","←","→","↰","↱"))return left?7:3;
            return 0;
        }
        return has(s,"↑","прямо","continue straight","go straight","straight ahead")?1:0;
    }
    private static boolean has(String s,String... words){for(String w:words)if(s.contains(w))return true;return false;}
    private static String normalize(String s){s=safe(s);if(s.length()>2048)s=s.substring(0,2048);return (s.replace('\u00a0',' ').replace('\u202f',' ').trim().toLowerCase(Locale.ROOT));}
    private static String safe(String s){return s==null?"":s;}
}
