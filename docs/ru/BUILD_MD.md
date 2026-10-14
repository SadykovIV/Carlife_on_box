# Сборка Android MD

[English](../BUILD_MD.md) · [Русский](BUILD_MD.md)

MD — сторона подключения, которая работает на Android Box. Нативная сборка объединяет общий код `android-md/src` и слой `tools/boot_native/md/src`: второй заменяет общий `MdMain` и добавляет готовность при загрузке и обработку нативной сессии. Сборка только общего слоя не воспроизводит установленный нативный runtime.

## Требования

- Python 3.9 или новее; используется только стандартная библиотека.
- JDK 17 (`java` и `javac`).
- Android SDK platform 35 (`android.jar`).
- D8 из установленного Android SDK Build Tools либо R8 JAR с `com.android.tools.r8.D8`.

Установите зависимости из официальных дистрибутивов и примите их лицензии. Скрипт ничего не скачивает, не вызывает ADB, не устанавливает APK и не меняет устройство или USB.

## Сборка и тесты

Из корня репозитория:

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
python3 scripts/build_md.py
```

Скрипт находит platform 35 и D8 из самой новой установленной версии Build Tools. Для выбора конкретных файлов:

```sh
python3 scripts/build_md.py \
  --java-home /path/to/jdk-17 \
  --android-jar /path/to/android-sdk/platforms/android-35/android.jar \
  --r8-jar /path/to/r8-8.3.37.jar
```

Вместо `--r8-jar` можно указать `--d8 /path/to/build-tools/VERSION/d8`. Поддерживаются переменные `JAVA_HOME`, `ANDROID_SDK_ROOT` (или `ANDROID_HOME`), `D8` и `R8_JAR`. Выберите один способ указания D8. Пути с пробелами заключайте в кавычки.

Результат:

- `build/md/md-boot-native.jar`: архив DEX, точка входа `org.carlife.hulab.MdMain`.
- `build/md/manifest.json`: хеши исходников и инструментов, результат тестов.
- `build/md/*.stdout.txt`, `*.stderr.txt`, `steps.json`: локальные журналы сборки.

Для отдельной сборки используйте `--output /path/to/output`. Режим только тестов компилирует оба слоя и выполняет все 26 JVM-наборов без D8:

```sh
python3 scripts/build_md.py --test-only
```

Минимальный API для DEX — 21. Это настройка байткода, **не** подтверждение работы зеркалирования и привилегированного ввода на любой версии Android. Измеренный runtime работает на Android 15 с описанной прошивкой SM6350.

## Что проверяют тесты

Наборы покрывают framing и фрагментацию пакетов, гонки RESET и writer/watchdog, остановку и дренирование энкодера, владение source lease, SPS/PPS и access units, OEM CSD guard, геометрию и тач, владение accessory и восстановление, PAUSE/heartbeat, асинхронный ввод, диагностику feature/touch и политику видеожурнала. Заглушки Android API находятся только в тестах и в DEX не попадают.

Три синтетических libx264-образца серого изображения сохранены текстом `.h264.hex`. Перед тестами SHA256 декодированных байтов проверяется по `android-md/tests/fixtures/provenance.json`. Записей экрана устройства в них нет. От дерева разработки отличается только тестовый загрузчик этих текстовых образцов; production Java сохранён без изменений.

Публичная сборка прошла все **26 наборов**. Существующий набор JDK 17.0.16, API 35 и R8 8.3.37 дал точный установленный baseline:

```text
347c6ef1fdf0fa25d0c8ae4bd333c8cfce2ed6ed6d7c70cba9b6cbd1c55b87dd  md-boot-native.jar
```

Другая версия JDK или D8 может изменить байты артефакта; сверяйте созданный manifest. Успешная сборка на компьютере не подтверждает работу USB, захвата дисплея, прав ввода или конкретной магнитолы.

## Интеграция runtime

Передайте JAR в сборку и установку runtime для конкретной платы, описанную в основном руководстве. Этот скрипт не создаёт подписанный APK. Сохранённые `android-md/AndroidManifest.xml` и `manifest_binary.py` описывают историческую нейтральную тестовую Activity; нативному JAR они не нужны и не устанавливают CarLife как обычное приложение.

Проверенный нативный тракт зеркалирует настоящий Android-дисплей через EGL в Surface MediaCodec, передаёт H.264 напрямую через USB accessory и вводит события CarLife с существующими правами процесса. Нужны USB/accessory конкретной платы, совместимые API зеркалирования и права захвата/ввода. Не удаляйте source lease, проверки поколений RESET или OEM CSD guard для обхода несовместимости. Особый выбор CSD привязан к измеренному хешу OEM-библиотеки и требует проверки целевой прошивки.

Аудио, микрофон/голос и обязательная аутентификация/шифрование ГУ не реализованы. Парсеры нескольких указателей есть, но на проверенном Honda ГУ не получены подтверждённые координаты второго пальца в наблюдаемом режиме CarLife. Эти ограничения не снимаются успешной сборкой.

## Дополнительно: выбор режима в OEM APK

[`scripts/build_oem_selector.py`](../../scripts/build_oem_selector.py) добавляет существующий выбор CarLife в две **конкретные проверенные версии OEM APK**. Он использует собственные `PatchDex.java` и `SignPlatform.java`; OEM-приложения, SDK/зависимости JAR и ключи подписи в проект не включены. Для другой прошивки нужен разбор её исходных методов, а не обход проверки хеша.

Передайте свои соответствующие APK, сертификат и закрытый ключ. Ключ должен совпадать с сертификатом прошивки: инструмент проверяет это, а затем проверяет подпись итогового APK. Подмена подписи произвольным ключом разработчика не выполняется.

```sh
python3 scripts/build_oem_selector.py \
  --main-apk /private/firmware/MainAiBox.apk \
  --settings-apk /private/firmware/SudingSettings_chelianyi_new.apk \
  --android-jar /path/to/android-sdk/platforms/android-35/android.jar \
  --r8-jar /path/to/r8-8.3.37.jar \
  --dexlib-jar /path/to/smali-dexlib2-3.0.9.jar \
  --guava-jar /path/to/guava-33.4.0-jre.jar \
  --apksig-jar /path/to/apksig-8.3.2.jar \
  --java-home /path/to/jdk-17 \
  --signing-reference /private/signing-reference \
  --output build/oem-selector
```

Каталог `--signing-reference` содержит `platform.x509.pem` и `platform.pk8`. Каталог результата должен быть пустым. Проверяемые SHA256:

| Вход | SHA256 |
| --- | --- |
| MainAiBox APK | `09b2f141f63a7ae016a1a7a43dd52be853b044dfb985d058c348fdaca3714b85` |
| SudingSettings APK | `8df7ba4a1785c472fa365336597eadeefe0958ea205920359a1833d8f78230fb` |
| Сертификат в DER | `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8` |

Инструмент проверяет число обёрнутых методов и побайтовую сохранность остальных записей APK, включая manifest, ресурсы и нативные библиотеки. Создаются `MainAiBox-selector.apk`, `SudingSettings-selector.apk` и локальный manifest сборки. При подготовке публикации обе подписи итоговых APK проверены. Это сборка на компьютере: установка и совместимость устройства проверяются отдельно. Привилегированный selector daemon и runtime собираются отдельно через `scripts/build_runtime.py`.
