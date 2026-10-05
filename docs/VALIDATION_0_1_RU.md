# Проверка первого этапа — 0.1.0

5 октября 2026. Сборка проверена локально. Это проверка конкретного прототипа; production cloud safety и возможности всех провайдеров ещё не подтверждены.

## Автоматические проверки

- `gradlew test :app:assembleDebug :app:lintDebug`: успешно, 46 JVM-тестов, Android APK, lint без ошибок. Оставшиеся предупреждения относятся к версиям зависимостей и стилю KTX; Compose baseline намеренно совместим с SDK 36.
- `python scripts/test_pos_fixtures.py`: четыре настоящих контейнера от Kotlin writer, независимо восстановленных Python decoder. Размеры 0, 1, 65536 и 131079 байт. Побайтовое равенство и SHA-256 совпали; усечение и лишний хвост отклонены.
- На отдельном эмуляторе API 35 `Ciel_Vitrine_Test` запущены 3 Android instrumentation tests: полный copy/recovery/readback через ContentResolver; отказ в перезаписи источника через URI-алиас; усечённый footer не даёт успеха, источник сохраняется. Результат `OK (3 tests)`.

JVM-тесты включают привязки паспорта/доказательства после открытия БД, неподходящий хеш, старое доказательство, инвалидирование, недостающий ресурс Live Photo, один физический объект под двумя ролями, конкурентное резервирование двумя экземплярами БД, неизвестный результат удаления, неверный URI, атомарный результат/outbox, восстановленную корзину, исходные CRC/framing/bounds, передачу decoder error через pipe, archive-first scheduler и различение ответов Яндекс API.

## Проверки интерфейса

APK установлен только на отдельный эмулятор. На процедурных тестовых JPEG проверены MediaStore scan, миниатюры, открытие фото, папки, сохранение избранного после переустановки с сохранением данных и создание местного альбома. Приложение не обращалось к пользовательскому облаку и не устанавливалось на пользовательский телефон.

Фото/GIF/video viewer входят в сборку; полный набор реальных кодеков/HDR/RAW, воспроизведение всех вариантов Live Photo и стресс-проверки медиатек остаются отдельными задачами. ContentResolver tests используют местный Android MediaStore provider; они не доказывают поведение всех сторонних SAF-провайдеров.

## Оставшиеся release gates

Самодостаточный bootstrap и production original format; multipart; Android durable control DB; source-original FD/metadata permission probes; OAuth/account/root binding; реальное облачное обратное чтение; persistent/reconcilable jobs; private Gallery encryption; conflict handling; Android destructive consent; cloud Trash/purge/restore concurrency. Поэтому в APK нет команд облачного удаления или освобождения оригиналов.
