package com.heartsplus;

import com.heartsplus.render.HeartsAboveHeadRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class HeartsPlusClient implements ClientModInitializer {
	/** Keybind category; in this stretch it is a plain string that doubles as the translation key. */
	private static final String KEY_CATEGORY = "key.category.heartsplus.main";
	private static KeyBinding toggleRenderingKey;
	private static KeyBinding openSettingsKey;

	@Override
	public void onInitializeClient() {
		HeartsPlusConfig.load();
		HeartsPlusLog.LOGGER.info("HeartsPlus client initialized");

		// Atlas sprites are re-stitched on resource reloads and their UV
		// coordinates move, so the renderer's sprite cache must be dropped.
		ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(
				new SimpleSynchronousResourceReloadListener() {
					@Override
					public Identifier getFabricId() {
						return Identifier.of(HeartsPlus.MOD_ID, "atlas_sprite_cache");
					}

					@Override
					public void reload(ResourceManager resourceManager) {
						HeartsAboveHeadRenderer.invalidateAtlasSprites();
					}
				});

		toggleRenderingKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.heartsplus.toggle", GLFW.GLFW_KEY_UNKNOWN, KEY_CATEGORY));
		openSettingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.heartsplus.settings", GLFW.GLFW_KEY_H, KEY_CATEGORY));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// Default-pack heart textures are only needed in default-texture
			// mode. Registration must happen outside a frame (lazy mid-frame
			// uploads stay blank), so it runs from the first tick with the mode
			// enabled; the call itself no-ops once warmed.
			if (HeartsPlusConfig.isVanillaTextures()) {
				HeartsAboveHeadRenderer.warmUpVanillaTextures(client.getTextureManager(), client.getResourceManager());
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
