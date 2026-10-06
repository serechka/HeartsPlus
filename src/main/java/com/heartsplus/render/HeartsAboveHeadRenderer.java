package com.heartsplus.render;

import com.heartsplus.HeartsPlusConfig;
import com.heartsplus.HeartsPlusLog;
import com.heartsplus.mixin.EntityRenderDispatcherMixin;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiSpriteManager;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;
import org.slf4j.Logger;

/**
 * Draws a row of vanilla heart sprites above a player's head (classic
 * 1.21.0/1.21.1 pipeline). Called at the end of LivingEntityRenderer.render,
 * so the incoming PoseStack is positioned at the entity origin and quads
 * can be emitted straight into the frame's MultiBufferSource.
 *
 * <p>The transform mirrors the vanilla name tag recipe from
 * EntityRenderer.renderNameTag: translate to the anchor, rotate by the
 * camera orientation, scale(0.025 * scale, -0.025 * scale, 0.025 * scale).
 * Hearts are drawn with the world-text render types, which — like name
 * tags — are shaded only by the lightmap, so they look identical from every
 * viewing angle. The anchor height is a fixed constant so the bar never jumps
 * with pose changes. Sprites, pass structure and the animation all mirror the
 * vanilla HUD (Gui.renderHearts): containers deepest, then absorption, then
 * the blink overlay, with the health hearts on top — the same painter order
 * the HUD uses, flattened onto z layers. Sneaking players get nothing,
 * exactly like the see-through part of a vanilla name tag; everyone else gets
 * the two nametag passes — a depth-tested one and, when "show behind blocks"
 * is on, a dimmed half-transparent see-through copy. Geometry is submitted in
 * passes per texture (containers, absorption, blinking, health) because
 * default-texture mode binds individual sprites instead of the shared GUI
 * atlas.</p>
 */
public final class HeartsAboveHeadRenderer {
	private static final Logger LOGGER = HeartsPlusLog.LOGGER;
	/** Default-pack textures that failed to load; those hearts fall back to atlas sprites. */
	private static final Set<ResourceLocation> unavailableFileTextures = new HashSet<>();
	/** Each skip reason is logged once so missing hearts can be diagnosed from the log. */
	private static final Set<String> reportedSkips = new HashSet<>();
	/**
	 * Resolved sprites per heart family, indexed by the (half, blinking)
	 * variant. Default-pack entries are constant for a session; atlas entries
	 * are re-stitched on resource reloads and dropped by
	 * {@link #invalidateAtlasSprites()}. Render-thread only, no locking.
	 */
	private static final Map<HeartType, ResolvedSprite[]> fileSprites = new EnumMap<>(HeartType.class);
	private static final Map<HeartType, ResolvedSprite[]> atlasSprites = new EnumMap<>(HeartType.class);
	private static boolean vanillaTexturesWarmed;
	private static boolean reportedFirstHeart;
	/** Render failures are logged with a stack this many times; later ones only bump the counter. */
	private static final int MAX_REPORTED_FAILURES = 5;
	/** One line per this many suppressed failures, so a persistent error stays visible without spam. */
	private static final int SUPPRESSED_FAILURE_REPORT_STEP = 100;
	private static int reportedFailures;
	private static int suppressedFailures;

	/**
	 * Fixed height above the entity origin where the heart bar sits:
	 * 0.4.3 ideal +2px (2 GUI px × 0.025). Deliberately not derived from
	 * nameTagAttachment/boundingBoxHeight: those sag in the sneak pose with
	 * interpolation lag, which made the hearts jump.
	 */
	private static final float HEART_ANCHOR_HEIGHT = 2.10F;
	private static final float PIXELS_PER_BLOCK = 0.025F;
	/** Alpha of the name tag's see-through copy: vanilla colour 0x20FFFFFF (EntityRenderer.renderNameTag). */
	private static final int SEE_THROUGH_ALPHA = 0x20;
	/** Vanilla shakes the bar from a generator seeded per tick with this exact product (Gui.renderHealthLevel). */
	private static final int SHAKE_SEED_MULTIPLIER = 312871;

	private HeartsAboveHeadRenderer() {
	}

