package com.heartsplus.render;

import com.heartsplus.HeartsPlusConfig;
import com.heartsplus.HeartsPlusLog;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiSpriteManager;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.slf4j.Logger;

/**
 * Draws a row of vanilla heart sprites above a player's head (1.21.x line).
 * Called at the end of LivingEntityRenderer.render, so the incoming PoseStack
 * is positioned at the entity origin and the geometry is written straight
 * into the frame's MultiBufferSource — the classic immediate render path this
 * stretch of versions still uses (the submit/extract split only arrived in
 * 1.21.9).
 *
 * <p>Hearts are drawn with the world-text render types, which — like name
 * tags — are shaded only by the lightmap, so they look identical from every
 * viewing angle. The anchor height is a fixed constant so the bar never jumps
 * with pose changes. Sprites, pass structure and the animation all mirror the
 * vanilla HUD (Gui.renderHearts): containers deepest, then absorption, then
 * the blink overlay, with the health hearts on top — the same painter order
 * the HUD uses, flattened onto z layers. Sneaking players get nothing,
 * exactly like the see-through part of a vanilla name tag; everyone else gets
 * the two nametag passes — a depth-tested one and, when "show behind blocks"
 * is on, a dimmed half-transparent see-through copy. Geometry is drawn in
 * passes per texture (containers, health, blinking, absorption) because
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
	 * nameTagAttachment/boundingBoxHeight: those sag in the sneak
	 * pose with interpolation lag, which made the hearts jump.
	 */
	private static final float HEART_ANCHOR_HEIGHT = 2.10F;
	private static final float PIXELS_PER_BLOCK = 0.025F;
	/** Vanilla name tag text is drawn with this much extra light emission (EntityRenderer.renderNameTag). */
	private static final int NAMETAG_EMISSION = 2;
	/** Alpha of the nametag's see-through copy: vanilla colour 0x80FFFFFF — half-transparent white. */
	private static final int SEE_THROUGH_ALPHA = 0x80;
	/** Vanilla shakes the bar from a generator seeded per tick with this exact product (Gui.renderHealthLevel). */
	private static final int SHAKE_SEED_MULTIPLIER = 312871;

	private HeartsAboveHeadRenderer() {
	}

	/**
	 * Registers and uploads every default-pack heart texture before the first
	 * frame needs them. Lazy registration from inside render produces
	 * textures that are never actually uploaded, which makes the hearts
	 * invisible, so warm-up is done from the first client tick. The built-in
	 * default pack is static — these textures never need reload
	 * invalidation, unlike the atlas sprites. Textures are registered with
	 * forced NEAREST filtering: they are 9x9 pixel art and must stay crisp at
	 * any scale.
	 */
	public static void warmUpVanillaTextures(TextureManager textureManager) {
		if (vanillaTexturesWarmed) {
			return;
		}
		vanillaTexturesWarmed = true;
		ResourceManager resourceManager = Minecraft.getInstance().getResourceManager();
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

	public static void render(PlayerRenderState state, HealthHolder health, PoseStack poseStack,
			MultiBufferSource bufferSource, int packedLight, Quaternionf cameraRotation) {
		try {
			renderHearts(state, health, poseStack, bufferSource, packedLight, cameraRotation);
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

	private static void renderHearts(PlayerRenderState state, HealthHolder health, PoseStack poseStack,
			MultiBufferSource bufferSource, int packedLight, Quaternionf cameraRotation) {
		if (!HeartsPlusConfig.isEnabled() || state.isSpectator) {
			skipOnce("mod disabled or player is a spectator");
			return;
		}
		if (health.heartsplus$isLocalPlayer() && !HeartsPlusConfig.isShowOwnHearts()) {
			skipOnce("own player hidden (enable 'Show above yourself')");
			return;
		}
		if (state.isDiscrete) {
			// Vanilla never hides a sneaking player's solid name tag but drops
			// its see-through copy (EntityRenderer gates both passes on
			// !isDiscrete), so a sneaking player behind blocks shows nothing at
			// all. The hearts follow that rule unconditionally.
			skipOnce("player is sneaking");
			return;
		}
		double maxDistance = HeartsPlusConfig.getRenderDistance();
		if (state.distanceToCameraSq > maxDistance * maxDistance) {
			skipOnce("player beyond the render distance");
			return;
		}
		if (state.isInvisibleToPlayer
				&& !(HeartsPlusConfig.isShowInvisiblePlayers() && health.heartsplus$hasVisibleArmour())) {
			// Hidden by default; even when enabled, armour is the only thing
			// that betrays an invisible player.
			skipOnce("player is invisible");
			return;
		}

		// With the animation off the bar is static: no blink windows and no
		// displayHealth lag, so the blink passes are skipped entirely and their
		// blinking sprites are never resolved (the resolve calls sit inside the
		// `blinking` gate).
		boolean animation = HeartsPlusConfig.isBlinkAnimationEnabled();
		int animationTick = health.heartsplus$getAnimationTick();
		int displayHealth = animation ? health.heartsplus$getDisplayHealth() : (int) Math.ceil(health.heartsplus$getHealth());
		boolean blinking = animation && health.heartsplus$isBlinking();
		HeartBarLayout layout = HeartBarLayout.of(health.heartsplus$getHealth(), health.heartsplus$getMaxHealth(),
				health.heartsplus$getAbsorption(), displayHealth);
		if (layout.slots() <= 0) {
			// Degenerate health values — nothing to draw, skip all geometry work.
			return;
		}
		GuiSpriteManager guiSpriteManager = Minecraft.getInstance().getGuiSprites();
		HeartType family = HeartType.forStatus(health.heartsplus$isPoisoned(), health.heartsplus$isWithered(),
				health.heartsplus$isFrozen());
		// Withered players keep their black absorption hearts, exactly like the vanilla HUD.
		HeartType absorptionFamily = family == HeartType.WITHERED ? HeartType.WITHERED : HeartType.ABSORBING;
		int[] shake = shakeOffsets(layout, animationTick, animation);
		int bounceSlot = layout.regenBounceSlot(animationTick, animation && health.heartsplus$isRegenerating());
		ResolvedSprite container = resolve(HeartType.CONTAINER, false, blinking, guiSpriteManager);

		poseStack.pushPose();
		poseStack.translate(0.0F, HEART_ANCHOR_HEIGHT, 0.0F);
		// rotateAround(..., 0, 0, 0) equals a plain rotation and avoids the
		// per-frame quaternion allocation a mulPose copy would need.
		poseStack.rotateAround(cameraRotation, 0.0F, 0.0F, 0.0F);
		float pixelScale = PIXELS_PER_BLOCK * (float) HeartsPlusConfig.getScale();
		poseStack.scale(pixelScale, -pixelScale, pixelScale);
		poseStack.translate(0.0F, -HeartsPlusConfig.getHeartOffset(), 0.0F);
		Matrix4f pose = poseStack.last().pose();

		// Passes are drawn lazily per family so empty passes (no blinking, no
		// absorption) cost nothing at all. Each family sits on its own z layer
		// in the HUD's painter order: containers deepest, then absorption, then
		// the blink overlay, with the health hearts on top — without the
		// offsets the depth test z-fights the quads apart.
		float zStep = HeartLayerSpacing.zStep(Mth.sqrt((float) state.distanceToCameraSq));
		final float containerZ = 0.0F;
		final float absorbingZ = zStep;
		final float familyBlinkingZ = 2 * zStep;
		final float familyZ = 3 * zStep;
		drawPass(bufferSource, container, packedLight, (vertices, passLight, alpha) -> {
			for (int slot = 0; slot < layout.slots(); slot++) {
				emitHeart(vertices, pose, layout.x(slot), slotY(layout, slot, shake, bounceSlot), container, passLight, containerZ, alpha);
			}
		});
		if (hasAbsorption(layout, false)) {
			ResolvedSprite absorbingFull = resolve(absorptionFamily, false, false, guiSpriteManager);
			drawPass(bufferSource, absorbingFull, packedLight, (vertices, passLight, alpha) -> {
				for (int slot = layout.healthContainers(); slot < layout.slots(); slot++) {
					if (layout.hasAbsorptionHeart(slot) && !layout.isAbsorptionHalf(slot)) {
						emitHeart(vertices, pose, layout.x(slot), slotY(layout, slot, shake, bounceSlot), absorbingFull, passLight, absorbingZ, alpha);
					}
				}
			});
		}
		if (hasAbsorption(layout, true)) {
			ResolvedSprite absorbingHalf = resolve(absorptionFamily, true, false, guiSpriteManager);
			drawPass(bufferSource, absorbingHalf, packedLight, (vertices, passLight, alpha) -> {
				for (int slot = layout.healthContainers(); slot < layout.slots(); slot++) {
					if (layout.hasAbsorptionHeart(slot) && layout.isAbsorptionHalf(slot)) {
						emitHeart(vertices, pose, layout.x(slot), slotY(layout, slot, shake, bounceSlot), absorbingHalf, passLight, absorbingZ, alpha);
					}
				}
			});
		}
		if (blinking) {
			ResolvedSprite familyFullBlinking = resolve(family, false, true, guiSpriteManager);
			drawPass(bufferSource, familyFullBlinking, packedLight, (vertices, passLight, alpha) -> {
				for (int slot = 0; slot < layout.slots(); slot++) {
					if (layout.hasBlinkHeart(slot) && !layout.isBlinkHalf(slot)) {
						emitHeart(vertices, pose, layout.x(slot), slotY(layout, slot, shake, bounceSlot), familyFullBlinking, passLight, familyBlinkingZ, alpha);
					}
				}
			});
			if (hasBlinkHalf(layout)) {
				ResolvedSprite familyHalfBlinking = resolve(family, true, true, guiSpriteManager);
				drawPass(bufferSource, familyHalfBlinking, packedLight, (vertices, passLight, alpha) -> {
					for (int slot = 0; slot < layout.slots(); slot++) {
						if (layout.hasBlinkHeart(slot) && layout.isBlinkHalf(slot)) {
							emitHeart(vertices, pose, layout.x(slot), slotY(layout, slot, shake, bounceSlot), familyHalfBlinking, passLight, familyBlinkingZ, alpha);
						}
					}
				});
			}
		}
		if (hasHealth(layout, false)) {
			ResolvedSprite familyFull = resolve(family, false, false, guiSpriteManager);
			drawPass(bufferSource, familyFull, packedLight, (vertices, passLight, alpha) -> {
				for (int slot = 0; slot < layout.slots(); slot++) {
					if (layout.hasHealthHeart(slot) && !layout.isHealthHalf(slot)) {
						emitHeart(vertices, pose, layout.x(slot), slotY(layout, slot, shake, bounceSlot), familyFull, passLight, familyZ, alpha);
					}
				}
			});
		}
		if (hasHealth(layout, true)) {
			ResolvedSprite familyHalf = resolve(family, true, false, guiSpriteManager);
			drawPass(bufferSource, familyHalf, packedLight, (vertices, passLight, alpha) -> {
				for (int slot = 0; slot < layout.slots(); slot++) {
					if (layout.hasHealthHeart(slot) && layout.isHealthHalf(slot)) {
						emitHeart(vertices, pose, layout.x(slot), slotY(layout, slot, shake, bounceSlot), familyHalf, passLight, familyZ, alpha);
					}
				}
			});
		}

		poseStack.popPose();
		if (!reportedFirstHeart) {
			reportedFirstHeart = true;
			LOGGER.info("Hearts drawn above a player for the first time ({} hearts, texture {})",
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

	private static ResolvedSprite resolve(HeartType type, boolean half, boolean blinking, GuiSpriteManager guiSpriteManager) {
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
			TextureAtlasSprite sprite = guiSpriteManager.getSprite(spriteId);
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
	 * Draws one sprite's geometry as the two vanilla name tag passes: a
	 * depth-tested one with the nametag's +2 light emission, and — when the
	 * option is on — a see-through copy without depth test, dimmed to the
	 * nametag's half-transparent white (0x80FFFFFF) and lit by the plain
	 * light coords, bit-exact with EntityRenderer.renderNameTag.
	 */
	private static void drawPass(MultiBufferSource bufferSource, ResolvedSprite sprite, int packedLight,
			HeartEmitter emitter) {
		int emissiveLight = LightTexture.lightCoordsWithEmission(packedLight, NAMETAG_EMISSION);
		// Mirrors vanilla nameplates: the normal layer is occluded by walls,
		// the see-through layer shows dimly through them. All quads of a pass
		// are emitted before the next buffer is requested, so a shared vertex
		// builder can never mix the passes up.
		emitter.emit(bufferSource.getBuffer(RenderType.text(sprite.texture())), emissiveLight, 255);
		if (HeartsPlusConfig.isShowBehindBlocks()) {
			// Same geometry again without a depth test, so it stays visible
			// through walls — exactly how vanilla name tags draw their
			// see-through part. Drawn only when the option is on, so the
			// default path stays untouched.
			emitter.emit(bufferSource.getBuffer(RenderType.textSeeThrough(sprite.texture())), packedLight, SEE_THROUGH_ALPHA);
		}
	}

	/** One slot's quad emission, parameterised by the per-pass light and alpha. */
	@FunctionalInterface
	private interface HeartEmitter {
		void emit(VertexConsumer vertices, int light, int alpha);
	}

	/**
	 * A heart sprite loaded straight from Minecraft's built-in default
	 * resource pack. {@link ResourceManager#getResourceStack} lists every
	 * pack's copy top-down (the active pack first), and the bottom-most entry
	 * is always the built-in default pack — reading that entry pins the look
	 * to the unmodified default sprites even when the player stacks override
	 * packs on top. Forced NEAREST magnification and minification (no
	 * mipmaps): the 9x9 pixel art must stay crisp, while the metadata-less
	 * default can resolve to a linear sampler.
	 */
	private static final class DefaultPackTexture extends SimpleTexture {
		private DefaultPackTexture(ResourceLocation location) {
			super(location);
		}

		@Override
		public void load(ResourceManager manager) throws IOException {
			super.load(manager);
			this.setFilter(false, false);
		}

		@Override
		protected SimpleTexture.TextureImage getTextureImage(ResourceManager manager) {
			try {
				List<Resource> stack = manager.getResourceStack(this.location);
				// An empty stack should not happen for vanilla-owned sprites; fall
				// back to the ordinary lookup (topmost pack) instead of failing.
				InputStream stream = stack.isEmpty()
						? manager.open(this.location)
						: stack.getLast().open();
				try (stream) {
					return new SimpleTexture.TextureImage(null, NativeImage.read(stream));
				}
			} catch (IOException e) {
				return new SimpleTexture.TextureImage(e);
			}
		}
	}

	private record ResolvedSprite(ResourceLocation texture, float u0, float v0, float u1, float v1) {
	}

	private static void emitHeart(VertexConsumer vertices, Matrix4f pose, float x, float yTop,
			ResolvedSprite sprite, int packedLight, float z, int alpha) {
		float endX = x + HeartBarLayout.HEART_SIZE;
		float endY = yTop + HeartBarLayout.HEART_SIZE;
		// The world-text vertex format is POSITION_TEX_LIGHTMAP_COLOR; every
		// element must be set or the BufferBuilder validation rejects the vertex.
		// The pipeline culls back faces, so the winding must match vanilla text
		// quads (BakedQuad of the font renderer): top-left, bottom-left,
		// bottom-right, top-right.
		vertices.addVertex(pose, x, yTop, z).setUv(sprite.u0(), sprite.v0())
				.setLight(packedLight).setColor(255, 255, 255, alpha);
		vertices.addVertex(pose, x, endY, z).setUv(sprite.u0(), sprite.v1())
				.setLight(packedLight).setColor(255, 255, 255, alpha);
		vertices.addVertex(pose, endX, endY, z).setUv(sprite.u1(), sprite.v1())
				.setLight(packedLight).setColor(255, 255, 255, alpha);
		vertices.addVertex(pose, endX, yTop, z).setUv(sprite.u1(), sprite.v0())
				.setLight(packedLight).setColor(255, 255, 255, alpha);
	}
}
