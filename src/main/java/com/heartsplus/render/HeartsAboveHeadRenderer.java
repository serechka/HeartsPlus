package com.heartsplus.render;

import com.heartsplus.HeartsPlusConfig;
import com.heartsplus.HeartsPlusLog;
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
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

/**
 * Draws a row of vanilla heart sprites above an avatar's head.
 * Called at the end of LivingEntityRenderer.submit, so the incoming
 * PoseStack is positioned at the entity origin and the geometry is
 * recorded through the frame's SubmitNodeCollector.
 *
 * <p>Hearts are drawn with the world-text render type, which — like name
 * tags — is shaded only by the lightmap, so they look identical from every
 * viewing angle. Hearts are occluded by walls like normal geometry;
 * a dim see-through variant is planned but needs a custom pipeline.
 * Sneaking players get no hearts at all, matching how vanilla hides their
 * name tag. Recent health drops blink exactly like the vanilla HUD does.
 * Geometry is submitted in passes per texture
 * (containers, health, blinking, absorption) because vanilla-texture mode
 * binds individual files instead of the shared GUI atlas.</p>
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

	/** One nameplate text row in world units; must track vanilla EntityRenderer.submitNameDisplay. */
	private static final float NAMETAG_ROW_HEIGHT = 9.0F * 1.15F * 0.025F;
	/** Small breathing room between the nameplate and the bottom heart row, in world units. */
	private static final float NAMEPLATE_GAP = 2.0F * 0.025F;
	private static final float PIXELS_PER_BLOCK = 0.025F;

	private HeartsAboveHeadRenderer() {
	}

	/**
	 * Registers and uploads every bundled heart texture before the first
	 * frame needs them. Lazy registration from inside render submission
	 * produces textures that are never actually uploaded, which makes the
	 * hearts invisible, so warm-up is done from the first client tick.
	 */
	public static void warmUpVanillaTextures(TextureManager textureManager) {
		if (vanillaTexturesWarmed) {
			return;
		}
		vanillaTexturesWarmed = true;
		for (HeartType type : HeartType.values()) {
			for (Identifier texture : type.fileTextures()) {
				try {
					textureManager.getTexture(texture);
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
			SubmitNodeCollector collector, CameraRenderState camera) {
		try {
			renderHearts(state, health, poseStack, collector, camera);
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
			SubmitNodeCollector collector, CameraRenderState camera) {
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
		if (!HeartsPlusConfig.isShowSneakingPlayers() && state.isCrouching) {
			skipOnce("player is sneaking");
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
		HeartType family = HeartType.forStatus(health.heartsplus$isPoisoned(), health.heartsplus$isWithered(), state.isFullyFrozen);
		ResolvedSprite container = resolve(HeartType.CONTAINER, false, false, atlasManager);

		poseStack.pushPose();
		poseStack.translate(0.0F, heartsY(state), 0.0F);
		// rotateAround(..., 0, 0, 0) equals a plain rotation; it is the only
		// quaternion-rotation call shared by every supported game version.
		poseStack.rotateAround(camera.orientation, 0.0F, 0.0F, 0.0F);
		float pixelScale = PIXELS_PER_BLOCK * (float) HeartsPlusConfig.getScale();
		poseStack.scale(pixelScale, -pixelScale, pixelScale);
		poseStack.translate(0.0F, -HeartsPlusConfig.getHeartOffset(), 0.0F);

		int light = state.lightCoords;
		int blinkFrom = layout.heartsRed();
		int blinkTo = layout.blinkUpperBound((int) Math.floor(state.ageInTicks), health.heartsplus$getBlinkEndTick(),
				health.heartsplus$getBlinkOldHealth(), health.heartsplus$getHealth());

		// Sprites are resolved lazily per pass so empty passes (no blinking, no
		// absorption) cost nothing at all.
		submitPass(collector, poseStack, container, (pose, vertices) -> {
			for (int heart = 0; heart < layout.heartsTotal(); heart++) {
				emitHeart(pose, vertices, layout.x(heart), layout.yTop(heart), container, light);
			}
		});
		if (layout.heartsRed() > 0) {
			ResolvedSprite familyFull = resolve(family, false, false, atlasManager);
			submitPass(collector, poseStack, familyFull, (pose, vertices) -> {
				for (int heart = 0; heart < layout.heartsRed(); heart++) {
					if (!layout.isRedHalf(heart)) {
						emitHeart(pose, vertices, layout.x(heart), layout.yTop(heart), familyFull, light);
					}
				}
			});
			if (layout.hasRedHalf()) {
				ResolvedSprite familyHalf = resolve(family, true, false, atlasManager);
				submitPass(collector, poseStack, familyHalf, (pose, vertices) ->
						emitHeart(pose, vertices, layout.x(layout.heartsRed() - 1), layout.yTop(layout.heartsRed() - 1), familyHalf, light));
			}
		}
		if (blinkTo > blinkFrom) {
			ResolvedSprite familyFullBlinking = resolve(family, false, true, atlasManager);
			submitPass(collector, poseStack, familyFullBlinking, (pose, vertices) -> {
				for (int heart = blinkFrom; heart < blinkTo; heart++) {
					if (heart != blinkTo - 1 || !layout.lastBlinkHalf()) {
						emitHeart(pose, vertices, layout.x(heart), layout.yTop(heart), familyFullBlinking, light);
					}
				}
			});
			if (layout.lastBlinkHalf()) {
				ResolvedSprite familyHalfBlinking = resolve(family, true, true, atlasManager);
				submitPass(collector, poseStack, familyHalfBlinking, (pose, vertices) ->
						emitHeart(pose, vertices, layout.x(blinkTo - 1), layout.yTop(blinkTo - 1), familyHalfBlinking, light));
			}
		}
		if (layout.heartsTotal() > layout.heartsNormal()) {
			ResolvedSprite absorbingFull = resolve(HeartType.ABSORBING, false, false, atlasManager);
			submitPass(collector, poseStack, absorbingFull, (pose, vertices) -> {
				for (int heart = layout.heartsNormal(); heart < layout.heartsTotal(); heart++) {
					if (!layout.isYellowHalf(heart)) {
						emitHeart(pose, vertices, layout.x(heart), layout.yTop(heart), absorbingFull, light);
					}
				}
			});
			if (layout.hasYellowHalf()) {
				ResolvedSprite absorbingHalf = resolve(HeartType.ABSORBING, true, false, atlasManager);
				submitPass(collector, poseStack, absorbingHalf, (pose, vertices) ->
						emitHeart(pose, vertices, layout.x(layout.heartsTotal() - 1), layout.yTop(layout.heartsTotal() - 1), absorbingHalf, light));
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
			LOGGER.info("Hearts above a player were skipped: {} [enabled={}, showOwnHearts={}, showInvisible={}, showSneaking={}, vanillaTextures={}, scale={}, renderDistance={}]",
					reason, HeartsPlusConfig.isEnabled(), HeartsPlusConfig.isShowOwnHearts(),
					HeartsPlusConfig.isShowInvisiblePlayers(), HeartsPlusConfig.isShowSneakingPlayers(),
					HeartsPlusConfig.isVanillaTextures(), HeartsPlusConfig.getScale(), HeartsPlusConfig.getRenderDistance());
		}
	}

	private static ResolvedSprite resolve(HeartType type, boolean half, boolean blinking, AtlasManager atlasManager) {
		if (HeartsPlusConfig.isVanillaTextures()) {
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
			TextureAtlasSprite sprite = atlasManager.get(new SpriteId(Sheets.GUI_SHEET, spriteId));
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

	private static void submitPass(SubmitNodeCollector collector, PoseStack poseStack, ResolvedSprite sprite,
			SubmitNodeCollector.CustomGeometryRenderer renderer) {
		// NOTE: the see-through text pipeline is GUI-oriented in 26.x and
		// breaks the whole custom-geometry phase when submitted here, so the
		// wall-transparent variant is intentionally not drawn for now.
		collector.submitCustomGeometry(poseStack, RenderTypes.text(sprite.texture()), renderer);
	}

	/**
	 * Height above the entity origin, sitting on top of the nameplate
	 * when one is displayed, mirroring vanilla name tag placement.
	 * The +0.5F pad matches the nameplate attachment lift vanilla applies.
	 */
	private static float heartsY(AvatarRenderState state) {
		if (state.nameTag != null && state.nameTagAttachment != null) {
			float y = (float) state.nameTagAttachment.y + 0.5F + NAMETAG_ROW_HEIGHT + NAMEPLATE_GAP;
			if (state.scoreText != null) {
				y += NAMETAG_ROW_HEIGHT;
			}
			return y;
		}
		return state.boundingBoxHeight + 0.5F + NAMEPLATE_GAP;
	}

	private record ResolvedSprite(Identifier texture, float u0, float v0, float u1, float v1) {
	}

	private static void emitHeart(PoseStack.Pose pose, VertexConsumer vertices, float x, float yTop,
			ResolvedSprite sprite, int light) {
		float endX = x + HeartBarLayout.HEART_SIZE;
		float endY = yTop + HeartBarLayout.HEART_SIZE;
		// The world-text vertex format is POSITION_TEX_LIGHTMAP_COLOR; every
		// element must be set or the BufferBuilder validation rejects the vertex.
		// The pipeline culls back faces, so the winding must match vanilla text
		// quads (BakedSheetGlyph): top-left, bottom-left, bottom-right, top-right.
		vertices.addVertex(pose, x, yTop, 0.0F).setUv(sprite.u0(), sprite.v0())
				.setLight(light).setColor(255, 255, 255, 255);
		vertices.addVertex(pose, x, endY, 0.0F).setUv(sprite.u0(), sprite.v1())
				.setLight(light).setColor(255, 255, 255, 255);
		vertices.addVertex(pose, endX, endY, 0.0F).setUv(sprite.u1(), sprite.v1())
				.setLight(light).setColor(255, 255, 255, 255);
		vertices.addVertex(pose, endX, yTop, 0.0F).setUv(sprite.u1(), sprite.v0())
				.setLight(light).setColor(255, 255, 255, 255);
	}
}
