package org.carlife.selector;
import android.app.*;
import android.content.*;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.lang.reflect.*;

/** Added inside the existing OEM APKs. Not a new launcher or exported component. */
public final class OemSelectorUi {
    private static final String[] MODES = {"android_auto", "carplay", "carlife"};
    private static final String[] LABELS = {"Android Auto", "CarPlay", "Baidu CarLife"};
    private static final String VIDEO_CONFIG = "carlife_video_config";
    private static final String VIDEO_STATUS = "carlife_video_status";
    private static final String[] VIDEO_MODES = {"auto", "720p", "800x480", "1920x1080", "1280x480", "1728x648", "1920x720", "2240x976"};
    private static final String[] VIDEO_FPS = {"auto", "15", "30", "60"};
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
                            }).setNegativeButton(text(a, "Отмена", "Cancel"), null)
                            .setNeutralButton(text(a, "Параметры Baidu CarLife", "Baidu CarLife settings"),
                                (dialog, which) -> showVideoSettings(a)).show();
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
    private static String text(Activity a, String ru, String en) {
        return "ru".equals(a.getResources().getConfiguration().getLocales().get(0).getLanguage()) ? ru : en;
    }
    private static int index(String[] values, String value) {
        return java.util.Arrays.asList(values).indexOf(value);
    }
    /** A single provider value keeps the resolution/FPS pair atomic. */
    static int[] videoSelection(String value) {
        if (value != null) {
            String[] parts = value.split(":", -1);
            if (parts.length == 3 && "v1".equals(parts[0])) {
                int mode = index(VIDEO_MODES, parts[1]);
                int fps = index(VIDEO_FPS, parts[2]);
                if (mode >= 0 && fps >= 0) return new int[] {mode, fps};
            }
        }
        return new int[] {0, 0};
    }
    static String videoStatus(String value) {
        if (value == null || value.isEmpty()) return null;
        return value.split(";", 2)[0].replace(" @ ", " · ").replaceFirst("^(\\d+)x(\\d+)", "$1 × $2");
    }
    private static TextView label(Activity a, String value) {
        TextView result = new TextView(a);
        result.setText(value);
        result.setTextSize(18);
        result.setPadding(0, 12, 0, 8);
        return result;
    }
    private static Spinner spinner(Activity a, String[] labels, int selected) {
        Spinner result = new Spinner(a);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(a, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        result.setAdapter(adapter);
        result.setSelection(selected);
        return result;
    }
    private static void showVideoSettings(Activity a) {
        if (a.isFinishing() || a.isDestroyed()) return;
        final String config;
        final String status;
        try {
            config = Settings.Global.getString(a.getContentResolver(), VIDEO_CONFIG);
            status = Settings.Global.getString(a.getContentResolver(), VIDEO_STATUS);
        } catch (Exception e) {
            android.util.Log.e("CarLifeSelector", "Video settings read failed", e);
            message(a, text(a, "Настройки недоступны", "Settings unavailable"),
                text(a, "Не удалось прочитать параметры видео.", "Unable to read video settings."));
            return;
        }
        int[] selected = videoSelection(config);
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * a.getResources().getDisplayMetrics().density + 0.5f);
        content.setPadding(padding, 8, padding, 8);
        content.addView(label(a, text(a, "Разрешение видеопотока", "Video resolution")));
        String[] resolutions = {
            text(a, "По запросу ГУ", "Requested by head unit"),
            "1280 × 720",
            "800 × 480", "1920 × 1080", "1280 × 480", "1728 × 648", "1920 × 720", "2240 × 976"
        };
        final Spinner resolution = spinner(a, resolutions, selected[0]);
        content.addView(resolution);
        content.addView(label(a, text(a, "Целевая частота кадров", "Target frame rate")));
        final Spinner fps = spinner(a, new String[] {
            text(a, "По запросу ГУ", "Requested by head unit"), "15 FPS", "30 FPS",
            text(a, "60 FPS — экспериментально", "60 FPS — experimental")
        }, selected[1]);
        content.addView(fps);
        content.addView(label(a, text(a,
            "Применится при следующем подключении CarLife. Авто FPS: запрос ГУ, до 30 FPS. Запрос ГУ может снизить частоту. Частота зависит также от дисплея Android; 60 FPS не гарантирует 60 разных кадров. Масштаб интерфейса Android не меняется.",
            "Applies on the next CarLife connection. Auto FPS: head unit request, up to 30 FPS. The head unit may request a lower frame rate. Frame rate also depends on the Android display; 60 FPS does not guarantee 60 distinct frames. Android interface scale stays unchanged.")));
        String summary = videoStatus(status);
        String current = summary == null
            ? text(a, "Ещё нет данных текущей сессии.", "No current session data yet.") : summary;
        content.addView(label(a, text(a, "Согласованный режим (частота не измерена):\n", "Negotiated mode (frame rate not measured):\n") + current));
        ScrollView scroll = new ScrollView(a);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(a)
            .setTitle(text(a, "Параметры Baidu CarLife", "Baidu CarLife settings"))
            .setView(scroll)
            .setPositiveButton(text(a, "Сохранить", "Save"), null)
            .setNegativeButton(text(a, "Отмена", "Cancel"), null)
            .setNeutralButton(text(a, "По умолчанию", "Defaults"), null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                resolution.setSelection(0);
                fps.setSelection(0);
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String value = "v1:" + VIDEO_MODES[resolution.getSelectedItemPosition()] + ":" + VIDEO_FPS[fps.getSelectedItemPosition()];
                try {
                    if (!Settings.Global.putString(a.getContentResolver(), VIDEO_CONFIG, value)
                        || !value.equals(Settings.Global.getString(a.getContentResolver(), VIDEO_CONFIG))) {
                        throw new IllegalStateException("Video settings not confirmed");
                    }
                    dialog.dismiss();
                    message(a, text(a, "Параметры сохранены", "Settings saved"), text(a,
                        "Применятся при следующем подключении CarLife.", "They will apply on the next CarLife connection."));
                } catch (Exception e) {
                    android.util.Log.e("CarLifeSelector", "Video settings write failed", e);
                    message(a, text(a, "Ошибка сохранения", "Save failed"), text(a,
                        "Не удалось подтвердить сохранение параметров. Проверьте выбор повторно.", "Unable to confirm saved settings. Reopen settings to check the selection."));
                }
            });
        });
        dialog.show();
    }
    private OemSelectorUi() {}
}
