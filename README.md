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

## Что уже сделано

- upstream Winlator закреплён в `upstream/winlator-app.lock`;
- `scripts/prepare-winlator-fork.sh` клонирует ровно закреплённый commit и применяет наши патчи;
- CI формирует архив исходников производной сборки вместе с уведомлениями LGPL;
- добавлен начальный профиль DataExpress: 1280×800, WineD3D/VirGL, автозапуск `C:\\DataExpress\\dataexpress.exe`;
- DataExpress планируется собирать из официального `dxbit/dataexpress` под Apache-2.0;
- `PadegUC.dll` и другие неподтверждённые проприетарные файлы исключаются.

## Почему не GitHub Fork

Этот репозиторий уже был создан отдельно. Поэтому используется модель **upstream + patch overlay**: она технически эквивалентна поддерживаемому форку, но позволяет хранить наши изменения отдельно и регулярно переносить их на проверенные версии Winlator. Подготовленный полный исходный код производной сборки публикуется как CI artifact.

## Подготовка исходников форка

```bash
bash scripts/prepare-winlator-fork.sh
```

Результат появится в `build/winlator-src`. Если upstream изменился так, что патч больше не применим, сборка должна завершиться ошибкой, а не выпустить обычный Winlator без изменений.

## Ближайший технический рубеж

1. Добавить патч первого запуска, создающий единственный контейнер DataExpress.
2. Заменить общий домашний экран Winlator на запуск/выбор базы DataExpress.
3. Встроить проверенный payload DataExpress и Firebird client.
4. Собрать debug APK в GitHub Actions.
5. Проверить на Huawei MatePad: запуск, русский ввод, мышь, доступ к `.DXDB` и сохранение данных.

## Лицензии

- Winlator / winlator-app: LGPL-2.1; сохраняются исходники, лицензия, attribution и список изменений.
- DataExpress: Apache-2.0; сохраняются `LICENSE.txt` и `NOTICE.txt`.
- Wine, Box64, Mesa, Firebird и другие компоненты: применяются их собственные лицензии и уведомления.

См. [NOTICE-WINLATOR.md](NOTICE-WINLATOR.md) и [docs/licensing.md](docs/licensing.md).
