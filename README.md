# DataExpress Android

Специализированная Android-сборка на основе Winlator для запуска настольного DataExpress на ARM64-планшетах.

## Текущее направление

Проект больше не разрабатывает собственный Wine/Box64 launcher с нуля. За основу берётся открытый `brunodev85/winlator-app` под LGPL-2.1, фиксируется конкретный upstream commit и поверх него применяются воспроизводимые DataExpress-патчи и профиль контейнера.

```text
Winlator app source (pinned commit)
  + DataExpress Android patches
  + DataExpress container profile
  + DataExpress.exe built from dxbit/dataexpress
  + Firebird client and required notices
  = DataExpress Android APK
```

## Что реализовано в текущей ветке

- upstream Winlator закреплён в `upstream/winlator-app.lock`;
- `scripts/prepare-winlator-fork.sh` клонирует ровно закреплённый commit и применяет наши патчи;
- CI формирует архив исходников производной сборки вместе с уведомлениями LGPL;
- исправлена нативная сборка закреплённого Winlator; debug APK собирается в GitHub Actions;
- DataExpress собирается в CI из закреплённого `dxbit/dataexpress` под Apache-2.0;
- Win32 runtime, Firebird embedded и официальная учебная `DEMO_DB.DXDB` помещаются внутрь APK;
- при первом запуске автоматически создаётся единственный контейнер 1280×800 с WineD3D/VirGL;
- нажатие на значок запускает DataExpress сразу с учебной базой;
- `.DXDB` и `.FDB` можно открыть из Android-файлового менеджера, включая USB-накопитель;
- внешняя база копируется в изолированный каталог, а при штатном выходе записывается обратно;
- модули `.epas` исполняются штатным настольным движком DataExpress внутри Wine, без web-конвертации;
- `PadegUC.dll` и другие неподтверждённые проприетарные файлы исключаются.

Сборка пока имеет статус технического preview: успешный CI подтверждает структуру APK,
но запуск и обратную запись ещё нужно проверить на физическом ARM64-устройстве.

## Почему не GitHub Fork

Этот репозиторий уже был создан отдельно. Поэтому используется модель **upstream + patch overlay**: она технически эквивалентна поддерживаемому форку, но позволяет хранить наши изменения отдельно и регулярно переносить их на проверенные версии Winlator. Подготовленный полный исходный код производной сборки публикуется как CI artifact.

## Подготовка исходников форка

```bash
bash scripts/prepare-winlator-fork.sh
```

Результат появится в `build/winlator-src`. Если upstream изменился так, что патч больше не применим, сборка должна завершиться ошибкой, а не выпустить обычный Winlator без изменений.

## Ближайший технический рубеж

1. Получить зелёную совместную сборку Win32 DataExpress + Android APK.
2. Проверить на ARM64-планшете: первый запуск, русский ввод, мышь и учебную базу.
3. Открыть `.DXDB` непосредственно с USB и проверить обратную запись после выхода.
4. Прогнать базу с `.epas`, Win32 DLL и печатными шаблонами; составить матрицу совместимости.
5. Сделать подписанную preview-сборку и страницу загрузки.

## Лицензии

- Winlator / winlator-app: LGPL-2.1; сохраняются исходники, лицензия, attribution и список изменений.
- DataExpress: Apache-2.0; сохраняются `LICENSE.txt` и `NOTICE.txt`.
- Wine, Box64, Mesa, Firebird и другие компоненты: применяются их собственные лицензии и уведомления.

См. [NOTICE-WINLATOR.md](NOTICE-WINLATOR.md) и [docs/licensing.md](docs/licensing.md).
