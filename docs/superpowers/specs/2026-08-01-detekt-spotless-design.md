# Статичний аналіз: detekt і Spotless

**Дата:** 2026-08-01
**Статус:** затверджено

## Проблема

У проєкті немає жодного інструмента статичного аналізу. Стиль коду тримається на дисципліні
автора та налаштуваннях IDE, а розділ «Contributing» у `README.md` просить «keep the code style
consistent», не пояснюючи, що це означає й чим це перевірити. Немає ні `.editorconfig`, ні
лінтера, ні перевірки на рівні збірки. Розбіжності у форматуванні спливають у код-рев'ю замість
того, щоб виправлятися автоматично.

## Мета

1. Форматування Kotlin-коду перевіряється й виправляється автоматично, однією командою.
2. Типові дефекти (складність, потенційні баги, стилістика) ловляться лінтером до мерджу.
3. І те, і те падає локально на `./gradlew check` та окремим швидким чеком у CI.

## Обсяг

**Входить:**

- Плагін Spotless із форматером ktlint — **тільки для файлів `*.kt`**.
- Плагін detekt 2.0.0-alpha.5 (координати `dev.detekt`) із дефолтним набором правил і
  невеликим файлом точкових відхилень.
- Файл `.editorconfig` у корені як джерело правил для ktlint та IDE.
- Новий джоб `static-analysis` у наявному `.github/workflows/ci.yml`.
- Одноразове переформатування наявного коду та виправлення знайдених зауважень.
- Файл `.git-blame-ignore-revs` із коммітом переформатування.
- Оновлення розділу «Contributing» у `README.md`.

**Не входить:**

- Spotless для `*.gradle.kts`, `*.md`, `*.yaml`, `*.toml` — свідомо, за рішенням щодо обсягу.
- Ruleset `detekt-rules-ktlint-wrapper` — форматування веде виключно Spotless.
- Режим detekt як плагіна компілятора (`enableCompilerPlugin`) і, відповідно, type resolution.
- `detekt-baseline.xml` — усі зауваження розгрібаються одразу.
- Локальний pre-commit hook.
- Покриття коду, binary-compatibility-validator.

## Розподіл відповідальності

Два інструменти не перетинаються, і це не збіг, а умова конфігурації:

| Інструмент | Відповідає за | Виправляє автоматично |
|---|---|---|
| Spotless + ktlint | розкладку тексту: відступи, переноси, пробіли, порядок імпортів | так, `spotlessApply` |
| detekt | смисл коду: складність, потенційні баги, іменування, мертвий код | ні |

У detekt 2 форматувальні правила винесені в окремий артефакт
`dev.detekt:detekt-rules-ktlint-wrapper`, який публікується самостійно й не входить у типову
залежність плагіна. Ми його не підключаємо — отже, накладання правил ktlint із двох боків
неможливе за побудовою, без жодних ручних виключень у конфігу.

## Архітектура

### Version catalog

`gradle/libs.versions.toml`:

```toml
[versions]
detekt = "2.0.0-alpha.5"
spotless = "8.9.0"

[plugins]
detekt = { id = "dev.detekt", version.ref = "detekt" }
spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
```

Версія ktlint окремо не фіксується: Spotless 8.9.0 везе ktlint 1.8.0 і викликається як
`ktlint()` без аргументів. Оновлення ktlint відбувається разом з оновленням Spotless — одна
змінна замість двох, які треба тримати сумісними.

### Підключення в корені

Обидва плагіни оголошуються в кореневому `build.gradle.kts` з `apply false` і роздаються через
наявний блок `allprojects {}` — так само, як там уже роздається Dokka:

```kotlin
import com.diffplug.gradle.spotless.SpotlessExtension
import dev.detekt.gradle.extensions.DetektExtension

plugins {
    // ... наявні плагіни
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless) apply false
}

allprojects {
    // ... наявна конфігурація
    apply(plugin = "org.jetbrains.dokka")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "dev.detekt")

    extensions.configure<SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            targetExclude("**/build/**")
            ktlint()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        parallel = true
        basePath = rootDir
    }
}
```

Конфігурація ведеться через `extensions.configure<T>()`, а не через блоки `spotless { }` /
`detekt { }`: типізовані аксесори Gradle генеруються лише для плагінів, застосованих у блоці
`plugins {}` того самого скрипта, а тут плагіни застосовуються динамічно всередині
`allprojects`. Класи розширень доступні для імпорту, бо `apply false` усе одно кладе плагін на
classpath збірки.

