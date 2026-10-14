# Contributing / Участие

English is the primary documentation language. Keep the linked Russian guides in sync when behavior or commands change; code comments and issue reports may use either language.

Before a pull request, run the relevant offline tests from the README and the MD JVM suite for Android changes. Describe the concrete behavior change, the device/firmware profile and what was tested. Hardware results must name their transport and scope. Attach a small redacted log excerpt, not a full firmware dump or personal recording.

Do not commit firmware images, OEM APKs, signing keys, ADB keys, device identifiers, downloaded SDKs or local capture/build directories. The project-owned integration code can refer to a verified OEM input hash without redistributing that input. Preserve upstream notices for any third-party code you introduce.

Separate protocol/video changes from board-specific boot and USB changes. Preserve RESET generation fencing, source ownership, codec validation and asynchronous input. Avoid hiding failed sessions behind a later successful reconnect. For device ports, document the original firmware, early startup point, USB routing, permissions, power and rollback.

---

Основная документация — английская. При изменении поведения или команд обновляйте связанную русскую версию; комментарии и issues допустимы на обоих языках.

Перед PR запустите подходящие офлайн-тесты из README, а для MD — JVM-набор. Опишите изменение, профиль устройства/прошивки и реальные проверки. В аппаратных результатах укажите транспорт и объём приёмки. Прикладывайте небольшой обезличенный фрагмент журнала вместо полного дампа или личного захвата.

Не коммитьте прошивки, OEM APK, ключи подписи/ADB, идентификаторы устройств, скачанные SDK и локальные captures/build. Ссылаться на проверенный SHA OEM-входа можно без публикации самого файла. Сохраняйте лицензии добавленного стороннего кода.

Отделяйте протокол/видео от загрузки и USB конкретной платы. Сохраняйте RESET, владение источником, codec validation и асинхронный ввод. Успешный reconnect не должен скрывать предыдущую ошибку. Для порта документируйте исходную прошивку, ранний запуск, USB, права, питание и откат.
