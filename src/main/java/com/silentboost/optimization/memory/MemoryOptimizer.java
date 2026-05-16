package com.silentboost.optimization.memory;

import com.silentboost.SilentBoost;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Слідкує за використанням купи (heap) сервера і коли тиск росте — викликає
 * зареєстровані cleaners (наприклад чистить кеш ферм у EntityTickOptimizer).
 *
 * <h3>Що цей модуль робить</h3>
 * <ul>
 *   <li>Раз на N серверних тіків (за замовчуванням 100 = 5с) читає
 *       {@code Runtime.getRuntime().totalMemory()/freeMemory()/maxMemory()}.</li>
 *   <li>Тримає поточне і пікове значення used heap (для статистики у Кроці 7).</li>
 *   <li>Якщо used/max перевищив {@code warnThreshold} (75%) — викликає всі
 *       зареєстровані cleaners.</li>
 *   <li>Якщо перевищив {@code highThreshold} (90%) — викликає cleaners, пише
 *       WARN у лог і викликає {@code System.gc()} як hint для JVM (але не частіше
 *       ніж раз на {@code gcCooldownTicks} = 30с).</li>
 *   <li>Має cooldown між повторними cleanup'ами щоб не молотити CPU кожен тік.</li>
 * </ul>
 *
 * <h3>Що цей модуль НЕ робить</h3>
 * <ul>
 *   <li>НЕ чіпає чанки — vanilla сама розв'язує що тримати в пам'яті.</li>
 *   <li>НЕ чіпає сутності або block entities.</li>
 *   <li>НЕ чіпає vanilla кеші (PathStorage, BlockStateCache і подібні).</li>
 *   <li>НЕ блокує тред — все робить on-tick синхронно за мілісекунди.</li>
 *   <li>НЕ викликає {@code System.gc()} часто — тільки при HIGH і не частіше
 *       ніж раз на 30 секунд. У JVM з G1GC (default Java 21) це безпечно.</li>
 * </ul>
 *
 * <h3>Як модулі підключають свої cleaners</h3>
 * У своєму init() кожен модуль викликає
 * {@link #registerCleaner(String, Runnable)}. Наприклад:
 *
 * <pre>{@code
 *   MemoryOptimizer.registerCleaner("EntityTickOptimizer.FARM_CACHE",
 *       EntityTickOptimizer::clearFarmCache);
 * }</pre>
 *
 * При memory pressure всі зареєстровані cleaners викликаються по черзі у тому
 * порядку, у якому були додані. Якщо один cleaner кинув виняток — інші
 * продовжують виконуватись (fail-safe).
 */
public final class MemoryOptimizer {

	private static final AtomicReference<MemoryOptimizer> INSTANCE = new AtomicReference<>(null);

	// === Configurable (Крок 6 зчитуватиме з silentboost_config.json) ===

	/** Глобальний вимикач. */
	public static volatile boolean enabled = true;

	/** Скільки серверних тіків між перевірками heap. 100 = 5 секунд. */
	public static volatile int checkIntervalTicks = 100;

	/** Поріг WARN: при перевищенні цього коефіцієнта used/max викликаємо cleaners. */
	public static volatile double warnThreshold = 0.75;

	/** Поріг HIGH: при перевищенні викликаємо cleaners + WARN у лог + опційно System.gc(). */
	public static volatile double highThreshold = 0.90;

	/** Cooldown між послідовними cleanup-ами (в тіках). 600 = 30 секунд. */
	public static volatile int cleanupCooldownTicks = 600;

	/** Cooldown між викликами System.gc() (в тіках). 600 = 30 секунд. */
	public static volatile int gcCooldownTicks = 600;

	/** Чи дозволено викликати System.gc() при HIGH тиску. */
	public static volatile boolean allowGcHints = true;

	// === Внутрішній стан ===

	/** Зареєстровані cleaners (зберігаємо порядок вставки → LinkedHashMap). */
	private final LinkedHashMap<String, Runnable> cleaners = new LinkedHashMap<>();

	private volatile long lastCleanupTick = -1L;
	private volatile long lastGcTick      = -1L;

	private volatile long currentUsedBytes = 0L;
	private volatile long currentMaxBytes  = 0L;
	private volatile long peakUsedBytes    = 0L;

	// === Лічильники для статистики (Крок 7) ===

	public final AtomicLong cleanupsTriggered = new AtomicLong();
	public final AtomicLong warningsLogged    = new AtomicLong();
	public final AtomicLong gcHintsIssued     = new AtomicLong();

	private MemoryOptimizer() {}

	/** Викликається з SilentBoost.safeInit("memoryOpt", ...). */
	public static void init() {
		MemoryOptimizer self = new MemoryOptimizer();
		INSTANCE.set(self);

		ServerTickEvents.END_SERVER_TICK.register(self::onServerTick);

		SilentBoost.LOGGER.info(
			"MemoryOptimizer initialized (warn={}%, high={}%, interval={} ticks)",
			(int) (warnThreshold * 100), (int) (highThreshold * 100), checkIntervalTicks
		);
	}

	public static boolean isEnabled() {
		return enabled && INSTANCE.get() != null;
	}

	/**
	 * Реєструє cleaner у глобальному реєстрі. Безпечно викликати декілька разів —
	 * другий виклик з тим самим іменем перепише попередній.
	 *
	 * @param name        унікальне ім'я для логування
	 * @param cleaner     ваш метод який звільняє пам'ять (clear кешу і таке інше)
	 */
	public static void registerCleaner(String name, Runnable cleaner) {
		MemoryOptimizer self = INSTANCE.get();
		if (self == null) {
			// MemoryOptimizer ще не активований — не зберігаємо нічого, модуль викине логування.
			// Це нормально: якщо memoryOpt вимкнений, ніяких автоматичних cleanup'ів не буде.
			SilentBoost.LOGGER.debug("MemoryOptimizer not initialized yet; cleaner '{}' will not be auto-invoked.", name);
			return;
		}
		synchronized (self.cleaners) {
			self.cleaners.put(name, cleaner);
		}
		SilentBoost.LOGGER.info("MemoryOptimizer: registered cleaner '{}'", name);
	}

	private void onServerTick(MinecraftServer server) {
		if (!enabled) return;
		long tick = server.getTicks();
		if (tick % checkIntervalTicks != 0) return;
		try {
			pollHeap(tick);
		} catch (Throwable t) {
			// Fail-safe: будь-який сюрприз → лог + ігнор, сервер не падає.
			SilentBoost.LOGGER.error("MemoryOptimizer.pollHeap failed", t);
		}
	}

	private void pollHeap(long tick) {
		Runtime r = Runtime.getRuntime();
		long max   = r.maxMemory();        // -Xmx
		long total = r.totalMemory();      // зараз віддано JVM
		long free  = r.freeMemory();       // вільно в total
		long used  = total - free;

		currentUsedBytes = used;
		currentMaxBytes  = max;
		if (used > peakUsedBytes) peakUsedBytes = used;

		// Якщо JVM не повідомила max (повертає Long.MAX_VALUE) — не вмикаємо тиск.
		if (max <= 0L || max == Long.MAX_VALUE) return;

		double ratio = (double) used / (double) max;

		if (ratio >= highThreshold) {
			triggerCleanup(tick, "HIGH", ratio, used, max, true);
		} else if (ratio >= warnThreshold) {
			triggerCleanup(tick, "WARN", ratio, used, max, false);
		}
	}

	private void triggerCleanup(long tick, String level, double ratio, long used, long max, boolean considerGc) {
		// Cooldown для cleanups
		if (lastCleanupTick >= 0L && tick - lastCleanupTick < cleanupCooldownTicks) {
			return;
		}
		lastCleanupTick = tick;

		warningsLogged.incrementAndGet();
		SilentBoost.LOGGER.warn(
			"[memory] {} pressure: {}% used ({} / {} MiB)",
			level, (int) (ratio * 100), used >> 20, max >> 20
		);

		// Виклик всіх зареєстрованих cleaners
		Map<String, Runnable> snapshot;
		synchronized (cleaners) {
			snapshot = new LinkedHashMap<>(cleaners);
		}
		for (Map.Entry<String, Runnable> e : snapshot.entrySet()) {
			try {
				e.getValue().run();
				cleanupsTriggered.incrementAndGet();
			} catch (Throwable t) {
				SilentBoost.LOGGER.error("MemoryOptimizer: cleaner '{}' failed", e.getKey(), t);
				// Продовжуємо до наступного cleaner — fail-safe.
			}
		}

		// На HIGH рівні — даємо JVM хінт про GC, але рідко.
		if (considerGc && allowGcHints) {
			if (lastGcTick < 0L || tick - lastGcTick >= gcCooldownTicks) {
				lastGcTick = tick;
				gcHintsIssued.incrementAndGet();
				SilentBoost.LOGGER.info("[memory] HIGH pressure — issuing System.gc() hint");
				// System.gc() — лише підказка JVM; G1GC може проігнорувати. Не блокує тред помітно.
				System.gc();
			}
		}
	}

	// === API для Кроку 7 (статистика) ===

	public static long getCurrentUsedBytes() {
		MemoryOptimizer self = INSTANCE.get();
		return self == null ? 0L : self.currentUsedBytes;
	}

	public static long getCurrentMaxBytes() {
		MemoryOptimizer self = INSTANCE.get();
		return self == null ? 0L : self.currentMaxBytes;
	}

	public static long getPeakUsedBytes() {
		MemoryOptimizer self = INSTANCE.get();
		return self == null ? 0L : self.peakUsedBytes;
	}

	public static long getCleanupsTriggered() {
		MemoryOptimizer self = INSTANCE.get();
		return self == null ? 0L : self.cleanupsTriggered.get();
	}

	public static long getWarningsLogged() {
		MemoryOptimizer self = INSTANCE.get();
		return self == null ? 0L : self.warningsLogged.get();
	}

	public static long getGcHintsIssued() {
		MemoryOptimizer self = INSTANCE.get();
		return self == null ? 0L : self.gcHintsIssued.get();
	}

	/** Скидає лічильники після репорту. */
	public static void resetCounters() {
		MemoryOptimizer self = INSTANCE.get();
		if (self == null) return;
		self.cleanupsTriggered.set(0);
		self.warningsLogged.set(0);
		self.gcHintsIssued.set(0);
		self.peakUsedBytes = self.currentUsedBytes;
	}
}
