package com.heartsplus.render;

import com.heartsplus.HeartsPlusConfig;
import com.heartsplus.HeartsPlusLog;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.AtlasManager;
import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

/**
 * Draws a row of vanilla heart sprites above a player's head (1.21.x line).
 * Called at the end of LivingEntityRenderer.submit, so the incoming PoseStack
 * is positioned at the entity origin and the geometry is recorded through the
 * frame's SubmitNodeCollector.
 *
 * <p>Hearts are drawn with the world-text render types, which — like name
 * tags — are shaded only by the lightmap. The anchor height is a fixed
 * constant so the bar never jumps with pose changes. Each sprite family sits
 * on its own z layer (containers deepest, overlays closest) so the depth
 * test cannot hide the health hearts behind their containers. When "show
 * behind blocks" is on, every pass is also submitted with the see-through
 * text render type, mirroring how vanilla name tags draw their see-through
 * part. Sneaking players always get hearts. Recent health drops blink
 * exactly like the vanilla HUD does. Geometry is submitted in passes per
 * texture (containers, health, blinking, absorption) because
 * vanilla-texture mode binds individual files instead of the shared GUI
 * atlas.</p>
 */
public final class HeartsAboveHeadRenderer {
	private static final Logger LOGGER = HeartsPlusLog.LOGGER;
	/** Bundled-file textures that failed to load; those hearts fall back to atlas sprites. */
	private static final Set<Identifier> unavailableVanillaTextures = new HashSet<>();
	/** Each skip reason is logged once so missing hearts can be diagnosed from the log. */
	private static final Set<String> reportedSkips = new HashSet<>();
	/**
	 * Resolved sprites per heart family, indexed by the (half, blinking)
	 * variant. Bundled-file entries are constant for a session; atlas entries
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
	 * Fixed height above the entity origin where the heart bar sits — player
	 * height (1.8) plus the vanilla name tag pad (0.5). Deliberately not
	 * derived from nameTagAttachment/boundingBoxHeight: those sag in the sneak
	 * pose with interpolation lag, which made the hearts jump.
	 */
	private static final float HEART_ANCHOR_HEIGHT = 2.3F;
	/** Z offset between consecutive sprite layers, in quad pixel units (before scale). */
	private static final float LAYER_Z_STEP = 0.01F;
	private static final float PIXELS_PER_BLOCK = 0.025F;

	private HeartsAboveHeadRenderer() {
	}

