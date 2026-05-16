# SilentBoost — Minecraft 1.21.1 Fabric Mod

**Серверна оптимізація без впливу на геймплей + прихована команда `/sb` для видачі предметів російською назвою.**

---

## Що це

SilentBoost — це Fabric мод для Minecraft 1.21.1, який вирішує дві задачі:

1. **Знижує навантаження на сервер** (TPS, MSPT), не змінюючи нічого, що бачить гравець:
   - моби, авто-ферми, редстоун, hopper'и, dispenser'и, dropper'и — все працює абсолютно як у ваніль.
   - оптимізація — суто за рахунок зменшення тіків сутностей далеко від гравців, скорочення зайвих перерахунків редстоуну, та оптимізації блок-ентіті.

2. **Дає прихований чит-канал у вигляді команди `/sb <назва_російською> <кількість>`**:
   - команда не з'являється у `/help`, без autocomplete, без підказок.
   - не пише нічого в чат, не пише нічого у лог сервера.
   - доступна **всім** гравцям без перевірки прав (`/sb stats` і `/sb reload` — тільки для OP).
   - словник містить 200+ найпопулярніших предметів з варіантами імен (`алмаз` = `діамант` = diamond, `кіраса` = `нагрудник` = chestplate, тощо).

---

## Версії і залежності

| Що | Версія |
|---|---|
| Minecraft | 1.21.1 |
| Fabric Loader | ≥ 0.16.5 |
| Fabric API | 0.102.1+1.21.1 |
| Yarn Mappings | 1.21.1+build.3 |
| Java | 21 |
| Gradle (через wrapper) | 8.x |
| Loom plugin | 1.7-SNAPSHOT |
| Mixin compatibility | JAVA_21 |

---

## Структура проекту

```
silentboost/
├── build.gradle                                        # Loom + Fabric Gradle конфіг
├── gradle.properties                                   # версії MC / Yarn / Loader / Fabric API
├── settings.gradle                                     # ім'я проекту
├── src/
│   ├── main/                                           # СЕРВЕРНА ЧАСТИНА (тут уся логіка мода)
│   │   ├── java/com/silentboost/
│   │   │   ├── SilentBoost.java                        # ✦ головний entry point, safeInit() для модулів
│   │   │   ├── command/                                # Крок 5,7: /sb команда
│   │   │   ├── config/                                 # Крок 6: silentboost_config.json
│   │   │   ├── dictionary/                             # Крок 5: 200+ предметів російською
│   │   │   ├── mixin/                                  # Крок 2-4: Mixin-класи
│   │   │   │   └── ServerWorldTickEntityMixin.java     # ✦ Крок 2: скіп тіків
│   │   │   ├── optimization/
│   │   │   │   ├── entity/EntityTickOptimizer.java     # ✦ Крок 2: логіка скіпу тіків
│   │   │   │   ├── chunk/                              # Крок 3: оптимізація чанків/редстоуну
│   │   │   │   └── memory/                             # Крок 4: GC і кеші
│   │   │   └── stats/                                  # Крок 7: статистика
│   │   └── resources/
│   │       ├── fabric.mod.json                         # ✦ маніфест мода
│   │       └── silentboost.mixins.json                 # ✦ список mixin'ів
│   └── client/                                         # КЛІЄНТСЬКА ЧАСТИНА (заглушка)
│       ├── java/com/silentboost/client/
│       │   └── SilentBoostClient.java                  # ✦ порожній ClientModInitializer
│       └── resources/
│           └── silentboost.client.mixins.json          # ✦ порожній клієнтський mixin конфіг
├── LICENSE
└── README.md
```

Файли позначені ✦ — наявні зараз.

---

## Як зібрати

Вимагається встановлена Java 21.

```bash
./gradlew build
```

Готовий jar з'явиться у `build/libs/silentboost-1.0.0.jar`.

## Як запустити dev-клієнт у IntelliJ

1. Відкрити проект в IntelliJ IDEA Community.
2. Дочекатись Gradle sync.
3. Вибрати `Minecraft Client` у dropdown зверху справа → натиснути ▶.
4. У Run-логах має бути `[main/INFO] (SilentBoost) SilentBoost loaded.`

## Як встановити на сервер

1. Поставити Fabric Server для MC 1.21.1.
2. Покласти `silentboost-1.0.0.jar` у папку `mods/`.
3. Поставити туди ж `fabric-api-0.102.1+1.21.1.jar` (з https://modrinth.com/mod/fabric-api).
4. Запустити сервер.

---

## Рекомендовані JVM флаги для сервера (Aikar's flags для MC 1.21)

```
-Xms6G -Xmx6G
-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200
-XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch
-XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M
-XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4
-XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90
-XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32
-XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1
-Dusing.aikars.flags=https://mcflags.emc.gs -Daikars.new.flags=true
```

---

## Дорожня карта (по кроках)

| Крок | Стан | Що зроблено |
|---|---|---|
| 0. Середовище | ✅ | Java 21, IntelliJ, Fabric MDK |
| 1. Архітектура | ✅ | `fabric.mod.json`, `SilentBoost.java`, `SilentBoostClient.java`, mixin-конфіги |
| 2. Тіки сутностей | ✅ | `EntityTickOptimizer` + `ServerWorldTickEntityMixin` |
| 3. Чанки і редстоун | ⏳ | TODO |
| 4. Пам'ять / GC | ⏳ | TODO |
| 5. Команда `/sb` + словник 200+ | ⏳ | TODO |
| 6. Config `silentboost_config.json` | ⏳ | TODO |
| 7. `/sb stats` і `/sb reload` (OP) | ⏳ | TODO |
| 8. Збірка JAR і інсталяція на сервер | ⏳ | TODO |

Повну специфікацію кожного кроку дивись у [`HANDOFF.md`](HANDOFF.md).

---

## Безпекові інваріанти

1. ❌ НЕ змінюємо логіку редстоуну — результат роботи має бути ідентичний ванілі.
2. ❌ НЕ видаляємо мобів, не зменшуємо їх spawn rate.
3. ❌ НЕ ламаємо hopper'и — предмети не повинні губитись.
4. ❌ НЕ чіпаємо dropper / dispenser.
5. ❌ НЕ ламаємо авто ферми — поряд з hopper/dispenser/dropper моби тікають як у ванілі.
6. ✅ `/sb` непомітна — нуль слідів у логах і чаті.
7. ✅ `/sb` доступна всім — без перевірки прав.
8. ✅ `/sb stats` і `/sb reload` — тільки для OP.
9. ✅ Мод не крашить сервер при помилці модуля (див. `safeInit()`).
