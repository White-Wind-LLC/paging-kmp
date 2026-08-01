# Інваріант `cacheSize >= preloadSize`

**Дата:** 2026-08-01
**Статус:** затверджено
**Issue:** [#13](https://github.com/White-Wind-LLC/paging-kmp/issues/13)

## Проблема

Конфігурація пейджерів валідується лише на знак: `loadSize > 0`, `preloadSize >= 0`,
`cacheSize >= 0`. Ніде не перевіряється співвідношення `cacheSize` і `preloadSize`, хоча між ними
є жорстка залежність: пейджер завантажує дані у вікні радіусом `preloadSize` навколо останнього
прочитаного ключа, а утримує в пам'яті лише вікно радіусом `cacheSize` навколо того самого ключа.

Коли `cacheSize < preloadSize`, пейджер відкриває стріми на вікно, яке не здатен утримати.
`StreamingPagerState.onPortion` обрізає кожну порцію, що приходить, по `cacheRange` і викидає
більшу її частину одразу після отримання, а стріми лишаються відкритими й далі штовхають дані,
які так само викидаються. Помилки немає, попередження немає, логу немає.

Заміряно на `loadSize=20, preloadSize=100, cacheSize=40` зі стрибком на позицію 500:

- 340 елементів витягнуто по мережі
- 81 елемент утримано
- ~76% трафіку викинуто на вході
- 11 портційних стрімів лишилися відкритими й довічно штовхають дані в нікуди

Та сама залежність існує в `Pager` (`Pager.kt:228` проти `Pager.kt:330`) і, транзитивно, у
`PagingMediatorConfig`, який пробрасує `prefetchSize`/`cacheSize` у `Pager`.

Додатково, KDoc сам вводить в оману: у `Pager` написано «Maximum number of items to keep in
memory (default: 100)», хоча `cacheSize` — це радіус, і реально утримується до `2*cacheSize+1`
елементів. Формулювання «Cache radius in indices around the last accessed key» у
`StreamingPagerConfig` не бреше, але й не натякає, що радіус має бути не меншим за preload-радіус.

## Мета

1. Невалідне співвідношення `cacheSize`/`preloadSize` виявляється одразу при створенні пейджера,
   а не проявляється як мовчазна втрата трафіку.
2. Повідомлення про помилку пояснює зв'язок між параметрами, а не лише констатує факт.
3. Документація описує обидва параметри як радіуси та фіксує інваріант.

## Обсяг

**Входить:**

- `require(cacheSize >= preloadSize)` у `StreamingPagerConfig`, `Pager`, `PagingMediatorConfig`.
- Перенесення наявних `require` зі `StreamingPager.init` у `init` самого `StreamingPagerConfig`.
- Повний набір `require` у `Pager`, який зараз не має жодної валідації.
- Виправлення KDoc для `cacheSize`/`preloadSize`/`prefetchSize` у трьох місцях + коментарі в
  `README.md`.
- Приведення власних тестів до інваріанта та переписування репро-тесту F8 у регресійний.
- Нові тести на валідацію для всіх трьох конфігурацій.
- Запис у `CHANGELOG.md`.

**Не входить:**

- Clamp `preloadSize` до `cacheSize` — свідомо відкинуто на користь fail-fast.
- Логер у `Pager`/`PagingMediator` — потрібен був би лише для варіанта з clamp.
- Зміна сітки чанків у `StreamingPager` — залишковий відкид на краях сітки документується,
  але не виправляється в межах цього issue.
- Валідація інших полів (`concurrency`, `closeThreshold` понад наявну перевірку знаку).

## Рішення

### Чому fail-fast, а не clamp

`cacheSize < preloadSize` — це помилка конфігурації, у якої немає розумного runtime-фолбеку.
Мовчазний clamp дав би користувачу менший preload, ніж він просив, і сховав би причину. Виняток
при створенні пейджера вказує на конкретний рядок конфігурації.

Це поведінково ламаюча зміна для застосунків із нині невалідною конфігурацією: замість тихої
деградації вони отримають падіння. Такі застосунки вже зараз палять трафік і батарею, тож зміна
робить наявну проблему видимою, а не створює нову. Реліз має бути мінорним, не патчем.

### Чому саме `cacheSize >= preloadSize`

У `StreamingPager` реальне вікно стрімів вирівняне по сітці `loadSize` й тому виходить за
preload-радіус: центральний чанк, що містить ключ, розширюється на `preloadSize` у кожен бік, а
далі вікно замощується чанками, останній з яких може вийти за межу вікна. Точна умова «жоден
завантажений елемент не викидається одразу» — це `cacheSize >= preloadSize + 2*loadSize - 2`.

Ця формула не годиться як `require`: її важко пояснити, вона прив'язана до деталі реалізації
планувальника чанків і відхиляла б конфігурації, які втрачають одиниці відсотків. Тому:

- `require` фіксує просте `cacheSize >= preloadSize` — воно прибирає катастрофічний випадок;
- KDoc рекомендує `cacheSize >= preloadSize + loadSize` для `StreamingPager`, щоб прибрати й
  залишковий відкид на краях сітки.

Дефолти бібліотеки (`loadSize=20, preloadSize=60, cacheSize=100`) задовольняють обидві умови.

### Де саме валідувати

| Місце | Перевірки |
|---|---|
| `StreamingPagerConfig.init` | `loadSize > 0`, `preloadSize >= 0`, `closeThreshold >= 0`, `keyDebounceMs >= 0`, `cacheSize >= preloadSize` |
| `Pager.init` | `loadSize > 0`, `preloadSize >= 0`, `cacheSize >= preloadSize` |
| `PagingMediatorConfig.init` | `loadSize > 0`, `prefetchSize >= 0`, `cacheSize >= prefetchSize` |

`cacheSize >= preloadSize` разом із `preloadSize >= 0` покриває `cacheSize >= 0`, тож окрема
перевірка знаку `cacheSize` стає надлишковою і прибирається.

Валідація `StreamingPagerConfig` переїжджає з `StreamingPager.init` у `init` самого data-класу:
`init` виконується і для `copy()`, тож ловить випадки, де конфігурація мутується після створення й
передається кудись іще. Після перенесення `StreamingPager.init` порожній і видаляється — єдине
джерело правди лишається одне.

`PagingMediatorConfig` валідує сам, попри те що зрештою делегує в `Pager`: параметр там зветься
`prefetchSize`, і повідомлення має називати те ім'я, яке користувач бачить у своєму коді.

### Повідомлення про помилку

Єдиний формат для всіх трьох місць, із фактичними значеннями та поясненням зв'язку:

```
cacheSize (40) must be >= preloadSize (100): the pager retains only ±cacheSize items around
the last accessed key, so a wider preload radius fetches data that is discarded on arrival
```

Для `PagingMediatorConfig` — те саме з `prefetchSize` замість `preloadSize`.

### Документація

`cacheSize` і `preloadSize` описуються як **радіуси в індексах** навколо останнього прочитаного
ключа, з явним зазначенням, що кеш утримує до `2*cacheSize+1` елементів. KDoc кожного з трьох
конфігів фіксує інваріант; KDoc `StreamingPagerConfig` додатково містить рекомендацію
`cacheSize >= preloadSize + loadSize`.

У `README.md` виправляються коментарі-описи біля прикладів конфігурації (рядки ~86–87, ~239–240,
~295–296), де `cacheSize` названо «max items retained in memory».

## Вплив на наявні тести

Два місця у власному коді порушують майбутній інваріант:

1. **`PagerTest.moving_far_evicts_outside_cache_range`** (`cacheSize=40, preloadSize=60`) —
   змінюється на `cacheSize=60, preloadSize=60`. Асерти лишаються без змін і лишаються валідними:
   вони перевіряють, що ключі лежать у `400±preloadSize`, а `cacheRange` тепер збігається з
   preload-вікном.

2. **`DiagnosticsFindingsTest.f8_cache_smaller_than_preload_streams_data_that_is_discarded`** —
   це і є репро з issue, воно закріплює погану поведінку числами `fetched=340, retained=81`.
   Переписується у стилі «(fixed)», як зроблено для F9: конфігурація `20/100/40` тепер кидає
   `IllegalArgumentException`, а поруч перевіряється валідна конфігурація `20/100/120`, для якої
   фіксуються фактичні `fetched`/`retained` — частка викинутого падає з ~76% до одиниць відсотків.
   Конкретні числа знімаються з реального прогону на етапі імплементації.

Решта тестів і семплів інваріант не порушують: `StreamingPagerTest` скрізь має
`cacheSize >= preloadSize` (найтісніше — `preloadSize=5, cacheSize=10`), `WindowHelpersTest`
використовує `20/20/200`, семпл `StreamingUserListViewModel` — дефолти `20/60/100`.

## Нові тести

Для кожної з трьох конфігурацій:

- невалідне співвідношення кидає `IllegalArgumentException`, і повідомлення містить обидва імена
  параметрів та їхні значення;
- межовий випадок `cacheSize == preloadSize` створюється успішно.

Для `StreamingPagerConfig` додатково: `copy(cacheSize = ...)`, що порушує інваріант, теж кидає —
це те, заради чого валідація переїхала в data-клас.

## Критерії готовності

1. `./gradlew check` проходить (включно з detekt і Spotless).
2. Конфігурація з issue (`20/100/40`) кидає `IllegalArgumentException` для всіх трьох пейджерів.
3. Дефолтні конфігурації створюються без змін у поведінці.
4. KDoc усіх трьох `cacheSize` описує радіус і фіксує інваріант; `README.md` узгоджений.
5. `CHANGELOG.md` містить запис під `[Unreleased] / ### Changed` із посиланням на #13.