	/**
	 * Registers and uploads every default-pack heart texture before the first
	 * frame needs them. Lazy registration from inside render submission
	 * produces textures that are never actually uploaded, which makes the
	 * hearts invisible, so warm-up is done from the first client tick. The
	 * built-in default pack is static — these textures never need reload
	 * invalidation, unlike the atlas sprites. Textures are registered with
	 * forced NEAREST filtering: they are 9x9 pixel art and must stay crisp at
	 * any scale.
	 */
	public static void warmUpVanillaTextures(TextureManager textureManager, ResourceManager resourceManager) {
		if (vanillaTexturesWarmed) {
			return;
		}
		vanillaTexturesWarmed = true;
		for (HeartType type : HeartType.values()) {
			for (ResourceLocation texture : type.fileTextures()) {
				// A missing resource would be swapped for the checkerboard
				// texture instead of failing, so the fallback is decided here.
				if (!resourceManager.getResource(texture).isPresent()) {
					unavailableFileTextures.add(texture);
					LOGGER.warn("Default-pack heart texture {} is missing; falling back to atlas sprites", texture);
					continue;
				}
				try {
					textureManager.register(texture, new DefaultPackTexture(texture));
				} catch (Exception e) {
					unavailableFileTextures.add(texture);
					LOGGER.warn("Failed to load default-pack heart texture {}; falling back to atlas sprites", texture, e);
				}
			}
		}
	}

	/**
	 * Drops cached atlas sprites. Must run on every client resource reload —
	 * sprites are re-stitched there and their UV coordinates move.
	 */
	public static void invalidateAtlasSprites() {
		atlasSprites.clear();
	}

	public static void render(Player player, EntityRenderDispatcher dispatcher, PoseStack poseStack,
			MultiBufferSource bufferSource, int packedLight) {
		try {
			renderHearts(player, dispatcher, poseStack, bufferSource, packedLight);
		} catch (Throwable t) {
			if (reportedFailures < MAX_REPORTED_FAILURES) {
				reportedFailures++;
				LOGGER.error("HeartsPlus failed to render hearts (report {} of {})", reportedFailures, MAX_REPORTED_FAILURES, t);
			} else {
				suppressedFailures++;
				if (suppressedFailures % SUPPRESSED_FAILURE_REPORT_STEP == 0) {
					LOGGER.warn("HeartsPlus: {} further render failures suppressed after the first {} reports",
							suppressedFailures, MAX_REPORTED_FAILURES);
				}
			}
		}
	}

