# Winlator attribution and modification notice

DataExpress Android is derived in part from Winlator / winlator-app by Bruno Devis and contributors.

Upstream source: https://github.com/brunodev85/winlator-app
License: GNU Lesser General Public License v2.1
Pinned revision: see `upstream/winlator-app.lock`.

## Modifications made by DataExpress Android

- application is specialized for launching DataExpress rather than serving as a general game/application launcher;
- runtime profile and first-run flow are intended to create one managed DataExpress container;
- DataExpress payload is built from `dxbit/dataexpress` under Apache-2.0;
- proprietary or unverified DLLs are excluded from the packaged payload;
- user databases are selected through Android Storage Access Framework;
- upstream attribution and corresponding source availability must be preserved in every distributed build.

This file does not replace the upstream LGPL-2.1 license or notices for Wine, Box64, Mesa, Firebird and other bundled components.
