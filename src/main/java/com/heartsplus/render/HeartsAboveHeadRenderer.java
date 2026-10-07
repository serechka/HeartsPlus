package com.heartsplus.render;

import com.heartsplus.HeartsPlusConfig;
import com.heartsplus.HeartsPlusLog;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
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
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Quaternionfc;
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
 * tags — are shaded only by the lightmap. The anchor height is a fixed
 * constant so the bar never jumps with pose changes. Sprites, pass structure
 * and the animation all mirror the vanilla HUD (Gui.renderHearts):
 * containers deepest, then absorption, then the blink overlay, with the
 * health hearts on top — the same painter order the HUD uses, flattened onto
 * z layers. The pass set mirrors EntityRenderer.renderNameTag: everyone gets
 * the depth-tested bright pass, players who are not sneaking additionally
 * get a dimmed half-transparent see-through copy when "show behind blocks"
 * is on, and a sneaking player gets only the bright pass (renderNameTag
 * passes !isDiscrete as the label's DisplayMode) — bright in the open,
 * hidden behind blocks. Geometry is drawn in passes per texture
 * (containers, health, blinking, absorption) because default-texture mode
 * binds individual sprites instead of the shared GUI atlas.</p>
 */
public final class HeartsAboveHeadRenderer {
	private static final Logger LOGGER = HeartsPlusLog.LOGGER;
	/** Default-pack textures that failed to load; those hearts fall back to atlas sprites. */
	private static final Set<ResourceLocation> unavailableFileTextures = new HashSet<>();
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
	 * Fixed height above the entity origin where the heart bar sits. Since
	 * 0.4.7 it includes the old default 10px lift (10 GUI px × 0.025), so the
	 * Vertical Offset setting reads 0 exactly at the owner-tuned height and
	 * tunes up and down symmetrically. Deliberately not derived from
	 * nameTagAttachment/boundingBoxHeight: those sag in the sneak
	 * pose with interpolation lag, which made the hearts jump.
	 */
	private static final float HEART_ANCHOR_HEIGHT = 2.35F;
	private static final float PIXELS_PER_BLOCK = 0.025F;
	/** Vanilla name tag text is drawn with this much extra light emission (EntityRenderer.renderNameTag). */
	private static final int NAMETAG_EMISSION = 2;
	/** Alpha of the nametag's see-through copy: vanilla colour 0x80FFFFFF — half-transparent white. */
	private static final int SEE_THROUGH_ALPHA = 0x80;

	private HeartsAboveHeadRenderer() {
	}

	/**
	 * Registers and uploads every default-pack heart texture before the first
	 * frame needs them. Lazy registration from inside render produces
	 * textures that are never actually uploaded, which makes the hearts
	 * invisible, so warm-up is done from the first client tick. The built-in
	 * default pack is static — these textures never need reload invalidation,
	 * unlike the atlas sprites. Textures are registered with forced NEAREST
	 * filtering: they are 9x9 pixel art and must stay crisp at any scale.
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
			MultiBufferSource bufferSource, int packedLight, Quaternionfc cameraRotation) {
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
			MultiBufferSource bufferSource, int packedLight, Quaternionfc cameraRotation) {
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

		// The bar always shows the current health instantly (no vanilla
		// displayHealth lag); the blink window from the animation state only
		// adds the flash overlays. With the animation off the state arms no
		// windows, so `blinking` stays false and the blinking sprites are
		// never resolved (the resolve calls sit inside the `blinking` gate).
		boolean blinking = health.heartsplus$isBlinking();
		HeartBarLayout layout = HeartBarLayout.of(health.heartsplus$getHealth(), health.heartsplus$getMaxHealth(),
				health.heartsplus$getAbsorption());
		if (layout.slots() <= 0) {
			// Degenerate health values — nothing to draw, skip all geometry work.
			return;
		}
		GuiSpriteManager guiSpriteManager = Minecraft.getInstance().getGuiSprites();
		HeartType family = HeartType.forStatus(health.heartsplus$isPoisoned(), health.heartsplus$isWithered(),
				health.heartsplus$isFrozen());
		// Withered players keep their black absorption hearts, exactly like the vanilla HUD.
		HeartType absorptionFamily = family == HeartType.WITHERED ? HeartType.WITHERED : HeartType.ABSORBING;
		// Lost-slot overlay of the damage window, in half-hearts; slot bounds
		// are resolved once per player per frame below. Vanilla also swaps the
		// container sprite to its blinking variant on every slot while any
		// window is live (Gui.renderHearts draws the container with the blink
		// flag) — the container pass does that via `blinking`, and that flash
		// is the whole heal pop, since acquired slots get no blinking heart.
		int overlayFrom = health.heartsplus$getBlinkOverlayStart();
		int overlayTo = health.heartsplus$getBlinkOverlayEnd();

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
		// offsets the depth test z-fights the quads apart. The painter order
		// doubles as the see-through submit order (HeartPass.renderOrder):
		// drawPhases emits every see-through layer before any bright pass.
		float zStep = HeartLayerSpacing.zStep(Mth.sqrt((float) state.distanceToCameraSq));
		final float containerZ = 0.0F;
		final float absorbingZ = zStep;
		final float familyBlinkingZ = 2 * zStep;
		final float familyZ = 3 * zStep;

		List<HeartPass> passes = HeartPass.passesFor(state.isDiscrete, HeartsPlusConfig.isShowBehindBlocks());
		List<FamilyDraw> barDraws = new ArrayList<>();
		int emissiveLight = LightTexture.lightCoordsWithEmission(packedLight, NAMETAG_EMISSION);

		ResolvedSprite container = resolve(HeartType.CONTAINER, false, blinking, guiSpriteManager);
		barDraws.add(new FamilyDraw(container, (vertices, passLight, alpha) -> {
			for (int slot = 0; slot < layout.slots(); slot++) {
				emitHeart(vertices, pose, layout.x(slot), layout.yTop(slot), container, passLight, containerZ, alpha);
			}
		}));
		if (hasAbsorption(layout, false)) {
			ResolvedSprite absorbingFull = resolve(absorptionFamily, false, false, guiSpriteManager);
			barDraws.add(new FamilyDraw(absorbingFull, (vertices, passLight, alpha) -> {
				for (int slot = layout.healthContainers(); slot < layout.slots(); slot++) {
					if (layout.hasAbsorptionHeart(slot) && !layout.isAbsorptionHalf(slot)) {
						emitHeart(vertices, pose, layout.x(slot), layout.yTop(slot), absorbingFull, passLight, absorbingZ, alpha);
					}
				}
			}));
		}
		if (hasAbsorption(layout, true)) {
			ResolvedSprite absorbingHalf = resolve(absorptionFamily, true, false, guiSpriteManager);
			barDraws.add(new FamilyDraw(absorbingHalf, (vertices, passLight, alpha) -> {
				for (int slot = layout.healthContainers(); slot < layout.slots(); slot++) {
					if (layout.hasAbsorptionHeart(slot) && layout.isAbsorptionHalf(slot)) {
						emitHeart(vertices, pose, layout.x(slot), layout.yTop(slot), absorbingHalf, passLight, absorbingZ, alpha);
					}
				}
			}));
		}
		if (blinking && overlayFrom < overlayTo) {
			// The damage flash: vanilla draws the blinking sprites while the
			// window's square wave is on (Gui.renderHearts draws a blinking
			// heart on every half-heart index below the lagging displayHealth
			// copy — here the lost-slot interval of the instant bar), visible
			// only where no normal heart covers them. The half variant only
			// ever lands on the range's top slot (index + 1 == displayHealth).
			int overlayFirstSlot = overlayFrom / 2;
			int overlayLastSlot = Math.min((overlayTo - 1) / 2, layout.slots() - 1);
			int overlayHalfSlot = overlayTo % 2 == 1 ? overlayLastSlot : -1;
			ResolvedSprite familyFullBlinking = resolve(family, false, true, guiSpriteManager);
			barDraws.add(new FamilyDraw(familyFullBlinking, (vertices, passLight, alpha) -> {
				for (int slot = overlayFirstSlot; slot <= overlayLastSlot; slot++) {
					if (slot != overlayHalfSlot) {
						emitHeart(vertices, pose, layout.x(slot), layout.yTop(slot), familyFullBlinking, passLight, familyBlinkingZ, alpha);
					}
				}
			}));
			if (overlayHalfSlot >= 0) {
				ResolvedSprite familyHalfBlinking = resolve(family, true, true, guiSpriteManager);
				barDraws.add(new FamilyDraw(familyHalfBlinking, (vertices, passLight, alpha) ->
						emitHeart(vertices, pose, layout.x(overlayHalfSlot), layout.yTop(overlayHalfSlot),
								familyHalfBlinking, passLight, familyBlinkingZ, alpha)));
			}
		}
		if (hasHealth(layout, false)) {
			ResolvedSprite familyFull = resolve(family, false, false, guiSpriteManager);
			barDraws.add(new FamilyDraw(familyFull, (vertices, passLight, alpha) -> {
				for (int slot = 0; slot < layout.slots(); slot++) {
					if (layout.hasHealthHeart(slot) && !layout.isHealthHalf(slot)) {
						emitHeart(vertices, pose, layout.x(slot), layout.yTop(slot), familyFull, passLight, familyZ, alpha);
					}
				}
			}));
		}
		if (hasHealth(layout, true)) {
			ResolvedSprite familyHalf = resolve(family, true, false, guiSpriteManager);
			barDraws.add(new FamilyDraw(familyHalf, (vertices, passLight, alpha) -> {
				for (int slot = 0; slot < layout.slots(); slot++) {
					if (layout.hasHealthHeart(slot) && layout.isHealthHalf(slot)) {
						emitHeart(vertices, pose, layout.x(slot), layout.yTop(slot), familyHalf, passLight, familyZ, alpha);
					}
				}
			}));
		}

		drawPhases(bufferSource, barDraws, passes, packedLight, emissiveLight);

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
	 * One sprite family's geometry in HUD painter order, drawn once per
	 * selected {@link HeartPass} in {@link #drawPhases}.
	 */
	private record FamilyDraw(ResolvedSprite sprite, HeartEmitter renderer) {
	}

	/**
	 * Emits the bar's passes in the vanilla name tag's two phases: every
	 * dimmed see-through copy first, then every depth-tested bright pass —
	 * the order EntityRenderer.renderNameTag itself submits (its
	 * DisplayMode.SEE_THROUGH draw precedes the bright DisplayMode.NORMAL
	 * draw). All world-text render types run on the frame BufferSource's
	 * shared buffer (MultiBufferSource.BufferSource ends the current batch as
	 * soon as a different one is requested), and text render types are never
	 * re-sorted, so the depth-blind see-through layers keep exactly this
	 * submission order — the HUD's painter order (containers, absorption,
	 * blinking, health) stays stable frame to frame, which is
	 * HeartPass.renderOrder's contract realised without a submit-order API.
	 * The bright passes order themselves by depth: the text pipeline
	 * depth-tests with LEQUAL and writes depth, so in direct sight the
	 * closest z layer always wins over anything drawn earlier.
	 */
	private static void drawPhases(MultiBufferSource bufferSource, List<FamilyDraw> barDraws,
			List<HeartPass> passes, int packedLight, int emissiveLight) {
		if (passes.contains(HeartPass.SEE_THROUGH)) {
			for (FamilyDraw draw : barDraws) {
				draw.renderer().emit(bufferSource.getBuffer(RenderType.textSeeThrough(draw.sprite().texture())), packedLight, SEE_THROUGH_ALPHA);
			}
		}
		if (passes.contains(HeartPass.NORMAL)) {
			for (FamilyDraw draw : barDraws) {
				draw.renderer().emit(bufferSource.getBuffer(RenderType.text(draw.sprite().texture())), emissiveLight, 255);
			}
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
			this.setFilter(false, false);
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