	private static void renderHearts(Player player, EntityRenderDispatcher dispatcher, PoseStack poseStack,
			MultiBufferSource bufferSource, int packedLight) {
		if (!HeartsPlusConfig.isEnabled() || player.isSpectator()) {
			skipOnce("mod disabled or player is a spectator");
			return;
		}
		if (player == Minecraft.getInstance().player && !HeartsPlusConfig.isShowOwnHearts()) {
			skipOnce("own player hidden (enable 'Show above yourself')");
			return;
		}
		double maxDistance = HeartsPlusConfig.getRenderDistance();
		if (dispatcher.distanceToSqr(player) > maxDistance * maxDistance) {
			skipOnce("player beyond the render distance");
			return;
		}
		if (player.isInvisible()
				&& !(HeartsPlusConfig.isShowInvisiblePlayers() && hasVisibleArmour(player))) {
			// Hidden by default; even when enabled, armour is the only thing
			// that betrays an invisible player.
			skipOnce("player is invisible");
			return;
		}
		if (!((EntityRenderDispatcherMixin) dispatcher).heartsplus$shouldRenderShadow()) {
			// Embedded entity previews (the inventory screen model) render
			// through the same code path with shadows disabled; the heart bar
			// is a world overlay and must not appear there.
			skipOnce("embedded entity preview (shadows disabled)");
			return;
		}

		float health = player.getHealth();
		// The animation state is fed before the sneak gate, so the vanilla
		// animation keeps ticking for every rendered player even while their
		// hearts are not drawn.
		HeartAnimationState animationState = PlayerBlinkTracker.stateFor(player.getUUID());
		animationState.tick(Mth.ceil(health), player.tickCount, Util.getMillis(), player.invulnerableTime > 0);
		if (player.isDiscrete()) {
			// A sneaking player's name tag drops its see-through copy
			// (EntityRenderer.renderNameTag gates it on !isDiscrete), so a
			// sneaking player behind blocks shows nothing at all. The hearts
			// follow that rule unconditionally.
			skipOnce("player is sneaking");
			return;
		}

		// With the animation off the bar is static: no blink windows and no
		// displayHealth lag, so the blink passes are skipped entirely and their
		// blinking sprites are never resolved (the resolve calls sit inside the
		// `blinking` gate).
		boolean animation = HeartsPlusConfig.isBlinkAnimationEnabled();
		int animationTick = player.tickCount;
		int displayHealth = animation ? animationState.displayHealth() : (int) Math.ceil(health);
		boolean blinking = animation && animationState.isBlinking();
		HeartBarLayout layout = HeartBarLayout.of(health, player.getMaxHealth(), player.getAbsorptionAmount(),
				displayHealth);
		if (layout.slots() <= 0) {
			// Degenerate health values — nothing to draw, skip all geometry work.
			return;
		}
		GuiSpriteManager spriteManager = Minecraft.getInstance().getGuiSprites();
		HeartType family = HeartType.forStatus(player.hasEffect(MobEffects.POISON),
				player.hasEffect(MobEffects.WITHER), player.isFullyFrozen());
		// Withered players keep their black absorption hearts, exactly like the vanilla HUD.
		HeartType absorptionFamily = family == HeartType.WITHERED ? HeartType.WITHERED : HeartType.ABSORBING;
		int[] shake = shakeOffsets(layout, animationTick, animation);
		int bounceSlot = layout.regenBounceSlot(animationTick, animation
				&& player.hasEffect(MobEffects.REGENERATION));

		poseStack.pushPose();
		poseStack.translate(0.0F, HEART_ANCHOR_HEIGHT, 0.0F);
		// The vanilla name tag recipe: billboard the bar towards the camera.
		poseStack.mulPose(dispatcher.cameraOrientation());
		float pixelScale = PIXELS_PER_BLOCK * (float) HeartsPlusConfig.getScale();
		poseStack.scale(pixelScale, -pixelScale, pixelScale);
		poseStack.translate(0.0F, -HeartsPlusConfig.getHeartOffset(), 0.0F);

		// Sprites are resolved lazily per pass so empty passes (no blinking, no
		// absorption) cost nothing at all. Each family sits on its own z layer
		// in the HUD's painter order: containers deepest, then absorption, then
		// the blink overlay, with the health hearts on top — without the
		// offsets the depth test z-fights the quads apart.
		float zStep = HeartLayerSpacing.zStep(Mth.sqrt((float) dispatcher.distanceToSqr(player)));
		final float containerZ = 0.0F;
		final float absorbingZ = zStep;
		final float familyBlinkingZ = 2 * zStep;
		final float familyZ = 3 * zStep;

		ResolvedSprite container = resolve(HeartType.CONTAINER, false, blinking, spriteManager);
		submitPass(bufferSource, poseStack, container, containerZ, packedLight, (vertices, matrix, light, alpha) -> {
			for (int slot = 0; slot < layout.slots(); slot++) {
				emitHeart(vertices, matrix, layout.x(slot), slotY(layout, slot, shake, bounceSlot), container, light, containerZ, alpha);
			}
		});
		if (hasAbsorption(layout, false)) {
			ResolvedSprite absorbingFull = resolve(absorptionFamily, false, false, spriteManager);
			submitPass(bufferSource, poseStack, absorbingFull, absorbingZ, packedLight, (vertices, matrix, light, alpha) -> {
				for (int slot = layout.healthContainers(); slot < layout.slots(); slot++) {
					if (layout.hasAbsorptionHeart(slot) && !layout.isAbsorptionHalf(slot)) {
						emitHeart(vertices, matrix, layout.x(slot), slotY(layout, slot, shake, bounceSlot), absorbingFull, light, absorbingZ, alpha);
					}
				}
			});
		}
		if (hasAbsorption(layout, true)) {
			ResolvedSprite absorbingHalf = resolve(absorptionFamily, true, false, spriteManager);
			submitPass(bufferSource, poseStack, absorbingHalf, absorbingZ, packedLight, (vertices, matrix, light, alpha) -> {
				for (int slot = layout.healthContainers(); slot < layout.slots(); slot++) {
					if (layout.hasAbsorptionHeart(slot) && layout.isAbsorptionHalf(slot)) {
						emitHeart(vertices, matrix, layout.x(slot), slotY(layout, slot, shake, bounceSlot), absorbingHalf, light, absorbingZ, alpha);
					}
				}
			});
		}
		if (blinking) {
			ResolvedSprite familyFullBlinking = resolve(family, false, true, spriteManager);
			submitPass(bufferSource, poseStack, familyFullBlinking, familyBlinkingZ, packedLight, (vertices, matrix, light, alpha) -> {
				for (int slot = 0; slot < layout.slots(); slot++) {
					if (layout.hasBlinkHeart(slot) && !layout.isBlinkHalf(slot)) {
						emitHeart(vertices, matrix, layout.x(slot), slotY(layout, slot, shake, bounceSlot), familyFullBlinking, light, familyBlinkingZ, alpha);
					}
				}
			});
			if (hasBlinkHalf(layout)) {
				ResolvedSprite familyHalfBlinking = resolve(family, true, true, spriteManager);
				submitPass(bufferSource, poseStack, familyHalfBlinking, familyBlinkingZ, packedLight, (vertices, matrix, light, alpha) -> {
					for (int slot = 0; slot < layout.slots(); slot++) {
						if (layout.hasBlinkHeart(slot) && layout.isBlinkHalf(slot)) {
							emitHeart(vertices, matrix, layout.x(slot), slotY(layout, slot, shake, bounceSlot), familyHalfBlinking, light, familyBlinkingZ, alpha);
						}
					}
				});
			}
		}
		if (hasHealth(layout, false)) {
			ResolvedSprite familyFull = resolve(family, false, false, spriteManager);
			submitPass(bufferSource, poseStack, familyFull, familyZ, packedLight, (vertices, matrix, light, alpha) -> {
				for (int slot = 0; slot < layout.slots(); slot++) {
					if (layout.hasHealthHeart(slot) && !layout.isHealthHalf(slot)) {
						emitHeart(vertices, matrix, layout.x(slot), slotY(layout, slot, shake, bounceSlot), familyFull, light, familyZ, alpha);
					}
				}
			});
		}
		if (hasHealth(layout, true)) {
			ResolvedSprite familyHalf = resolve(family, true, false, spriteManager);
			submitPass(bufferSource, poseStack, familyHalf, familyZ, packedLight, (vertices, matrix, light, alpha) -> {
				for (int slot = 0; slot < layout.slots(); slot++) {
					if (layout.hasHealthHeart(slot) && layout.isHealthHalf(slot)) {
						emitHeart(vertices, matrix, layout.x(slot), slotY(layout, slot, shake, bounceSlot), familyHalf, light, familyZ, alpha);
					}
				}
			});
		}

		poseStack.popPose();
		if (!reportedFirstHeart) {
			reportedFirstHeart = true;
			LOGGER.info("Hearts submitted above a player for the first time ({} hearts, texture {})",
					layout.slots(), container.texture());
		}
	}

