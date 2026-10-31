# Third-party materials

The Apache-2.0 license in this repository applies to Ciel Vitrine's original
source and documentation. It does not replace the licenses of dependencies,
Android platform components, build tools or externally downloaded media.

## Dependencies

Dependencies are resolved through Gradle and pip rather than copied into the
source tree. Their versions are declared in the build files and requirements
files. Consult each upstream distribution for its complete license and notices:

- [AndroidX and Media3](https://android.googlesource.com/platform/frameworks/support/)
- [Kotlin and kotlinx](https://github.com/JetBrains/kotlin)
- [Coil](https://github.com/coil-kt/coil)
- [Yandex LoginSDK](https://yandex.ru/dev/id/doc/ru/mobileauthsdk/about)
- [Gradle](https://github.com/gradle/gradle) — the wrapper JAR and scripts are included
- [Python cryptography](https://github.com/pyca/cryptography), [Pillow](https://github.com/python-pillow/Pillow)
  and [pillow-heif](https://github.com/bigcat88/pillow_heif) — recovery/test tooling

Yandex and Google product names identify integrations. This project is not
affiliated with or endorsed by those providers.

## Test media

`scripts/prepare-photo-corpus.ps1` downloads ten public JPEG samples from
[ianare/exif-samples](https://github.com/ianare/exif-samples) at a pinned revision.
The source URLs, sizes and hashes are recorded in
[the corpus manifest](docs/fixtures/ten-photo-corpus.json). These downloaded files
are excluded from Git. Their original rights and upstream terms continue to
apply; this project's license does not relicense those photographs.
The pinned upstream README states that user-contributed images are released
under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).

`scripts/prepare-safety-corpus.py` generates synthetic image/video fixtures
locally. It uses Pillow, pillow-heif and an installed FFmpeg; generated media and
FFmpeg binaries are not distributed in this source tree.