Кореневий проєкт теж отримує обидва плагіни. Він не має каталогу `src`, тож `target("src/**")`
дає порожній набір, а задачі `:spotlessCheck` і `:detekt` виконуються вхолосту. Це прийнятна
ціна за одне місце конфігурації: третій модуль, доданий у майбутньому, потрапляє під аналіз
автоматично.

Окрема проводка до `check` не потрібна — обидва плагіни чіпляють свої `*Check`-задачі самі.

### `.editorconfig`

Новий файл у корені репозиторію. Саме звідти ktlint читає правила; без нього форматер працює на
власних дефолтах, які можуть розійтися з IDE.

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
indent_style = space
indent_size = 4
insert_final_newline = true
trim_trailing_whitespace = true

[*.{kt,kts}]
ktlint_code_style = intellij_idea
max_line_length = 120

[*.{yml,yaml,json,toml}]
indent_size = 2
```

Ключове рішення — `ktlint_code_style = intellij_idea`, а не `ktlint_official`. У
`gradle.properties` уже стоїть `kotlin.code.style=official`, тобто IDE форматує код у стилі
JetBrains. `ktlint_official` — це власний, помітно суворіший стиль ktlint (зокрема інші правила
переносу параметрів і обов'язкові trailing commas). Обравши `intellij_idea`, ми гарантуємо, що
`Cmd+Alt+L` в IDE та `spotlessApply` дають однаковий результат; інакше розробник, який
відформатував файл засобами IDE, отримував би червоний `spotlessCheck`.

### `config/detekt/detekt.yml`

Файл містить **лише** відхилення від дефолту (`buildUponDefaultConfig = true`), а не копію
повного конфігу на ~800 рядків. Це навмисно: копія дефолту застаріває з кожним оновленням
detekt і приховує, що саме ми змінили свідомо.

Допускаються записи рівно двох видів:

1. **Вимкнене або послаблене правило** — обов'язково з коментарем, який пояснює причину.
2. **Виключення тестових шляхів.** Дефолтні виключення detekt орієнтовані на шаблони на кшталт
   `**/test/**`, тоді як у KMP-модулі шляхи мають вигляд `paging-core/src/commonTest/kotlin/…`.
   Тестові патерни (`**/commonTest/**`, `**/jvmTest/**`, `**/androidUnitTest/**`) виносяться в
   YAML-anchor і перевикористовуються правилами, які на тестах шумлять.

Конкретний перелік записів формується під час реалізації за результатом першого прогону.
Правило ухвалення рішення задане тут і не залишає простору для «хай поки шумить»: кожне
зауваження або виправляється в коді, або глушиться в `detekt.yml` із письмовим обґрунтуванням.

### CI

У наявний `.github/workflows/ci.yml` додається третій джоб — паралельно до `test-linux` і
`test-apple`:

```yaml
  static-analysis:
    name: Static analysis
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Tune Gradle memory for CI runner
        run: |
          mkdir -p ~/.gradle
          {
            echo "org.gradle.jvmargs=-Xmx4g"
            echo "kotlin.daemon.jvmargs=-Xmx4g"
          } >> ~/.gradle/gradle.properties

      - name: Make gradlew executable
        run: chmod +x ./gradlew

      - name: Run Spotless and detekt
        run: ./gradlew spotlessCheck detekt

      - name: Upload analysis reports
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: static-analysis-reports
          path: '**/build/reports/detekt/**'
          retention-days: 7
```

Три рішення в цьому джобі варті пояснення.

**Немає кешу `~/.konan`.** Standalone-задача detekt не компілює код, тож тулчейн Kotlin/Native
не потрібен. Це і є причина, чому джоб швидкий: секунди-хвилини проти сорока з гаком хвилин у
тестових джобах, тобто фідбек про формат приходить задовго до результатів тестів.

**Немає `-PexcludeSamples=true`**, на відміну від `test-linux`. Модуль `paging-samples` — теж
наш код, і виключати його з аналізу означало б лишити третину репозиторію без перевірки. Важкої
збірки тут немає, а Android SDK на `ubuntu-latest` передвстановлений, тож конфігурація модуля
проходить.

**Джоб додається в `needs` гейта `ci`** разом із перевіркою результату:

```yaml
  ci:
    needs: [static-analysis, test-linux, test-apple]
    # ...
    [ "${{ needs.static-analysis.result }}" = "success" ] || exit 1
```

Завдяки цьому налаштування branch protection міняти не треба: required status check `ci` уже
налаштований і тепер починає враховувати статичний аналіз.

**Зміна в наявному джобі `test-linux`.** Щоб не ганяти ті самі задачі двічі, крок `Run checks`
доповнюється виключеннями:

```
./gradlew -PexcludeSamples=true check \
  -x kotlinStoreYarnLock -x kotlinWasmStoreYarnLock \
  -x spotlessCheck -x detekt
```

## Критерії приймання

1. `./gradlew spotlessCheck detekt` на чистому дереві завершується успіхом.
2. `./gradlew check` без додаткових прапорців виконує обидві задачі (перевіряється за
   `--dry-run`).
3. **Покриття source set'ів.** У KMP detekt реєструє і зведену задачу, і задачі на компіляцію;
   на що саме дивиться `./gradlew detekt` у 2.0.0-alpha.5, перевіряється емпірично: тимчасове
   порушення вноситься в `paging-core/src/commonMain` і в `paging-samples/src/iosMain`, обидва
   мають бути знайдені. Якщо зведена задача покриває не все — потрібні задачі довішуються до
   `check` явно.
4. **Configuration cache.** У проєкті стоїть `org.gradle.configuration-cache=true`. Два
   послідовні прогони `./gradlew spotlessCheck detekt`, другий має повідомити
   `Reusing configuration cache`.
5. **Ідемпотентність форматера.** Повторний `./gradlew spotlessApply` після першого не дає
   змін у робочому дереві.
6. **Узгодженість з IDE.** Форматування довільного файлу засобами IDE (`Cmd+Alt+L`) не створює
   розбіжності зі `spotlessCheck`.
7. Джоб `static-analysis` зелений на пул-ріквесті, гейт `ci` враховує його результат.

## План викочування

Три окремі комміти — послідовність важлива:

1. **Інфраструктура.** `libs.versions.toml`, кореневий `build.gradle.kts`, `.editorconfig`,
   `config/detekt/detekt.yml`, `ci.yml`. Збірка на цьому коміті ще червона — це очікувано.
2. **`spotlessApply`.** Масове переформатування ~45 файлів і **нічого більше**: жодних правок
   логіки, жодних змін конфігу.
3. **Правки під detekt.** Виправлення в коді плюс фінальні записи в `detekt.yml`.

Другий комміт тримається окремим саме для того, щоб додати його SHA у новий файл
`.git-blame-ignore-revs` — інакше `git blame` по всій бібліотеці почне вказувати на
переформатування замість авторів реальних змін. Файл підхоплюється GitHub автоматично; для
локального `git blame` у README додається команда
`git config blame.ignoreRevsFile .git-blame-ignore-revs`.

## Ризики

**detekt 2.0.0-alpha.5 — альфа.** Це усвідомлений вибір: гілка 1.23.x не розвивається й має
відомі проблеми з Gradle 9 (у проєкті — Gradle 9.2.1), тоді як 2.x на нього орієнтована. Ризик
проявиться на першому ж локальному прогоні, не пізніше. Відкат — зміна двох рядків у version
catalog на `io.gitlab.arturbosch.detekt` 1.23.8 плюс зміна id плагіна; він не безкоштовний, і
якщо 1.23.8 не заведеться на Gradle 9, альтернативою лишається тимчасово підключити detekt лише
до `paging-core` або відкласти detekt, залишивши Spotless.

**Configuration cache.** Spotless 8.x підтримує його повністю з версії 7.0. Для alpha-версії
detekt це не гарантовано. Якщо кеш ламається — джоб `static-analysis` тимчасово запускається з
`--no-configuration-cache`; це локалізує проблему в CI й не чіпає решту збірки.

**Обсяг переформатування.** 45 файлів у другому коміті — великий diff. Пом'якшується тим, що він
ізольований і потрапляє в `.git-blame-ignore-revs`.

## Документація

Розділ «Contributing» у `README.md` наразі просить «keep the code style consistent», не
пояснюючи як. Замінюється на конкретику: `./gradlew spotlessApply` перед коммітом,
`./gradlew spotlessCheck detekt` для перевірки, згадка про `.editorconfig` як джерело правил і
рядок про `blame.ignoreRevsFile`.

## Ручні кроки після мерджу

Немає. Гейт `ci` уже налаштований як required status check і починає враховувати новий джоб
автоматично.
