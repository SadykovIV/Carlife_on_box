import java.io.*;
import java.util.*;
import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.*;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

/** Wrap pinned OEM methods; preserve every original instruction in a private copy. */
public final class PatchDex {
    static final String UI = "Lorg/carlife/selector/OemSelectorUi;";
    static ImmutableMethodReference ref(String c, String n, String r, String... p) {
        return new ImmutableMethodReference(c, n, Arrays.asList(p), r);
    }
    static Instruction call(Opcode op, ImmutableMethodReference ref, int... regs) {
        int[] v = new int[5]; System.arraycopy(regs, 0, v, 0, regs.length);
        return new ImmutableInstruction35c(op, regs.length, v[0], v[1], v[2], v[3], v[4], ref);
    }
    static Method renamed(Method m) {
        return new ImmutableMethod(m.getDefiningClass(), m.getName()+"$CarLifeOriginal", m.getParameters(), m.getReturnType(),
            (m.getAccessFlags() & ~5) | 2, m.getAnnotations(), m.getHiddenApiRestrictions(), m.getImplementation());
    }
    static Method wrapper(Method m, String kind) {
        List<Instruction> i = new ArrayList<>(); int regs = 3;
        ImmutableMethodReference original = new ImmutableMethodReference(m.getDefiningClass(), m.getName()+"$CarLifeOriginal", m.getParameterTypes(), m.getReturnType());
        if (kind.equals("click")) {
            i.add(call(Opcode.INVOKE_STATIC, ref(UI,"onItemClick","Z","Ljava/lang/Object;","Landroid/view/View;"),1,2)); //0..2
            i.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT,0)); //3
            i.add(new ImmutableInstruction21t(Opcode.IF_EQZ,0,3)); //4 ->7
            i.add(new ImmutableInstruction10x(Opcode.RETURN_VOID)); //6
            i.add(call(Opcode.INVOKE_DIRECT,original,1,2)); //7
            i.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
        } else if (kind.equals("create")) {
            i.add(call(Opcode.INVOKE_STATIC, ref(UI,"handles","Z","Landroid/app/Activity;"),1)); //0
            i.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT,0)); //3
            i.add(new ImmutableInstruction21t(Opcode.IF_EQZ,0,9)); //4 ->13
            i.add(call(Opcode.INVOKE_SUPER, ref("Landroid/app/Activity;","onCreate","V","Landroid/os/Bundle;"),1,2)); //6
            i.add(call(Opcode.INVOKE_STATIC, ref(UI,"create","V","Landroid/app/Activity;"),1)); //9
            i.add(new ImmutableInstruction10x(Opcode.RETURN_VOID)); //12
            i.add(call(Opcode.INVOKE_DIRECT,original,1,2)); //13
            i.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
        } else if (kind.equals("fragment")) {
            // Actual CLYNewActivity tab2 uses this Fragment, not the legacy
            // OriginalVehicleSetActivity. Keep its original rows and adapter;
            // append the native selector row to that same list after init.
            regs=7; // p0=v6; v0..v5 are locals.
            String fragment=m.getDefiningClass(), helper="Lcom/suding/settings/SettingsItemHelp;";
            String adapter="Lcom/suding/settings/RecyclerAdapter;", data="Lcom/suding/settings/RecyclerData;";
            i.add(call(Opcode.INVOKE_DIRECT,original,6));
            i.add(call(Opcode.INVOKE_STATIC,ref(helper,"getInstance",helper)));
            i.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT,0));
            i.add(new ImmutableInstruction21s(Opcode.CONST_16,1,56));
            i.add(new ImmutableInstruction11n(Opcode.CONST_4,2,2));
            i.add(call(Opcode.INVOKE_VIRTUAL,ref(fragment,"getResources","Landroid/content/res/Resources;"),6));
            i.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT,3));
            i.add(new ImmutableInstruction31i(Opcode.CONST,4,0x7f0f0114));
            i.add(call(Opcode.INVOKE_VIRTUAL,ref("Landroid/content/res/Resources;","getString","Ljava/lang/String;","I"),3,4));
            i.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT,3));
            i.add(call(Opcode.INVOKE_VIRTUAL,ref(helper,"createRecyclerData",data,"I","I","Ljava/lang/String;"),0,1,2,3));
            i.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT,4));
            i.add(new ImmutableInstruction22c(Opcode.IGET_OBJECT,5,6,new ImmutableFieldReference(fragment,"mRecyclerDatas","Ljava/util/List;")));
            i.add(call(Opcode.INVOKE_INTERFACE,ref("Ljava/util/List;","add","Z","Ljava/lang/Object;"),5,4));
            i.add(new ImmutableInstruction22c(Opcode.IGET_OBJECT,0,6,new ImmutableFieldReference(fragment,"mRecyclerAdapter",adapter)));
            i.add(call(Opcode.INVOKE_VIRTUAL,ref(adapter,"notifyDataSetChanged","V"),0));
            i.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
        } else {
            // Full 8-argument createRecyclerData overload, p0..p8=v1..v9.
            regs=10;
            // ID56 is already requested by OriginalVehicleSetActivity. Its old
            // customer predicate hides this row on the actual box. Construct
            // only this row exactly like the original common prologue; every
            // other ID retains the unmodified OEM implementation.
            List<Instruction> row = new ArrayList<>();
            String data = m.getReturnType();
            row.add(new ImmutableInstruction21c(Opcode.NEW_INSTANCE,0,new ImmutableTypeReference(data)));
            row.add(call(Opcode.INVOKE_DIRECT,ref(data,"<init>","V"),0));
            String[] names={"id","itemtype","itemTitle","itemIcon","itemMessage","itemClickDialogTitle","itemClickDialogMessage","layoutView"};
            String[] types={"I","I","Ljava/lang/String;","I","Ljava/lang/String;","Ljava/lang/String;","Ljava/lang/String;","Landroid/view/View;"};
            for(int n=0;n<names.length;n++) row.add(new ImmutableInstruction22c(
                types[n].equals("I")?Opcode.IPUT:Opcode.IPUT_OBJECT,n+2,0,new ImmutableFieldReference(data,names[n],types[n])));
            row.add(call(Opcode.INVOKE_STATIC, ref(UI,"decorate","Ljava/lang/Object;","Ljava/lang/Object;","I"),0,2));
            row.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT,0));
            row.add(new ImmutableInstruction21c(Opcode.CHECK_CAST,0,new ImmutableTypeReference(data)));
            row.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT,0));
            i.add(new ImmutableInstruction21s(Opcode.CONST_16,0,56));
            int rowUnits=0; for(Instruction ins:row) rowUnits+=ins.getCodeUnits();
            i.add(new ImmutableInstruction22t(Opcode.IF_NE,2,0,2+rowUnits));
            i.addAll(row);
            i.add(new ImmutableInstruction3rc(Opcode.INVOKE_DIRECT_RANGE,1,9,original));
            i.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT,0));
            i.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT,0));
        }
        return new ImmutableMethod(m.getDefiningClass(),m.getName(),m.getParameters(),m.getReturnType(),m.getAccessFlags(),
            m.getAnnotations(),m.getHiddenApiRestrictions(),new ImmutableMethodImplementation(regs,i,Collections.emptyList(),Collections.emptyList()));
    }
    public static void main(String[] a) throws Exception {
        if (a.length != 3) throw new IllegalArgumentException("input output expected-count");
        DexFile dex = DexFileFactory.loadDexFile(new File(a[0]), Opcodes.forApi(29));
        List<ClassDef> classes=new ArrayList<>(); int changed=0;
        for (ClassDef c:dex.getClasses()) {
            List<Method> methods=new ArrayList<>();
            for(Method m:c.getMethods()) {
                String kind=null;
                if(c.getType().equals("Lcom/suding/settings/SettingsItemHelp;") && m.getName().equals("onItemClick") && m.getParameterTypes().equals(Arrays.asList("Landroid/view/View;"))) kind="click";
                if(c.getType().equals("Lcom/suding/settings/SettingsItemHelp;") && m.getName().equals("createRecyclerData") && m.getParameterTypes().size()==8) kind="row";
                if(c.getType().equals("Lcom/suding/settings/fragment/SettingsOriginalFragment;") && m.getName().equals("initView") && m.getParameterTypes().isEmpty()) kind="fragment";
                if(c.getType().equals("Lcom/carsyso/main/ui/activity/RevertConnectTypeSwitchActivity;") && m.getName().equals("onCreate") && m.getParameterTypes().equals(Arrays.asList("Landroid/os/Bundle;"))) kind="create";
                if(kind==null) methods.add(m); else { methods.add(renamed(m)); methods.add(wrapper(m,kind)); changed++; System.out.println("WRAP "+m+" "+kind); }
            }
            classes.add(new ImmutableClassDef(c.getType(),c.getAccessFlags(),c.getSuperclass(),c.getInterfaces(),c.getSourceFile(),c.getAnnotations(),c.getFields(),methods));
        }
        if(changed!=Integer.parseInt(a[2])) throw new IllegalStateException("Unexpected patch count: "+changed);
        DexPool.writeTo(a[1],new ImmutableDexFile(dex.getOpcodes(),classes));
    }
}
