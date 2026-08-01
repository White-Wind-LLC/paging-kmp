# CI: тестування коду на пул-ріквестах

**Дата:** 2026-08-01
**Статус:** затверджено

## Проблема

У репозиторії є єдиний workflow — `publish.yml`, який спрацьовує на теги `v*` і публікує
`paging-core` у Maven Central. Пул-ріквести не перевіряються нічим: зламані тести або таргет, що
не компілюється, виявляються лише під час публікації релізу.

## Мета

Кожен PR у `main` автоматично запускає тести й перевіряє, що всі таргети `paging-core`
компілюються. Результат — один статус-чек, придатний для branch protection.

## Обсяг

**Входить:**

- Запуск тестів `commonTest` на JVM, Android, JS (Node), Wasm/JS (Node), linuxX64, macosArm64,
  iosSimulatorArm64.
- Компіляція решти таргетів `paging-core`: linuxArm64, macosX64, iosX64, iosArm64.
- Публікація звітів про тести: артефакти при падінні + зведення й анотації в PR.

**Не входить:**

- Збірка модуля `paging-samples` (виконується з `-PexcludeSamples=true`).
- `apiCheck` / binary-compatibility-validator.
- Лінтери (ktlint, detekt), покриття коду.
- Windows-раннер.

## Архітектура

Новий файл `.github/workflows/ci.yml` з назвою `CI`, незалежний від `publish.yml`. Три джоби.

### Тригери

```yaml
on:
  pull_request:
    branches: [main]
  push:
    branches: [main]
  workflow_dispatch:

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}
```

Прогони на `main` не переривати — скасування застарілих запусків діє лише для пул-ріквестів.

### Джоб `test-linux` (`ubuntu-latest`)

Виконує `./gradlew -PexcludeSamples=true check`. Одна команда покриває:

- `jvmTest`, `jsNodeTest`, `wasmJsNodeTest`, `linuxX64Test`, android unit-тести;
- компіляцію `linuxArm64` і `mingwX64` — залежно від того, що Kotlin підтримує на Linux-хості.

Кроки:

1. `actions/checkout@v4`
2. `actions/setup-java@v4` — temurin, java 17
3. `gradle/actions/wrapper-validation@v4`
4. `gradle/actions/setup-gradle@v4`
5. `actions/cache@v4` для `~/.konan`
6. `chmod +x ./gradlew`
7. запуск `check`
8. звіти (див. нижче)

### Джоб `test-apple` (`macos-latest`)

Ті самі кроки підготовки, але замість `check` — явний список тасок:

- тести: `:paging-core:macosArm64Test`, `:paging-core:iosSimulatorArm64Test`;
- компіляція: `macosX64`, `iosX64`, `iosArm64` (`compileKotlin*` і `compileTestKotlin*`).

`macosX64Test` не запускається: раннер `macos-latest` має arm64-архітектуру, і виконання
x86-бінарників через Rosetta повільне та ненадійне. Таргет перевіряється компіляцією.

Джоб не запускає jvm/js/wasm-тести — вони вже виконані на Linux, дублювати їх на дорожчому
раннері немає сенсу.

### Джоб `ci` (gate)

```yaml
needs: [test-linux, test-apple]
if: always()
```

Падає, якщо будь-який із залежних джобів не завершився успіхом. Дає один стабільний статус-чек
`ci` для branch protection — при зміні набору джобів налаштування захисту гілки міняти не треба.

## Конфігурація Gradle в CI

`gradle.properties` не змінюємо — параметри перекриваються прапорцями командного рядка, щоб не
псувати локальну розробку.

**Памʼять.** Файл задає `org.gradle.jvmargs=-Xmx6G` і `kotlin.daemon.jvmargs=-Xmx6G`. Раннер
`macos-latest` має ~7 GB RAM, чого для двох таких процесів не вистачає. У CI передаємо:

- ubuntu: `-Dorg.gradle.jvmargs=-Xmx4g -Dkotlin.daemon.jvmargs=-Xmx4g`
- macOS: `-Dorg.gradle.jvmargs=-Xmx3g -Dkotlin.daemon.jvmargs=-Xmx3g`

**Yarn lockfile.** `kotlin-js-store` закомічений у репозиторій. CI **не** запускає
`kotlinUpgradeYarnLock` (на відміну від `publish.yml`): якщо лок розійшовся із залежностями,
збірка падає, і автор PR оновлює лок сам. Це навмисна перевірка.

**Таймаут.** `timeout-minutes: 45` на кожен джоб із тестами.

## Кешування

- `gradle/actions/setup-gradle@v4` кешує Gradle-кеші. Поведінка за замовчуванням підходить:
  кеш записується лише з дефолтної гілки, PR читають його.
- `~/.konan` кешується окремим `actions/cache@v4` з ключем по хешу `gradle/libs.versions.toml`.
  Без цього кожен джоб завантажує тулчейн Kotlin/Native заново.

## Звіти про тести

- `actions/upload-artifact@v4` з `if: failure()` — шляхи `**/build/reports/tests/**` та
  `**/build/test-results/**`, retention 7 днів.
- `mikepenz/action-junit-report@v5` з `if: always()` — парсить JUnit XML, пише зведення в
  GitHub Job Summary і ставить анотації на рядках із впалими тестами в diff пул-ріквеста.

Обидва кроки додаються в кожен із джобів `test-linux` та `test-apple`; імена артефактів
розрізняються за джобом.

## Відомі пробіли

**`mingwX64`.** Якщо Kotlin не підтримує крос-компіляцію Windows-таргета з Linux-хоста,
`check` на ubuntu цей таргет пропустить. Windows-раннер навмисно не додається (свідоме рішення
щодо вартості та часу прогону). Наслідок: `mingwX64` перевіряється лише під час публікації
релізу. Рішення переглядається, якщо цей таргет колись зламається на релізі.

## Ручні кроки після мерджу

У Settings → Branches для гілки `main` увімкнути required status check `ci`. Це не налаштовується
файлами в репозиторії.

## Документація

Додати бейдж статусу CI на початок `README.md`.
