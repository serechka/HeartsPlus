package com.heartsplus.render;

import com.heartsplus.HeartsPlus;
import com.heartsplus.HeartsPlusConfig;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.client.util.SpriteIdentifier;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.joml.Quaternionf;

/**
 * Draws a row of vanilla heart sprites above a player's head (1.21.x line).
 * Called at the end of LivingEntityRenderer.render, so the incoming MatrixStack
 * is positioned at the entity origin and the geometry is recorded through the
 * frame's OrderedRenderCommandQueue.
 *
 * <p>Hearts are drawn with the world-text render layers, which — like name
 * tags — are shaded only by the lightmap. Like a nameplate, each pass is
 * submitted twice: a bright normal variant that is occluded by walls, and a
 * dim see-through variant that shows through walls. Sneaking players get no
 * hearts at all, matching how vanilla hides their name tag. Recent health
 * drops blink exactly like the vanilla HUD does. Geometry is submitted in
 * passes per texture (containers, health, blinking, absorption) because
 * vanilla-texture mode binds individual files instead of the shared GUI
 * atlas.</p>
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

	/** One nameplate text row in world units; must track vanilla label rendering. */
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
	private static final Identifier GUI_ATLAS = Identifier.ofVanilla("textures/atlas/gui.png");

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

	public static void render(PlayerEntityRenderState state, HealthHolder health, MatrixStack matrices,
			OrderedRenderCommandQueue queue, CameraRenderState cameraState) {
		try {
			renderHearts(state, health, matrices, queue, cameraState);
		} catch (Throwable t) {
			if (!reportedFailure) {
				reportedFailure = true;
				LOGGER.error("HeartsPlus failed to render hearts; further errors are suppressed", t);
			}
		}
	}

	private static void renderHearts(PlayerEntityRenderState state, HealthHolder health, MatrixStack matrices,
			OrderedRenderCommandQueue queue, CameraRenderState cameraState) {
		if (!HeartsPlusConfig.isEnabled()) {
			skipOnce("mod disabled");
			return;
		}
		if (health.heartsplus$isLocalPlayer() && !HeartsPlusConfig.isShowOwnHearts()) {
			skipOnce("own player hidden (enable 'Show above yourself')");
			return;
		}
		double maxDistance = HeartsPlusConfig.getRenderDistance();
		if (state.squaredDistanceToCamera > maxDistance * maxDistance) {
			skipOnce("player beyond the render distance");
			return;
		}
		if (state.invisibleToPlayer
				&& !(HeartsPlusConfig.isShowInvisiblePlayers() && health.heartsplus$hasVisibleArmour())) {
			// Hidden by default; even when enabled, armour is the only thing
			// that betrays an invisible player.
			skipOnce("player is invisible");
			return;
		}
		if (!HeartsPlusConfig.isShowSneakingPlayers() && state.sneaking) {
			skipOnce("player is sneaking");
			return;
		}

		HeartType family = HeartType.forStatus(health.heartsplus$isPoisoned(), health.heartsplus$isWithered(),
				health.heartsplus$isFrozen());
		ResolvedSprite container = resolve(HeartType.CONTAINER, false, false);
		ResolvedSprite familyFull = resolve(family, false, false);
		ResolvedSprite familyHalf = resolve(family, true, false);
		ResolvedSprite familyFullBlinking = resolve(family, false, true);
		ResolvedSprite familyHalfBlinking = resolve(family, true, true);
		ResolvedSprite absorbingFull = resolve(HeartType.ABSORBING, false, false);
		ResolvedSprite absorbingHalf = resolve(HeartType.ABSORBING, true, false);

		matrices.push();
		matrices.translate(0.0F, heartsY(state), 0.0F);
		matrices.multiply(new Quaternionf(cameraState.orientation));
		float pixelScale = PIXELS_PER_BLOCK * (float) HeartsPlusConfig.getScale();
		matrices.scale(pixelScale, -pixelScale, pixelScale);
		matrices.translate(0.0F, -HeartsPlusConfig.getHeartOffset(), 0.0F);

		int light = state.light;
		Layout layout = layoutOf(health);
		int blinkFrom = layout.heartsRed();
		int blinkTo = blinkTo(health, layout, (int) Math.floor(state.age));

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
		if (blinkTo > blinkFrom) {
			submitPass(queue, matrices, familyFullBlinking, (entry, vertices) -> {
				for (int heart = blinkFrom; heart < blinkTo; heart++) {
					if (heart != blinkTo - 1 || !layout.lastBlinkHalf()) {
						emitHeart(vertices, entry.getPositionMatrix(), layout.x(heart), layout.yTop(heart), familyFullBlinking, light);
					}
				}
			});
			if (layout.lastBlinkHalf()) {
				submitPass(queue, matrices, familyHalfBlinking, (entry, vertices) ->
						emitHeart(vertices, entry.getPositionMatrix(), layout.x(blinkTo - 1), layout.yTop(blinkTo - 1), familyHalfBlinking, light));
			}
		}
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

		matrices.pop();
		if (!reportedFirstHeart) {
			reportedFirstHeart = true;
			LOGGER.info("Hearts submitted above a player for the first time ({} hearts, texture {})",
					layout.heartsTotal(), container.texture());
			var player = MinecraftClient.getInstance().player;
			if (player != null) {
				player.sendMessage(net.minecraft.text.Text.translatable("heartsplus.message.first_render"), true);
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

	private static ResolvedSprite resolve(HeartType type, boolean half, boolean blinking) {
		if (HeartsPlusConfig.isVanillaTextures()) {
			// Standalone texture files are drawn whole, so UV covers 0..1.
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
		Sprite sprite = MinecraftClient.getInstance().getAtlasManager().getSprite(new SpriteIdentifier(GUI_ATLAS, spriteId));
		return new ResolvedSprite(sprite.getAtlasId(), sprite.getMinU(), sprite.getMinV(),
				sprite.getMaxU(), sprite.getMaxV());
	}

	private static void submitPass(OrderedRenderCommandQueue queue, MatrixStack matrices, ResolvedSprite sprite,
			OrderedRenderCommandQueue.Custom renderer) {
		// Mirrors vanilla nameplates: the normal layer is occluded by walls,
		// the see-through layer shows dimly through them.
		queue.submitCustom(matrices, RenderLayers.text(sprite.texture()), renderer);
		queue.submitCustom(matrices, RenderLayers.textSeeThrough(sprite.texture()), renderer);
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
	 */
	private static float heartsY(PlayerEntityRenderState state) {
		if (state.displayName != null && state.nameLabelPos != null) {
			return (float) state.nameLabelPos.y + 0.5F + NAMETAG_ROW_HEIGHT + NAMEPLATE_GAP;
		}
		return state.height + 0.5F + NAMEPLATE_GAP;
	}

	private static Layout layoutOf(HealthHolder health) {
		int healthRed = MathHelper.ceil(health.heartsplus$getHealth());
		int maxHealth = MathHelper.ceil(health.heartsplus$getMaxHealth());
		int healthYellow = MathHelper.ceil(health.heartsplus$getAbsorption());

		int heartsRed = MathHelper.ceil(healthRed / 2.0F);
		boolean lastRedHalf = (healthRed & 1) == 1;
		int heartsNormal = MathHelper.ceil(maxHealth / 2.0F);
		int heartsYellow = MathHelper.ceil(healthYellow / 2.0F);
		boolean lastYellowHalf = (healthYellow & 1) == 1;
		int heartsTotal = heartsNormal + heartsYellow;

		int blinkHalves = MathHelper.ceil(health.heartsplus$getBlinkOldHealth());
		int heartsBlink = MathHelper.ceil(blinkHalves / 2.0F);
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
