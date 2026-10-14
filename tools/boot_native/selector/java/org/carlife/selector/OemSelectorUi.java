package org.carlife.selector;
import android.app.*;
import android.content.*;
import android.view.View;
import android.widget.TextView;
import java.lang.reflect.*;

/** Added inside the existing OEM APKs. Not a new launcher or exported component. */
public final class OemSelectorUi {
    private static final String[] MODES = {"android_auto", "carplay", "carlife"};
    private static final String[] LABELS = {"Android Auto", "CarPlay", "Baidu CarLife"};
    private static Object field(Object o, String key) throws Exception {
        Field f = o.getClass().getDeclaredField(key); f.setAccessible(true); return f.get(o);
    }
    public static boolean onItemClick(Object owner, View view) {
        if (view.getId() != 56) return false;
        try {
            final Activity a = (Activity) field(owner, "mActivity");
            new Thread(() -> {
                try {
                    final String selected = SelectorClient.request(null);
                    a.runOnUiThread(() -> {
                        if (a.isFinishing() || a.isDestroyed()) return;
                        int check = java.util.Arrays.asList(MODES).indexOf(selected);
                        new AlertDialog.Builder(a).setTitle("Протокол подключения")
                            .setSingleChoiceItems(LABELS, check, (dialog, which) -> {
                                dialog.dismiss();
                                Intent i = new Intent();
                                i.setClassName("com.carsyso.main", "com.carsyso.main.ui.activity.RevertConnectTypeSwitchActivity");
                                i.putExtra("direct_switch_type", which == 0 ? "2" : which == 1 ? "1" : "carlife");
                                i.putExtra("countdown", false);
                                a.startActivity(i);
                            }).setNegativeButton("Отмена", null).show();
                    });
                } catch (Exception e) {
                    android.util.Log.e("CarLifeSelector", "GET failed", e);
                    message(a, "Выбор недоступен", "Служба выбора не ответила или отклонила запрос. Настройки не изменены.");
                }
            }, "CarLifeSelectorRead").start();
        } catch (Exception e) { throw new IllegalStateException("Pinned OEM UI changed", e); }
        return true;
    }
    public static Object decorate(Object data, int id) {
        if (id == 56 && data != null) try {
            Field f = data.getClass().getField("itemMessage");
            f.set(data, "Android Auto / CarPlay / Baidu CarLife");
        } catch (Exception e) { throw new IllegalStateException("Pinned OEM row changed", e); }
        return data;
    }
    private static String mode(Activity a) {
        String t = a.getIntent().getStringExtra("direct_switch_type");
        return "1".equals(t) ? "carplay" : "2".equals(t) ? "android_auto" : "carlife".equals(t) ? "carlife" : null;
    }
    public static boolean handles(Activity a) { return mode(a) != null; }
    public static void create(Activity a) {
        final String selected = mode(a);
        String label = LABELS[java.util.Arrays.asList(MODES).indexOf(selected)];
        TextView content = new TextView(a);
        content.setText("Протокол подключения: " + label + "\nИзменение применяется после перезагрузки.");
        content.setTextSize(24); content.setPadding(40, 40, 40, 40); a.setContentView(content);
        new AlertDialog.Builder(a).setTitle(label)
            .setMessage("Сохранить выбор для следующей загрузки? Автоматической перезагрузки не будет.")
            .setPositiveButton("Сохранить", (d, w) -> new Thread(() -> apply(a, selected), "CarLifeSelectorApply").start())
            .setNegativeButton("Отмена", (d, w) -> a.finish())
            .setOnCancelListener(d -> a.finish()).show();
    }
    private static void apply(Activity a, String selected) {
        try {
            // Prove the root endpoint exists before sending the single OEM request.
            SelectorClient.request(null);
            if (!"carlife".equals(selected)) {
                Class<?> c = Class.forName("com.carsyso.main.misc.manager.MPUCarPlayManager");
                Object manager = c.getMethod("getInstance").invoke(null);
                // Preserves OEM serial ownership. Return value is explicitly NOT an acknowledgment.
                c.getMethod("sendATCommand", String.class).invoke(manager, "android_auto".equals(selected) ? "AT#CS2" : "AT#CS1");
            }
            SelectorClient.request(selected); // Root independently reads cp_aa before AA/CP commit.
            message(a, "Выбор сохранён", "Выбрано: " + LABELS[java.util.Arrays.asList(MODES).indexOf(selected)] +
                ". Применится после перезагрузки. Перезагрузка не выполнена.");
        } catch (Exception e) {
            android.util.Log.e("CarLifeSelector", "SELECT not confirmed", e);
            message(a, "Выбор не подтверждён", "Не удалось подтвердить сохранение. OEM-запрос мог быть отправлен; автоматической перезагрузки нет. Проверьте состояние перед повтором.");
        }
    }
    private static void message(Activity a, String title, String text) {
        a.runOnUiThread(() -> { if (!a.isFinishing() && !a.isDestroyed())
            new AlertDialog.Builder(a).setTitle(title).setMessage(text).setPositiveButton("OK", null).show(); });
    }
    private OemSelectorUi() {}
}
