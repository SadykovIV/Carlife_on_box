# Перенос CarLife на Android-бокс

[English](../PORTING.md) · [Главная](../../README.ru.md)

Переносятся протокол, видео/ввод и схема загрузки с одним владельцем USB. Интеграцию платы надо установить для конкретной прошивки. Во время разработки сохраняйте рабочий образ и runtime.

## 1. Снять небольшой профиль устройства

Через уже авторизованный ADB соберите данные только для чтения. Перенаправление вывода выполняется на компьютере:

```sh
adb -s "$ANDROID_SERIAL" shell getprop ro.product.board
adb -s "$ANDROID_SERIAL" shell getprop ro.product.cpu.abilist
adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk
adb -s "$ANDROID_SERIAL" shell getprop ro.build.fingerprint
adb -s "$ANDROID_SERIAL" shell getprop ro.boot.slot_suffix
adb -s "$ANDROID_SERIAL" shell getenforce
adb -s "$ANDROID_SERIAL" shell ls /sys/class/udc
adb -s "$ANDROID_SERIAL" shell ls /config/usb_gadget
adb -s "$ANDROID_SERIAL" shell ls -l /dev/usb_accessory
adb -s "$ANDROID_SERIAL" shell dumpsys display > display.txt
adb -s "$ANDROID_SERIAL" shell dumpsys media.codec > codec.txt
adb -s "$ANDROID_SERIAL" shell dumpsys usb > usb.txt
```

Отказ в доступе тоже фиксирует реальные права. Сырые dumps не публикуйте. Сопоставьте init, projection-скрипты и поддержку USB в ядре точной прошивки. Если нужные факты уже известны, полный аудит заново не требуется.

## 2. Найти владельца USB

Определите, какая init-служба первой запускает OEM AA/CarPlay и кто записывает descriptors, UDC и USB routing. Новый dispatcher должен прочитать сохранённый режим **до первого запуска projection**, чтобы OEM и CarLife не управляли одним USB одновременно.

Эталонный контроллер — `tools/boot_native/runtime/runtime-controller.sh`. UDC `a600000.dwc3`, configfs, плата `chelianyi-led`, канал `sd_msm6350`, GPIO 1132 и `cp_aa` относятся к конкретной плате. Менять их следует после установления аналогичного механизма на новом устройстве. Сохраните общую инициализацию: пропуск `vendor.qcom.main.start=1` в эталонной прошивке также останавливал запуск сотовых служб.

ГУ выступает AOA host. Бокс показывает подходящую discovery-функцию, принимает AOA-команды ГУ и предоставляет accessory endpoints. Observer получает настоящее событие ядра `ACCESSORY=START`; наличие device node или запущенного таймера не доказывает соединение. Проверяйте detach/reattach, неполные read/write и время жизни accessory-дескриптора.

## 3. Сохранить контракты видео и ввода

Общий MD и native overlay собираются вместе. Путь: зеркало дисплея → EGL → MediaCodec H.264 → CarLife. Эталонный mirror/input использует привилегированные и скрытые Android API. Обычный APK с MediaProjection — другой способ развёртывания со своими правилами согласия и жизненного цикла; текущий privileged-код не становится обычным APK автоматически.

Проверьте реальные SPS/PPS, начальный IDR, размеры, H.264 level, PTS и повтор статической картинки. Сохраняйте RESET-барьер, одного владельца источника, ограниченные очереди и регистрацию ошибок. CSD guard защищает известный OEM-путь: замена хеша без исследования другой библиотеки не является переносом.

Размеры видео, crop/rotation и координаты касаний должны описывать одну геометрию. DPI меняет размер Android UI, но не исправляет неверное преобразование координат. Сохраняйте асинхронный ввод. Поддержка форматов нескольких указателей не означает, что повторяющиеся legacy-пакеты с одной координатой содержат независимый второй палец.

## 4. Интегрировать ранний запуск и сохранение выбора

Эталонный порядок:

1. После готовности `/data` создать versioned runtime и selector; readiness записать последним.
2. Прочитать сохранённый режим раньше OEM projection.
3. Для AA/CarPlay выполнить штатный путь; для CarLife запросить native init-service.
4. ART-службы запускать после готовности default APEX namespace.
5. USB и reconnect обслуживает один controller.

Magisk допустим как носитель раннего внедрения. Поздний `service.sh`, который сначала запускает AA, а потом забирает USB, этого порядка не обеспечивает. Системная/boot-интеграция должна учитывать verified boot и подписи целевой прошивки.

Настройки и аппаратная кнопка должны работать с одним saved selection. Эталонный OEM UI использует `AT#CS1` / `AT#CS2` и `cp_aa`; это детали конкретной прошивки. В ней `direct_switch_type=3` означает wireless CarPlay, а не CarLife. Цикл из трёх режимов по RESET в текущем публичном коде не реализован.

Замена OEM APK требует подходящей подписи и совместимости shared UID. Если такого пути подписи нет, используйте поддерживаемую интеграцию собственного UI/службы; APK с посторонним ключом не заменит системный. OEM APK и ключи здесь не распространяются.

## 5. Проверить права и восстановление

Нужны реальные права на configfs, accessory, захват display и input injection. Тестовый бокс уже был permissive; это не доказывает работу под enforcing. Подготовьте и проверьте политику целевой системы, не отключая защиту.

До переключения USB проверьте независимый доступ: например Wi-Fi ADB либо доступный внешний экран/ГУ. Сохраните оригинальные boot/system и откат с соответствующим resolver. Питание тоже часть проверки: циклические brownout-перезагрузки выглядят как сбой протокола или холодного запуска.

## 6. Принять перенос по этапам

- Офлайн-тесты и сборка.
- Privileged MD: захват и ввод на целевом боксе.
- Прямой USB CarLife: настоящий launcher, приложение и клавиатура.
- Обычный reboot, затем полное снятие питания при заранее готовом ГУ.
- Физический reconnect, логический RESET, обычная работа и длительная сессия.
- Сохранённые CarLife/AA/CarPlay, Home/Back, настройки сотовой сети и мобильный интернет.
- Конкретный возврат прежней конфигурации.

Фиксируйте реальную длительность видео и ошибки. Офлайн/mock PASS отделяйте от аппаратной приёмки. Полную прошивку проверяйте отдельно от runtime, установленного через другую загрузочную цепочку.

## Источники

- [Android Open Accessory](https://source.android.com/docs/core/interaction/accessories/aoa)
- [MediaProjection](https://developer.android.com/media/grow/media-projection)
- [SELinux](https://source.android.com/docs/security/features/selinux/implement)
- [Android Verified Boot](https://source.android.com/docs/security/features/verifiedboot/avb)
- [HeadSwap](https://github.com/YegorkaPomidorka/headswap)