	/**
	 * Registers and uploads every bundled heart texture before the first
	 * frame needs them. Lazy registration from inside render submission
	 * produces textures that are never actually uploaded, which makes the
	 * hearts invisible, so warm-up is done from the first client tick.
	 * Textures are registered with forced NEAREST filtering: they are 9x9
	 * pixel art and must stay crisp at any scale.
	 */
	public static void warmUpVanillaTextures(TextureManager textureManager) {
		if (vanillaTexturesWarmed) {
			return;
		}
		vanillaTexturesWarmed = true;
		for (HeartType type : HeartType.values()) {
			for (Identifier texture : type.fileTextures()) {
				try {
					textureManager.registerAndLoad(texture, new NearestPixelTexture(texture));
				} catch (Exception e) {
					unavailableVanillaTextures.add(texture);
					LOGGER.warn("Failed to load bundled heart texture {}; falling back to atlas sprites", texture, e);
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

	public static void render(AvatarRenderState state, HealthHolder health, PoseStack poseStack,
			SubmitNodeCollector collector, CameraRenderState cameraState) {
		try {
			renderHearts(state, health, poseStack, collector, cameraState);
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

	private static void renderHearts(AvatarRenderState state, HealthHolder health, PoseStack poseStack,
			SubmitNodeCollector collector, CameraRenderState cameraState) {
		if (!HeartsPlusConfig.isEnabled() || state.isSpectator) {
			skipOnce("mod disabled or player is a spectator");
			return;
		}
		if (health.heartsplus$isLocalPlayer() && !HeartsPlusConfig.isShowOwnHearts()) {
			skipOnce("own player hidden (enable 'Show above yourself')");
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

		HeartBarLayout layout = HeartBarLayout.of(health.heartsplus$getHealth(), health.heartsplus$getMaxHealth(),
				health.heartsplus$getAbsorption(), health.heartsplus$getBlinkOldHealth());
		if (layout.heartsTotal() <= 0) {
			// Degenerate health values — nothing to draw, skip all geometry work.
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		AtlasManager atlasManager = minecraft.getAtlasManager();
		HeartType family = HeartType.forStatus(health.heartsplus$isPoisoned(), health.heartsplus$isWithered(),
				health.heartsplus$isFrozen());
		ResolvedSprite container = resolve(HeartType.CONTAINER, false, false, atlasManager);

		poseStack.pushPose();
		poseStack.translate(0.0F, HEART_ANCHOR_HEIGHT, 0.0F);
		// rotateAround(..., 0, 0, 0) equals a plain rotation and avoids the
		// per-frame quaternion allocation a mulPose copy would need.
		poseStack.rotateAround(cameraState.orientation, 0.0F, 0.0F, 0.0F);
		float pixelScale = PIXELS_PER_BLOCK * (float) HeartsPlusConfig.getScale();
		poseStack.scale(pixelScale, -pixelScale, pixelScale);
		poseStack.translate(0.0F, -HeartsPlusConfig.getHeartOffset(), 0.0F);

		int light = state.lightCoords;
		int blinkFrom = layout.heartsRed();
		int blinkTo = layout.blinkUpperBound((int) Math.floor(state.ageInTicks), health.heartsplus$getBlinkEndTick(),
				health.heartsplus$getBlinkOldHealth(), health.heartsplus$getHealth());

		// Sprites are resolved lazily per pass so empty passes (no blinking, no
		// absorption) cost nothing at all. Each family sits on its own z layer:
		// containers deepest, then health, blinking and absorption overlays —
		// without the offsets the depth test z-fights the quads apart.
		final float containerZ = 0.0F;
		final float familyFullZ = LAYER_Z_STEP;
		final float familyHalfZ = 2 * LAYER_Z_STEP;
		final float familyFullBlinkingZ = 3 * LAYER_Z_STEP;
		final float familyHalfBlinkingZ = 4 * LAYER_Z_STEP;
		final float absorbingFullZ = 5 * LAYER_Z_STEP;
		final float absorbingHalfZ = 6 * LAYER_Z_STEP;
		submitPass(collector, poseStack, container, containerZ, (pose, vertices) -> {
			for (int heart = 0; heart < layout.heartsTotal(); heart++) {
				emitHeart(pose, vertices, layout.x(heart), layout.yTop(heart), container, light, containerZ);
			}
		});
		if (layout.heartsRed() > 0) {
			ResolvedSprite familyFull = resolve(family, false, false, atlasManager);
			submitPass(collector, poseStack, familyFull, familyFullZ, (pose, vertices) -> {
				for (int heart = 0; heart < layout.heartsRed(); heart++) {
					if (!layout.isRedHalf(heart)) {
						emitHeart(pose, vertices, layout.x(heart), layout.yTop(heart), familyFull, light, familyFullZ);
					}
				}
			});
			if (layout.hasRedHalf()) {
				ResolvedSprite familyHalf = resolve(family, true, false, atlasManager);
				submitPass(collector, poseStack, familyHalf, familyHalfZ, (pose, vertices) ->
						emitHeart(pose, vertices, layout.x(layout.heartsRed() - 1), layout.yTop(layout.heartsRed() - 1), familyHalf, light, familyHalfZ));
			}
		}
		if (blinkTo > blinkFrom) {
			ResolvedSprite familyFullBlinking = resolve(family, false, true, atlasManager);
			submitPass(collector, poseStack, familyFullBlinking, familyFullBlinkingZ, (pose, vertices) -> {
				for (int heart = blinkFrom; heart < blinkTo; heart++) {
					if (heart != blinkTo - 1 || !layout.lastBlinkHalf()) {
						emitHeart(pose, vertices, layout.x(heart), layout.yTop(heart), familyFullBlinking, light, familyFullBlinkingZ);
					}
				}
			});
			if (layout.lastBlinkHalf()) {
				ResolvedSprite familyHalfBlinking = resolve(family, true, true, atlasManager);
				submitPass(collector, poseStack, familyHalfBlinking, familyHalfBlinkingZ, (pose, vertices) ->
						emitHeart(pose, vertices, layout.x(blinkTo - 1), layout.yTop(blinkTo - 1), familyHalfBlinking, light, familyHalfBlinkingZ));
			}
		}
		if (layout.heartsTotal() > layout.heartsNormal()) {
			ResolvedSprite absorbingFull = resolve(HeartType.ABSORBING, false, false, atlasManager);
			submitPass(collector, poseStack, absorbingFull, absorbingFullZ, (pose, vertices) -> {
				for (int heart = layout.heartsNormal(); heart < layout.heartsTotal(); heart++) {
					if (!layout.isYellowHalf(heart)) {
						emitHeart(pose, vertices, layout.x(heart), layout.yTop(heart), absorbingFull, light, absorbingFullZ);
					}
				}
			});
			if (layout.hasYellowHalf()) {
				ResolvedSprite absorbingHalf = resolve(HeartType.ABSORBING, true, false, atlasManager);
				submitPass(collector, poseStack, absorbingHalf, absorbingHalfZ, (pose, vertices) ->
						emitHeart(pose, vertices, layout.x(layout.heartsTotal() - 1), layout.yTop(layout.heartsTotal() - 1), absorbingHalf, light, absorbingHalfZ));
			}
		}

		poseStack.popPose();
		if (!reportedFirstHeart) {
			reportedFirstHeart = true;
			LOGGER.info("Hearts submitted above a player for the first time ({} hearts, texture {})",
					layout.heartsTotal(), container.texture());
		}
	}

	private static void skipOnce(String reason) {
		if (reportedSkips.add(reason)) {
			LOGGER.info("Hearts above a player were skipped: {} [enabled={}, showOwnHearts={}, showInvisible={}, showBehindBlocks={}, vanillaTextures={}, scale={}, renderDistance={}]",
					reason, HeartsPlusConfig.isEnabled(), HeartsPlusConfig.isShowOwnHearts(),
					HeartsPlusConfig.isShowInvisiblePlayers(), HeartsPlusConfig.isShowBehindBlocks(),
					HeartsPlusConfig.isVanillaTextures(), HeartsPlusConfig.getScale(), HeartsPlusConfig.getRenderDistance());
		}
	}

	private static ResolvedSprite resolve(HeartType type, boolean half, boolean blinking, AtlasManager atlasManager) {
		// The warm-up gate keeps the first frames after enabling the option on
		// the atlas: bundled textures are registered from a client tick, and a
		// mid-frame first upload would leave them blank until F3+T.
		if (HeartsPlusConfig.isVanillaTextures() && vanillaTexturesWarmed) {
			Identifier file = blinking
					? half ? type.fileHalfBlinking : type.fileFullBlinking
					: half ? type.fileHalf : type.fileFull;
			if (!unavailableVanillaTextures.contains(file)) {
				// Standalone texture files are drawn whole, so UV covers 0..1.
				return cachedSprite(fileSprites, type, half, blinking,
						() -> new ResolvedSprite(file, 0.0F, 0.0F, 1.0F, 1.0F));
			}
		}
		Identifier spriteId = blinking
				? half ? type.atlasHalfBlinking : type.atlasFullBlinking
				: half ? type.atlasHalf : type.atlasFull;
		return cachedSprite(atlasSprites, type, half, blinking, () -> {
			TextureAtlasSprite sprite = atlasManager.get(new Material(Sheets.GUI_SHEET, spriteId));
			return new ResolvedSprite(sprite.atlasLocation(), sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1());
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

	private static void submitPass(SubmitNodeCollector collector, PoseStack poseStack, ResolvedSprite sprite, float z,
			SubmitNodeCollector.CustomGeometryRenderer renderer) {
		// Mirrors vanilla nameplates: the normal layer is occluded by walls,
		// the see-through layer shows dimly through them. The extra pass is
		// submitted only when the option is on, so the default path stays
		// untouched.
		collector.submitCustomGeometry(poseStack, RenderTypes.text(sprite.texture()), renderer);
		if (HeartsPlusConfig.isShowBehindBlocks()) {
			collector.submitCustomGeometry(poseStack, RenderTypes.textSeeThrough(sprite.texture()), renderer);
		}
	}

	/**
	 * A bundled texture registered with NEAREST magnification and
	 * minification (no mipmaps): the 9x9 pixel art must stay crisp, while the
	 * metadata-less default can resolve to a linear sampler.
	 */
	private static final class NearestPixelTexture extends SimpleTexture {
		private NearestPixelTexture(Identifier location) {
			super(location);
		}

		@Override
		public void apply(TextureContents contents) {
			super.apply(contents);
			this.sampler = RenderSystem.getSamplerCache().getSampler(
					AddressMode.REPEAT, AddressMode.REPEAT, FilterMode.NEAREST, FilterMode.NEAREST, false);
		}
	}

	private record ResolvedSprite(Identifier texture, float u0, float v0, float u1, float v1) {
	}

	private static void emitHeart(PoseStack.Pose pose, VertexConsumer vertices, float x, float yTop,
			ResolvedSprite sprite, int light, float z) {
		float endX = x + HeartBarLayout.HEART_SIZE;
		float endY = yTop + HeartBarLayout.HEART_SIZE;
		// The world-text vertex format is POSITION_TEX_LIGHTMAP_COLOR; every
		// element must be set or the BufferBuilder validation rejects the vertex.
		// The pipeline culls back faces, so the winding must match vanilla text
		// quads (BakedSheetGlyph): top-left, bottom-left, bottom-right, top-right.
		vertices.addVertex(pose, x, yTop, z).setUv(sprite.u0(), sprite.v0())
				.setLight(light).setColor(255, 255, 255, 255);
		vertices.addVertex(pose, x, endY, z).setUv(sprite.u0(), sprite.v1())
				.setLight(light).setColor(255, 255, 255, 255);
		vertices.addVertex(pose, endX, endY, z).setUv(sprite.u1(), sprite.v1())
				.setLight(light).setColor(255, 255, 255, 255);
		vertices.addVertex(pose, endX, yTop, z).setUv(sprite.u1(), sprite.v0())
				.setLight(light).setColor(255, 255, 255, 255);
	}
}
