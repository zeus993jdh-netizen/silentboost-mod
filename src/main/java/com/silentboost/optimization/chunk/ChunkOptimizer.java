package com.silentboost.optimization.chunk;

import com.silentboost.SilentBoost;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Зменшує кількість random ticks у чанках далеко від гравців.
 *
 * <h3>Що таке random ticks і чому це безпечно міняти</h3>
 * Random ticks у Minecraft — це псевдовипадкові виклики {@code BlockState.randomTick(...)}
 * для невеликої кількості позицій у кожному завантаженому під-чанку кожен серверний тік.
 * Це окремий механізм, ВІДОКРЕМЛЕНИЙ від звичайних tickів block entity, від редстоуну,
 * від спавну мобів, і від AI сутностей.
 *
 * <p>Random ticks впливають ТІЛЬКИ на такі речі:
 * <ul>
 *   <li>Ріст рослин (вирощування пшениці, моркви, картоплі, буряка, какао, цукрової тростини, кактуса, бамбуку, дині, гарбуза, ягід ландшафту, грибів)</li>
 *   <li>Ріст саджанців / дерев</li>
 *   <li>Поширення трави і міцелію</li>
 *   <li>Гниття листя</li>
 *   <li>Окиснення міді</li>
 *   <li>Танення льоду / снігу, утворення льоду в холодних біомах</li>
 *   <li>Поширення вогню</li>
 *   <li>Кашкадні події у губках і кашовнях (rare)</li>
 * </ul>
 *
 * <p>Random ticks НЕ впливають на:
 * <ul>
 *   <li>Спавн мобів (це окремий механізм у SpawnHelper)</li>
 *   <li>Редстоун (це окремий механізм у RedstoneWireBlock / RedstoneCircuit)</li>
 *   <li>Hopper / Dispenser / Dropper (це block entity ticks, окремий механізм)</li>
 *   <li>Furnace / BrewingStand / Beacon / Comparator (теж block entity ticks)</li>
 *   <li>TNT, мінекарти, моби, гравці</li>
 *   <li>Будь-які авто ферми які працюють на хопперах + редстоуні</li>
 * </ul>
 *
 * <h3>Стратегія зниження</h3>
 * Vanilla {@code randomTickSpeed} = 3 (з gamerule). Цей мод залежно від відстані центру
 * чанку до найближчого гравця міняє ефективне значення для конкретного виклику
 * {@code ServerWorld.tickChunk(chunk, randomTickSpeed)} (САМ gamerule НЕ змінюємо):
 *
 * <pre>
 *   ≤ {@value NEAR_BLOCKS} блоків  → randomTickSpeed без змін (наприклад 3)
 *   ≤ {@value MID_BLOCKS}  блоків  → max(1, original / 2)
 *   >  {@value MID_BLOCKS}  блоків  → 1
 * </pre>
 *
 * <p>Min значення — 1 (не 0), щоб рослини у відведеному зрізі чанку все одно росли,
 * просто повільніше. Якщо ферма поза 96 блоками від гравця — вона буде працювати,
 * але збір буде у тричі повільніший. На практиці AFK ферми завжди в радіусі &lt; 32 блоки
 * від гравця, тож вони НЕ зачіпаються.
 *
 * <p>Vanilla сам random-tick'ає тільки чанки в межах simulation distance (зазвичай
 * 8–12 чанків = 128–192 блоки). Тому наша "далека" зона (&gt; 96 блоків) — це фактично
 * краєва смуга simulation distance.
 *
 * <h3>Що цей модуль НЕ робить</h3>
 * <ul>
 *   <li>НЕ скасовує жоден тік block entity (hopper, dropper, dispenser, furnace, comparator…)</li>
 *   <li>НЕ змінює жодного редстоун-сигналу і не кешує getReceivedRedstonePower</li>
 *   <li>НЕ міняє кількість мобів і spawn rate</li>
 *   <li>НЕ скасовує lightning, ice/snow accumulation, чи інші речі поза тілом random tick loop</li>
 * </ul>
 *
 * Точка скорочення — лише параметр {@code randomTickSpeed} перед for-loop у
 * {@code ServerWorld.tickChunk}, через {@code @ModifyVariable} у
 * {@link com.silentboost.mixin.ServerWorldRandomTickMixin}.
 */
public final class ChunkOptimizer {

	private static final AtomicReference<ChunkOptimizer> INSTANCE = new AtomicReference<>(null);

	/** Пороги відстані до найближчого гравця (квадрати, щоб не рахувати корінь). */
	public static final int NEAR_BLOCKS = 32;
	public static final int MID_BLOCKS  = 96;

