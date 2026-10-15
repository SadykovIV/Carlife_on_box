package org.carlife.hulab;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit maneuver text only. No inference from notification category or package alone. */
final class YandexNotificationRoute {
    static final class Turn {
        final int nextTurn,meters;final String instruction;
        Turn(int nextTurn,int meters,String instruction){this.nextTurn=nextTurn;this.meters=meters;this.instruction=instruction;}
    }
    private static final Pattern DISTANCE=Pattern.compile("(?<![\\d.,])(\\d{1,5}(?:[.,]\\d{1,2})?)\\s*(км|km|метров|метра|метр|meters|metres|м|m)(?![\\p{L}])");
    static boolean supports(String pkg){return "ru.yandex.yandexnavi".equals(pkg)||"ru.yandex.yandexmaps".equals(pkg);}
    static Turn parse(String pkg,String title,String text,String bigText){
        if(!supports(pkg))return null;
        String[] parts={safe(title),safe(text),safe(bigText)};
        // Do not mix a distance from a different notification line with a maneuver.
        for(String part:parts){
            String s=part.toLowerCase(Locale.ROOT);if(s.length()>2048)s=s.substring(0,2048);
            Matcher m=DISTANCE.matcher(s);if(!m.find())continue;
            int direction=direction(s);if(direction==0)continue;
            double value=Double.parseDouble(m.group(1).replace(',','.'));if(m.group(2).equals("км")||m.group(2).equals("km"))value*=1000;
            if(value<0||value>100000)continue;
            return new Turn(direction,(int)Math.round(value),part.length()>256?part.substring(0,256):part);
        }
        return null;
    }
    static int direction(String s){
        // CarLife RouteGuideParams primary Baidu APK: straight=1, right=3, U-turn=5, left=7.
        boolean u=s.contains("↶")||s.contains("↷")||s.contains("развернит")||s.contains("разворот")||s.contains("u-turn");
        boolean left=s.contains("←")||s.contains("↰")||s.contains("налево")||s.contains("turn left");
        boolean right=s.contains("→")||s.contains("↱")||s.contains("направо")||s.contains("turn right");
        boolean front=s.contains("↑")||s.contains("прямо")||s.contains("continue straight");
        // Multiple explicit instructions/complex maneuvers are not guessed.
        if((u?1:0)+(left?1:0)+(right?1:0)+(front?1:0)!=1)return 0;
        if(s.contains("slight")||s.contains("резко")||s.contains("плавно")||s.contains("съезд")||s.contains("exit")||s.contains("круг"))return 0;
        return u?5:left?7:right?3:1;
    }
    private static String safe(String s){return s==null?"":s;}
}
