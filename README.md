# Folio — открытый просмотрщик документов для Android
надо заново
Kotlin + Jetpack Compose. Разработка: только VS Code + GitHub. Проект целиком открытый, лицензия **AGPL-3.0-or-later**
(требование MuPDF — основного движка).

## Что умеет (v0.1, каркас)

| Функция | Статус |
|---|---|
| PDF, EPUB, MOBI, FB2, CBZ, XPS, SVG, TXT, HTML, изображения | через MuPDF |
| DOCX / XLSX / PPTX / ODF | через MuPDF ≥ 1.25 (проверьте версию в `app/build.gradle.kts`) |
| CBR | junrar → конвертация в CBZ |
| DjVu | **не реализовано**: нужен NDK-модуль на djvulibre (GPL), интерфейс `DocumentEngine` готов |
| Перо, маркер, подчёркивание, ластик, заметки | нормализованные координаты страницы, не зависят от текстового слоя; пишутся в Room |
| Зум щипком, поиск, оглавление, пароль | есть |
| Миниатюры | скрываемая панель, ленивый рендер, значок аннотаций |
| Библиотека | SAF-папки, WorkManager-скан, обложки, прогресс, избранное, фильтры, сортировка |
| Экспорт в PDF | аннотации пишутся стандартными Ink/Text-аннотациями |
| Редактирование страниц (склейка, поворот, удаление), формы, подпись | план |
| Выделение по словам при наличии текстового слоя (MuPDF StructuredText) | план |
| Тайловый рендер при большом зуме (сейчас потолок 2400 px) | план |

Аннотации в EPUB/FB2 привязаны к параметрам вёрстки (ширина × высота × размер шрифта): при смене шрифта они скрываются
и возвращаются при тех же параметрах. Привязка к CFI — следующий шаг.

## Сборка

```bash
gradle wrapper --gradle-version 8.11.1   # один раз, закоммитьте gradlew и gradle/wrapper
./gradlew :app:assembleDebug
./gradlew :app:installDebug              # телефон по USB, adb
```
Нужны JDK 17 и Android SDK (`ANDROID_HOME`, platform 35, build-tools). CI: `.github/workflows/build.yml`
собирает APK на каждый push, а на публикацию релиза прикладывает APK.

## Структура

- `engine/` — `DocumentEngine`, `MuPdfEngine`, `SafeEngine` (мьютекс + кэш), `PdfExporter`, `ComicConverter`
- `data/` — Room, открытие документов (SAF → кэш), `ScanWorker`
- `annotations/` — модель штрихов и `PageOverlay` (Compose Canvas, перо/стилус)
- `ui/` — библиотека, читалка, ViewModel'и

## Важно проверить при первой сборке

Код не компилировался в среде автора. Самое вероятное место правок — вызовы MuPDF Java API, их сигнатуры немного
меняются между версиями (`Page.search`, `setInkList`, `AndroidDrawDevice.drawPageFitWidth`, `resolveLink`).
Смотрите javadoc выбранной версии: https://maven.ghostscript.com/com/artifex/mupdf/fitz/

## Лицензии зависимостей

MuPDF — AGPL-3.0; junrar — unrar-лицензия (открытая); AndroidX, Compose, Room, WorkManager — Apache-2.0;
djvulibre (если добавите) — GPL-2.0+, совместима с AGPL-3.0.
