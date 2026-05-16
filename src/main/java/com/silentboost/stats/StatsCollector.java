package com.silentboost.stats;

import com.silentboost.SilentBoost;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Збирає метрики продуктивності сервера для команди {@code /sb stats}.
 *
 * <h3>Що збирається</h3>
 * <ul>
 *   <li>Час кожного серверного тіку в наносекундах (rolling window 100 = 5 сек).</li>
 *   <li>Похідні значення: середній MSPT, поточний TPS (з cap'ом на 20.0).</li>
 * </ul>
 *
 * <p>Інші лічильники (entities skipped, chunks reduced, heap usage…) тримають
 * самі модулі, які їх виробляють — {@code /sb stats} лише агрегує і друкує.
 *
 * <h3>Що цей модуль НЕ робить</h3>
 * <ul>
 *   <li>НЕ пише статистику у лог (тільки на запит OP через {@code /sb stats}).</li>
 *   <li>НЕ блокує тред — операції в START/END_SERVER_TICK на масивах фіксованої довжини.</li>
 *   <li>НЕ зберігає історію на диск — все в пам'яті, перезапуск скидає.</li>
 * </ul>
 */
public final class StatsCollector {

	private static final AtomicReference<StatsCollector> INSTANCE = new AtomicReference<>(null);

	/** Скільки останніх тіків усереднюємо для TPS/MSPT. 100 тіків = 5 сек реального часу. */
	private static final int SAMPLE_WINDOW = 100;

	/** Кільцевий буфер з тривалостями тіків у наносекундах. */
	private final long[] tickDurationsNanos = new long[SAMPLE_WINDOW];
	private int sampleIdx = 0;
	private int filled = 0;

	/** Момент початку поточного тіку. */
	private volatile long tickStartNanos = 0L;

	private StatsCollector() {}

	/** Викликається з SilentBoost.safeInit("stats", ...). */
	public static void init() {
		StatsCollector self = new StatsCollector();
		INSTANCE.set(self);

		ServerTickEvents.START_SERVER_TICK.register(self::onTickStart);
		ServerTickEvents.END_SERVER_TICK.register(self::onTickEnd);

		SilentBoost.LOGGER.info("StatsCollector initialized (sample window = {} ticks)", SAMPLE_WINDOW);
	}

	private void onTickStart(MinecraftServer server) {
		tickStartNanos = System.nanoTime();
	}

	private void onTickEnd(MinecraftServer server) {
		long start = tickStartNanos;
		if (start <= 0L) return;
		long dur = System.nanoTime() - start;
		synchronized (tickDurationsNanos) {
			tickDurationsNanos[sampleIdx] = dur;
			sampleIdx = (sampleIdx + 1) % SAMPLE_WINDOW;
			if (filled < SAMPLE_WINDOW) filled++;
		}
	}

	/**
	 * Середній час одного серверного тіку в мілісекундах за останнє вікно.
	 * 0.0 якщо ще не зібрано жодного тіку.
	 */
	public static double avgMsPerTick() {
		StatsCollector self = INSTANCE.get();
		if (self == null) return 0.0;
		long sum = 0L;
		int n;
		synchronized (self.tickDurationsNanos) {
			n = self.filled;
			for (int i = 0; i < n; i++) sum += self.tickDurationsNanos[i];
		}
		if (n == 0) return 0.0;
		return (sum / 1_000_000.0) / n;
	}

	/**
	 * Поточний TPS, обмежений зверху на 20.0. Якщо сервер ще не зібрав даних — 20.0.
	 * Vanilla тік триває 50 мс (1000ms / 20). Якщо середній MSPT &lt; 50ms — сервер
	 * встигає; ми все одно ріжемо TPS до 20.0 щоб не показувати "21 TPS".
	 */
	public static double currentTps() {
		double mspt = avgMsPerTick();
		if (mspt <= 0.0) return 20.0;
		double tps = 1000.0 / Math.max(mspt, 50.0);
		return Math.min(tps, 20.0);
	}
}