	/** True when any slot draws a full (half=false) or half (half=true) absorption heart. */
	private static boolean hasAbsorption(HeartBarLayout layout, boolean half) {
		for (int slot = layout.healthContainers(); slot < layout.slots(); slot++) {
			if (layout.hasAbsorptionHeart(slot) && layout.isAbsorptionHalf(slot) == half) {
				return true;
			}
		}
		return false;
	}

	/** True when any slot draws a full (half=false) or half (half=true) health heart. */
	private static boolean hasHealth(HeartBarLayout layout, boolean half) {
		for (int slot = 0; slot < layout.slots(); slot++) {
			if (layout.hasHealthHeart(slot) && layout.isHealthHalf(slot) == half) {
				return true;
			}
		}
		return false;
	}

	/** True while a half-heart blink overlay should be drawn (only the top displayHealth heart can be half). */
	private static boolean hasBlinkHalf(HeartBarLayout layout) {
		for (int slot = 0; slot < layout.slots(); slot++) {
			if (layout.hasBlinkHeart(slot) && layout.isBlinkHalf(slot)) {
				return true;
			}
		}
		return false;
	}

	/** Top GUI pixel of a slot's sprites, with the low-health shake and the Regeneration bounce applied. */
	private static float slotY(HeartBarLayout layout, int slot, int[] shake, int bounceSlot) {
		float y = layout.yTop(slot) + shake[slot];
		if (slot == bounceSlot) {
			y -= 2.0F;
		}
		return y;
	}

