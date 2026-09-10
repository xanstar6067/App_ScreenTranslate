# Lenslate — инструкции по проекту

## Назначение и границы

Android-приложение для ручного перевода экрана поверх игр и приложений. Исходное ТЗ: `docs/SPECIFICATION.md`.
Приложение: **Lenslate**, applicationId/namespace: `com.adam.app_screentranslate`.
MVP 0.1: Kotlin, Jetpack Compose Material 3, Coroutines, Android API 26–36.
Не добавлять автоматический захват, Accessibility, S Pen, аккаунты, облачную историю, камеру или файловые разрешения без новой задачи.

В этой задаче пользователь разрешил сборку и локальные проверки. **Запуск приложения и тестирование на эмуляторе отложены пользователем до отдельного чата.** Не запускать emulator, installDebug, connectedAndroidTest или приложение в рамках текущей задачи. В следующем чате явная просьба о проверке на эмуляторе является достаточным разрешением.

## Структура

Основной код: `app/src/main/java/com/adam/app_screentranslate/`.

- `MainActivity.kt`: Activity Result API, выдача overlay/notification/projection разрешений, переход к исключению из оптимизации батареи, Compose.
- `TranslatorApp.kt`: контейнер настроек, SQLite-кэша и StateFlow состояния сессии; состояние захвата не сохраняется.
- `model/Models.kt`: неизменяемые модели, Box в физических пикселях захвата, языки, состояния и настройки.
- `ui/HomeScreen.kt`, `ui/theme/`: русский интерфейс, главный экран и настройки, тёмная тема с мятным акцентом.
- `service/TranslationService.kt`: foreground mediaProjection service, один pipeline на нажатие, отмена/поколения кадров, уведомление, пауза при остановке проекции системой и реакция на блокировку экрана.
- `capture/ScreenCaptureManager.kt`: MediaProjection → единственный VirtualDisplay → ImageReader; кадр только в памяти.
- `capture/FrameMapping.kt`: перевод координат кадра в координаты экрана, включая letterbox при несовпадении формы. Чистая Kotlin-логика.
- `ocr/OCRManager.kt`: встроенные ML Kit Latin/Japanese/Korean, локальный language-id, яркость областей.
- `ocr/TextBlockReconstructor.kt`: нормализация, гармонизация смешанных алфавитов, отсев числовых строк, определение письменности, подавление дублей и восстановление абзацев. Чистая Kotlin-логика.
- `translation/WebProviders.kt`: изолированные неофициальные Google/Yandex, динамическая авторизация, TTL, отменяемый OkHttp, HTML escape/parse.
- `translation/RequestBatcher.kt`: пакеты до 900 символов, сохранение языковой пары, безопасное разбиение UTF-16.
- `translation/TranslationManager.kt`: кэш → сеть, объединение одинаковых запросов, retry, fallback, частичные результаты.
- `data/TranslationCache.kt`: SQLiteOpenHelper, интерфейс TranslationStore для тестов, лимит 20 000 записей.
- `data/SettingsManager.kt`: SharedPreferences + StateFlow; нормализованные позиции кнопки отдельно для двух ориентаций.
- `overlay/OverlayController.kt`: отдельные управляющее и пропускающее касания окна.
- `overlay/OverlayRenderer.kt`: контраст, подбор шрифта, построение StaticLayout, отрисовка карточек и ручное перетаскивание в режиме правки.
- `overlay/LabelLayout.kt`: геометрия карточек — привязка к исходному тексту, свободный прямоугольник вокруг блока, рост только в него, однострочные подписи без переноса по слогам. Чистая Kotlin-логика.
- `res/drawable/ic_launcher_*.xml`: исходники адаптивной векторной иконки; `ic_brand.xml` для интерфейса; `ic_notification.xml` для уведомления.
- `src/test/`: JVM-тесты алгоритмов и orchestration без эмулятора.
- `docs/DEVICE_TEST_PLAN.md`: сценарии будущей проверки на устройстве.
- `docs/IMPLEMENTATION.md`: технические решения и ограничения.

## Инварианты

