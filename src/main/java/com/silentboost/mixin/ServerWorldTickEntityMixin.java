package com.silentboost.mixin;

import com.silentboost.optimization.entity.EntityTickOptimizer;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Втручається у ServerWorld.tickEntity(Entity) на самому HEAD і скасовує
 * виклик, якщо EntityTickOptimizer вирішив, що цій сутності цього серверного
 * тіку тікати не треба.
 *
 * НЕ змінює поведінку сутності — лише пропускає виклик entity.tick() цього тіку.
 * Наступний "дозволений" тік (через 4 чи 8 серверних тіків) виконається повністю
 * як зазвичай: фізика, AI, ефекти, despawn-логіка — все спрацює.
 */
@Mixin(ServerWorld.class)
public abstract class ServerWorldTickEntityMixin {

	@Inject(method = "tickEntity", at = @At("HEAD"), cancellable = true)
	private void silentboost$skipDistantEntityTick(Entity entity, CallbackInfo ci) {
		ServerWorld self = (ServerWorld) (Object) this;
		if (EntityTickOptimizer.shouldSkipTick(self, entity)) {
			ci.cancel();
		}
	}
}
