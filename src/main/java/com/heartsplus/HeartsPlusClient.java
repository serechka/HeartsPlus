package com.heartsplus;

import com.heartsplus.render.BlinkTracker;
import com.heartsplus.render.HeartsAboveHeadRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

public class HeartsPlusClient implements ClientModInitializer {
	/** GLFW key codes, inlined so the mod does not depend on the LWJGL glfw package. */
	private static final int KEY_UNKNOWN = -1;
	private static final int KEY_H = 72;

	private static KeyMapping toggleRenderingKey;
	private static KeyMapping openSettingsKey;

	@Override
	public void onInitializeClient() {
		HeartsPlusConfig.load();
		HeartsPlusLog.LOGGER.info("HeartsPlus client initialized");

		// Atlas sprites are re-stitched on resource reloads and their UV
		// coordinates move, so the renderer's sprite cache must be dropped.
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
			@Override
			public Identifier getFabricId() {
				return Identifier.fromNamespaceAndPath(HeartsPlus.MOD_ID, "atlas_sprite_cache");
			}

			@Override
			public void onResourceManagerReload(ResourceManager resourceManager) {
				HeartsAboveHeadRenderer.invalidateAtlasSprites();
			}
		});

		KeyMapping.Category category = KeyMapping.Category.register(
				Identifier.fromNamespaceAndPath(HeartsPlus.MOD_ID, "main"));
		toggleRenderingKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.heartsplus.toggle", KEY_UNKNOWN, category));
		openSettingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.heartsplus.settings", KEY_H, category));

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
