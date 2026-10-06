package com.heartsplus.render;

import com.heartsplus.HeartsPlus;
import com.heartsplus.HeartsPlusConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.HashSet;
import java.util.Set;
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
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
	private static final Logger LOGGER = LoggerFactory.getLogger(HeartsPlus.class);
	/** Bundled-file textures that failed to load; those hearts fall back to atlas sprites. */
	private static final Set<Identifier> unavailableVanillaTextures = new HashSet<>();
	/** Each skip reason is logged once so missing hearts can be diagnosed from the log. */
	private static final Set<String> reportedSkips = new HashSet<>();
	private static boolean vanillaTexturesWarmed;
	private static boolean reportedFirstHeart;
	private static boolean reportedFailure;

	/** One nameplate text row in world units; must track vanilla EntityRenderer.submitNameDisplay. */
	private static final float NAMETAG_ROW_HEIGHT = 9.0F * 1.15F * 0.025F;
	/** Small breathing room between the nameplate and the bottom heart row, in world units. */
	private static final float NAMEPLATE_GAP = 2.0F * 0.025F;
	private static final float PIXELS_PER_BLOCK = 0.025F;
	private static final float HEART_SIZE = 9.0F;
	private static final float HEART_SPACING = 8.0F;
	private static final int HEARTS_PER_ROW = 10;
	private static final int ROW_SPACING_BASE = 10;
	private static final int MIN_ROW_SPACING = 3;
	private static final int BLINK_INTERVAL_TICKS = 3;

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

	public static void render(AvatarRenderState state, HealthHolder health, PoseStack poseStack,
			SubmitNodeCollector collector, CameraRenderState camera) {
		try {
			renderHearts(state, health, poseStack, collector, camera);
		} catch (Throwable t) {
			if (!reportedFailure) {
				reportedFailure = true;
				LOGGER.error("HeartsPlus failed to render hearts; further errors are suppressed", t);
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

		Minecraft minecraft = Minecraft.getInstance();
		AtlasManager atlasManager = minecraft.getAtlasManager();
		HeartType family = HeartType.forStatus(health.heartsplus$isPoisoned(), health.heartsplus$isWithered(), state.isFullyFrozen);
		ResolvedSprite container = resolve(HeartType.CONTAINER, false, false, atlasManager);
		ResolvedSprite familyFull = resolve(family, false, false, atlasManager);
		ResolvedSprite familyHalf = resolve(family, true, false, atlasManager);
		ResolvedSprite familyFullBlinking = resolve(family, false, true, atlasManager);
		ResolvedSprite familyHalfBlinking = resolve(family, true, true, atlasManager);
		ResolvedSprite absorbingFull = resolve(HeartType.ABSORBING, false, false, atlasManager);
		ResolvedSprite absorbingHalf = resolve(HeartType.ABSORBING, true, false, atlasManager);

		poseStack.pushPose();
		poseStack.translate(0.0F, heartsY(state), 0.0F);
		// rotateAround(..., 0, 0, 0) equals a plain rotation; it is the only
		// quaternion-rotation call shared by every supported game version.
		poseStack.rotateAround(camera.orientation, 0.0F, 0.0F, 0.0F);
		float pixelScale = PIXELS_PER_BLOCK * (float) HeartsPlusConfig.getScale();
		poseStack.scale(pixelScale, -pixelScale, pixelScale);
		poseStack.translate(0.0F, -HeartsPlusConfig.getHeartOffset(), 0.0F);

		Layout layout = layoutOf(health);
		int light = state.lightCoords;
		int blinkFrom = layout.heartsRed();
		int blinkTo = blinkTo(health, layout, (int) Math.floor(state.ageInTicks));

		submitPass(collector, poseStack, container, (pose, vertices) -> {
			for (int heart = 0; heart < layout.heartsTotal(); heart++) {
				emitHeart(pose, vertices, layout.x(heart), layout.yTop(heart), container, light);
			}
		});
		submitPass(collector, poseStack, familyFull, (pose, vertices) -> {
			for (int heart = 0; heart < layout.heartsRed(); heart++) {
				if (!layout.isRedHalf(heart)) {
					emitHeart(pose, vertices, layout.x(heart), layout.yTop(heart), familyFull, light);
				}
			}
		});
		if (layout.hasRedHalf()) {
			submitPass(collector, poseStack, familyHalf, (pose, vertices) ->
					emitHeart(pose, vertices, layout.x(layout.heartsRed() - 1), layout.yTop(layout.heartsRed() - 1), familyHalf, light));
		}
		if (blinkTo > blinkFrom) {
			submitPass(collector, poseStack, familyFullBlinking, (pose, vertices) -> {
				for (int heart = blinkFrom; heart < blinkTo; heart++) {
					if (heart != blinkTo - 1 || !layout.lastBlinkHalf()) {
						emitHeart(pose, vertices, layout.x(heart), layout.yTop(heart), familyFullBlinking, light);
					}
				}
			});
			if (layout.lastBlinkHalf()) {
				submitPass(collector, poseStack, familyHalfBlinking, (pose, vertices) ->
						emitHeart(pose, vertices, layout.x(blinkTo - 1), layout.yTop(blinkTo - 1), familyHalfBlinking, light));
			}
		}
		submitPass(collector, poseStack, absorbingFull, (pose, vertices) -> {
			for (int heart = layout.heartsNormal(); heart < layout.heartsTotal(); heart++) {
				if (!layout.isYellowHalf(heart)) {
					emitHeart(pose, vertices, layout.x(heart), layout.yTop(heart), absorbingFull, light);
				}
			}
		});
		if (layout.hasYellowHalf()) {
			submitPass(collector, poseStack, absorbingHalf, (pose, vertices) ->
					emitHeart(pose, vertices, layout.x(layout.heartsTotal() - 1), layout.yTop(layout.heartsTotal() - 1), absorbingHalf, light));
		}

		poseStack.popPose();
		if (!reportedFirstHeart) {
			reportedFirstHeart = true;
			LOGGER.info("Hearts submitted above a player for the first time ({} hearts, texture {})",
					layout.heartsTotal(), container.texture());
			var player = Minecraft.getInstance().player;
			if (player != null) {
				player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("heartsplus.message.first_render"));
			}
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
			Identifier texture = blinking
					? half ? type.fileHalfBlinking : type.fileFullBlinking
					: half ? type.fileHalf : type.fileFull;
			if (!unavailableVanillaTextures.contains(texture)) {
				return new ResolvedSprite(texture, 0.0F, 0.0F, 1.0F, 1.0F);
			}
		}
		Identifier spriteId = blinking
				? half ? type.atlasHalfBlinking : type.atlasFullBlinking
				: half ? type.atlasHalf : type.atlasFull;
		TextureAtlasSprite sprite = atlasManager.get(new SpriteId(Sheets.GUI_SHEET, spriteId));
		return new ResolvedSprite(sprite.atlasLocation(), sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1());
	}

	private static void submitPass(SubmitNodeCollector collector, PoseStack poseStack, ResolvedSprite sprite,
			SubmitNodeCollector.CustomGeometryRenderer renderer) {
		// NOTE: the see-through text pipeline is GUI-oriented in 26.x and
		// breaks the whole custom-geometry phase when submitted here, so the
		// wall-transparent variant is intentionally not drawn for now.
		collector.submitCustomGeometry(poseStack, RenderTypes.text(sprite.texture()), renderer);
	}

	/**
	 * Hearts between the current and pre-drop health blink for a short window
	 * after damage, alternating on a fixed cadence like the vanilla HUD.
	 * Returns the exclusive upper heart index, or the current index when not
	 * in the blink window / on the "off" beat of the cadence.
	 */
	private static int blinkTo(HealthHolder health, Layout layout, int nowTick) {
		if (nowTick >= health.heartsplus$getBlinkEndTick()
				|| health.heartsplus$getBlinkOldHealth() <= health.heartsplus$getHealth()
				|| nowTick / BLINK_INTERVAL_TICKS % 2 != 0) {
			return layout.heartsRed();
		}
		return Math.min(layout.heartsBlink(), layout.heartsNormal());
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

	private static Layout layoutOf(HealthHolder health) {
		int healthRed = Mth.ceil(health.heartsplus$getHealth());
		int maxHealth = Mth.ceil(health.heartsplus$getMaxHealth());
		int healthYellow = Mth.ceil(health.heartsplus$getAbsorption());

		int heartsRed = Mth.ceil(healthRed / 2.0F);
		boolean lastRedHalf = (healthRed & 1) == 1;
		int heartsNormal = Mth.ceil(maxHealth / 2.0F);
		int heartsYellow = Mth.ceil(healthYellow / 2.0F);
		boolean lastYellowHalf = (healthYellow & 1) == 1;
		int heartsTotal = heartsNormal + heartsYellow;

		int blinkHalves = Mth.ceil(health.heartsplus$getBlinkOldHealth());
		int heartsBlink = Mth.ceil(blinkHalves / 2.0F);
		boolean lastBlinkHalf = (blinkHalves & 1) == 1;

		int heartsPerRow = HEARTS_PER_ROW;
		int rowsTotal = (heartsTotal + heartsPerRow - 1) / heartsPerRow;
		// Vanilla-like row compression: rows slide closer together as the bar
		// grows taller, down to a minimum overlap step.
		int rowOffset = Math.max(ROW_SPACING_BASE - (rowsTotal - 2), MIN_ROW_SPACING);
		float rowWidth = Math.min(heartsTotal, heartsPerRow) * HEART_SPACING + 1.0F;
		float startX = -rowWidth / 2.0F;

		return new Layout(heartsRed, heartsNormal, heartsTotal, lastRedHalf, lastYellowHalf, lastBlinkHalf,
				heartsPerRow, rowOffset, startX, heartsBlink);
	}

	/**
	 * Precomputed heart-bar geometry. Row 0 is the bottom row (closest to
	 * the nameplate); extra rows stack upward like the vanilla HUD.
	 */
	private record Layout(int heartsRed, int heartsNormal, int heartsTotal, boolean lastRedHalf, boolean lastYellowHalf,
			boolean lastBlinkHalf, int heartsPerRow, int rowOffset, float startX, int heartsBlink) {

		float x(int heart) {
			return this.startX + heart % this.heartsPerRow * HEART_SPACING;
		}

		/** Top edge of a heart's quad in GUI pixels; rows grow upwards. */
		float yTop(int heart) {
			return -(heart / this.heartsPerRow * this.rowOffset) - HEART_SIZE;
		}

		boolean isRedHalf(int heart) {
			return heart == this.heartsRed - 1 && this.lastRedHalf;
		}

		boolean hasRedHalf() {
			return this.heartsRed > 0 && this.lastRedHalf;
		}

		boolean isYellowHalf(int heart) {
			return heart == this.heartsTotal - 1 && this.lastYellowHalf && this.heartsTotal > this.heartsNormal;
		}

		boolean hasYellowHalf() {
			return this.heartsTotal > this.heartsNormal && this.lastYellowHalf;
		}
	}

	private record ResolvedSprite(Identifier texture, float u0, float v0, float u1, float v1) {
	}

	private static void emitHeart(PoseStack.Pose pose, VertexConsumer vertices, float x, float yTop,
			ResolvedSprite sprite, int light) {
		float endX = x + HEART_SIZE;
		float endY = yTop + HEART_SIZE;
		// The world-text vertex format is POSITION_TEX_LIGHTMAP_COLOR; every
		// element must be set or the BufferBuilder validation rejects the vertex.
		vertices.addVertex(pose, x, yTop, 0.0F).setUv(sprite.u0(), sprite.v0())
				.setLight(light).setColor(255, 255, 255, 255);
		vertices.addVertex(pose, endX, yTop, 0.0F).setUv(sprite.u1(), sprite.v0())
				.setLight(light).setColor(255, 255, 255, 255);
		vertices.addVertex(pose, endX, endY, 0.0F).setUv(sprite.u1(), sprite.v1())
				.setLight(light).setColor(255, 255, 255, 255);
		vertices.addVertex(pose, x, endY, 0.0F).setUv(sprite.u0(), sprite.v1())
				.setLight(light).setColor(255, 255, 255, 255);
	}
}
