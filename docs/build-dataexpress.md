# Сборка DataExpress для Android runtime

## Подтверждённая исходная среда

Официальный README `dxbit/dataexpress` указывает:

- Lazarus 4.5;
- Free Pascal 3.2.3;
- 32-битную Windows toolchain;
- компоненты `dxbit/dataexpress-depend` в `D:\LazComponents`.

Поэтому первая целевая сборка — Win32 `DataExpress.exe`. На ARM64 Android она будет запускаться через Wine WoW64 и Box86/Box64.

## Проверенная локальная сборка

Сборка от 5 августа 2026 года проверена на официальном установщике Lazarus 4.6 / FPC 3.2.2 Win32:

- файл `lazarus-4.6-fpc-3.2.2-win32.exe`;
- SHA-256 `0BCFDDE9D533058F3B730034C4575B4412821F2117CBA77D078EEE41836AF10A`;
- Authenticode-подпись `Stichting Programming Free Pascal & Lazarus Foundation`;
- portable-распаковка выполнена `innoextract` 1.9;
- `fpc.cfg` при необходимости создаёт сам `scripts/build-dataexpress-windows.ps1` через штатный `fpcmkcfg.exe`.

Перед сборкой к закреплённому commit `89523b3125a5bda55aa7aca7232c8773913fed99`
нужно применить `patches/dataexpress/legacy-xml-reader.patch`:

```powershell
git -C C:\src\dataexpress apply C:\src\dataexpress-android\patches\dataexpress\legacy-xml-reader.patch
```

Патч исправляет потерю DataExpress-выражений `[!…]`, `[?!…]` и `[?…]` при SAX-загрузке
старого XML из DXDB. Исправление проверено на оригинальной `СОТ.DXDB`: запросы
`_Контроль обучения` и `_Напоминания по всем модулям` загрузились без ошибки
`Поле источника [] не найдено`.

## Этапы CI

1. Закрепить commit SHA `dxbit/dataexpress` и `dxbit/dataexpress-depend`.
2. Подготовить Windows runner с Lazarus/FPC указанной версии.
3. Разместить зависимости в `D:\LazComponents`.
4. Собрать `dataexpress.lpi` через `lazbuild`.
5. Создать чистый staging-каталог только из необходимых файлов.
6. Скопировать `LICENSE.txt` и `NOTICE.txt`.
7. Удалить и запретить `PadegUC.dll` и иные неподтверждённые бинарники.
8. Запустить `scripts/verify-runtime-payload.py`.
9. Опубликовать ZIP и manifest как CI artifact.

## Почему toolchain не загружается автоматически

Lazarus 4.5 и FPC 3.2.3 указаны upstream-проектом, но CI должен получать их из проверяемого источника с фиксированным хешем. Слепая загрузка «последней версии» fpcupdeluxe сделает сборку невоспроизводимой и создаст риск supply-chain.

Перед включением полноценной workflow необходимо перенести уже проверенные параметры локальной сборки в CI:

- URL или release toolchain;
- SHA-256 установщика/архива;
- команды unattended-установки;
- точный выходной путь `DataExpress.exe`;
- минимальный перечень runtime DLL.

## Локальная проверка payload

```bash
python scripts/verify-runtime-payload.py \
  build/runtime/dataexpress \
  --source-revision <commit-sha> \
  --manifest build/runtime/dataexpress-manifest.json
```

Скрипт не заменяет юридический аудит, но блокирует известные запрещённые файлы и формирует перечень файлов с SHA-256.
