# Safari Browser

Локальный Android-браузер в стиле Safari (RU).

**© СпустяРуковаРекордс · Author-ID: `srr-safari-2026` · package `ru.srr.safari`**

Лицензия: [LICENSE](LICENSE) (proprietary). Копировать и выкладывать под чужим именем нельзя.

## Защита авторства

Публичный код можно смотреть, но:

- в APK зашиты `AUTHOR` / `AUTHOR_ID` / отпечаток подписи;
- release подписывается **личным ключом** (файл `.jks` и пароли **не в git**);
- публичный SHA-256: [`keystore/sha256-fingerprint.txt`](keystore/sha256-fingerprint.txt).

Полностью «запретить украсть исходники» технически нельзя. Юр. защита - LICENSE + DMCA. Identity приложения на сторах - твой release-ключ.

Пароли ключа лежат локально в `keystore/BACKUP-THIS.txt` (не коммитить). Сохрани копию вне проекта.

## Сборка

```bash
export JAVA_HOME=/Users/alfa/jdks/temurin-17
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease   # нужен keystore.properties
```

## Версия

См. `app/build.gradle.kts` (`versionName` / `versionCode`).
