# Проверка сборки — 08.09.2026

Команда: `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain`.

- BUILD SUCCESSFUL.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (около 61 МиБ).
- JVM: 20 тестов, 0 ошибок, 0 пропусков (13 алгоритмов текста, 7 менеджера перевода/авторизации).
- Android Lint: 0 ошибок, 28 предупреждений. Остались рекомендации о новых версиях зависимостей/SDK, version catalog и KTX; target 36 сохранён по ТЗ.
- В APK проверено присутствие встроенных Latin/Japanese/Korean OCR-моделей и language-id.
- Google и Yandex проверены отдельными HTTP-запросами с искусственными фразами; оба вернули перевод на русский.
- `git diff --check`: без ошибок форматирования.
- Эмулятор не запускался. APK не устанавливался. MediaProjection, OCR на Android и взаимодействие с играми требуют отдельной проверки по DEVICE_TEST_PLAN.md.

Отчёты: `app/build/reports/tests/testDebugUnitTest/index.html`, `app/build/reports/lint-results-debug.html`.
