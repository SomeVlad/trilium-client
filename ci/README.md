# Проверки и релизы

[build.yml](../.github/workflows/build.yml) проверяет изменения в `master`, pull request и ручные запуски: JVM-тесты, Android lint и debug APK. Подпись и доступ к Trilium не нужны; артефакт хранится семь дней.

[release.yml](../.github/workflows/release.yml) принимает только стабильные теги `vMAJOR.MINOR.PATCH`. Тег должен точно совпадать с `versionName` и указывать на коммит из истории `master`. Перед новым релизом обновите `versionName` и увеличьте `versionCode` в `app/build.gradle.kts`.

После обновления версии и коммита отправьте ветку и новый тег; пример для `0.5.1`:

```sh
git push origin master
git tag v0.5.1
git push origin v0.5.1
```

Для подписанного APK нужны четыре Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Сохраняйте существующий ключ подписи: другой ключ не позволит обновить уже установленное приложение. CI проверяет подпись, идентификатор пакета и версию APK; временный файл ключа удаляется после сборки.

Релиз содержит `trilium-client-VERSION.apk`, `trilium-client-VERSION-source.tar.gz` и `SHA256SUMS`. APK и исходники собираются из точного тега релиза. Перед публикацией CI скачивает загруженные файлы и проверяет контрольные суммы.

Опубликованные файлы не перезаписываются. Для изменений выпускайте новую версию и тег; несовпадающий незавершённый черновик сначала проверьте вручную.

Локальные проверки автоматизации:

```sh
actionlint .github/workflows/*.yml
python3 scripts/verify-release.py --tag v0.5.0
python3 -m unittest discover -s ci -p 'test_*.py'
```
