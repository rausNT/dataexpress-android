# Firebird 5 для Wine / Winlator

DXDB использует Firebird 5 Embedded. Официальные Win32-бинарники Firebird 5.0.3
под Wine доходили до `EPrivilege` или `Wrong file for memory mapping`, поэтому runtime
собран из исходников тега `v5.0.3` (commit `e98c36b17071c5ce228a5c069449c65b037799a0`)
с патчем `patches/firebird/wine-compat.patch`.

Патч делает две узкие совместимые правки:

- распознаёт возвращаемый Wine путь вида `\\??\\C:\\…` при проверке memory-mapped файла;
- под Wine использует существующий fallback имён kernel objects вместо недоступного private namespace API.

Android runtime проверяет размер и SHA-256 четырёх ключевых DLL до запуска DXDB.
Те же проверки выполняет `scripts/verify-runtime-payload.py`, поэтому штатная или
случайно пропатченная бинарно сборка не попадёт в APK.

Проверенные файлы публикуются как закреплённый release asset
`firebird-5.0.3-wine-x86.zip`; архив содержит лицензионные уведомления Firebird.