	/**
	 * Vanilla's per-tick low-health jitter: while the bar holds two hearts or
	 * less, every heart is offset by a random extra pixel. The generator is
	 * seeded per tick — with the same integer product as vanilla — and
	 * consumed from the top slot down exactly like Gui.renderHearts. A static
	 * bar (animation off) skips the jitter entirely.
	 */
	private static int[] shakeOffsets(HeartBarLayout layout, int tick, boolean animation) {
		int[] shake = new int[layout.slots()];
		if (animation && layout.shakes()) {
			RandomSource random = RandomSource.create();
			random.setSeed(tick * SHAKE_SEED_MULTIPLIER);
			for (int slot = layout.slots() - 1; slot >= 0; slot--) {
				shake[slot] = random.nextInt(2);
			}
		}
		return shake;
	}

	private static void skipOnce(String reason) {
		if (reportedSkips.add(reason)) {
			LOGGER.info("Hearts above a player were skipped: {} [enabled={}, showOwnHearts={}, showInvisible={}, showBehindBlocks={}, vanillaTextures={}, scale={}, renderDistance={}]",
					reason, HeartsPlusConfig.isEnabled(), HeartsPlusConfig.isShowOwnHearts(),
					HeartsPlusConfig.isShowInvisiblePlayers(), HeartsPlusConfig.isShowBehindBlocks(),
					HeartsPlusConfig.isVanillaTextures(), HeartsPlusConfig.getScale(), HeartsPlusConfig.getRenderDistance());
		}
	}

	private static boolean hasVisibleArmour(Player player) {
		return !player.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
				|| !player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
				|| !player.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
				|| !player.getItemBySlot(EquipmentSlot.FEET).isEmpty();
	}

	private static ResolvedSprite resolve(HeartType type, boolean half, boolean blinking,
			GuiSpriteManager spriteManager) {
		// The warm-up gate keeps the first frames after enabling the option on
		// the atlas: default-pack textures are registered from a client tick,
		// and a mid-frame first upload would leave them blank until F3+T.
		if (HeartsPlusConfig.isVanillaTextures() && vanillaTexturesWarmed) {
			ResourceLocation file = blinking
					? half ? type.fileHalfBlinking : type.fileFullBlinking
					: half ? type.fileHalf : type.fileFull;
			if (!unavailableFileTextures.contains(file)) {
				// Standalone texture files are drawn whole, so UV covers 0..1.
				return cachedSprite(fileSprites, type, half, blinking,
						() -> new ResolvedSprite(file, 0.0F, 0.0F, 1.0F, 1.0F));
			}
		}
		ResourceLocation spriteId = blinking
				? half ? type.atlasHalfBlinking : type.atlasFullBlinking
				: half ? type.atlasHalf : type.atlasFull;
		return cachedSprite(atlasSprites, type, half, blinking, () -> {
			TextureAtlasSprite sprite = spriteManager.getSprite(spriteId);
			return new ResolvedSprite(sprite.atlasLocation(), sprite.getU0(), sprite.getV0(),
					sprite.getU1(), sprite.getV1());
		});
	}

	/** Slot index for a (half, blinking) variant inside a family's sprite array. */
	private static int variantIndex(boolean half, boolean blinking) {
		return (half ? 1 : 0) | (blinking ? 2 : 0);
	}

	private static ResolvedSprite cachedSprite(Map<HeartType, ResolvedSprite[]> cache, HeartType type,
			boolean half, boolean blinking, Supplier<ResolvedSprite> loader) {
		ResolvedSprite[] variants = cache.computeIfAbsent(type, t -> new ResolvedSprite[4]);
		int slot = variantIndex(half, blinking);
		ResolvedSprite sprite = variants[slot];
		if (sprite == null) {
			sprite = loader.get();
			variants[slot] = sprite;
		}
		return sprite;
	}

