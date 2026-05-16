package com.silentboost.mixin;

import com.silentboost.optimization.chunk.ChunkOptimizer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Втручається у {@code ServerWorld.tickChunk(WorldChunk, int randomTickSpeed)} на HEAD
 * і повертає скорочений randomTickSpeed для чанків далеко від гравців.
 *
 * <p>Технічно: {@code @ModifyVariable} з {@code argsOnly = true, ordinal = 0} замінює
 * значення першого {@code int}-аргументу (а саме {@code randomTickSpeed}) на повернене
 * нашим handler'ом. Handler приймає original-value та решту аргументів target-методу
 * для контексту (нам потрібен {@code chunk}).
 *
 * <p>Інші аспекти tickChunk (lightning, ice/snow, profiling) НЕ зачіпаються — мод
 * втручається лише в кількість random tick ітерацій.
 *
 * <p><b>Fail-safe:</b> якщо ChunkOptimizer не активний або handler кинув виняток —
 * повертаємо original. Vanilla поведінка зберігається.
 */
@Mixin(ServerWorld.class)
public abstract class ServerWorldRandomTickMixin {

	@ModifyVariable(
		method = "tickChunk(Lnet/minecraft/world/chunk/WorldChunk;I)V",
		at = @At("HEAD"),
		argsOnly = true,
		ordinal = 0
	)
	private int silentboost$adjustRandomTickSpeed(
		int original,
		WorldChunk chunk,
		int randomTickSpeed
	) {
		// `original` і `randomTickSpeed` мають однакове значення (це той самий аргумент);
		// дублікат потрібен лише для коректного signature-mapping у Mixin handler'і
		// (handler приймає value-being-modified + повний список аргументів target-методу).
		if (!ChunkOptimizer.isEnabled()) {
			return original;
		}
		try {
			ServerWorld self = (ServerWorld) (Object) this;
			return ChunkOptimizer.adjustRandomTickSpeed(self, chunk, original);
		} catch (Throwable t) {
			// Fail-safe: ніколи не ламати vanilla, навіть якщо логіка оптимізатора впала.
			return original;
		}
	}
}
