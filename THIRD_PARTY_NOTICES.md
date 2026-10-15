# Third-party references and dependencies

The repository publishes the project's implementation and integration source. It does not relicense third-party material. Optional r7 release assets contain modified user-supplied MainAiBox and SudingSettings binaries for the exact tested firmware profile, plus the prior overlay for rollback. These binaries are separate from the Apache-2.0 project source and retain their original third-party rights; no OEM redistribution license is represented by the project license. Full firmware, Android SDKs, JDKs, signing credentials and the research archive are not included.

## Protocol and interoperability references

- [CarLife-MD-Lib](https://github.com/Doris-duo/CarLife-MD-Lib/tree/b3cc871f7c5c4b41947a3c512caee76624eaffd9): Apache-2.0 in the inspected upstream source. Used as a protocol/reference implementation source.
- [Apollo DuerOS mirror](https://github.com/milesking/apollo-DuerOS/tree/6a07419818f46aadda34c5f55e63d4a1fc8136ef): Apache-2.0 in the inspected source. CarLife headers and protobuf descriptions informed wire-format work. The historical ApolloAuto upstream was unavailable during research; this link identifies the inspected mirror.
- [HeadSwap](https://github.com/YegorkaPomidorka/headswap): GPL-3.0 in the inspected source. Used for protocol behavior and interoperability comparison. No HeadSwap application, Kotlin implementation or GPL source file is bundled in this export. Short wire examples describe protocol bytes, not a redistribution of that application.
- [AOSP](https://source.android.com/): Android USB accessory, display/input, init, SELinux and verified-boot documentation and API references.

The publication review did not identify copied HeadSwap implementation blocks in the exported Java/Python/C source. This is a scoped review, not an assertion about every possible future contribution. Preserve the original license if you add upstream code.

## Build and host dependencies

JDK, Android SDK, R8/D8, apksig, smali/dexlib2, Guava, Clang/LLD, libusb, PyAV/FFmpeg, Pillow and PySide6 are obtained separately. They retain their own licenses and distribution terms; the project license does not replace them. In particular, review PySide6/Qt and FFmpeg licensing if distributing a packaged HU application.

Synthetic H.264 image fixtures are stored as hexadecimal test data with provenance and contain neutral generated images. Small OEM codec configuration fixtures contain SPS/PPS metadata only, without captured image frames.

## Trademark notice

Baidu CarLife, Android Auto, CarPlay, Carlinkit, Honda and Alpine names identify compatibility targets or references. Their owners do not sponsor or endorse this project.

---

Русский: репозиторий содержит собственную реализацию и исходники интеграции. Полные OEM-прошивки, SDK/JDK, ключи и исследовательские дампы не включены. Отдельные release assets r7 содержат изменённые OEM-приложения и прежний overlay для отката на точно проверенной прошивке; исходные права на эти бинарники сохраняются, Apache-2.0 проекта на них не распространяется. Baidu/Apollo использовались как справочные реализации протокола, HeadSwap — для сравнения поведения и совместимости; его приложение и GPL-исходники не включены. Все внешние зависимости сохраняют собственные лицензии. При переносе чужого кода сохраняйте его лицензию и уведомления; лицензия этого проекта не распространяется на сторонние прошивки и инструменты. Названия продуктов используются только для указания совместимости и источников.