	/**
	 * Submits one sprite's geometry as the two vanilla name tag passes
	 * (EntityRenderer.renderNameTag): a depth-tested one with the plain light
	 * coords, and — when the option is on — a see-through copy without depth
	 * test, dimmed to the name tag's half-transparent white (0x20FFFFFF) and
	 * lit by the same plain light coords.
	 */
	private static void submitPass(MultiBufferSource bufferSource, PoseStack poseStack,
			ResolvedSprite sprite, float z, int packedLight, PassGeometry geometry) {
		Matrix4f matrix = poseStack.last().pose();
		VertexConsumer vertices = bufferSource.getBuffer(RenderType.text(sprite.texture()));
		geometry.emit(vertices, matrix, packedLight, 255);
		if (HeartsPlusConfig.isShowBehindBlocks()) {
			VertexConsumer seeThrough = bufferSource.getBuffer(RenderType.textSeeThrough(sprite.texture()));
			geometry.emit(seeThrough, matrix, packedLight, SEE_THROUGH_ALPHA);
		}
	}

	/** One emitted quad set for a single texture pass, parameterised by the per-pass light and alpha. */
	private interface PassGeometry {
		void emit(VertexConsumer vertices, Matrix4f matrix, int light, int alpha);
	}

	/**
	 * A heart sprite loaded straight from Minecraft's built-in default
	 * resource pack. {@link ResourceManager#getResourceStack} lists every
	 * pack's copy top-down (the active pack first), and the bottom-most entry
	 * is always the built-in default pack — reading that entry pins the look
	 * to the unmodified default sprites even when the player stacks override
	 * packs on top. Registered with NEAREST magnification and minification
	 * (no mipmaps): the 9x9 pixel art must stay crisp, while vanilla leaves
	 * freshly loaded textures on the linear GL defaults.
	 */
	private static final class DefaultPackTexture extends SimpleTexture {
		private DefaultPackTexture(ResourceLocation location) {
			super(location);
		}

		@Override
		protected TextureImage getTextureImage(ResourceManager resourceManager) {
			try {
				List<Resource> stack = resourceManager.getResourceStack(this.location);
				// An empty stack should not happen for vanilla-owned sprites;
				// fall back to the ordinary lookup (topmost pack) instead of
				// failing.
				Resource bottom = stack.isEmpty()
						? resourceManager.getResourceOrThrow(this.location)
						: stack.getLast();
				NativeImage image;
				// Heart sprite files carry no .mcmeta metadata.
				try (InputStream stream = bottom.open()) {
					image = NativeImage.read(stream);
				}
				return new TextureImage(null, image);
			} catch (IOException e) {
				return new TextureImage(e);
			}
		}

		@Override
		public void load(ResourceManager resourceManager) throws IOException {
			super.load(resourceManager);
			this.setFilter(false, false);
		}
	}

	private record ResolvedSprite(ResourceLocation texture, float u0, float v0, float u1, float v1) {
	}

	private static void emitHeart(VertexConsumer vertices, Matrix4f matrix, float x, float yTop,
			ResolvedSprite sprite, int packedLight, float z, int alpha) {
		float endX = x + HeartBarLayout.HEART_SIZE;
		float endY = yTop + HeartBarLayout.HEART_SIZE;
		// The world-text vertex format is POSITION_COLOR_TEX_LIGHTMAP; every
		// element must be set or the BufferBuilder validation rejects the vertex.
		// The pipeline culls back faces, so the winding must match vanilla text
		// quads (GlyphRenderTypes): top-left, bottom-left, bottom-right, top-right.
		vertices.addVertex(matrix, x, yTop, z).setColor(255, 255, 255, alpha).setUv(sprite.u0(), sprite.v0()).setLight(packedLight);
		vertices.addVertex(matrix, x, endY, z).setColor(255, 255, 255, alpha).setUv(sprite.u0(), sprite.v1()).setLight(packedLight);
		vertices.addVertex(matrix, endX, endY, z).setColor(255, 255, 255, alpha).setUv(sprite.u1(), sprite.v1()).setLight(packedLight);
		vertices.addVertex(matrix, endX, yTop, z).setColor(255, 255, 255, alpha).setUv(sprite.u1(), sprite.v0()).setLight(packedLight);
	}
}
