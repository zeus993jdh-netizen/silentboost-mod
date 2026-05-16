package com.silentboost;

import com.silentboost.command.SbCommand;
import com.silentboost.config.ConfigManager;
import com.silentboost.optimization.chunk.ChunkOptimizer;
import com.silentboost.optimization.entity.EntityTickOptimizer;
import com.silentboost.optimization.memory.MemoryOptimizer;
import com.silentboost.stats.StatsCollector;
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
		// 1. Команди реєструються до старту сервера — CommandRegistrationCallback
		//    може спрацювати раніше за SERVER_STARTING, тож слухача чіпляємо тут.
		safeInit("commands", SbCommand::register);

		// 2. Сервер стартує — підіймаємо підмодулі. ConfigManager.load() МАЄ бути
		//    перший, бо переписує volatile-поля інших модулів до їхнього init().
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			SERVER.set(server);
			safeInit("config",    () -> ConfigManager.load());
			// memoryOpt активуємо одразу після config — інші модулі реєструють у ньому cache-cleaners.
			safeInit("memoryOpt", () -> MemoryOptimizer.init());
			safeInit("stats",     () -> StatsCollector.init());
			safeInit("entityOpt", () -> EntityTickOptimizer.init());
			safeInit("chunkOpt",  () -> ChunkOptimizer.init());
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
