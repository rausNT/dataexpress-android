# Экспериментальная ветка ARM32

Отдельная линия DataExpress Android для `armeabi-v7a` (32-битный ARM). В Test 1 используется профиль Android TV и встроенная учебная база. Проверено на одном Xiaomi MiTV_MSSP0 с Android 9; перенос на другие ARM32-устройства ещё не подтверждён. Это техническая сборка, не полноценный выпуск для повседневной работы.

Требования: Android 8.0+, поддержка ARMv7/NEON, не менее 900 МиБ свободного места для подготовки среды. Для работы с таблицами удобнее мышь и клавиатура. Кнопка запуска доступна с пульта.

## Что проверено

Установка, автоматическая подготовка Wine32, открытие DEMO_DB с записями, повторное открытие из кеша, работа указателя и меню «Файл». Подробности, измерения и ограничения: [TEST-RESULTS.md](TEST-RESULTS.md).

Первый запуск долгий: распаковка среды около минуты, затем первичная настройка Wine около двух минут. Повторный запуск пропускает обе подготовки, но пока тоже требует ожидания. Скорость запуска ещё требует улучшения. Не проверены реальные пользовательские базы, отчёты, печать, сетевые подключения, импорт/экспорт и другие устройства.

## Сборка Android-оболочки

Исходники находятся в `winlator6/`. Нужны JDK 17, Android SDK 34, NDK 24.0.8215888 и CMake 3.22.1. Gradle wrapper использует Gradle 7.3.3 / AGP 7.2.2. Две JNI-библиотеки PRoot и X-сервер собираются из исходников; ABI только `armeabi-v7a`.

В [ARM32 Test 1 release](https://github.com/rausNT/dataexpress-android/releases/tag/v0.1-arm32-test.1) размещён свежий Debian/Wine/Box86 runtime `imagefs.tzst`. Поместите его в `winlator6/app/src/main/assets/imagefs.tzst` и проверьте SHA256:

```
802D0B6EA9529925C6C5E7B6D9E8689EF1A0D994D27CD07F6F3203154B2D71B9
```

Задайте `JAVA_HOME` и `ANDROID_HOME` для своих установленных инструментов и выполните из `winlator6/`:

```
gradlew.bat --no-daemon assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Пакет `ru.mydataexpress.tv32`, версия `0.1-tv-arm32-test.1`. Встроенный `tv-start.sh` обновляет гостевой сценарий запуска после извлечения runtime. Runtime не хранится в Git: он большой и уже доступен как отдельный артефакт выпуска.

## Runtime и происхождение исходников

- Android shell / X server: [Winlator 6.1](https://github.com/brunodev85/winlator/tree/6.1), GPL-3.0, с локальными изменениями GLES2 / ARM32. Лицензия включена в дерево. Старые бинарные assets и JNI из upstream не используются.
- PRoot: [Termux commit a179d3e8a4e045aaa1fb8cc3284f23509d96d353](https://github.com/termux/proot/tree/a179d3e8a4e045aaa1fb8cc3284f23509d96d353). Исходники и COPYING включены. Минимальный ARM loader и talloc взяты из исходного дерева и собираются с точкой входа `_start` без CRT.
- Box86: [v0.3.8 / ec278bd7843252d7bfc6e5e91d7074f9c0310de7](https://github.com/ptitSeb/box86/tree/ec278bd7843252d7bfc6e5e91d7074f9c0310de7), ARMv7 NEON / hard float / ARM dynarec. Скрипт фиксирует эту ревизию.
- Wine 8.0~repack-4 и ARMHF-библиотеки: подписанные Debian Bookworm repositories. Исходники Wine: [Debian Sources](https://sources.debian.org/src/wine/8.0~repack-4/); исходники остальных Debian-компонентов доступны из соответствующих source packages.
- Windows zlib1.dll: отдельный Debian пакет `libz-mingw-w64`.
- Zstd JNI: официальный Maven-артефакт `com.github.luben:zstd-jni:1.5.7-12@aar`.

Скрипты генерации runtime находятся рядом с этим README. Они требуют root в Linux/WSL, debootstrap, QEMU user emulation, gcc-arm-linux-gnueabihf, cmake/ninja, binutils, tar, git и zstd. Рабочий каталог задаётся `DX_ARM32_WORK`, по умолчанию `/root/dataexpress-arm32`. Порядок: `build-rootfs.sh`, `prepare-wine.sh`, `build-box86.sh`, `add-wine-zlib.sh`, `package-rootfs.sh`. Для последнего шага в `demo-runtime/` нужен тот же проверенный Windows payload и учебная база из Preview 2. Скрипты документируют сборку проверенного runtime; полная генерация с нуля этими перенесёнными скриптами отдельно не проверена. Для повторной сборки оболочки используйте runtime из выпуска.

`restore-pe-flags.py` сохраняет исходные разрешения PE-секций после удаления отладочной информации GNU strip. Без этого Wine DLL/EXE повреждаются. Wine prefix создаётся самим Wine, отдельная zlib DLL устанавливается автоматически, а сценарий ждёт Wine server после выхода Explorer. TV-профиль отключает Wine-драйверы Windows USB/HID; обычный ввод обрабатывают Android и X-сервер.