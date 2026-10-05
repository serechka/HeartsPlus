package com.heartsplus;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class HeartsPlusClient implements ClientModInitializer {
	/** GLFW key codes, inlined so the mod does not depend on the LWJGL glfw package. */
	private static final int KEY_UNKNOWN = -1;
	private static final int KEY_H = 72;

	private static KeyMapping toggleRenderingKey;
	private static KeyMapping openSettingsKey;

	@Override
	public void onInitializeClient() {
		HeartsPlusConfig.load();

		KeyMapping.Category category = KeyMapping.Category.register(
				Identifier.fromNamespaceAndPath(HeartsPlus.MOD_ID, "main"));
		toggleRenderingKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.heartsplus.toggle", KEY_H, category));
		openSettingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.heartsplus.settings", KEY_UNKNOWN, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleRenderingKey.consumeClick()) {
				HeartsPlusConfig.setEnabled(!HeartsPlusConfig.isEnabled());
				reportState(client);
			}
			while (openSettingsKey.consumeClick()) {
				client.setScreenAndShow(new HeartsPlusConfigScreen(null));
			}
		});
	}

	private static void reportState(Minecraft client) {
		if (client.player != null) {
			client.player.sendOverlayMessage(HeartsPlusConfig.isEnabled()
					? Component.translatable("heartsplus.message.enabled")
					: Component.translatable("heartsplus.message.disabled"));
		}
	}
}
