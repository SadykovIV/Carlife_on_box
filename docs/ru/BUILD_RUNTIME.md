# Сборка runtime и системного overlay

[English](../BUILD_RUNTIME.md) · [Главная](../../README.ru.md)

Сборка создаёт локальные файлы: она не подключается к устройству, ничего не устанавливает и не прошивает. Контроллер и init-шаблоны относятся к эталонной SM6350-прошивке; перед переносом на другую плату прочитайте [PORTING](PORTING.md).

## 1. Собрать MD

Выполните [BUILD_MD](BUILD_MD.md). Результат — `build/md/md-boot-native.jar`. При JDK 17, API 35 и R8 8.3.37 воспроизводится рабочий SHA-256 `347c6ef1fdf0fa25d0c8ae4bd333c8cfce2ed6ed6d7c70cba9b6cbd1c55b87dd`.

## 2. Собрать observer, runtime и selector

Нужны Python 3.11+, JDK 17, `android.jar` API 35, R8/D8 JAR, Clang с AArch64 и LLVM LLD. Используйте свои установленные инструменты. Observer собирается без libc и не требует sysroot Android NDK.

```sh
python3 scripts/build_runtime.py \
  --md-jar build/md/md-boot-native.jar \
  --android-jar "$ANDROID_SDK_ROOT/platforms/android-35/android.jar" \
  --r8-jar /path/to/r8.jar \
  --java-home "$JAVA_HOME" \
  --clang /path/to/clang \
  --ld-lld /path/to/ld.lld \
  --output build/runtime
```

Выходная папка должна быть новой. Результат:

- `runtime/boot-native-runtime.tar` и `runtime/bundle-manifest.json`;
- `selector/selector-daemon.jar`, `selector-control.sh`, `selector-daemon.sh` и manifest;
- `uevent-watch` и `BUILD.json`.

Это компоненты, а не готовая прошивка. Selector daemon — локальная служба с привилегиями. `OemSelectorUi.java` содержит код интеграции: сборка DEX сама по себе не добавляет пункт в OEM Settings. Необязательный сборщик OEM-патча описан в [BUILD_MD](BUILD_MD.md).

## 3. Создать overlay точной эталонной прошивки

Нужны собственные извлечённые оригинальные `init.rc` и `sd-reverse.sh`. OEM-прошивки, APK и ключи подписи в репозиторий не входят.

Исходный пакет эталонного опыта:

`10.05.2026_FIXCARCAR_27.03.2026_TBox_Ultra_1_2_6350_Android 15_-_TG_-_@aibox.zip`

SHA-256: `e71f6640a41df2d3456761ccf3244706c23aab04bb316555dfbc808b7d17dbf3`.

```sh
python3 integration/sm6350/build_integration.py \
  --runtime build/runtime/runtime/boot-native-runtime.tar \
  --runtime-manifest build/runtime/runtime/bundle-manifest.json \
  --selector-release build/runtime/selector \
  --init /path/to/extracted/system/etc/init/hw/init.rc \
  --oem-script /path/to/extracted/system/bin/sd-reverse.sh \
  --default-selection carlife \
  --output build/system-overlay
```

Проверяются точный SHA исходного init и миграция resolver. Создаются `files/`, `overlay-map.json`, `init.rc.patch`, read-only preflight данных и `BUILD.json`. В map указаны права, владелец и SELinux-контексты.

Default записывается только при отсутствии собственных данных. Существующий корректный выбор сохраняется. Единственная встроенная миграция — resolver `b97…` → `6add…`; неизвестные данные не перезаписываются. Исходный старый resolver требует отключённого исторического пилота; системный сборщик преобразует его для свежей установки, где старого пилота нет. Не используйте необработанный resolver как универсальный установщик.

## 4. Интегрировать в прошивку

Примените map и проверенный selector-патч средствами сборки целевой прошивки. Пересоберите метаданные файловой системы, AVB и OTA-подписи согласованно с исходником. Публичный репозиторий не содержит полного упаковщика OTA из локального опыта и готовых OEM-образов. Созданный overlay ещё не задаёт подходящую команду прошивки.

В локальном полном образе сохранены kernel, vendor, recovery, DTBO, product, system_ext и odm; изменены boot, system и соответствующие AVB-метаданные. Восстановлен общий запуск Qualcomm radio, удалена реальная serial-console конфигурация. Офлайн-результат — в [STATUS](STATUS.md); приёмка загрузки полного образа ещё не выполнена.

Для enforcing SELinux нужны проверенные домены служб и права на устройства, display и input. Эталонные шаблоны используют init-domain служб исходного опыта; они не являются проверенной enforcing-политикой и не отключают SELinux.

## Перед установкой

Выполните офлайн-тесты из README. Сохраните исходную прошивку, свой boot, хеши сборки и конкретный способ восстановления. Dispatcher должен вступить в работу раньше первого OEM-владельца USB. Проверьте подачу питания с заранее готовым ГУ, launcher/приложения/клавиатуру/ввод/reconnect, сохранение выбора и мобильную сеть. Работа runtime при другом способе загрузки не доказывает загрузку нового системного образа.
