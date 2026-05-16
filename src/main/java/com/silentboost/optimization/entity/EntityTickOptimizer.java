package com.silentboost.optimization.entity;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.DispenserBlock;
import net.minecraft.block.DropperBlock;
import net.minecraft.block.HopperBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Зменшує частоту тіків сутностей далеко від гравців без впливу на:
 *  - гравців,
 *  - TNT, падаючі блоки, мінекарти, бос-мобів, рибальський поплавок, ендер кристали,
 *  - сутності з ім'ям (namtag),
 *  - сутності з пасажиром або які їдуть,
 *  - сутності біля hopper/dispenser/dropper (евристика "це авто ферма").
 *
 * Сама пропускає виклик entity.tick() через Mixin у ServerWorld.tickEntity
 * (див. ServerWorldTickEntityMixin).
 */
public final class EntityTickOptimizer {

	/**
	 * Поточні пороги відстаней (квадрати, щоб не рахувати корінь).
	 * У Кроці 6 ці значення підтягуватимуться з конфіга.
	 */
	public static volatile double nearDistSq = 32.0 * 32.0;
	public static volatile double midDistSq  = 64.0 * 64.0;
	public static volatile double farDistSq  = 128.0 * 128.0;

	/** Частоти тіків у відповідних зонах. */
	public static volatile int midFrequency  = 4;   // 64..128 → раз на 4 тіки
	public static volatile int farFrequency  = 8;   // >128    → раз на 8 тіків

	/** Радіус перевірки чи поруч є hopper/dispenser/dropper (по горизонталі). */
	public static volatile int farmCheckRadius = 2; // 5×3×5 куб
	public static volatile int farmCacheTicks  = 40; // 2 секунди кеш

	/** Глобальний вимикач модуля — Крок 6 робить його configurable. */
	public static volatile boolean enabled = true;

	/** Кеш: коли востаннє перевіряли farm-proximity і яким був результат. */
	private static final Map<UUID, FarmCacheEntry> FARM_CACHE = new HashMap<>();

	/** Лічильник пропущених тіків (для статистики у Кроці 7). */
	public static final AtomicLong ticksSkipped = new AtomicLong(0);

	private EntityTickOptimizer() {}

	/** Реєструється з SilentBoost.onInitialize. */
	public static void init() {
		// Раз на хвилину чистимо кеш від ентрі сутностей які зникли.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTicks() % 1200 == 0) {
				cleanupCache();
			}
		});
	}

	/**
	 * Головна точка прийняття рішення. Викликається Mixin'ом ДО entity.tick().
	 * Повертає true → ServerWorld.tickEntity скасується і tick цього циклу пропуститься.
	 */
	public static boolean shouldSkipTick(ServerWorld world, Entity entity) {
		if (!enabled) return false;

		// 1. Критичні типи — ніколи не чіпаємо.
		if (isCritical(entity)) return false;

		// 2. Безпечні евристики: ім'я, пасажир, верховий — не чіпаємо.
		if (entity.hasCustomName())  return false;
		if (entity.hasPassengers())  return false;
		if (entity.hasVehicle())     return false;

		// 3. Дешева перевірка відстані до найближчого гравця.
		double distSq = nearestPlayerSquaredDistance(world, entity);

		// Гравців у світі немає — нема сенсу оптимізувати, чанк все одно не тікає.
		if (distSq == Double.MAX_VALUE) return false;

		// У ближній зоні — нормальний тік.
		if (distSq <= midDistSq) return false;

		// 4. Визначаємо частоту цільового тіку.
		int frequency = (distSq > farDistSq) ? farFrequency : midFrequency;

		// 5. Чи ЦЕЙ тік природно випадає на "потрібний" слот?
		long phase = (world.getTime() + entity.getId()) % frequency;
		if (phase == 0) return false; // це і є дозволений тік — пропускати не треба

		// 6. Перед тим як вирішити пропустити — перевіримо чи це не біля авто ферми.
		if (isNearAutoFarm(world, entity)) return false;

		// 7. Скіп.
		ticksSkipped.incrementAndGet();
		return true;
	}

	private static boolean isCritical(Entity entity) {
		return entity instanceof PlayerEntity
			|| entity instanceof TntEntity
			|| entity instanceof FallingBlockEntity
			|| entity instanceof AbstractMinecartEntity
			|| entity instanceof EnderDragonEntity
			|| entity instanceof WitherEntity
			|| entity instanceof EndCrystalEntity
			|| entity instanceof FishingBobberEntity;
	}

	private static double nearestPlayerSquaredDistance(ServerWorld world, Entity entity) {
		double min = Double.MAX_VALUE;
		for (ServerPlayerEntity player : world.getPlayers()) {
			double d = player.squaredDistanceTo(entity);
			if (d < min) min = d;
		}
		return min;
	}

	private static boolean isNearAutoFarm(ServerWorld world, Entity entity) {
		UUID id = entity.getUuid();
		long now = world.getTime();

		FarmCacheEntry cached = FARM_CACHE.get(id);
		if (cached != null && now - cached.checkedAtTick < farmCacheTicks) {
			return cached.isNear;
		}

		boolean near = scanForFarmBlocks(world, entity);
		FARM_CACHE.put(id, new FarmCacheEntry(now, near));
		return near;
	}

	private static boolean scanForFarmBlocks(ServerWorld world, Entity entity) {
		BlockPos base = entity.getBlockPos();
		BlockPos.Mutable cursor = new BlockPos.Mutable();

		int r = farmCheckRadius;
		for (int dy = -2; dy <= 2; dy++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					cursor.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);

					// Якщо чанк не завантажений — не примушуємо його завантажуватись.
					if (!world.isChunkLoaded(cursor)) continue;

					Block block = world.getBlockState(cursor).getBlock();
					if (block instanceof HopperBlock
						|| block instanceof DispenserBlock
						|| block instanceof DropperBlock) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static void cleanupCache() {
		// Повний скид раз на хвилину — найдешевший і безпечний варіант.
		// При наступному запиті кеш наповниться лише для тих сутностей,
		// які реально пройдуть усі ранні відсічення.
		FARM_CACHE.clear();
	}

	/** Скидає лічильник пропущених тіків (для статистики "за останню хвилину"). */
	public static long resetSkippedCounter() {
		return ticksSkipped.getAndSet(0);
	}

	private static final class FarmCacheEntry {
		final long checkedAtTick;
		final boolean isNear;

		FarmCacheEntry(long checkedAtTick, boolean isNear) {
			this.checkedAtTick = checkedAtTick;
			this.isNear = isNear;
		}
	}
}
