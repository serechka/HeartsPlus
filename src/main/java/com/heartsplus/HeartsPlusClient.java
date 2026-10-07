package com.heartsplus;

import com.heartsplus.render.BlinkTracker;
import com.heartsplus.render.HeartsAboveHeadRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.SinglePreparationResourceReloader;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.profiler.Profiler;
import org.lwjgl.glfw.GLFW;

public class HeartsPlusClient implements ClientModInitializer {
	private static KeyBinding toggleRenderingKey;
	private static KeyBinding openSettingsKey;

	@Override
	public void onInitializeClient() {
		HeartsPlusConfig.load();
		HeartsPlusLog.LOGGER.info("HeartsPlus client initialized");

		// Atlas sprites are re-stitched on resource reloads and their UV
		// coordinates move, so the renderer's sprite cache must be dropped.
		ResourceLoader.get(ResourceType.CLIENT_RESOURCES).registerReloader(
				Identifier.of(HeartsPlus.MOD_ID, "atlas_sprite_cache"),
				new SinglePreparationResourceReloader<Void>() {
					@Override
					protected Void prepare(ResourceManager resourceManager, Profiler profiler) {
						return null;
					}

					@Override
					protected void apply(Void prepared, ResourceManager resourceManager, Profiler profiler) {
						HeartsAboveHeadRenderer.invalidateAtlasSprites();
					}
				});

		KeyBinding.Category category = KeyBinding.Category.create(
				Identifier.of(HeartsPlus.MOD_ID, "main"));
		toggleRenderingKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.heartsplus.toggle", GLFW.GLFW_KEY_UNKNOWN, category));
		openSettingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.heartsplus.settings", GLFW.GLFW_KEY_H, category));

		// Per-player state is keyed by UUID and must never outlive the play
		// session: a rejoin hands the same UUID a fresh entity whose tick
		// counter restarted, and stale blink windows would flash afterwards.
		// execute() pins the clear onto the render thread, where the tracker
		// lives (the disconnect hook can fire on a network thread).
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(BlinkTracker::clear));

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
