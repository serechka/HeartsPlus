package com.heartsplus.render;

import com.heartsplus.HeartsPlusConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * Draws a row of vanilla heart sprites above an avatar's head.
 * Called at the end of LivingEntityRenderer.submit, so the incoming
 * PoseStack is positioned at the entity origin and the geometry is
 * recorded through the frame's SubmitNodeCollector.
 *
 * <p>Hearts are drawn with the world-text render type, which — like name
 * tags — is shaded only by the lightmap, so they look identical from every
 * viewing angle. Geometry is submitted in three texture passes (containers,
 * health, absorption) because vanilla-texture mode binds individual files
 * instead of the shared GUI atlas.</p>
 */
public final class HeartsAboveHeadRenderer {
	/** One nameplate text row in world units; must track vanilla EntityRenderer.submitNameDisplay. */
	private static final float NAMETAG_ROW_HEIGHT = 9.0F * 1.15F * 0.025F;
	private static final float PIXELS_PER_BLOCK = 0.025F;
	private static final float HEART_SIZE = 9.0F;
	private static final float HEART_SPACING = 8.0F;
	private static final int HEARTS_PER_ROW = 10;
	private static final int ROW_SPACING_BASE = 10;
	private static final int MIN_ROW_SPACING = 3;

	private HeartsAboveHeadRenderer() {
	}

	public static void render(AvatarRenderState state, HealthHolder health, PoseStack poseStack,
			SubmitNodeCollector collector, CameraRenderState camera) {
		if (!HeartsPlusConfig.isEnabled() || state.isSpectator) {
			return;
		}
		if (health.heartsplus$isLocalPlayer() && !HeartsPlusConfig.isShowOwnHearts()) {
			return;
		}
		double maxDistance = HeartsPlusConfig.getRenderDistance();
		if (state.distanceToCameraSq > maxDistance * maxDistance) {
			return;
		}
		if (HeartsPlusConfig.isHideWhenInvisible() && state.isInvisibleToPlayer) {
			return;
		}
		if (HeartsPlusConfig.isHideWhenSneaking() && state.isCrouching) {
			return;
		}

		AtlasManager atlasManager = Minecraft.getInstance().getAtlasManager();
		HeartType family = HeartType.forStatus(health.heartsplus$isPoisoned(), health.heartsplus$isWithered(), state.isFullyFrozen);
		ResolvedSprite container = resolve(HeartType.CONTAINER, false, atlasManager);
		ResolvedSprite familyFull = resolve(family, false, atlasManager);
		ResolvedSprite familyHalf = resolve(family, true, atlasManager);
		ResolvedSprite absorbingFull = resolve(HeartType.ABSORBING, false, atlasManager);
		ResolvedSprite absorbingHalf = resolve(HeartType.ABSORBING, true, atlasManager);

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
		if (HeartsPlusConfig.isShowAbsorption()) {
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
		}

		poseStack.popPose();
	}

	private static ResolvedSprite resolve(HeartType type, boolean half, AtlasManager atlasManager) {
		if (HeartsPlusConfig.isUseVanillaTextures()) {
			Identifier texture = half ? type.fileHalf : type.fileFull;
			return new ResolvedSprite(texture, 0.0F, 0.0F, 1.0F, 1.0F);
		}
		Identifier spriteId = half ? type.atlasHalf : type.atlasFull;
		TextureAtlasSprite sprite = atlasManager.get(new SpriteId(Sheets.GUI_SHEET, spriteId));
		return new ResolvedSprite(sprite.atlasLocation(), sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1());
	}

	private static void submitPass(SubmitNodeCollector collector, PoseStack poseStack, ResolvedSprite sprite,
			SubmitNodeCollector.CustomGeometryRenderer renderer) {
		RenderType renderType = RenderTypes.text(sprite.texture());
		collector.submitCustomGeometry(poseStack, renderType, renderer);
	}

	/**
	 * Height above the entity origin, sitting on top of the nameplate
	 * when one is displayed, mirroring vanilla name tag placement.
	 * The +0.5F pad matches the nameplate attachment lift vanilla applies.
	 */
	private static float heartsY(AvatarRenderState state) {
		if (state.nameTag != null && state.nameTagAttachment != null) {
			float y = (float) state.nameTagAttachment.y + 0.5F + NAMETAG_ROW_HEIGHT;
			if (state.scoreText != null) {
				y += NAMETAG_ROW_HEIGHT;
			}
			return y;
		}
		return state.boundingBoxHeight + 0.5F;
	}

	private static Layout layoutOf(HealthHolder health) {
		int healthRed = Mth.ceil(health.heartsplus$getHealth());
		int maxHealth = Mth.ceil(health.heartsplus$getMaxHealth());
		int healthYellow = Mth.ceil(health.heartsplus$getAbsorption());

		int heartsRed = Mth.ceil(healthRed / 2.0F);
		boolean lastRedHalf = (healthRed & 1) == 1;
		int heartsNormal = Mth.ceil(maxHealth / 2.0F);
		int heartsYellow = HeartsPlusConfig.isShowAbsorption() ? Mth.ceil(healthYellow / 2.0F) : 0;
		boolean lastYellowHalf = (healthYellow & 1) == 1;
		int heartsTotal = heartsNormal + heartsYellow;

		int heartsPerRow = HeartsPlusConfig.isStackHearts() ? HEARTS_PER_ROW : Math.max(heartsTotal, 1);
		int rowsTotal = (heartsTotal + heartsPerRow - 1) / heartsPerRow;
		// Vanilla-like row compression: rows slide closer together as the bar
		// grows taller, down to a minimum overlap step.
		int rowOffset = Math.max(ROW_SPACING_BASE - (rowsTotal - 2), MIN_ROW_SPACING);
		float rowWidth = Math.min(heartsTotal, heartsPerRow) * HEART_SPACING + 1.0F;
		float startX = -rowWidth / 2.0F;

		return new Layout(heartsRed, heartsNormal, heartsTotal, lastRedHalf, lastYellowHalf,
				heartsPerRow, rowOffset, startX);
	}

	/**
	 * Precomputed heart-bar geometry. Row 0 is the bottom row (closest to
	 * the nameplate); extra rows stack upward like the vanilla HUD.
	 */
	private record Layout(int heartsRed, int heartsNormal, int heartsTotal, boolean lastRedHalf, boolean lastYellowHalf,
			int heartsPerRow, int rowOffset, float startX) {

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
