package com.heartsplus.render;

import com.heartsplus.HeartsPlusConfig;
import com.heartsplus.HeartsPlusLog;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.texture.GuiAtlasManager;
import net.minecraft.client.texture.ResourceTexture;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.TextureContents;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionfc;
import org.slf4j.Logger;

/**
 * Draws a row of vanilla heart sprites above a player's head (1.21.x line).
 * Called at the end of LivingEntityRenderer.render, so the incoming MatrixStack
 * is positioned at the entity origin and the geometry is written straight into
 * the frame's VertexConsumerProvider — the classic immediate render path this
 * stretch of versions still uses (the submit/extract split only arrived in
 * 1.21.9).
 *
 * <p>Hearts are drawn with the world-text render layers, which — like name
 * tags — are shaded only by the lightmap, so they look identical from every
 * viewing angle. The anchor height is a fixed constant so the bar never jumps
 * with pose changes. Each sprite family is emitted on its own z layer
 * (containers deepest, overlays closest) so the depth test cannot hide the
 * health hearts behind their containers; inside one buffer the emission order
 * keeps the same stacking. When "show behind blocks" is on, every pass is
 * drawn a second time with the see-through text render layer, mirroring how
 * vanilla name tags draw their see-through part. Sneaking players always get
 * hearts. Recent health drops blink exactly like the vanilla HUD does.
 * Geometry is drawn in passes per texture (containers, health, blinking,
 * absorption) because vanilla-texture mode binds individual files instead of
 * the shared GUI atlas.</p>
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
	 * derived from nameLabelPos/height: the nameplate position sags in the
	 * sneak pose with interpolation lag, which made the hearts jump.
	 */
	private static final float HEART_ANCHOR_HEIGHT = 2.3F;
	/** Z offset between consecutive sprite layers, in quad pixel units (before scale). */
	private static final float LAYER_Z_STEP = 0.01F;
	private static final float PIXELS_PER_BLOCK = 0.025F;

	private HeartsAboveHeadRenderer() {
	}

	/**
	 * Registers and uploads every bundled heart texture before the first
	 * frame needs them. Lazy registration from inside render produces
	 * textures that are never actually uploaded, which makes the hearts
	 * invisible, so warm-up is done from the first client tick. Textures are
	 * registered with forced NEAREST filtering: they are 9x9 pixel art and
	 * must stay crisp at any scale.
	 */
	public static void warmUpVanillaTextures(TextureManager textureManager, ResourceManager resourceManager) {
		if (vanillaTexturesWarmed) {
			return;
		}
		vanillaTexturesWarmed = true;
		for (HeartType type : HeartType.values()) {
			for (Identifier texture : type.fileTextures()) {
				// A missing resource would be swapped for the checkerboard
				// texture instead of failing, so the fallback is decided here.
				if (!resourceManager.getResource(texture).isPresent()) {
					unavailableVanillaTextures.add(texture);
					LOGGER.warn("Bundled heart texture {} is missing; falling back to atlas sprites", texture);
					continue;
				}
				try {
					textureManager.registerTexture(texture, new NearestPixelTexture(texture));
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

	public static void render(PlayerEntityRenderState state, HealthHolder health, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light, Quaternionfc cameraRotation) {
		try {
			renderHearts(state, health, matrices, vertexConsumers, light, cameraRotation);
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

	private static void renderHearts(PlayerEntityRenderState state, HealthHolder health, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light, Quaternionfc cameraRotation) {
		if (!HeartsPlusConfig.isEnabled() || state.spectator) {
			skipOnce("mod disabled or player is a spectator");
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

		HeartBarLayout layout = HeartBarLayout.of(health.heartsplus$getHealth(), health.heartsplus$getMaxHealth(),
				health.heartsplus$getAbsorption(), health.heartsplus$getBlinkOldHealth());
		if (layout.heartsTotal() <= 0) {
			// Degenerate health values — nothing to draw, skip all geometry work.
			return;
		}
		GuiAtlasManager guiAtlasManager = MinecraftClient.getInstance().getGuiAtlasManager();
		HeartType family = HeartType.forStatus(health.heartsplus$isPoisoned(), health.heartsplus$isWithered(),
				health.heartsplus$isFrozen());
		ResolvedSprite container = resolve(HeartType.CONTAINER, false, false, guiAtlasManager);

		matrices.push();
		matrices.translate(0.0F, HEART_ANCHOR_HEIGHT, 0.0F);
		// multiply(x, 0, 0, 0) around the origin equals a plain rotation; this
		// MatrixStack overload (the Yarn name of 26.x rotateAround) is the only
		// quaternion-rotation call shared by every supported game version.
		matrices.multiply(cameraRotation, 0.0F, 0.0F, 0.0F);
		float pixelScale = PIXELS_PER_BLOCK * (float) HeartsPlusConfig.getScale();
		matrices.scale(pixelScale, -pixelScale, pixelScale);
		matrices.translate(0.0F, -HeartsPlusConfig.getHeartOffset(), 0.0F);
		Matrix4f positionMatrix = matrices.peek().getPositionMatrix();

		int blinkFrom = layout.heartsRed();
		int blinkTo = layout.blinkUpperBound((int) Math.floor(state.age), health.heartsplus$getBlinkEndTick(),
				health.heartsplus$getBlinkOldHealth(), health.heartsplus$getHealth());

		// Passes are drawn lazily per family so empty passes (no blinking, no
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
		drawPass(vertexConsumers, container, vertices -> {
			for (int heart = 0; heart < layout.heartsTotal(); heart++) {
				emitHeart(vertices, positionMatrix, layout.x(heart), layout.yTop(heart), container, light, containerZ);
			}
		});
		if (layout.heartsRed() > 0) {
			ResolvedSprite familyFull = resolve(family, false, false, guiAtlasManager);
			drawPass(vertexConsumers, familyFull, vertices -> {
				for (int heart = 0; heart < layout.heartsRed(); heart++) {
					if (!layout.isRedHalf(heart)) {
						emitHeart(vertices, positionMatrix, layout.x(heart), layout.yTop(heart), familyFull, light, familyFullZ);
					}
				}
			});
			if (layout.hasRedHalf()) {
				ResolvedSprite familyHalf = resolve(family, true, false, guiAtlasManager);
				drawPass(vertexConsumers, familyHalf, vertices ->
						emitHeart(vertices, positionMatrix, layout.x(layout.heartsRed() - 1), layout.yTop(layout.heartsRed() - 1), familyHalf, light, familyHalfZ));
			}
		}
		if (blinkTo > blinkFrom) {
			ResolvedSprite familyFullBlinking = resolve(family, false, true, guiAtlasManager);
			drawPass(vertexConsumers, familyFullBlinking, vertices -> {
				for (int heart = blinkFrom; heart < blinkTo; heart++) {
					if (heart != blinkTo - 1 || !layout.lastBlinkHalf()) {
						emitHeart(vertices, positionMatrix, layout.x(heart), layout.yTop(heart), familyFullBlinking, light, familyFullBlinkingZ);
					}
				}
			});
			if (layout.lastBlinkHalf()) {
				ResolvedSprite familyHalfBlinking = resolve(family, true, true, guiAtlasManager);
				drawPass(vertexConsumers, familyHalfBlinking, vertices ->
						emitHeart(vertices, positionMatrix, layout.x(blinkTo - 1), layout.yTop(blinkTo - 1), familyHalfBlinking, light, familyHalfBlinkingZ));
			}
		}
		if (layout.heartsTotal() > layout.heartsNormal()) {
			ResolvedSprite absorbingFull = resolve(HeartType.ABSORBING, false, false, guiAtlasManager);
			drawPass(vertexConsumers, absorbingFull, vertices -> {
				for (int heart = layout.heartsNormal(); heart < layout.heartsTotal(); heart++) {
					if (!layout.isYellowHalf(heart)) {
						emitHeart(vertices, positionMatrix, layout.x(heart), layout.yTop(heart), absorbingFull, light, absorbingFullZ);
					}
				}
			});
			if (layout.hasYellowHalf()) {
				ResolvedSprite absorbingHalf = resolve(HeartType.ABSORBING, true, false, guiAtlasManager);
				drawPass(vertexConsumers, absorbingHalf, vertices ->
						emitHeart(vertices, positionMatrix, layout.x(layout.heartsTotal() - 1), layout.yTop(layout.heartsTotal() - 1), absorbingHalf, light, absorbingHalfZ));
			}
		}

		matrices.pop();
		if (!reportedFirstHeart) {
			reportedFirstHeart = true;
			LOGGER.info("Hearts drawn above a player for the first time ({} hearts, texture {})",
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

	private static ResolvedSprite resolve(HeartType type, boolean half, boolean blinking, GuiAtlasManager guiAtlasManager) {
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
			Sprite sprite = guiAtlasManager.getSprite(spriteId);
			return new ResolvedSprite(sprite.getAtlasId(), sprite.getMinU(), sprite.getMinV(),
					sprite.getMaxU(), sprite.getMaxV());
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

	private static void drawPass(VertexConsumerProvider vertexConsumers, ResolvedSprite sprite, HeartEmitter emitter) {
		// Mirrors vanilla nameplates: the normal layer is occluded by walls,
		// the see-through layer shows dimly through them. All quads of a pass
		// are emitted before the next buffer is requested, so a shared vertex
		// builder can never mix the passes up.
		emitter.emit(vertexConsumers.getBuffer(RenderLayer.getText(sprite.texture())));
		if (HeartsPlusConfig.isShowBehindBlocks()) {
			// Same geometry again without a depth test, so it stays visible
			// through walls — exactly how vanilla name tags draw their
			// see-through part. Drawn only when the option is on, so the
			// default path stays untouched.
			emitter.emit(vertexConsumers.getBuffer(RenderLayer.getTextSeeThrough(sprite.texture())));
		}
	}

	/** One pass's worth of heart quads, emitted into whatever buffer the pass targets. */
	@FunctionalInterface
	private interface HeartEmitter {
		void emit(VertexConsumer vertices);
	}

	/**
	 * A bundled texture registered with NEAREST magnification and
	 * minification (no mipmaps): the 9x9 pixel art must stay crisp, while the
	 * metadata-less default can resolve to a linear sampler.
	 */
	private static final class NearestPixelTexture extends ResourceTexture {
		private NearestPixelTexture(Identifier location) {
			super(location);
		}

		@Override
		public void reload(TextureContents contents) {
			super.reload(contents);
			this.setFilter(false, false);
		}
	}

	private record ResolvedSprite(Identifier texture, float u0, float v0, float u1, float v1) {
	}

	private static void emitHeart(VertexConsumer vertices, Matrix4f matrix, float x, float yTop,
			ResolvedSprite sprite, int light, float z) {
		float endX = x + HeartBarLayout.HEART_SIZE;
		float endY = yTop + HeartBarLayout.HEART_SIZE;
		// The world-text vertex format is POSITION_TEX_LIGHTMAP_COLOR; every
		// element must be set or the BufferBuilder validation rejects the vertex.
		// The pipeline culls back faces, so the winding must match vanilla text
		// quads (BakedQuad of the font renderer): top-left, bottom-left,
		// bottom-right, top-right.
		vertices.vertex(matrix, x, yTop, z).color(255, 255, 255, 255).texture(sprite.u0(), sprite.v0()).light(light);
		vertices.vertex(matrix, x, endY, z).color(255, 255, 255, 255).texture(sprite.u0(), sprite.v1()).light(light);
		vertices.vertex(matrix, endX, endY, z).color(255, 255, 255, 255).texture(sprite.u1(), sprite.v1()).light(light);
		vertices.vertex(matrix, endX, yTop, z).color(255, 255, 255, 255).texture(sprite.u1(), sprite.v0()).light(light);
	}
}
