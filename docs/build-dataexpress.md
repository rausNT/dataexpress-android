# Сборка DataExpress для Android runtime

## Подтверждённая исходная среда

Официальный README `dxbit/dataexpress` указывает:

- Lazarus 4.5;
- Free Pascal 3.2.3;
- 32-битную Windows toolchain;
- компоненты `dxbit/dataexpress-depend` в `D:\LazComponents`.

Поэтому первая целевая сборка — Win32 `DataExpress.exe`. На ARM64 Android она будет запускаться через Wine WoW64 и Box86/Box64.

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

## Почему toolchain пока не загружается автоматически

Lazarus 4.5 и FPC 3.2.3 указаны upstream-проектом, но CI должен получать их из проверяемого источника с фиксированным хешем. Слепая загрузка «последней версии» fpcupdeluxe сделает сборку невоспроизводимой и создаст риск supply-chain.

Перед включением полноценной workflow необходимо зафиксировать:

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
