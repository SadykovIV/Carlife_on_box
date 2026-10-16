package org.carlife.hulab;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import java.io.ByteArrayOutputStream;

/** A real app maneuver drawable or a semantic arrow, never a notification/app logo. */
final class NativeTurnIcon {
    private static final byte[][] arrows=new byte[25][];
    static byte[] drawable(Drawable drawable){
        if(drawable==null)return new byte[0];Bitmap bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
        Rect old=new Rect(drawable.getBounds());try{drawable.setBounds(8,8,120,120);drawable.draw(new Canvas(bitmap));return png(bitmap);}
        finally{drawable.setBounds(old);bitmap.recycle();}
    }
    static synchronized byte[] arrow(int turn){
        if(turn<1||turn>=arrows.length)return new byte[0];if(arrows[turn]!=null)return arrows[turn];
        Bitmap bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
        try{Canvas canvas=new Canvas(bitmap);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.WHITE);paint.setStrokeWidth(9);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStyle(Paint.Style.STROKE);
            boolean left=turn==6||turn==7||turn==8||turn==11||turn==13||turn==14||turn==18||turn==20;
            if(left)canvas.scale(-1,1,64,64);
            Path path=new Path();float x=64,y=20,dx=0,dy=-1;path.moveTo(64,110);
            if(turn==5){path.lineTo(64,42);path.cubicTo(64,7,108,7,108,42);path.lineTo(108,77);x=108;y=77;dy=1;}
            else if(turn==9||turn==10){path.lineTo(64,85);path.cubicTo(22,83,22,35,64,35);path.cubicTo(105,35,104,85,64,85);path.moveTo(64,35);if(turn==10){path.lineTo(110,35);x=110;y=35;dx=1;dy=0;}else path.lineTo(64,15);}
            else if(turn==4||turn==6){path.lineTo(64,36);path.lineTo(103,79);x=103;y=79;dx=.67f;dy=.74f;}
            else if(turn==2||turn==8||turn==11||turn==12||turn==13||turn==14||turn==15||turn==16||turn==18||turn==19||turn==20||turn==21){path.lineTo(64,66);path.lineTo(104,24);x=104;y=24;dx=.69f;dy=-.72f;}
            else if(turn==3||turn==7){path.lineTo(64,47);path.lineTo(111,47);x=111;y=47;dx=1;dy=0;}
            else path.lineTo(64,20);
            canvas.drawPath(path,paint);Path head=new Path();head.moveTo(x-dx*19-dy*13,y-dy*19+dx*13);head.lineTo(x,y);head.lineTo(x-dx*19+dy*13,y-dy*19-dx*13);canvas.drawPath(head,paint);
            return arrows[turn]=png(bitmap);
        }finally{bitmap.recycle();}
    }
    private static byte[] png(Bitmap bitmap){ByteArrayOutputStream out=new ByteArrayOutputStream();return bitmap.compress(Bitmap.CompressFormat.PNG,100,out)&&out.size()<=30000?out.toByteArray():new byte[0];}
}
