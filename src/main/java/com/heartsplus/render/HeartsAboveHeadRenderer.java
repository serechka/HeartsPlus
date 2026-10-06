package com.heartsplus.render;

import com.heartsplus.HeartsPlusConfig;
import com.heartsplus.HeartsPlusLog;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
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
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.util.Mth;
import org.slf4j.Logger;

/**
 * Draws a row of vanilla heart sprites above an avatar's head.
 * Called at the end of LivingEntityRenderer.submit, so the incoming
 * PoseStack is positioned at the entity origin and the geometry is
 * recorded through the frame's SubmitNodeCollector.
 *
 * <p>Hearts are drawn with the world-text render type, which — like name
 * tags — is shaded only by the lightmap, so they look identical from every
 * viewing angle. The anchor height is a fixed constant so the bar never
 * jumps with pose changes. Each sprite family is emitted on its own z layer
 * (containers deepest, overlays closest) so the depth test cannot hide the
 * health hearts behind their containers; the gaps between layers scale with
 * the camera distance to outpace depth-buffer precision loss. When "show
 * behind blocks" is on,
 * every pass is submitted a second time with the see-through text render
 * type, mirroring how vanilla name tags draw their see-through part.
 * Sneaking players always get hearts. Geometry is submitted in passes per
 * texture (containers, health, blinking, absorption) because
 * default-texture mode binds individual sprites instead of the shared GUI
 * atlas.</p>
 */
public final class HeartsAboveHeadRenderer {
	private static final Logger LOGGER = HeartsPlusLog.LOGGER;
	/** Default-pack textures that failed to load; those hearts fall back to atlas sprites. */
	private static final Set<Identifier> unavailableFileTextures = new HashSet<>();
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
	 * Fixed height above the entity origin where the heart bar sits — player
	 * height (1.8) plus the vanilla name tag pad (0.5) minus the 10 GUI px
	 * (10 × 0.025) that the Height Offset used to subtract by default: offset
	 * 0 now lands the bar at the same playtested position the old anchor 2.3
	 * + offset -10 produced. Deliberately not derived from
	 * nameTagAttachment/boundingBoxHeight: those sag in the sneak pose with
	 * interpolation lag, which made the hearts jump.
	 */
	private static final float HEART_ANCHOR_HEIGHT = 2.05F;
	private static final float PIXELS_PER_BLOCK = 0.025F;

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
	public static void warmUpVanillaTextures(TextureManager textureManager) {
		if (vanillaTexturesWarmed) {
			return;
		}
		vanillaTexturesWarmed = true;
		for (HeartType type : HeartType.values()) {
			for (Identifier texture : type.fileTextures()) {
				try {
					textureManager.registerAndLoad(texture, new DefaultPackTexture(texture));
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
		poseStack.translate(0.0F, HEART_ANCHOR_HEIGHT, 0.0F);
		// rotateAround(..., 0, 0, 0) equals a plain rotation; it is the only
		// quaternion-rotation call shared by every supported game version.
		poseStack.rotateAround(camera.orientation, 0.0F, 0.0F, 0.0F);
		float pixelScale = PIXELS_PER_BLOCK * (float) HeartsPlusConfig.getScale();
		poseStack.scale(pixelScale, -pixelScale, pixelScale);
		poseStack.translate(0.0F, -HeartsPlusConfig.getHeartOffset(), 0.0F);

		int light = state.lightCoords;
		int blinkFrom = layout.heartsRed();
		// With the animation disabled blinkTo stays at blinkFrom, so the blink
		// passes are skipped entirely and their blinking sprites are never
		// resolved (the resolve calls sit inside the `blinkTo > blinkFrom` gate).
		int blinkTo = HeartsPlusConfig.isBlinkAnimationEnabled()
				? layout.blinkUpperBound((int) Math.floor(state.ageInTicks), health.heartsplus$getBlinkEndTick(),
						health.heartsplus$getBlinkOldHealth(), health.heartsplus$getHealth())
				: blinkFrom;

		// Sprites are resolved lazily per pass so empty passes (no blinking, no
		// absorption) cost nothing at all. Each family sits on its own z layer:
		// containers deepest, then health, blinking and absorption overlays —
		// without the offsets the depth test z-fights the quads apart.
		//
		// The layer gap must grow with the camera distance: depth precision
		// degrades quadratically (at d blocks the depth buffer resolves gaps of
		// roughly d² × 1.2e-6 blocks), so the old fixed 0.01 px step merged the
		// passes far away — at 32 m it resolves 0.0012 blocks, at 128 m only
		// 0.0196, and the red hearts lost to their containers. One milliblock
		// per block of range gives 0.128 blocks per layer at 128 m (well above
		// the required ~0.02) while six layers total 0.77 blocks — under a
		// pixel of parallax on screen; up close the 0.002 floor keeps the bar
		// visually coplanar (0.008 blocks at 8 m — imperceptible).
		float zStep = Math.max(0.002F, Mth.sqrt((float) state.distanceToCameraSq) * 0.001F);
		final float containerZ = 0.0F;
		final float familyFullZ = zStep;
		final float familyHalfZ = 2 * zStep;
		final float familyFullBlinkingZ = 3 * zStep;
		final float familyHalfBlinkingZ = 4 * zStep;
		final float absorbingFullZ = 5 * zStep;
		final float absorbingHalfZ = 6 * zStep;
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
		// the atlas: default-pack textures are registered from a client tick,
		// and a mid-frame first upload would leave them blank until F3+T.
		if (HeartsPlusConfig.isVanillaTextures() && vanillaTexturesWarmed) {
			Identifier file = blinking
					? half ? type.fileHalfBlinking : type.fileFullBlinking
					: half ? type.fileHalf : type.fileFull;
			if (!unavailableFileTextures.contains(file)) {
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

	private static void submitPass(SubmitNodeCollector collector, PoseStack poseStack, ResolvedSprite sprite, float z,
			SubmitNodeCollector.CustomGeometryRenderer renderer) {
		collector.submitCustomGeometry(poseStack, RenderTypes.text(sprite.texture()), renderer);
		if (HeartsPlusConfig.isShowBehindBlocks()) {
			// Same geometry again without a depth test, so it stays visible
			// through walls — exactly how vanilla name tags submit their
			// see-through part alongside the depth-tested one. Submitted only
			// when the option is on, so the default path stays untouched.
			collector.submitCustomGeometry(poseStack, RenderTypes.textSeeThrough(sprite.texture()), renderer);
		}
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
	private static final class DefaultPackTexture extends ReloadableTexture {
		private DefaultPackTexture(Identifier location) {
			super(location);
		}

		@Override
		public TextureContents loadContents(ResourceManager resourceManager) throws IOException {
			List<Resource> stack = resourceManager.getResourceStack(this.resourceId());
			// An empty stack should not happen for vanilla-owned sprites; fall
			// back to the ordinary lookup (topmost pack) instead of failing.
			InputStream stream = stack.isEmpty()
					? resourceManager.open(this.resourceId())
					: stack.getLast().open();
			try (stream) {
				return new TextureContents(NativeImage.read(stream), null);
			}
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
