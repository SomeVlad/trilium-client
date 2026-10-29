# Проверки и релизы

[build.yml](../.github/workflows/build.yml) проверяет изменения в `master`, pull request и ручные запуски: JVM-тесты, Android lint и debug APK. Подпись и доступ к Trilium не нужны; артефакт хранится семь дней.

[release.yml](../.github/workflows/release.yml) принимает только стабильные теги `vMAJOR.MINOR.PATCH`. Тег должен точно совпадать с `versionName` и указывать на коммит из истории `master`. Перед новым релизом обновите `versionName` и увеличьте `versionCode` в `app/build.gradle.kts`.

После обновления версии и коммита отправьте ветку и новый тег; пример для `0.6.0`:

```sh
git push origin master
git tag v0.6.0
git push origin v0.6.0
```

Для подписанного APK нужны четыре Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Сохраняйте существующий ключ подписи: другой ключ не позволит обновить уже установленное приложение. CI проверяет подпись, идентификатор пакета и версию APK; временный файл ключа удаляется после сборки.

Релиз содержит `trilium-client-VERSION.apk`, `trilium-client-VERSION-source.tar.gz`, отдельные `diagram-mermaid-PACKAGE_VERSION.zip`, `diagram-canvas-PACKAGE_VERSION.zip` и `SHA256SUMS` для всех четырёх файлов. APK и исходники собираются из точного тега релиза. Перед публикацией CI сверяет ZIP с манифестом внутри APK, затем скачивает все загруженные файлы и проверяет контрольные суммы.

Тяжёлые библиотеки Mermaid/Canvas и полный набор шрифтов не входят в APK. В нём остаются оболочка просмотра, манифест `assets/diagrams/packages.json` и все лицензионные уведомления. Каждый ZIP также содержит применимые уведомления. Исходное дерево vendor, source maps, исходники и закреплённые upstream-архивы сохраняются в полном архиве исходников.

Версия каждого пакета независима от версии приложения: URL закреплён за тегом выпуска содержимого, а SHA-256, размер и полный список файлов — в манифесте. Обычная сборка `python3 scripts/package-diagrams.py /tmp/diagram-packages` воспроизводит ZIP и проверяет его соответствие манифесту. ZIP без сжатия имеет фиксированные порядок, время и права файлов; результат не зависит от версии zlib. Для намеренного обновления vendor сначала пересоберите библиотеки и source lock, затем выполните `python3 scripts/package-diagrams.py /tmp/new-diagram-packages --write-manifest --package-version vMAJOR.MINOR.PATCH` с новым тегом публикации и закоммитьте манифест. Неизменившийся пакет автоматически сохраняет прежние версию, URL и хеш. Исправления приложения не требуют повторного скачивания библиотек.

Опубликованные файлы не перезаписываются. Для изменений выпускайте новую версию и тег; несовпадающий незавершённый черновик сначала проверьте вручную.

Локальные проверки автоматизации:

```sh
actionlint .github/workflows/*.yml
python3 scripts/verify-release.py --tag v0.6.0
python3 -m unittest discover -s ci -p 'test_*.py'
```