	/** Поточні робочі значення. У Кроці 6 будуть configurable із silentboost_config.json. */
	public static volatile long  nearDistSq = (long) NEAR_BLOCKS * NEAR_BLOCKS;
	public static volatile long  midDistSq  = (long) MID_BLOCKS  * MID_BLOCKS;
	/** Дільник у середній зоні (32..96 блоків). */
	public static volatile int   midDivisor = 2;
	/** Значення в далекій зоні (&gt; 96 блоків). Завжди ≥ 1. */
	public static volatile int   farTickSpeed = 1;
	/** Глобальний вимикач модуля. Крок 6 робить configurable. */
	public static volatile boolean enabled = true;

	// === Лічильники для статистики (використовуються у Кроці 7) ===

	/** Скільки разів був викликаний adjustRandomTickSpeed (= скільки разів vanilla викликав tickChunk). */
	public final AtomicLong chunksProcessed = new AtomicLong();
	/** Скільки разів ми реально знизили tick speed (= оптимізовані тіки чанків). */
	public final AtomicLong chunksReduced   = new AtomicLong();
	/** Сума (original - adjusted) — скільки конкретних random tick "слотів" заощаджено. */
	public final AtomicLong randomTicksSaved = new AtomicLong();

	private ChunkOptimizer() {}

	/** Викликається з SilentBoost.safeInit("chunkOpt", ...). */
	public static void init() {
		INSTANCE.set(new ChunkOptimizer());
		SilentBoost.LOGGER.info(
			"ChunkOptimizer initialized (near={}b, mid={}b, midDivisor={}, farTickSpeed={})",
			NEAR_BLOCKS, MID_BLOCKS, midDivisor, farTickSpeed
		);
	}

	public static boolean isEnabled() {
		return enabled && INSTANCE.get() != null;
	}

	/**
	 * Викликається з ServerWorldRandomTickMixin перед for-loop у tickChunk.
	 * Повертає скоригований randomTickSpeed для конкретного чанку у цей тік.
	 *
	 * <p>Логіка fail-safe: будь-яка несподівана ситуація → повернути original.
	 * Це гарантує що мод НІКОЛИ не зламає vanilla поведінку чанків.
	 */
	public static int adjustRandomTickSpeed(ServerWorld world, WorldChunk chunk, int original) {
		ChunkOptimizer self = INSTANCE.get();
		if (self == null || !enabled || original <= 0) {
			return original;
		}
		self.chunksProcessed.incrementAndGet();

		long dsq = nearestPlayerChunkDistanceSq(world, chunk);
		if (dsq < 0L) {
			// Гравців у світі немає → vanilla все одно не tick'ає чанки. Нічого не міняємо.
			return original;
		}

		int adjusted;
		if (dsq <= nearDistSq) {
			adjusted = original;
		} else if (dsq <= midDistSq) {
			adjusted = Math.max(1, original / Math.max(1, midDivisor));
		} else {
			adjusted = Math.max(1, Math.min(original, farTickSpeed));
		}

		// Захист — ніколи не повернути більше за original (якщо хтось вручну виставив midDivisor < 1).
		if (adjusted > original) {
			adjusted = original;
		}

		if (adjusted < original) {
			self.chunksReduced.incrementAndGet();
			self.randomTicksSaved.addAndGet(original - adjusted);
		}
		return adjusted;
	}

	/**
	 * Квадрат горизонтальної (2D) відстані центру чанку до найближчого гравця у цьому світі.
	 * Y ігнорується бо random ticks не залежать від висоти.
	 * Повертає -1L якщо у цьому світі гравців немає.
	 */
	private static long nearestPlayerChunkDistanceSq(ServerWorld world, WorldChunk chunk) {
		List<ServerPlayerEntity> players = world.getPlayers();
		if (players.isEmpty()) return -1L;

		ChunkPos pos = chunk.getPos();
		// Центр чанку: (chunkX << 4) + 8, (chunkZ << 4) + 8
		double cx = ((double) pos.x * 16.0) + 8.0;
		double cz = ((double) pos.z * 16.0) + 8.0;

		long best = Long.MAX_VALUE;
		for (ServerPlayerEntity p : players) {
			double dx = p.getX() - cx;
			double dz = p.getZ() - cz;
			long d = (long) (dx * dx + dz * dz);
			if (d < best) best = d;
		}
		return best;
	}

	// === API для модуля статистики (Крок 7) ===

	public static long getChunksProcessed() {
		ChunkOptimizer self = INSTANCE.get();
		return self == null ? 0L : self.chunksProcessed.get();
	}

	public static long getChunksReduced() {
		ChunkOptimizer self = INSTANCE.get();
		return self == null ? 0L : self.chunksReduced.get();
	}

	public static long getRandomTicksSaved() {
		ChunkOptimizer self = INSTANCE.get();
		return self == null ? 0L : self.randomTicksSaved.get();
	}

	/** Скидає лічильники після репорту (наприклад, кожні 60s у Кроці 7). */
	public static void resetCounters() {
		ChunkOptimizer self = INSTANCE.get();
		if (self == null) return;
		self.chunksProcessed.set(0);
		self.chunksReduced.set(0);
		self.randomTicksSaved.set(0);
	}
}
