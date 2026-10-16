package org.carlife.hulab;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.widget.RemoteViews;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Reads app-provided notification data; optional RemoteViews details never gate text input. */
final class YandexNotificationData {
    static YandexNotificationRoute.Turn read(Context context,String pkg,Notification notification){
        if(notification==null||!YandexNotificationRoute.supports(pkg))return null;
        Bundle b=notification.extras;
        boolean navigation="navigation".equals(notification.category);
        YandexNotificationRoute.Turn turn=YandexNotificationRoute.parse(pkg,text(b,Notification.EXTRA_TITLE),text(b,Notification.EXTRA_TEXT),text(b,Notification.EXTRA_BIG_TEXT),navigation);
        if(navigation&&context!=null){
            try{
                Context app=context.createPackageContext(pkg,0);Resources res=app.getResources();
                // This is the exact automotive guidance layout emitted by Navi 31.1.3.
                // Resource-name resolution also works when an app update renumbers IDs.
                for(Object node:viewTree(notification.contentView,notification.bigContentView,notification.headsUpContentView)){
                    if(!(node instanceof RemoteViews))continue;RemoteViews views=(RemoteViews)node;
                    if(!pkg.equals(views.getPackage()))continue;
                    Object actions=field(views,"mActions");if(!(actions instanceof Iterable))continue;
                    String title=null,description=null,iconName=null;int iconId=0;
                    for(Object action:(Iterable<?>)actions){
                        Object id=field(action,"mViewId","viewId"),method=field(action,"mMethodName","methodName"),value=field(action,"mValue","value");
                        if(!(id instanceof Integer)||!(method instanceof String))continue;
                        String name;try{name=res.getResourceEntryName((Integer)id);}catch(Resources.NotFoundException absent){continue;}
                        if("setText".equals(method)&&value instanceof CharSequence){if("titleView".equals(name))title=value.toString();else if("descriptionView".equals(name))description=value.toString();}
                        if(("primaryIcon".equals(name)||"primaryIconTinted".equals(name))&&"setImageResource".equals(method)&&value instanceof Integer){
                            String candidate;try{candidate=res.getResourceEntryName((Integer)value);}catch(Resources.NotFoundException clearedOrAbsent){continue;}
                            if(YandexNotificationRoute.resourceDirection(candidate)!=0){iconName=candidate;iconId=(Integer)value;}
                        }
                    }
                    YandexNotificationRoute.Turn custom=YandexNotificationRoute.parse(pkg,title,description,null,true);
                    if(custom==null)custom=YandexNotificationRoute.fromResource(pkg,iconName,title,description);
                    if(custom!=null)turn=custom;
                    if(turn!=null&&iconId!=0&&YandexNotificationRoute.resourceDirection(iconName)==turn.nextTurn){
                        Drawable drawable=res.getDrawable(iconId,app.getTheme());return turn.withIcon(NativeTurnIcon.drawable(drawable));
                    }
                }
            }catch(Exception unsupported){/* A future app layout/reflection change retains explicit text support. */}
        }
        return turn==null?null:turn.withIcon(NativeTurnIcon.arrow(turn.nextTurn));
    }
    static PendingIntent stopAction(Context context,String pkg,Notification notification){
        if(context==null||notification==null||!YandexNotificationRoute.supports(pkg)||!"navigation".equals(notification.category))return null;
        if(notification.actions!=null)for(Notification.Action action:notification.actions){PendingIntent found=verifiedStop(pkg,action.actionIntent);if(found!=null)return found;}
        try{
            Resources resources=context.createPackageContext(pkg,0).getResources();
            for(Object node:viewTree(notification.bigContentView,notification.contentView,notification.headsUpContentView)){
                if(!(node instanceof RemoteViews))continue;RemoteViews views=(RemoteViews)node;
                if(!pkg.equals(views.getPackage()))continue;Object actions=field(views,"mActions");if(!(actions instanceof Iterable))continue;
                for(Object action:(Iterable<?>)actions){
                    Object id=field(action,"mViewId","viewId");if(!(id instanceof Integer))continue;
                    String name;try{name=resources.getResourceEntryName((Integer)id);}catch(Resources.NotFoundException absent){continue;}
                    if(!"actionButton".equals(name))continue;
                    Object response=field(action,"mResponse","response"),pending=field(response,"mPendingIntent","pendingIntent");
                    if(pending==null)pending=field(action,"pendingIntent","mPendingIntent");
                    if(pending instanceof PendingIntent){PendingIntent found=verifiedStop(pkg,(PendingIntent)pending);if(found!=null)return found;}
                }
            }
        }catch(Exception unavailable){/* Unsupported optional action remains unavailable, no guessed broadcast. */}
        return null;
    }
    private static PendingIntent verifiedStop(String pkg,PendingIntent pending){
        if(pending==null||!pkg.equals(pending.getCreatorPackage()))return null;
        try{Intent intent=(Intent)PendingIntent.class.getMethod("getIntent").invoke(pending);
            return intent!=null&&"GuidanceStopRouting".equals(intent.getAction())&&pkg.equals(intent.getPackage())?pending:null;
        }catch(ReflectiveOperationException|RuntimeException unsupported){return null;}
    }
    private static String text(Bundle b,String key){if(b==null)return null;CharSequence value=b.getCharSequence(key);return value==null?null:value.toString();}
    // Android 15 decorates custom notifications with its system template. The
    // app's real primaryIcon/actionButton live in ViewGroupActionAdd.mNestedViews.
    // Inspect data only: never apply RemoteViews or execute their actions here.
    static List<Object> viewTree(Object... roots){
        ArrayDeque<Object> pending=new ArrayDeque<Object>();List<Object> result=new ArrayList<Object>();
        Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        for(Object root:roots)if(root!=null)pending.add(root);
        while(!pending.isEmpty()&&result.size()<64){
            Object view=pending.remove();if(!seen.add(view))continue;result.add(view);
            for(String name:new String[]{"mLandscape","mPortrait"}){Object child=field(view,name);if(child!=null)pending.add(child);}
            Object actions=field(view,"mActions");if(!(actions instanceof Iterable))continue;
            int count=0;for(Object action:(Iterable<?>)actions){if(++count>1024)break;Object child=field(action,"mNestedViews","nestedViews");if(child!=null)pending.add(child);}
        }
        return result;
    }
    // Android 15 uses mViewId/mMethodName/mValue; older releases used unprefixed
    // names. Walk parent action classes (AOSP RemoteViews.BaseReflectionAction).
    static Object field(Object object,String... names){
        if(object==null)return null;
        for(String name:names)for(Class<?> c=object.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(ReflectiveOperationException|SecurityException ignored){}
        return null;
    }
}
