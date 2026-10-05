package com.heartsplus.render;

import com.heartsplus.HeartsPlusConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.SpriteIdentifier;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;

/**
 * Draws a row of vanilla heart sprites above a player's head (1.21.9+ line).
 * Called at the end of LivingEntityRenderer.render, so the incoming MatrixStack
 * is positioned at the entity origin and the geometry is recorded through the
 * frame's OrderedRenderCommandQueue.
 *
 * <p>Hearts are drawn with the world-text render layer, which — like name
 * tags — is shaded only by the lightmap, so they look identical from every
 * viewing angle. Geometry is submitted in separate passes per texture
 * (containers, health, absorption) because vanilla-texture mode binds
 * individual files instead of the shared GUI atlas.</p>
 */
public final class HeartsAboveHeadRenderer {
	/** One nameplate text row in world units; must track vanilla label rendering. */
	private static final float NAMETAG_ROW_HEIGHT = 9.0F * 1.15F * 0.025F;
	private static final float PIXELS_PER_BLOCK = 0.025F;
	private static final float HEART_SIZE = 9.0F;
	private static final float HEART_SPACING = 8.0F;
	private static final int HEARTS_PER_ROW = 10;
	private static final int ROW_SPACING_BASE = 10;
	private static final int MIN_ROW_SPACING = 3;
	private static final Identifier GUI_ATLAS = Identifier.ofVanilla("textures/atlas/gui.png");

	private HeartsAboveHeadRenderer() {
	}

	public static void render(PlayerEntityRenderState state, HealthHolder health, MatrixStack matrices,
			OrderedRenderCommandQueue queue, CameraRenderState cameraState) {
		if (!HeartsPlusConfig.isEnabled()) {
			return;
		}
		if (health.heartsplus$isLocalPlayer() && !HeartsPlusConfig.isShowOwnHearts()) {
			return;
		}
		double maxDistance = HeartsPlusConfig.getRenderDistance();
		if (state.squaredDistanceToCamera > maxDistance * maxDistance) {
			return;
		}
		if (HeartsPlusConfig.isHideWhenInvisible() && state.invisibleToPlayer) {
			return;
		}
		if (HeartsPlusConfig.isHideWhenSneaking() && state.sneaking) {
			return;
		}

		HeartType family = HeartType.forStatus(health.heartsplus$isPoisoned(), health.heartsplus$isWithered(),
				health.heartsplus$isFrozen());
		ResolvedSprite container = resolve(HeartType.CONTAINER, false);
		ResolvedSprite familyFull = resolve(family, false);
		ResolvedSprite familyHalf = resolve(family, true);
		ResolvedSprite absorbingFull = resolve(HeartType.ABSORBING, false);
		ResolvedSprite absorbingHalf = resolve(HeartType.ABSORBING, true);

		matrices.push();
		matrices.translate(0.0F, heartsY(state), 0.0F);
		matrices.multiply(new Quaternionf(cameraState.orientation));
		float pixelScale = PIXELS_PER_BLOCK * (float) HeartsPlusConfig.getScale();
		matrices.scale(pixelScale, -pixelScale, pixelScale);
		matrices.translate(0.0F, -HeartsPlusConfig.getHeartOffset(), 0.0F);

		int light = state.light;
		Layout layout = layoutOf(health);

		submitPass(queue, matrices, container, (entry, vertices) -> {
			for (int heart = 0; heart < layout.heartsTotal(); heart++) {
				emitHeart(vertices, entry.getPositionMatrix(), layout.x(heart), layout.yTop(heart), container, light);
			}
		});
		submitPass(queue, matrices, familyFull, (entry, vertices) -> {
			for (int heart = 0; heart < layout.heartsRed(); heart++) {
				if (!layout.isRedHalf(heart)) {
					emitHeart(vertices, entry.getPositionMatrix(), layout.x(heart), layout.yTop(heart), familyFull, light);
				}
			}
		});
		if (layout.hasRedHalf()) {
			submitPass(queue, matrices, familyHalf, (entry, vertices) ->
					emitHeart(vertices, entry.getPositionMatrix(), layout.x(layout.heartsRed() - 1), layout.yTop(layout.heartsRed() - 1), familyHalf, light));
		}
		if (HeartsPlusConfig.isShowAbsorption() && layout.heartsTotal() > layout.heartsNormal()) {
			submitPass(queue, matrices, absorbingFull, (entry, vertices) -> {
				for (int heart = layout.heartsNormal(); heart < layout.heartsTotal(); heart++) {
					if (!layout.isYellowHalf(heart)) {
						emitHeart(vertices, entry.getPositionMatrix(), layout.x(heart), layout.yTop(heart), absorbingFull, light);
					}
				}
			});
			if (layout.hasYellowHalf()) {
				submitPass(queue, matrices, absorbingHalf, (entry, vertices) ->
						emitHeart(vertices, entry.getPositionMatrix(), layout.x(layout.heartsTotal() - 1), layout.yTop(layout.heartsTotal() - 1),
								absorbingHalf, light));
			}
		}

		matrices.pop();
	}

