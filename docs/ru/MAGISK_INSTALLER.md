# TBox Ultra 1: установка, обновление и откат через Magisk

[English — основная версия](../MAGISK_INSTALLER.md) · [Главная](../../README.ru.md)

Установщик добавляет нативный CarLife в поддерживаемый профиль **TBox Ultra 1 / SM6350, Android 15**, без замены всей прошивки и удаления приложений или данных. ZIP собирается под собственный текущий boot целевого бокса с Magisk. Чужой boot с другого бокса не используется.

**Статус:** установка и восстановление проверены офлайн. На втором физическом TBox установка, обновление и откат ещё не проверены. Принятый рабочий runtime тестового бокса и его сборки сохранены.

## Установка

1. Получите `Carlife_on_box-TBox-Ultra-1-install.zip`, собранный под вашу текущую прошивку, boot и активный слот.
2. Откройте **Magisk → Модули → Установить из хранилища**, выберите ZIP и дождитесь успешного завершения. Во время сохранения и записи boot сохраняйте питание.
3. Перезагрузите бокс один раз. **По умолчанию выбран Baidu CarLife**; подключите бокс к готовому CarLife ГУ.

До записи установщик проверяет профиль устройства. Он сохраняет исходный boot, размещает runtime и добавляет ранний запуск в текущий boot-раздел. Системные разделы Android, второй загрузочный слот, существующие настройки AA/CarPlay и ADB не меняются. CarLife выбирается до захвата USB штатным projection-процессом; позднего перехвата через `service.sh` нет.

В режиме CarLife USB обслуживает ГУ; одновременный USB ADB не обещается. Для обслуживания используйте существующее соединение Wi-Fi ADB или резервный экран AA/CarPlay.

Для этого профиля нужны работающий Magisk 30.7 или новее/root, уже разблокированный загрузчик и существующий режим SELinux permissive эталонной прошивки. Установщик их не меняет. Уже установленные другим способом компоненты CarLife сохраняются: для рабочего тестового бокса используйте его прежний updater, а не установку поверх baseline.

Кнопка **Действие / Action** модуля показывает состояние. Она не переключает USB и не перезагружает бокс. Минимальный пакет не меняет проприетарные APK настроек и launcher, поэтому третий пункт в штатном меню AA/CarPlay не добавляется и поведение RESET не меняется. Необязательная интеграция OEM UI описана в [сборке из исходников](BUILD_MD.md).

## Обновление

Установите соответствующий `Carlife_on_box-TBox-Ultra-1-update.zip` через Magisk и перезагрузите бокс. До перезагрузки текущая сессия продолжает работать. Обновление заменяет payload runtime, сохраняет выбранный режим и исходный boot; **boot повторно не записывается**.

Обновление сохраняет закреплённые в boot точки входа resolver и selector. Их изменение относится к новой загрузочной интеграции, а не к обновлению runtime. Обычные обновления MD, контроллера и observer используют уже установленную интеграцию.

## Полный откат

Самый короткий способ: выполните команду в Android-терминале с root или внутри существующего ADB shell:

```sh
su -c '/data/adb/magisk/busybox sh /data/adb/carlife-on-box/manager.sh rollback'
```

После сообщения о восстановлении исходного boot перезагрузите бокс. Модуль удаляется, его runtime и файлы выбора режима очищаются. **Исходный** — это точный boot до установки этого модуля, включая уже имевшийся Magisk. Сброса к заводским настройкам и удаления root нет.

Другой вариант — **Удалить / Remove** в Magisk. Magisk запускает удаление при следующей загрузке; тогда восстанавливается boot. Нужна ещё одна перезагрузка, чтобы загрузиться именно с восстановленным образом.

Если **выключить** модуль в Magisk и перезагрузить бокс, временно вернётся существующий штатный режим AA/CarPlay. Загрузочные hooks CarLife и резервные файлы останутся; это не полный откат.

Резервные данные хранятся вне модуля: `/data/adb/carlife-on-box/`, включая `original.img`, manifests, manager восстановления и `lifecycle.log`. Удаление модуля не удаляет единственную копию boot. Если после установки boot меняли отдельно, откат сохранит новый образ и сообщит о несовпадении; проверяйте его по сохранённой копии. При обрыве записи boot может потребоваться штатный для устройства способ восстановления.

Если первая установка завершилась ошибкой до регистрации модуля в Magisk, выполните сохранённую команду отката, перезагрузитесь и повторите её для завершения очистки. В этом случае hook удаления модуля может отсутствовать.

## Сборка пакета

Сборка выполняется на компьютере и не обращается к устройству. После сборки [runtime и selector](BUILD_RUNTIME.md) достаточно Python 3.11+. Нужны текущий boot целевого бокса с Magisk, исходные `init.rc` и `sd-reverse.sh`, fingerprint прошивки и активный слот. Полный архив прошивки, проприетарные APK и ключ подписи этому сборщику не нужны.

При существующем авторизованном ADB-соединении эти данные можно прочитать без изменения бокса:

```sh
adb shell getprop ro.boot.slot_suffix
adb shell getprop ro.build.fingerprint
# Используйте слот из вывода выше; здесь пример для _a.
adb exec-out su -c 'cat /dev/block/by-name/boot_a' > target-magisk-boot.img
adb pull /system/etc/init/hw/init.rc target-init.rc
adb pull /system/bin/sd-reverse.sh target-sd-reverse.sh
```

Рассчитайте SHA-256 образа (`shasum -a 256 target-magisk-boot.img` на macOS или `sha256sum` на Linux) и подставьте полученные значения ниже.

```sh
python3 scripts/build_magisk_installer.py install \
  --current-image /path/to/target-magisk-boot.img \
  --expect-current-sha256 YOUR_BOOT_SHA256 \
  --init /path/to/system/etc/init/hw/init.rc \
  --oem-script /path/to/system/bin/sd-reverse.sh \
  --fingerprint 'TARGET_RO_BUILD_FINGERPRINT' \
  --slot-suffix _a \
  --runtime build/runtime/runtime/boot-native-runtime.tar \
  --runtime-manifest build/runtime/runtime/bundle-manifest.json \
  --selector-release build/runtime/selector \
  --output build/magisk-install
```

Укажите фактический слот (`_a` или `_b`). Результат — ZIP, `manifest.json` и `BUILD.json` с хешами. Сохраните установочный ZIP: обновления ссылаются на его загрузочную интеграцию.

```sh
python3 scripts/build_magisk_installer.py update \
  --base-package build/magisk-install/Carlife_on_box-TBox-Ultra-1-install.zip \
  --runtime build/runtime-new/runtime/boot-native-runtime.tar \
  --runtime-manifest build/runtime-new/runtime/bundle-manifest.json \
  --selector-release build/runtime-new/selector \
  --output build/magisk-update
```

В ZIP обновления нет boot-образа. Упаковка в установщик не превращает непроверенный аудиокандидат в принятый на ГУ: явно выбирайте нужный runtime. Для каждой сборки используйте новый выходной каталог.

Использован штатный [жизненный цикл установщика и boot-скриптов Magisk](https://topjohnwu.github.io/Magisk/guides.html). Первая установка и полный откат записывают только выбранный boot-раздел; обновления runtime меняют только файлы данных.
