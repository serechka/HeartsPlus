package com.heartsplus;

import com.heartsplus.render.HeartsAboveHeadRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HeartsPlusClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger(HeartsPlus.class);
	private static KeyBinding toggleRenderingKey;
	private static KeyBinding openSettingsKey;
	private static boolean texturesWarmedUp;

	@Override
	public void onInitializeClient() {
		HeartsPlusConfig.load();
		LOGGER.info("HeartsPlus client initialized");

		KeyBinding.Category category = KeyBinding.Category.create(
				Identifier.of(HeartsPlus.MOD_ID, "main"));
		toggleRenderingKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.heartsplus.toggle", GLFW.GLFW_KEY_UNKNOWN, category));
		openSettingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.heartsplus.settings", GLFW.GLFW_KEY_H, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// First tick: register and upload the bundled heart textures before
			// any frame tries to draw them (lazy mid-frame uploads stay blank).
			if (!texturesWarmedUp) {
				texturesWarmedUp = true;
				HeartsAboveHeadRenderer.warmUpVanillaTextures(client.getTextureManager());
			}
			while (toggleRenderingKey.wasPressed()) {
				HeartsPlusConfig.setEnabled(!HeartsPlusConfig.isEnabled());
				reportState(client);
			}
			while (openSettingsKey.wasPressed()) {
				if (client.currentScreen == null) {
					client.setScreen(new HeartsPlusConfigScreen(null));
				}
			}
		});
	}

	private static void reportState(MinecraftClient client) {
		if (client.player != null) {
			client.player.sendMessage(HeartsPlusConfig.isEnabled()
					? Text.translatable("heartsplus.message.enabled")
					: Text.translatable("heartsplus.message.disabled"), true);
		}
	}
}
