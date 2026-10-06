package com.heartsplus;

import com.heartsplus.render.HeartsAboveHeadRenderer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge client bootstrap: loads the config, registers key mappings and
 * consumes key presses every client tick. Rendering itself is handled by the
 * same mixins as on Fabric — NeoForge uses identical Mojang-mapped classes.
 */
@Mod(value = HeartsPlus.MOD_ID, dist = {Dist.CLIENT})
public class HeartsPlusClient {
	/** GLFW key codes, inlined so the mod does not depend on the LWJGL glfw package. */
	private static final int KEY_UNKNOWN = -1;
	private static final int KEY_H = 72;

	private static final Logger LOGGER = LoggerFactory.getLogger(HeartsPlus.class);
	private static KeyMapping toggleRenderingKey;
	private static KeyMapping openSettingsKey;
	private static boolean texturesWarmedUp;

	public HeartsPlusClient(ModContainer container) {
		HeartsPlusConfig.load();
		LOGGER.info("HeartsPlus client initialized");
		// Key-mapping registration is a mod-bus event, client ticks are
		// game-bus events — and each bus rejects listeners for the other's
		// events, so the two listeners must be registered separately.
		container.getEventBus().register(new KeyMappingListener());
		NeoForge.EVENT_BUS.register(new TickListener());
	}

	/** Mod-bus listeners: registration-time events only. */
	static final class KeyMappingListener {
		@SubscribeEvent
		public void registerKeyMappings(RegisterKeyMappingsEvent event) {
			KeyMapping.Category category = new KeyMapping.Category(
					Identifier.fromNamespaceAndPath(HeartsPlus.MOD_ID, "main"));
			event.registerCategory(category);
			toggleRenderingKey = new KeyMapping("key.heartsplus.toggle", KEY_UNKNOWN, category);
			openSettingsKey = new KeyMapping("key.heartsplus.settings", KEY_H, category);
			event.register(toggleRenderingKey);
			event.register(openSettingsKey);
		}
	}

	/** Game-bus listeners: per-tick events. */
	static final class TickListener {
		@SubscribeEvent
		public void onClientTick(ClientTickEvent.Post event) {
			Minecraft client = Minecraft.getInstance();
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
				if (client.screen == null) {
					client.setScreen(new HeartsPlusConfigScreen(null));
				}
			}
		}
	}

	private static void reportState(Minecraft client) {
		if (client.player != null) {
			client.player.displayClientMessage(HeartsPlusConfig.isEnabled()
					? Component.translatable("heartsplus.message.enabled")
					: Component.translatable("heartsplus.message.disabled"), true);
		}
	}
}
