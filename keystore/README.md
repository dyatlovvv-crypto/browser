# Release keystore

Файлы `srr-safari-release.jks`, `BACKUP-THIS.txt` и корневой `keystore.properties` **не коммитятся**.

Публичный отпечаток сертификата (можно светить): см. `sha256-fingerprint.txt`.

Сборка release:
```bash
export JAVA_HOME=/Users/alfa/jdks/temurin-17
./gradlew :app:assembleRelease
```
APK будет подписан ключом СпустяРуковаРекордс.

Без `keystore.properties` release-сборка не подпишется вашим ключом — так и задумано.
