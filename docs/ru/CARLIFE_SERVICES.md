# Дополнительные функции CarLife: раздельный звук, навигация и голос

[English](../CARLIFE_SERVICES.md) · [Аудиокандидат](AUDIO_CANDIDATE.md)

План реализации по исследованию HeadSwap от15 октября2026. Сейчас реализованы смешанный MEDIA-кандидат и приём аудио на HU. Остальные части ниже — конкретная архитектура, ещё не установленная на бокс. Первым источником навигации владелец выбрал **Яндекс Навигатор / Яндекс Карты**. Данные маршрута получаем от приложения.

## Потоки и источники

| Функция | Источник в AndroidBox | CarLife | Статус |
| --- | --- | --- | --- |
| Launcher, карта, приложения | Существующий EGL/MediaCodec | VIDEO2 | Рабочий baseline |
| Музыка | Захват output PCM; затем отдельный MEDIA mix | MEDIA3 | Смешанный кандидат, офлайн-тесты |
| Подсказки навигатора | Отдельный usage/session приложения | TTS4 | Архитектура, разделение ещё не проверено |
| Ответ ассистента | Выход ассистента либо собственный синтез PCM | VR5 | Архитектура |
| Микрофон ГУ | HU packets `0x58001` | Вход Android recognizer/assistant | Путь маршрутизации найден |
| Манёвр, дорога, расстояние | Адаптер Яндекса | CMD navigation | Нужны реальные данные приложения |
| Название трека, обложка, прогресс, кнопки | Android MediaSession/MediaController | CMD metadata/control | Следующий отдельный producer |

