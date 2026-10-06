package com.heartsplus;

import com.heartsplus.render.HeartsAboveHeadRenderer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
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

	public HeartsPlusClient() {
		HeartsPlusConfig.load();
		LOGGER.info("HeartsPlus client initialized");
		NeoForge.EVENT_BUS.register(this);
	}

	@SubscribeEvent
	public void registerKeyMappings(RegisterKeyMappingsEvent event) {
		KeyMapping.Category category = KeyMapping.Category.register(
				Identifier.fromNamespaceAndPath(HeartsPlus.MOD_ID, "main"));
		toggleRenderingKey = new KeyMapping("key.heartsplus.toggle", KEY_H, category);
		openSettingsKey = new KeyMapping("key.heartsplus.settings", KEY_UNKNOWN, category);
		event.register(toggleRenderingKey);
		event.register(openSettingsKey);
	}

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
			if (client.player != null) {
				client.player.sendOverlayMessage(HeartsPlusConfig.isEnabled()
						? Component.translatable("heartsplus.message.enabled")
						: Component.translatable("heartsplus.message.disabled"));
			}
		}
		while (openSettingsKey.consumeClick()) {
			client.setScreenAndShow(new HeartsPlusConfigScreen(null));
		}
	}
}
