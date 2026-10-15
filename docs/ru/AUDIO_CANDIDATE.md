# Кандидат: звук, мультитач и время запуска

[English](../AUDIO_CANDIDATE.md) · [Главная](../../README.ru.md)

Статус: **код реализован и проверен офлайн; установки и аппаратной приёмки нет**. Сохранён baseline commit `3b344d45ae34df215166385ba0c65f195652f5d8`, MD `347c6ef1…`, runtime `a602efce…`. Изменения находятся в `feature/audio-startup-multitouch`. Во время работы бокс был недоступен. Это отдельный кандидат, не собранная ранее полная прошивка.

## Что изменено

В рабочем MD отсутствовали источник PCM и отправка аудио, поэтому звук не работал. Теперь подготовлены:

- Захват выходного звука Android через `AudioRecord / REMOTE_SUBMIX`: запрашиваются и проверяются 48 кГц, stereo, PCM16. Используются существующие права процесса; разрешения и системная политика не меняются, микрофон не записывается.
- MEDIA channel3: INIT `0x30001`, DATA `0x30006`, STOP `0x30002`. В INIT — частота, **число каналов**, биты. PCM signed16 little-endian, чередование L/R. Порция20мс =3840байт.
- Отдельный владелец источника и очередь до10 PCM-порций с отбрасыванием старше200мс. INIT/STOP сохраняют порядок при быстром restart. Отказ захвата останавливает аудио, сохраняя видео.
- Один USB writer: приоритет команд, чередование готового видео и аудио. MUSIC status хранится отдельно с номером сессии; writer не ждёт места в собственной очереди. Новая VERSION отменяет старые PCM/status. Видео RESET/PAUSE не останавливают музыку. MUSIC=0 и PAUSE_MEDIA от ГУ прекращают USB PCM до явного возврата; сам Android-плеер пока не управляется.
- `AUDIO_TRANSMISSION_MODE=1` отключает USB PCM как Bluetooth-режим. Bluetooth-воспроизведение кандидат не реализует. MUSIC active отправляется после успешного INIT на проводе.
- Счётчики ненулевых PCM, записей, потерь и активности источника. Время DATA рассчитывается по числу захваченных samples от оценки начала захвата. Это не аппаратный timestamp и не измеренная синхронизация со звуком/видео ГУ.
- На HU — отдельные MEDIA/TTS состояния и ограниченный WAV по `--audio-capture`. Живого воспроизведения звука на Mac пока нет. Полученные PCM входят и в обычный сырой `rx.bin`, даже без WAV.

По умолчанию источник **off**. Для кандидата звук включается явно. В исходной прошивке найдены remote-submix HAL и нужный формат; фактические права, ненулевой PCM, маршрутизация и слышимый звук ещё не проверены. REMOTE_SUBMIX может перенаправлять локальное воспроизведение: возврат маршрута после release входит в опыт.

## Что взято из исследования HeadSwap

