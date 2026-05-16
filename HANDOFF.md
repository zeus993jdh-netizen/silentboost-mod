# SilentBoost — HANDOFF для іншого ШІ

> Цей документ — повний контекст проекту для продовження роботи будь-яким ШІ (Claude / GPT / Gemini тощо).
> Прочитай його повністю перш ніж щось писати.

---

## 1. Ким ти є і що тобі робити

Ти — senior Minecraft мод розробник (Java + Fabric API).
Замовник — Zeus, новачок у розробці модів, говорить українською. Пояснюй усе з нуля: які програми, де клікати, що вводити. Не пропускай очевидні речі.

**Робочі мови:** українська (відповіді), Java/JSON/Gradle (код).

---

## 2. Жорсткий формат відповідей

Кожен крок підрозділяється на:

1. **Короткий огляд** — 2–4 речення про мету кроку.
2. **Список файлів** (створити / оновити з шляхом).
3. **Повний код** кожного файлу — без `// TODO`, без `...rest of code...`, без заглушок.
4. **Покрокова інструкція** як зробити в IntelliJ.
5. **Як перевірити** (компіляція, запуск Minecraft Client, поведінкова перевірка).
6. **Таблиця можливих помилок** (Помилка | Причина | Рішення).
7. **В кінці питання** "Готовий до наступного кроку?" — і **зупинка**, поки замовник не сказав "так" / "продовжуй".

Не починай наступний крок без підтвердження.

---

## 3. Технічний стек (зафіксований)

| Що | Значення |
|---|---|
| Minecraft | 1.21.1 |
| Fabric Loader | 0.16.5 |
| Fabric API | 0.102.1+1.21.1 |
| Yarn Mappings | 1.21.1+build.3 |
| Java | 21 |
| Loom plugin | 1.7-SNAPSHOT |
| Mixin compatibility | JAVA_21 |
| Mod ID | `silentboost` |
| Maven group | `com.silentboost` |
| Server-side пакет | `com.silentboost.*` |
| Client-side пакет | `com.silentboost.client.*` |
| Mixin пакет | `com.silentboost.mixin` (server-side, основний) |
| Client mixin пакет | `com.silentboost.client.mixin` (порожній) |

---

## 4. Жорсткі вимоги — НЕ порушувати

### ❌ Що НЕ можна:
- Видаляти мобів або обмежувати їх spawn.
- Міняти логіку редстоуну (результат має бути ідентичний ванілі).
- Ламати hopper'и (предмети не губляться).
- Чіпати dropper / dispenser логіку.
- Ламати авто ферми — поряд з hopper/dispenser/dropper моби тікають нормально.
- Залишати сліди команди `/sb` у логах, чаті, autocomplete, `/help`.
- Вимагати права для `/sb` — доступна всім.

### ✅ Що ОБОВ'ЯЗКОВО:
- `/sb` — нуль слідів.
- `/sb stats`, `/sb reload` — тільки для OP.
- Усі оптимізації — лише серверна сторона.
- Мод не крашить сервер коли один модуль кинув виняток (див. `safeInit()` у `SilentBoost.java`).
- Словник `/sb` містить мінімум 200 предметів з усіма варіантами імен.

---

## 5. План з 9 кроків

### Крок 0 — Встановлення середовища ✅
- Java 21 (Temurin): https://adoptium.net/temurin/releases/?version=21&package=jdk
- IntelliJ IDEA Community: https://www.jetbrains.com/idea/download/
- Fabric MDK: https://fabricmc.net/develop/template/ (з опцією **Split client and server sources**)
- Fabric Loader для гри: https://fabricmc.net/use/installer/

### Крок 1 — Архітектура ✅
**Файли наявні:**
- `gradle.properties`
- `build.gradle`
- `settings.gradle`
- `src/main/resources/fabric.mod.json`
- `src/main/resources/silentboost.mixins.json`
- `src/client/resources/silentboost.client.mixins.json`
- `src/main/java/com/silentboost/SilentBoost.java` (з `safeInit()` під 7 модулів)
- `src/client/java/com/silentboost/client/SilentBoostClient.java` (порожній)

### Крок 2 — Оптимізація тіків сутностей ✅
**Файли наявні:**
- `src/main/java/com/silentboost/optimization/entity/EntityTickOptimizer.java`
- `src/main/java/com/silentboost/mixin/ServerWorldTickEntityMixin.java`

