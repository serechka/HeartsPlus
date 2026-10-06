package com.heartsplus;

import com.heartsplus.render.HeartsAboveHeadRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HeartsPlusClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger(HeartsPlus.class);
	/** GLFW key codes, inlined so the mod does not depend on the LWJGL glfw package. */
	private static final int KEY_UNKNOWN = -1;
	private static final int KEY_H = 72;

	private static KeyMapping toggleRenderingKey;
	private static KeyMapping openSettingsKey;
	private static boolean texturesWarmedUp;

	@Override
	public void onInitializeClient() {
		HeartsPlusConfig.load();
		LOGGER.info("HeartsPlus client initialized");

		KeyMapping.Category category = KeyMapping.Category.register(
				Identifier.fromNamespaceAndPath(HeartsPlus.MOD_ID, "main"));
		toggleRenderingKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.heartsplus.toggle", KEY_UNKNOWN, category));
		openSettingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.heartsplus.settings", KEY_H, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// First tick: register and upload the bundled heart textures before
			// any frame tries to draw them (lazy mid-frame uploads stay blank).
			if (!texturesWarmedUp) {
				texturesWarmedUp = true;
				HeartsAboveHeadRenderer.warmUpVanillaTextures(client.getTextureManager());
			}
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
