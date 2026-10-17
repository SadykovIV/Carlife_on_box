# v0.2 validation

Prerelease **v2026.10.17**, following [v2026.10.16](https://github.com/SadykovIV/Carlife_on_box/releases/tag/v2026.10.16) at commit `3f0cf6701f36b949764f686d086619bf2d4122e5`. Results below concern the reference TBox Ultra / SM6350 / Android 15 installation using direct USB CarLife, without Android Auto as an intermediary.

| Check | Observed result | Scope |
|---|---|---|
| Cold start, C9 | First IDR at **21.708 s Android uptime**, versus **45.310 s** in the earlier C5 cold run; owner confirmed automatic image on the first attempt | One cold run; excludes bootloader time and is not a power-to-visible-image stopwatch measurement |
| Hot USB reconnect, C10 | Owner accepted automatic image in approximately **5–7 s after reconnecting**; recorded USB-error-to-first-IDR intervals were **9.596 / 9.688 s** | Error-to-IDR includes unknown time with the cable unplugged; each recorded session completed a fresh handshake and produced video |
| MD retirement marker → controller rearm | **0.870 / 1.167 s** on C10, versus **2.924 / 2.992 s** on C9 | C10 removes the extra exit delay and uses a faster bounded node writer; this is not an isolated benchmark of either change |
| Final C11 stop cleanup | Bounded wait for remaining controller-cgroup helpers; an already empty group proceeds without sleeping | **Host-tested only; not installed or accepted on the device** |

The release packages C11, which adds only the stop-cleanup fix to C10. The accepted device remains on C10. PID/ownership checks, empty-cgroup requirements, USB readiness checks and failure preservation remain in place. The cleanup change is outside the first-frame and hot-reconnect paths.

**57 MD JVM suites and 167 controller/boot-native host tests passed.** The MD artifact is unchanged between the tested C9/C10 and packaged C11: SHA256 `a1ad080b9606390841c10aef756aa6cf90a8636e778fa1f9fd0b87a7f8cbf06a`. Host tests do not establish final-package hardware acceptance.

Automotive audio audibility, real SIM/VoIP duplex calls and physical HU microphone acceptance remain pending. The cold run recorded one recovered video-pressure episode with 16 dropped access units; an OEM-process crash in a separate earlier run remains unresolved. These observations do not establish universal HU compatibility, prolonged stability, 60 FPS, or clean installation on a second box.

Distribution uses one **`Carlife_on_box-TBox-Ultra-2026.10.17-v0.2.zip`** with the standard installation/update and on-device rollback workflow. Follow the [installation and rollback guide](INSTALL_RELEASE.md); no separate rollback ZIP is required.

## Русский

На C9 первый IDR при холодном старте получен на 21,708 с uptime Android вместо прежних 45,310 с; это не время от подачи питания до изображения. Горячее переподключение C10 принято владельцем: изображение появилось само примерно через 5–7 с после возврата кабеля. В релиз включён C11 с отдельным исправлением очистки при остановке: он прошёл локальные тесты, но ещё не устанавливался на устройство. Звук в автомобиле, реальные SIM/VoIP-звонки и приёмка микрофона ГУ остаются непроверенными. Один ZIP использует штатную установку и встроенный откат.
