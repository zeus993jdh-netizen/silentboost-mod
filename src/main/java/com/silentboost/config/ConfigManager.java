package com.silentboost.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.silentboost.SilentBoost;
import com.silentboost.optimization.chunk.ChunkOptimizer;
import com.silentboost.optimization.entity.EntityTickOptimizer;
import com.silentboost.optimization.memory.MemoryOptimizer;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Завантаження / збереження / гаряче перечитування конфіга SilentBoost.
 *
 * <h3>Розташування</h3>
 * <code>config/silentboost.json</code> у директорії сервера (Fabric standard).
 * Якщо файл відсутній — створюється з дефолтами при першому запуску сервера.
 *
 * <h3>Структура файлу</h3>
 * Дефолтні значення (саме такі будуть у новоствореному файлі):
 * <pre>
 * {
 *   "entity": {
 *     "enabled": true,
 *     "nearDistance": 32,
 *     "midDistance": 64,
 *     "farDistance": 128,
 *     "midFrequency": 4,
 *     "farFrequency": 8,
 *     "farmCheckRadius": 2,
 *     "farmCacheTicks": 40
 *   },
 *   "chunk": {
 *     "enabled": true,
 *     "nearDistance": 32,
 *     "midDistance": 96,
 *     "midDivisor": 2,
 *     "farTickSpeed": 1
 *   },
 *   "memory": {
 *     "enabled": true,
 *     "checkIntervalTicks": 100,
 *     "warnThreshold": 0.75,
 *     "highThreshold": 0.90,
 *     "cleanupCooldownTicks": 600,
 *     "gcCooldownTicks": 600,
 *     "allowGcHints": true
 *   }
 * }
 * </pre>
 *
 * <h3>Hot reload</h3>
 * {@code /sb reload} (OP only) викликає {@link #load()} — файл перечитується
 * і значення застосовуються до всіх модулів без рестарту сервера.
 */
public final class ConfigManager {

	/** Налаштування модуля оптимізації тіків сутностей. */
	public static final class EntityCfg {
		public boolean enabled = true;
		public int nearDistance = 32;
		public int midDistance = 64;
		public int farDistance = 128;
		public int midFrequency = 4;
		public int farFrequency = 8;
		public int farmCheckRadius = 2;
		public int farmCacheTicks = 40;
	}

	/** Налаштування модуля оптимізації random ticks у чанках. */
	public static final class ChunkCfg {
		public boolean enabled = true;
		public int nearDistance = 32;
		public int midDistance = 96;
		public int midDivisor = 2;
		public int farTickSpeed = 1;
	}

	/** Налаштування модуля моніторингу пам'яті. */
	public static final class MemoryCfg {
		public boolean enabled = true;
		public int checkIntervalTicks = 100;
		public double warnThreshold = 0.75;
		public double highThreshold = 0.90;
		public int cleanupCooldownTicks = 600;
		public int gcCooldownTicks = 600;
		public boolean allowGcHints = true;
	}

	/** Кореневий контейнер — це те, що серіалізується в JSON. */
	public static final class Root {
		public EntityCfg entity = new EntityCfg();
		public ChunkCfg chunk = new ChunkCfg();
		public MemoryCfg memory = new MemoryCfg();
	}

	private static final Gson GSON = new GsonBuilder()
		.setPrettyPrinting()
		.disableHtmlEscaping()
		.create();

	private static final AtomicReference<Root> CURRENT = new AtomicReference<>(new Root());

	private ConfigManager() {}

	/** Поточний застосований конфіг. Ніколи не null. */
	public static Root current() {
		return CURRENT.get();
	}

	/** Повний шлях до конфіг-файлу. */
	public static Path resolvePath() {
		return FabricLoader.getInstance().getConfigDir().resolve("silentboost.json");
	}

	/**
	 * Завантажує конфіг із silentboost.json і застосовує до всіх модулів.
	 * Якщо файл відсутній або зіпсований — використовує дефолти і (у випадку
	 * відсутності) створює файл з дефолтами на диску.
	 *
	 * @return true якщо завантаження пройшло без помилок, false при будь-яких винятках
	 */
	public static synchronized boolean load() {
		Path path = resolvePath();
		Root cfg;
		boolean ok = true;
		try {
			if (!Files.exists(path)) {
				cfg = new Root();
				saveInternal(path, cfg);
				SilentBoost.LOGGER.info("ConfigManager: created default config at {}", path);
			} else {
				try (BufferedReader reader = Files.newBufferedReader(path)) {
					cfg = GSON.fromJson(reader, Root.class);
				}
				if (cfg == null) cfg = new Root();
				if (cfg.entity == null) cfg.entity = new EntityCfg();
				if (cfg.chunk == null) cfg.chunk = new ChunkCfg();
				if (cfg.memory == null) cfg.memory = new MemoryCfg();
				SilentBoost.LOGGER.info("ConfigManager: loaded config from {}", path);
			}
		} catch (Exception e) {
			SilentBoost.LOGGER.error("ConfigManager: failed to load {}; using defaults", path, e);
			cfg = new Root();
			ok = false;
		}
		CURRENT.set(cfg);
		try {
			applyToModules(cfg);
		} catch (Throwable t) {
			SilentBoost.LOGGER.error("ConfigManager: failed to apply config to modules", t);
			ok = false;
		}
		return ok;
	}

	private static void saveInternal(Path path, Root cfg) throws IOException {
		Path parent = path.getParent();
		if (parent != null && !Files.exists(parent)) {
			Files.createDirectories(parent);
		}
		Files.writeString(path, GSON.toJson(cfg));
	}

	private static void applyToModules(Root cfg) {
		// EntityTickOptimizer ─────────────────────────────────────────────
		EntityTickOptimizer.enabled         = cfg.entity.enabled;
		EntityTickOptimizer.nearDistSq      = sq(cfg.entity.nearDistance);
		EntityTickOptimizer.midDistSq       = sq(cfg.entity.midDistance);
		EntityTickOptimizer.farDistSq       = sq(cfg.entity.farDistance);
		EntityTickOptimizer.midFrequency    = Math.max(1, cfg.entity.midFrequency);
		EntityTickOptimizer.farFrequency    = Math.max(1, cfg.entity.farFrequency);
		EntityTickOptimizer.farmCheckRadius = Math.max(1, cfg.entity.farmCheckRadius);
		EntityTickOptimizer.farmCacheTicks  = Math.max(1, cfg.entity.farmCacheTicks);

		// ChunkOptimizer ──────────────────────────────────────────────────
		ChunkOptimizer.enabled       = cfg.chunk.enabled;
		ChunkOptimizer.nearDistSq    = (long) cfg.chunk.nearDistance * cfg.chunk.nearDistance;
		ChunkOptimizer.midDistSq     = (long) cfg.chunk.midDistance  * cfg.chunk.midDistance;
		ChunkOptimizer.midDivisor    = Math.max(1, cfg.chunk.midDivisor);
		ChunkOptimizer.farTickSpeed  = Math.max(1, cfg.chunk.farTickSpeed);

		// MemoryOptimizer ─────────────────────────────────────────────────
		MemoryOptimizer.enabled              = cfg.memory.enabled;
		MemoryOptimizer.checkIntervalTicks   = Math.max(20, cfg.memory.checkIntervalTicks);
		MemoryOptimizer.warnThreshold        = clamp01(cfg.memory.warnThreshold);
		MemoryOptimizer.highThreshold        = Math.max(MemoryOptimizer.warnThreshold + 0.01,
		                                                clamp01(cfg.memory.highThreshold));
		MemoryOptimizer.cleanupCooldownTicks = Math.max(20, cfg.memory.cleanupCooldownTicks);
		MemoryOptimizer.gcCooldownTicks      = Math.max(20, cfg.memory.gcCooldownTicks);
		MemoryOptimizer.allowGcHints         = cfg.memory.allowGcHints;
	}

	private static double sq(int d) {
		return (double) d * d;
	}

	private static double clamp01(double v) {
		return Math.max(0.01, Math.min(0.99, v));
	}
}