1. Ни Bitmap, ни Image, ни screenshot не передавать в сетевой слой, не сохранять на диск и не логировать. Сетевые DTO содержат только текст и языки. Не логировать полный OCR-текст, перевод, SID или API key.
2. Снимок освобождается после OCR и расчёта яркости **до** сетевого перевода. Задачи ML Kit не отменяемы: дождаться native task перед recycle/close даже при отмене coroutine.
3. MediaProjection создаётся только после системного согласия, внутри уже запущенного foreground service. Intent согласия нельзя сохранять или повторно использовать.
4. На одно согласие ровно один createVirtualDisplay. Повороты используют resize и замену Surface. START_NOT_STICKY; после завершения/перезапуска требуется новое согласие. Если систему остановила проекцию (блокировка экрана, системный индикатор), сессия переходит в PAUSED: сервис, кнопка и уведомление остаются, а новое согласие запрашивается по нажатию. Intent согласия по-прежнему нельзя сохранять или использовать повторно.
5. Скрывать кнопку до получения нового кадра. Старые результаты после поворота/изменения настроек/остановки отбрасывать по generation.
6. Переводы рисуются в **одном** TYPE_APPLICATION_OVERLAY окне с FLAG_NOT_TOUCHABLE и FLAG_NOT_FOCUSABLE. На Android 12+ даже непринимающее касания окно перекрывает нижние, поэтому его alpha ограничена `maximumObscuringOpacityForTouch`; настройка прозрачности управляет фоном карточек. Управляющее окно кнопки отдельно принимает касания. Единственное исключение — режим правки: окно временно теряет FLAG_NOT_TOUCHABLE, забирает все касания себе и потому не ограничено по alpha. Режим правки живёт только пока на экране есть переводы и снимается вместе с ними.
7. Управляющее окно принимает касания только в пределах кнопки. READY → PROCESSING → TRANSLATED; повторная обработка игнорируется; tap в TRANSLATED очищает, tap в PAUSED запрашивает согласие на захват заново. Долгое нажатие открывает настройки, а в TRANSLATED переключает режим правки; tap в режиме правки выходит из него и ничего не очищает.
8. Не проглатывать CancellationException в retry/fallback. Ожидающие одинаковый запрос должны завершаться и при отмене владельца; cleanup выполнять в NonCancellable.
9. Ключ кэша: provider + source + target + нормализованный исходный текст. Сохранять регистр и пунктуацию. Кэш и настройки не участвуют в Android backup/device-transfer.
10. Google/Yandex — веб-протоколы без платных Cloud API. Ключ и SID живут только в памяти. Изменения endpoint/авторизации держать внутри providers. Не выполнять JS удалённого сервера.
11. Существующее ТЗ определяет min/target/compile SDK; не повышать их автоматически из-за подсказки Lint.
12. Не коммитить local.properties, APK, Gradle-кэш, подписи, ключи или машинные пути. Не создавать git commit без просьбы пользователя.
13. Перевод рисуется на месте распознанного текста. Карточка сохраняет исходную рамку, растёт только в свободное место рядом и никогда не переносится в другую часть экрана и не собирается в общую панель: положение — единственная связь перевода с оригиналом. Координаты OCR приводятся к экрану через `FrameMapping`.

## Сборка и проверки (PowerShell)

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.
JUnit: `app/build/reports/tests/testDebugUnitTest/index.html`.
Lint: `app/build/reports/lint-results-debug.html`.

Проект использует существующие Gradle Wrapper 9.6.0, AGP 9.4.0 и daemon JVM 25 из Android Studio. AGP использует встроенный Kotlin; не подключать второй kotlin-android plugin. Версии Compose plugin/библиотек находятся в `gradle/libs.versions.toml`.
При ограничении sandbox сборке нужен доступ к Android SDK, Gradle-кэшу и Maven. Не менять машинную конфигурацию ради обхода ограничения; запрашивать штатную эскалацию команды.

Не заменять реальные тесты арифметическим шаблоном. Для pure Kotlin тестировать меню/абзацы/колонки/смешанные письменности/дубли, пакетирование и surrogate pairs. Для менеджера использовать поддельные TranslationProvider и TranslationStore. UI/OCR/MediaProjection/tap-through необходимо отдельно проверить на Android; успешная сборка не доказывает их работу на устройстве.

