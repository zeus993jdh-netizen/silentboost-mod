package com.silentboost;

import com.silentboost.optimization.entity.EntityTickOptimizer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Головний entry point мода SilentBoost.
 *
 * Відповідальності:
 *  - єдине місце ініціалізації всіх підмодулів (команди, оптимізації, конфіг, статистика);
 *  - тримає посилання на поточний MinecraftServer (для потреб підмодулів);
 *  - ловить виняткові ситуації у підмодулях так, щоб одна несправна частина не валила решту;
 *  - НЕ пише нічого у звичайний серверний лог від імені /sb команди (тиша — ціль мода).
 */
public final class SilentBoost implements ModInitializer {

	public static final String MOD_ID = "silentboost";

	/**
	 * Внутрішній логер — використовується ВИКЛЮЧНО для діагностики самого мода
	 * (помилки завантаження, винятки модулів). Команда /sb ніколи нічого сюди не пише.
	 */
	public static final Logger LOGGER = LoggerFactory.getLogger("SilentBoost");

	/** Посилання на активний сервер. null коли сервер ще не стартував або вже зупинений. */
	private static final AtomicReference<MinecraftServer> SERVER = new AtomicReference<>(null);

	@Override
	public void onInitialize() {
		// 1. Сервер стартує — підіймаємо підмодулі.
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			SERVER.set(server);
			safeInit("config",       () -> { /* Крок 6: ConfigManager.load(server); */ });
			safeInit("dictionary",   () -> { /* Крок 5: ItemDictionary.init();        */ });
			safeInit("commands",     () -> { /* Крок 5/7: SbCommand.register();       */ });
			safeInit("entityOpt",    () -> EntityTickOptimizer.init());
			safeInit("chunkOpt",     () -> { /* Крок 3: ChunkOptimizer.init();        */ });
			safeInit("memoryOpt",    () -> { /* Крок 4: MemoryOptimizer.init();       */ });
			safeInit("stats",        () -> { /* Крок 7: StatsCollector.init();        */ });
		});

		// 2. Сервер тікає — гачок на випадок якщо потрібно щось централізоване.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			// Підмодулі реєструватимуть власні слухачі ServerTickEvents у своїх init().
		});

		// 3. Сервер зупиняється — чистимо посилання.
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			SERVER.set(null);
		});

		LOGGER.info("SilentBoost loaded.");
	}

	/**
	 * Запускає ініціалізатор підмодуля з захопленням винятків.
	 * Якщо один модуль кинув виняток — інші продовжують працювати, сервер не падає.
	 */
	private static void safeInit(String moduleName, Runnable initializer) {
		try {
			initializer.run();
		} catch (Throwable t) {
			LOGGER.error("SilentBoost module '{}' failed to initialize and will be disabled.", moduleName, t);
		}
	}

	/** Поточний активний сервер або null. */
	public static MinecraftServer getServer() {
		return SERVER.get();
	}
}