**Логіка:**
- Mixin у `ServerWorld.tickEntity(Entity)` @Inject HEAD cancellable.
- Critical типи (Player, TNT, FallingBlock, Minecart, EnderDragon, Wither, EndCrystal, FishingBobber) → завжди тікають.
- Має `hasCustomName()` / `hasPassengers()` / `hasVehicle()` → завжди тікають.
- Дистанція до найближчого гравця:
  - ≤ 64 блоки → нормальний тік
  - 64–128 → раз на 4 серверні тіки (фаза `(worldTime + entityId) % 4`)
  - > 128 → раз на 8 тіків
- Перед скіпом: якщо поряд (5×5×5) є hopper/dispenser/dropper → НЕ скіпати (авто ферма).
- Кеш farm-proximity на 40 тіків, повний скид раз на 1200 тіків.
- Лічильник `ticksSkipped` (AtomicLong) для Кроку 7.

### Крок 3 — Оптимізація чанків і редстоуну ⏳
**Завдання:**
- Зменшити частоту random ticks у чанках без гравців і без активного редстоуну (без зміни логіки).
- Hopper'и далеко від гравців — рідший block-entity тік, але БЕЗ втрати предметів. Підхід: skip hopper.tick() через Mixin на BlockEntityTickInvoker або HopperBlockEntity.serverTick() з гарантією що при повертанні гравця катчуп виконається.
- Оптимізація chest, furnace, hopper block-entities — пропуск тіків коли немає активних предметів.
- Жодні зміни не впливають на результат авто ферм.

**План реалізації:**
1. `optimization/chunk/ChunkOptimizer.java` — координація.
2. `mixin/HopperBlockEntityMixin.java` — Mixin у `HopperBlockEntity.serverTick(World, BlockPos, BlockState, HopperBlockEntity)` з логікою катчапу.
3. `mixin/WorldChunkMixin.java` (опц.) — Mixin у `WorldChunk` random tick selector.
4. Конфіг порогу відстані: `hopperFarDistSq` = 128 (default), `randomTickDistSq` = 256.

### Крок 4 — Пам'ять і GC ⏳
**Завдання:**
- Очищення кешів (NBT, ItemStack) кожні N тіків.
- Зменшення зайвих алокацій у головному потоці (Mutable BlockPos, переробка циклів).
- Рекомендовані JVM флаги в README (вже там).

**План реалізації:**
- `optimization/memory/MemoryOptimizer.java` — реєструє `ServerTickEvents.END_SERVER_TICK` і кожні 6000 тіків (5 хв) робить:
  - `WeakReference`-cleanup власних кешів.
  - Опціонально `System.gc()` зі стримуванням (тільки якщо heap > 80% і не частіше за 10 хв).
- Pre-allocated `BlockPos.Mutable` у `EntityTickOptimizer` (вже зроблено).

### Крок 5 — Команда `/sb <предмет_рос> <кількість>` ⏳
**Завдання:**
- Команда `/sb`, наприклад `/sb дуб 10` → `give @s minecraft:oak_log 10`.
- НІЯКОГО autocomplete: суфікс-argument через `StringArgumentType.greedyString()` АБО — краще — кастомний `ArgumentType` що повертає порожній список `getExamples()` і не реєструється у `CommandRegistryAccess`.
- Жодних повідомлень: НЕ використовувати `source.sendFeedback(...)`. Просто додати предмет у `player.getInventory().offerOrDrop(stack)` і повернути `Command.SINGLE_SUCCESS`.
- Команда **не з'являється у `/help`** — досягається через ServerCommandSource без feedback. Якщо все ще з'являється — використати Mixin у `CommandManager` що фільтрує `silentboost:sb` з відповіді на `/help`.
- Жодних логів — `SilentBoost.LOGGER` не використовувати.
- НЕ перевіряти permission level: `dispatcher.register(literal("sb").requires(s -> true)...)`.
- Якщо назва невідома або кількість невірна → return 0, тиша.
- **Словник 200+ предметів** російською, з варіантами (`алмаз`=`діамант`, `кіраса`=`нагрудник`, `сосна`=`ялина`...). Зберігати у `Map<String, Identifier>`.

