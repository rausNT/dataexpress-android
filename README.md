# DataExpress Android

Экспериментальный Android-клиент для запуска настольного DataExpress в локальном Wine/Box64-окружении на ARM64-планшетах.

## Статус

Проект находится на стадии технического прототипа. Текущий APK-каркас:

- выбирает базу `.DXDB`, `.FDB` или `.EPAS` через системный Android File Picker;
- сохраняет выданный Android доступ к выбранному файлу;
- показывает сведения об устройстве и готовности runtime;
- отделяет Android-оболочку от Wine, Box64, DataExpress и Firebird runtime;
- не содержит и не распространяет сторонние бинарники.

Следующий рубеж — подключить воспроизводимый ARM64 runtime и проверить запуск `DataExpress.exe` на реальном планшете.

## Предполагаемая архитектура

```text
Android launcher
  ├─ Storage Access Framework
  ├─ runtime manifest and integrity checks
  ├─ X11/graphics session
  ├─ Box64
  ├─ Wine x86_64
  └─ DataExpress.exe + Firebird client
```

Winlator рассматривается как технический ориентир для организации контейнера, запуска Wine/Box64 и вывода Windows-интерфейса, но проект не является переименованной сборкой Winlator.

## Сборка оболочки

Требования:

- Android Studio с JDK 17;
- Android SDK 36;
- Gradle wrapper 9.4.1;
- Android Gradle Plugin 9.2.0.

```bash
./gradlew assembleDebug
```

APK появится в `app/build/outputs/apk/debug/`.

## Правовой статус

Репозиторий содержит только собственную Android-оболочку, конфигурацию и документацию. Wine, Box64, Winlator, Firebird и DataExpress имеют собственные лицензии и правообладателей. Их бинарники нельзя добавлять в релиз автоматически без отдельной проверки лицензии, уведомлений и права распространения.

Каталог DataExpress при передаче должен сохраняться как стороннее ПО «как есть», вместе с относящимися к нему лицензиями и NOTICE-файлами. Проект не заявляет передачу исключительных прав на DataExpress или Firebird.

Подробности: [docs/licensing.md](docs/licensing.md).