	private static ResolvedSprite resolve(HeartType type, boolean half) {
		if (HeartsPlusConfig.isUseVanillaTextures()) {
			// Standalone texture files are drawn whole, so UV covers 0..1.
			return new ResolvedSprite(half ? type.fileHalf : type.fileFull, 0.0F, 0.0F, 1.0F, 1.0F);
		}
		Sprite sprite = MinecraftClient.getInstance().getAtlasManager()
				.getSprite(new SpriteIdentifier(GUI_ATLAS, half ? type.atlasHalf : type.atlasFull));
		return new ResolvedSprite(sprite.getAtlasId(), sprite.getMinU(), sprite.getMinV(),
				sprite.getMaxU(), sprite.getMaxV());
	}

	private static void submitPass(OrderedRenderCommandQueue queue, MatrixStack matrices, ResolvedSprite sprite,
			OrderedRenderCommandQueue.Custom renderer) {
		RenderLayer renderLayer = RenderLayers.text(sprite.texture());
		queue.submitCustom(matrices, renderLayer, renderer);
	}

	/**
	 * Height above the entity origin, sitting on top of the nameplate
	 * when one is displayed, mirroring vanilla name tag placement.
	 */
	private static float heartsY(PlayerEntityRenderState state) {
		if (state.displayName != null && state.nameLabelPos != null) {
			return (float) state.nameLabelPos.y + 0.5F + NAMETAG_ROW_HEIGHT;
		}
		return state.height + 0.5F;
	}

	private static Layout layoutOf(HealthHolder health) {
		int healthRed = MathHelper.ceil(health.heartsplus$getHealth());
		int maxHealth = MathHelper.ceil(health.heartsplus$getMaxHealth());
		int healthYellow = MathHelper.ceil(health.heartsplus$getAbsorption());

		int heartsRed = MathHelper.ceil(healthRed / 2.0F);
		boolean lastRedHalf = (healthRed & 1) == 1;
		int heartsNormal = MathHelper.ceil(maxHealth / 2.0F);
		int heartsYellow = HeartsPlusConfig.isShowAbsorption() ? MathHelper.ceil(healthYellow / 2.0F) : 0;
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

	private static void emitHeart(VertexConsumer vertices, Matrix4fc matrix, float x, float yTop,
			ResolvedSprite sprite, int light) {
		float endX = x + HEART_SIZE;
		float endY = yTop + HEART_SIZE;
		vertices.vertex(matrix, x, yTop, 0.0F).color(255, 255, 255, 255).texture(sprite.u0(), sprite.v0()).light(light);
		vertices.vertex(matrix, endX, yTop, 0.0F).color(255, 255, 255, 255).texture(sprite.u1(), sprite.v0()).light(light);
		vertices.vertex(matrix, endX, endY, 0.0F).color(255, 255, 255, 255).texture(sprite.u1(), sprite.v1()).light(light);
		vertices.vertex(matrix, x, endY, 0.0F).color(255, 255, 255, 255).texture(sprite.u0(), sprite.v1()).light(light);
	}
}