**Файли плану:**
- `command/SbCommand.java`
- `command/SbStatsCommand.java` (тільки для OP — Крок 7)
- `command/SbReloadCommand.java` (тільки для OP — Крок 6)
- `dictionary/ItemDictionary.java`
- `dictionary/items_ru.json` (resource — 200+ записів)

**Структура словника:**
```json
{
  "diamond": ["алмаз", "діамант"],
  "oak_log":  ["дуб"],
  "spruce_log": ["сосна", "ялина"],
  "chestplate_diamond": ["алмазний нагрудник", "алмазна кіраса"],
  ...
}
```
- `ItemDictionary.lookup(ruName) → Optional<Identifier>`
- Підтримка приставок матеріалу для зброї/броні: `алмазний меч`, `залізна кіраса`, `золота сокира`, тощо.

### Крок 6 — Config `silentboost_config.json` ⏳
**Завдання:**
- Файл у `world/silentboost_config.json` або `config/silentboost.json` (стандарт Fabric).
- Поля:
  - `entityTickOptimizer.enabled` (bool)
  - `entityTickOptimizer.nearDist`, `midDist`, `farDist` (числа, не квадрати)
  - `entityTickOptimizer.midFrequency`, `farFrequency` (int)
  - `chunkOptimizer.enabled` (bool)
  - `memoryOptimizer.enabled` (bool)
  - `commandSb.enabled` (bool)
  - `stats.enabled` (bool)
  - `stats.logFile` (string шлях)
- Автостворення з дефолтами при першому запуску.
- `/sb reload` (тільки OP) — перечитати з диску і застосувати без рестарту.
- Реалізація: GSON + record-based DTO.

**Файли:**
- `config/SilentBoostConfig.java` (record)
- `config/ConfigManager.java` (load/save/reload)

### Крок 7 — Статистика для OP ⏳
**Завдання:**
- `/sb stats` (тільки OP) — у відповідь приватним повідомленням від `system` ВСЕ ОДНО без console-логу:
  - Кількість сутностей зараз на зниженому тіку (підрахувати через `world.iterateEntities()`).
  - TPS сервера (зчитати з `MinecraftServer.getTickTimes()` — average).
  - Кількість оптимізованих чанків (Крок 3).
  - Економія тіків за останню хвилину (зчитати і скинути `EntityTickOptimizer.resetSkippedCounter()`).
- Звичайні гравці команди не бачать у `/help` (як і `/sb`).
- Статистику логувати у окремий файл `logs/silentboost-stats.log` (не у головний лог).

**Файли:**
- `stats/StatsCollector.java`
- `command/SbStatsCommand.java`

### Крок 8 — Збірка і встановлення ⏳
**Завдання:**
- `./gradlew build` → `build/libs/silentboost-1.0.0.jar`.
- README інструкція як поставити на vanilla Fabric server.
- JVM флаги (вже у README).
- Перевірка через `/fabric-info` (Fabric Loader) — мод видно у списку.

---

## 6. Стиль коду

- 4 пробіли індентація (як у Fabric template).
- Конкретні `import` (не `*`).
- JavaDoc українською для публічних класів і нетривіальних методів.
- Без `Any`, без `getattr/setattr`-style рефлексії в Java. Якщо потрібно — `accessor` Mixin або `@Invoker`.
- Mixin-методи з префіксом `silentboost$`.
- Конфігураційні значення — `public static volatile` у відповідному класі-оптимізаторі (так Крок 6 присвоює їх без рестарту).

---

## 7. Як працювати замовнику з тобою

Замовник пише "продовжуй" / "так" → ти даєш наступний крок повністю.
Якщо замовник скинув скріншот з помилкою — дай точне рішення з вказанням файлу і рядка.
Якщо замовник питає "як перевірити" — додай конкретні MC-команди та що шукати в логах.

---

## 8. Корисні посилання

- Fabric Wiki: https://fabricmc.net/wiki/
- Yarn Mappings browser: https://linkie.shedaniel.dev/mappings?namespace=yarn
- Mixin docs: https://github.com/SpongePowered/Mixin/wiki
- Minecraft 1.21.1 source (декомпіл): дивитись через IntelliJ у External Libraries.
- Поточні версії Fabric: https://fabricmc.net/develop/