HeadSwap v1.2.0 получает MEDIA и SPEECH из AA. Музыка идёт в channel3, а **навигационная речь и ответ ассистента вместе — в TTS4**. Отдельный VR-выход у него сейчас не реализован. Поэтому наша целевая схема с тремя потоками — дальнейшая native-реализация, а не буквальное копирование AA-моста. [Исходник AaBridge](https://github.com/YegorkaPomidorka/headswap/blob/0f5502ab76e2f220b68fa1e2c85b66838f01fd81/android/app/src/main/java/com/yegorkapomidorka/headswap/aa/AaBridge.kt).

## Разделить до общего микса

Общий REMOTE_SUBMIX уже смешан. Несколько записывающих процессов на одном адресе или анализ громкости не вернут отдельные музыку и речь. Публичный AudioPlaybackCapture тоже не универсален: обычный режим ограничен usages MEDIA/GAME/UNKNOWN и политикой захвата приложения; NAVIGATION_GUIDANCE и ASSISTANT он сам по себе не выделяет. [Документация Android](https://developer.android.com/media/platform/av-capture).

Конкретный системный вариант — одна **AudioPolicy**, несколько **PLAYERS AudioMix**, у каждого `ROUTE_FLAG_LOOP_BACK` и свой адрес. Через `createAudioRecordSink(mix)` создаются независимые PCM-источники:

| Поток | Начальное непересекающееся правило | Назначение |
| --- | --- | --- |
| MEDIA | UNKNOWN0, MEDIA1, GAME14 | 48кГц stereo PCM16 → MEDIA3 |
| NAV | ASSISTANCE_NAVIGATION_GUIDANCE12 | Измеренный формат → TTS4 |
| ASSISTANT | ASSISTANT16 | Измеренный формат → VR5 |

Чистый LOOP_BACK отличается от LOOP_BACK|RENDER в выборе маршрута native policy. `allowPrivilegedPlaybackCapture(true)` не используется как универсальный обход: для него AOSP задаёт ограничения, в том числе16кГц/mono. [AudioPolicy](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/media/java/android/media/audiopolicy/AudioPolicy.java), [AudioMix](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/media/java/android/media/audiopolicy/AudioMix.java), [native rules](https://android.googlesource.com/platform/frameworks/av/+/android-15.0.0_r1/services/audiopolicy/common/managerdefinitions/src/AudioPolicyMix.cpp).

В предоставленном OEM Android15 найдены реализации этих методов и remote-submix HAL. Поэтому заранее патчить firmware/HAL не требуется. На устройстве ещё нужно проверить регистрацию с имеющимся runtime, `MODIFY_AUDIO_ROUTING`, доступ к HAL и несколько адресов mix. Код не меняет защиту или разрешения.

Яндекс/ассистент могут использовать MEDIA для речи или отдельный UID сервиса. Сначала измеряются реальные AudioPlaybackConfiguration/AudioAttributes. Затем, где нужно, правило уточняется подтверждённым UID/session. Нельзя весь звук UID навигатора объявить TTS, если он воспроизводит и другую музыку. `CONTENT_TYPE_SPEECH` сам по себе не заменяет правило AudioMix. Неизвестный источник остаётся MEDIA до подтверждения; один общий PCM не дублируется по трём каналам.

Один владелец AudioPolicy последовательно регистрирует и удаляет маршруты. У каждого источника отдельные lease, очередь, поколение сессии и sample clock. Общий address0 REMOTE_SUBMIX освобождается до включения раздельного режима. При частичной ошибке удаляются успешно созданные маршруты; видео остаётся доступным. Явный fallback в общий MEDIA обозначается как смешанный режим.

## Приоритеты, радио и подсказки

Общий AudioCoordinator управляет MUSIC, NAVI, VR и порядком событий на единственном USB writer. Маршрутизация через AudioPolicy не требует сразу перехватывать всю системную focus policy.

- MUSIC отражает реальное состояние потока. Выбор радио на ГУ убирает хвост PCM, а после реализации MediaSession также ставит выбранный Android-плеер на паузу. Повтор VIDEO_START не означает автоматический Play.
- NAVI имеет независимых владельцев: маршрут и произносимую подсказку. Конец одной подсказки не отменяет маршрут; конец маршрута не обрывает недоговорённую фразу. Удержание NAVI весь маршрут или только во время речи проверяется с радио конкретного ГУ.
- TTS передаёт INIT→PCM→STOP по смыслу подсказки. Постоянная тишина не объявляется активной речью. Debounce подбирается по измеренным границам фраз, а таймеры HeadSwap не копируются как обязательный стандарт.
- Слушание и ответ VR имеют приоритет над музыкой. Одновременные NAV/VR сначала сериализуются; устаревшая речь отбрасывается, не проигрывается после возвращения.
- Android уже может приглушить музыку по focus. Надо проверить точку ослабления, чтобы не приглушить повторно на ГУ до почти полной тишины.
- После временной подсказки возвращается только действительно игравший источник, если владелец не остановил его через ГУ. Телефонные звонки/HFP — отдельная интеграция.

Первый опыт разделения: **один UID**, два различимых тона с usages MEDIA и NAVIGATION_GUIDANCE. В двух sinks должен оказаться только свой тон. Далее — настоящие подсказки Яндекса поверх музыки, смена радио, освобождение маршрутов и reconnect. Ненулевой общий PCM не доказывает разделения.

## Микрофон ГУ → штатный ассистент

Вход голосовой сессии: `MODULE_CONTROL 0x18028`, VR module4, либо поддержанный VR hardkey. MD просит запись `0x10024`; HU возвращает channel5 / `0x58001`; завершение — `0x10023`. Исходный кандидат формата16кГц mono PCM16 проверяется на реальном ГУ. AA ACK/окна передачи здесь не нужны; первые20пакетов не отбрасываются — текущий HeadSwap также использует skip0.

Основной путь — recorder **INJECTOR AudioMix**, LOOP_BACK, `AudioPolicy.createAudioTrackSource(mix)`. PCM направляется в фактический **UID распознавателя AND capture preset**, дополнительно в конкретный audio session ID, когда он известен. Два recorder одного UID/source требуют выбора session либо одного активного голосового потребителя. Реализация и проверки разрешений найдены прямо в OEM framework. [AudioMixingRule](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/media/java/android/media/audiopolicy/AudioMixingRule.java).

Recorder определяется по recording configuration: пакет интерфейса ассистента может отличаться от UID распознающего сервиса. Маршрут регистрируется до открытия recorder, затем вызывается разрешённое Android-действие выбранного ассистента. Позднее attach не объявляется гарантированным перенаправлением уже открытого микрофона. Разрешения записи приложения и mic privacy сохраняются.

Политикой управляет тот же owner, что разделяет output. У инъекционного трека `USAGE_VIRTUAL_SOURCE=15`; он исключён из выходных sinks, чтобы не создать цикл «захват→ГУ→микрофон». USB reader не блокируется на AudioTrack.write. Отдельный worker получает bounded очередь. Cancel/disconnect меняет voice epoch, посылает MIC_END, освобождает AudioTrack, удаляет только нужный mix и снимает VR. Другие recorders продолжают использовать свои входы.

Для собственного голосового интерфейса есть публичный запасной путь API33+: [RecognizerIntent.EXTRA_AUDIO_SOURCE](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE), PCM pipe с явными частотой/каналами/encoding. Provider может проигнорировать extra: это проверяется известным PCM и контрольным шумом около локального микрофона. Такой путь не подменяет автоматически вход любого assistant/IME. Собственный TTS может отдавать PCM через [UtteranceProgressListener](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener); тот же синтез нельзя одновременно проигрывать и повторно захватывать.

Первый probe: известный PCM → адресный обычный AudioRecord, параллельно контрольный recorder. Проверить доставку только нужному получателю и удаление маршрута. Затем — настоящая фраза с ГУ, распознавание обычным ассистентом, ответ через VR и возврат музыки. Начальный режим push-to-talk; barge-in, одновременное прослушивание/ответ и wake word требуют проверки эха и отдельного lifecycle.

## Навигация из Яндекс Навигатора / Карт

Карта в VIDEO, речь в TTS и данные манёвра — три разных тракта. Для первого адаптера надо установить реальные package/version Яндекса, проверить доступные интерфейсы приложения и его навигационные уведомления.

Официальные yandexnavi/yandexmaps URL API запускают приложение и строят маршрут; подписка на текущие манёвры в проверенной документации не найдена. `NotificationListenerService` даёт уведомления после разрешения владельца, но категория NAVIGATION не задаёт стандартные обязательные поля манёвра/расстояния. Поэтому сначала нужны настоящие примеры: лево/право/кольцо, изменение метров, перестроение маршрута и его окончание. Отсутствующие поля не заменяются выдуманными инструкциями. [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService).

Яндекс NaviKit предоставляет typed guidance внутри **собственного приложения с SDK**. Это не чтение маршрута установленного Яндекс Навигатора/Карт. AndroidX `NavigationManager.updateTrip` также отправляет данные от участвующего приложения к car host, а не подписывается на чужие маршруты. [Yandex guidance](https://yandex.com/maps-api/docs/mapkit/android/generated/navigation/guidance.html), [NavigationManager](https://developer.android.com/reference/androidx/car/app/navigation/NavigationManager).

План границы компонентов:

```text
Яндекс / проверенный адаптер приложения
  → NavigationProvider: маршрут, манёвр, дорога, метры, источник/sequence/time
  → NavigationCoordinator: текущий маршрут + подписка ГУ + схема peer
  → bounded очередь: последнее расстояние, сохранённые новый манёвр/конец
  → существующий CarLife CMD writer
```

Подписки: запрос `0x18043`, ответ `0x10044`, START `0x18045`, STOP `0x18046`. Navigation subscription ID=**0**, функциональный NAVI module ID=**2** — их нельзя смешивать. Манёвр — `NAVI_NEXT_TURN_INFO 0x10030`. Где его показывать — на ГУ, приборке или HUD — решает магнитола/автомобиль. Наличие подписки не доказывает видимый вывод на приборку.

Есть реальная несовместимость схем: в старом Baidu proto поле6 — required **int32 time**, в новом анализе Baidu от HeadSwap — **bytes turnIconData**. HeadSwap предлагает TIME/ICON/BOTH/NONE; две записи одного номера с разными wire types не объявляются универсально безопасными. Нужны явный encoder profile и проверка на известной работающей сессии целевого ГУ. [Старый proto](https://github.com/milesking/apollo-DuerOS/blob/6a07419818f46aadda34c5f55e63d4a1fc8136ef/CarLife-Vehicle-Lib/LibSource/protobuf/proto/CarlifeNaviNextTurnInfoProto.proto), [исследование HeadSwap](https://github.com/YegorkaPomidorka/headswap/blob/0f5502ab76e2f220b68fa1e2c85b66838f01fd81/docs/cluster-metadata-recon.md).

Provider различает отсутствие маршрута, движение, reroute, завершение и потерю источника. Потеря приложения не означает прибытие. После reconnect отправляется только свежий настоящий snapshot после подписки. До рабочего provider и проверенного encoder MD не объявляет поддержку этих метаданных. Демо-маршрутов в обычной работе нет.

## Порядок реализации

1. **Текущий кандидат:** настоящий смешанный MEDIA по USB, видео/тач продолжаются, release и reconnect проверены.
2. **Раздельный output:** два тона/два sink, затем Яндекс+музыка и assistant usage. Отдельные PTS/INIT/STOP, отсутствие дубля PCM и правильный focus. Расширяется существующий audio worker/writer; видеотракт сохраняется.
3. **Яндекс и метаданные:** реальные данные приложения, provider/coordinator и схема ГУ; фактические манёвры на экране ГУ, приборке/HUD отдельно. MediaSession control/metadata следует тому же правилу настоящего источника.
4. **Голос:** probe recorder route, затем кнопка/микрофон ГУ→native распознавание→ответ VR; cancel, повторы, очистка, возврат MEDIA. Затем совместная работа с навигацией.
5. **Общая приёмка:** музыка+подсказка+голос+тач, радио, отсоединение/возврат и холодное питание; bounded очереди и отсутствие оставленных маршрутов. Успех API/пакета/синтетического теста фиксируется отдельно от слышимого/видимого результата.

Прошивка для этих механизмов заранее не требуется. Если конкретный runtime-отказ потребует системной правки, сначала устанавливаются точный API/политика и адресный patch. Наличие методов в OEM ещё не является аппаратным PASS.
