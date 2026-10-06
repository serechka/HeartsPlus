package com.heartsplus;

import com.heartsplus.render.HeartsAboveHeadRenderer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * NeoForge client bootstrap: loads the config, registers key mappings and
 * reload listeners on the mod event bus, and consumes key presses every
 * client tick on the game event bus. Rendering itself is handled by the
 * same mixins as on Fabric — NeoForge uses identical Mojang-mapped classes.
 */
@Mod(value = HeartsPlus.MOD_ID, dist = {Dist.CLIENT})
public class HeartsPlusClient {
	/** GLFW key codes, inlined so the mod does not depend on the LWJGL glfw package. */
	private static final int KEY_UNKNOWN = -1;
	private static final int KEY_H = 72;

	private static final Logger LOGGER = HeartsPlusLog.LOGGER;
	private static KeyMapping toggleRenderingKey;
	private static KeyMapping openSettingsKey;

	public HeartsPlusClient(ModContainer container) {
		HeartsPlusConfig.load();
		LOGGER.info("HeartsPlus client initialized");
		// Key-mapping registration and reload-listener registration are
		// mod-bus events, client ticks are game-bus events — and each bus
		// rejects listeners for the other's events, so the two listeners
		// must be registered separately.
		container.getEventBus().register(new KeyMappingListener());
		NeoForge.EVENT_BUS.register(new TickListener());
	}

	/** Mod-bus listeners: registration-time events only. */
	static final class KeyMappingListener {
		@SubscribeEvent
		public void registerKeyMappings(RegisterKeyMappingsEvent event) {
			KeyMapping.Category category = KeyMapping.Category.register(
					Identifier.fromNamespaceAndPath(HeartsPlus.MOD_ID, "main"));
			toggleRenderingKey = new KeyMapping("key.heartsplus.toggle", KEY_UNKNOWN, category);
			openSettingsKey = new KeyMapping("key.heartsplus.settings", KEY_H, category);
			event.register(toggleRenderingKey);
			event.register(openSettingsKey);
		}

		@SubscribeEvent
		public void addReloadListeners(AddClientReloadListenersEvent event) {
			// Atlas sprites are re-stitched on resource reloads and their UV
			// coordinates move, so the renderer's sprite cache must be dropped.
			// The cast picks the single-method ResourceManagerReloadListener
			// flavour of PreparableReloadListener so a lambda compiles.
			event.addListener(Identifier.fromNamespaceAndPath(HeartsPlus.MOD_ID, "atlas_sprite_cache"),
					(ResourceManagerReloadListener) (ResourceManager resourceManager) ->
							HeartsAboveHeadRenderer.invalidateAtlasSprites());
		}
	}

	/** Game-bus listeners: per-tick events. */
	static final class TickListener {
		@SubscribeEvent
		public void onClientTick(ClientTickEvent.Post event) {
			Minecraft client = Minecraft.getInstance();
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
		}
	}

	private static void reportState(Minecraft client) {
		if (client.player != null) {
			client.player.sendOverlayMessage(HeartsPlusConfig.isEnabled()
					? Component.translatable("heartsplus.message.enabled")
					: Component.translatable("heartsplus.message.disabled"));
		}
	}
}
