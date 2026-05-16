package com.silentboost.client;

import net.fabricmc.api.ClientModInitializer;

/**
 * Клієнтський entry point.
 *
 * SilentBoost — серверний мод, у клієнта робити нічого. Цей клас існує лише
 * щоб Fabric Loader не скаржився на оголошений у fabric.mod.json client-entrypoint.
 *
 * Сюди НІЧОГО додавати в наступних кроках. Уся логіка живе у server-side.
 */
public final class SilentBoostClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		// навмисно порожньо
	}
}