Проверен [v1.2.0](https://github.com/YegorkaPomidorka/headswap/releases/tag/v1.2.0), commit `0f5502ab76e2f220b68fa1e2c85b66838f01fd81`, 51commit после прежнего `6835e50`. Новый релиз добавляет два контакта legacy; v1.1.11 содержит также updater и экспериментальное1440p.

HeadSwap получает разделённые MEDIA/SPEECH, голосовые и навигационные события из **Android Auto**. Источника внутреннего Android-звука для нашего бокса там нет. Полезные правила CarLife: INIT перед PCM, MUSIC status, выбор радио, независимость VIDEO_PAUSE, последовательность TTS/VR. ACK аудио AAP, HFP autoplay guard и повторное использование AA-сессии относятся к стороне AA и не перенесены. Источники: [AaBridge](https://github.com/YegorkaPomidorka/headswap/blob/0f5502ab76e2f220b68fa1e2c85b66838f01fd81/android/app/src/main/java/com/yegorkapomidorka/headswap/aa/AaBridge.kt), [CarLifeSession](https://github.com/YegorkaPomidorka/headswap/blob/0f5502ab76e2f220b68fa1e2c85b66838f01fd81/android/app/src/main/java/com/yegorkapomidorka/headswap/CarLifeSession.kt).

GPL-код HeadSwap не скопирован. Независимая реализация использует публичный [контракт Baidu](https://github.com/milesking/apollo-DuerOS/blob/6a07419818f46aadda34c5f55e63d4a1fc8136ef/CarLife-Vehicle-Lib/LibSource/protobuf/proto/CarlifeMusicInitProto.proto) и собственную инфраструктуру MD.

Раздельные подсказки/голос, микрофон/ассистент, данные манёвра, метаданные музыки и управление плеером разобраны в [плане дополнительных функций](CARLIFE_SERVICES.md). Смешанный MEDIA-поток не выдаётся за их готовую реализацию.

## Мультитач

Native recognized принимает дополнительные gesture opcodes и optional `pointerx/pointery`. Если MOVE/UP не повторяет координаты, сохраняется последняя реально измеренная позиция в raw-системе; геометрия применяется один раз. ID оставшегося пальца сохраняется. `(0,0)` остаётся корректной координатой. Новый палец без переданной позиции отменяет только жест, сохраняя изображение.

ACTION3, ASYNC-ввод, RESET, геометрия и CSD guard сохранены. Ограничения по марке автомобиля нет. На Honda прежний A/B пустого запроса не дал независимых координат второго пальца. Новый код ещё не проверен на VW или другом ГУ и не объявляется аппаратным PASS мультитача.

## Запуск

В сохранённом обычном reboot готовность кодека:14,496с, VERSION39,030с, START39,634с, первый AU41,968с после старта Android. Это не время видимого экрана и не физический cold test. В принятом cold test START37,417с, первый AU не сохранился.

Обязательного sleep50–60с нет. Основной необъяснённый интервал находится до handshake: подготовка/публикация USB, AOA, проверки контроллера и реакция ГУ. В существующие строки добавлены длительности фаз и время до/после UDC bind. Новых ожиданий и readiness-gate нет. `/proc/uptime` включает suspend, Java uptime — нет; источник часов явно помечен.

Fallback1500мс при отсутствии START уже был. Заставка HeadSwap начинает передаваться после CarLife handshake и не скрывает предыдущие десятки секунд ожидания USB. AA warm reuse не переживает отключение питания. Ускорение пока **не измерено**.

## Повторить сборку

После настройки toolchain из [BUILD_MD](BUILD_MD.md), из корня репозитория:

```sh
python3 scripts/build_md.py --output build/md-audio-candidate
python3 scripts/build_runtime.py \
  --md-jar build/md-audio-candidate/md-boot-native.jar \
  --android-jar "$ANDROID_SDK_ROOT/platforms/android-35/android.jar" \
  --r8-jar /path/to/r8.jar --java-home "$JAVA_HOME" \
  --clang /path/to/clang --ld-lld /path/to/ld.lld \
  --audio-source remote-submix \
  --runtime-version boot-native-audio-candidate-20261015 \
  --output build/runtime-audio-candidate
python3 scripts/test_host.py
python3 -m unittest discover -s tests -p 'test_boot_native_*.py'
python3 -m unittest discover -s tests -p test_build_runtime_options.py
```

Выходные каталоги должны быть новыми. Сборщик меняет только staging: default `--audio-source off`, custom version согласованно применяется к архиву, current, resolver и controller. Старый baseline сохраняется. Новый resolver не становится автоматически совместимым с адресной миграцией `integration/sm6350`; это не универсальная команда установки и не проверенная OTA.

Для опыта использовать существующий путь установки одного владельца MD с сохранённым baseline и ADB; второй процесс MD рядом с boot controller не запускать. Опция MD: `--audio-source remote-submix`. Возврат звука — `off` или сохранённый runtime.

## Первый аппаратный опыт

Известный локальный stereo WAV на боксе → прямой USB → MEDIA INIT/DATA и WAV на HU (`--audio-capture --audio-capture-limit-mib 8`). Проверить частоту, левый/правый канал, ненулевые samples, длительность, время и потери. Затем на настоящем ГУ прослушать оба канала при продолжающемся видео/вводе. Сам WAV или успешный USB write не доказывают звук в автомобильных динамиках.

Далее: радио→CarLife, VIDEO_PAUSE/RESET, отсоединение/возврат, ошибка/release источника, возврат локального маршрута;10минут видео+аудио+touch; музыка и навигационные приложения. Холодный запуск — с готовым ГУ, стабильным питанием и внешней записью подачи питания/появления экрана. Раздельные TTS/VR и ассистент принимаются отдельными проверками по плану функций.
