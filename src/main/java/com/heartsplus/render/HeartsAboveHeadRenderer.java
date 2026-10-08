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
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.AtlasManager;
import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import org.slf4j.Logger;

/**
 * Draws a row of vanilla heart sprites above a player's head (1.21.x line).
 * Called at the end of LivingEntityRenderer.submit, so the incoming PoseStack
 * is positioned at the entity origin and the geometry is recorded through the
 * frame's SubmitNodeCollector.
 *
 * <p>Hearts are drawn with the world-text render types, which — like name
 * tags — are shaded only by the lightmap, so they look identical from every
 * viewing angle. The anchor rides the vanilla name tag attachment point,
 * smoothed per player, so the bar sits right above the tag and glides with
 * it through pose changes. Sprites, pass structure and the animation all
 * mirror the vanilla HUD (Gui.renderHearts): containers deepest, then
 * absorption, then the blink overlay, with the health hearts on top — the
 * same painter order the HUD uses, flattened onto z layers. The pass set
 * mirrors NameTagFeatureRenderer.Storage.add: everyone gets the depth-tested
 * bright pass, players who are not sneaking additionally get a see-through
 * copy whose opacity follows the See-Through Opacity setting (0 drops the
 * pass entirely), and a sneaking player gets only the bright pass
 * (EntityRenderer passes !isDiscrete as the nametag's seeThrough flag) —
 * bright in the open, hidden behind walls at opacity 0. Geometry is
 * submitted in passes per texture (containers, health, blinking, absorption)
 * because default-texture mode binds individual sprites instead of the
 * shared GUI atlas.</p>
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
	 * Fixed height above the entity origin where the heart bar sits. Since
	 * 0.4.7 it includes the old default 10px lift (10 GUI px × 0.025), so the
	 * Height Offset setting reads 0 exactly at the owner-tuned height and
	 * tunes up and down symmetrically. Since 0.4.8 it is the fallback and the
	 * gap calibration point: the live anchor rides the vanilla name tag
	 * attachment point via {@link #heartAnchorForAttachment}.
	 */
	private static final float HEART_ANCHOR_HEIGHT = 2.35F;
	/**
	 * Name tag attachment height of a standing player. The player type
	 * declares no explicit NAME_TAG attachment, so it falls back to
	 * {@code EntityAttachment.Fallback.AT_HEIGHT} of the standing dimensions —
	 * {@code Avatar.STANDING_DIMENSIONS = EntityDimensions.scalable(0.6F, 1.8F)}
	 * — i.e. the bounding box height, 1.8. The pose-specific dimensions
	 * (crouching 1.5, swimming/fall-flying 0.6) lower the same attachment
	 * point, which is what the bar now glides along.
	 */
	private static final float STANDING_ATTACHMENT_Y = 1.8F;
	/**
	 * Vanilla lifts the name tag text half a block above the attachment point
	 * (SubmitNodeCollection.submitNameTag: {@code translate(..., y + 0.5, ...)}),
	 * so 2.3 is the tag's top edge for a standing player. The owner-tuned
	 * anchor 2.35 floats exactly {@code 2.35 − (1.8 + 0.5) = 0.05} above it.
	 */
	private static final float NAMETAG_TEXT_LIFT = 0.5F;
	/** Fixed gap between the name tag's top edge and the heart bar's bottom edge. */
	private static final float NAMETAG_GAP = HEART_ANCHOR_HEIGHT - STANDING_ATTACHMENT_Y - NAMETAG_TEXT_LIFT;
	private static final float PIXELS_PER_BLOCK = 0.025F;
	/** Vanilla name tag text is drawn with this much extra light emission (SubmitNodeCollection.submitNameTag). */
	private static final int NAMETAG_EMISSION = 2;

	private HeartsAboveHeadRenderer() {
	}

	/**
	 * Bar lift on top of the smoothed anchor for the entity's current pose,
	 * in GUI pixels - one slider per pose family since 0.4.9. Vanilla pose
	 * names: {@code CROUCHING} is the sneak pose, {@code SWIMMING} covers the
	 * water crawl and {@code FALL_FLYING} the elytra; every other pose
	 * (sitting, sleeping, dying, ...) uses the standing slider.
	 */
	private static int heartOffsetForPose(net.minecraft.world.entity.Pose pose) {
		return switch (pose) {
			case CROUCHING -> HeartsPlusConfig.getOffsetSneaking();
			case SWIMMING -> HeartsPlusConfig.getOffsetSwimming();
			case FALL_FLYING -> HeartsPlusConfig.getOffsetFlying();
			default -> HeartsPlusConfig.getOffsetStanding();
		};
	}

	/**
	 * The heart anchor for a name tag attachment at {@code attachmentY}:
	 * vanilla's half-block text lift plus the fixed gap, calibrated so a
	 * standing player (attachment 1.8) renders the bar at the untouched
	 * 0.4.7 height of 2.35.
	 */
	static float heartAnchorForAttachment(float attachmentY) {
		return attachmentY + NAMETAG_TEXT_LIFT + NAMETAG_GAP;
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
		Minecraft minecraft = Minecraft.getInstance();
		AtlasManager atlasManager = minecraft.getAtlasManager();
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
		// The bar rides the vanilla name tag attachment point (smoothed per
		// player in BlinkTracker), so it sits right above the tag and glides
		// with it through sneak/swim/fly poses. Extract always precedes
		// submit for a rendered state, so the value is fresh for this frame.
		poseStack.translate(0.0F, health.heartsplus$getHeartAnchorY(), 0.0F);
		// rotateAround(..., 0, 0, 0) equals a plain rotation and avoids the
		// per-frame quaternion allocation a mulPose copy would need.
		poseStack.rotateAround(cameraState.orientation, 0.0F, 0.0F, 0.0F);
		float pixelScale = PIXELS_PER_BLOCK * (float) HeartsPlusConfig.getScale();
		poseStack.scale(pixelScale, -pixelScale, pixelScale);
		// The per-pose lift rides along in GUI pixels: it lands inside the
		// already-scaled space, exactly like the old single offset did.
		poseStack.translate(0.0F, -heartOffsetForPose(state.pose), 0.0F);

		int light = state.lightCoords;
		// Sprites are resolved lazily per pass so empty passes (no blinking, no
		// absorption) cost nothing at all. Each family sits on its own z layer
		// in the HUD's painter order: containers deepest, then absorption, then
		// the blink overlay, with the health hearts on top — without the
		// offsets the depth test z-fights the quads apart. The layer index
		// doubles as the see-through submit order (HeartPass.renderOrder).
		float zStep = HeartLayerSpacing.zStep(Mth.sqrt((float) state.distanceToCameraSq));
		int wallOpacity = HeartsPlusConfig.getWallOpacity();
		List<HeartPass> passes = HeartPass.passesFor(state.isDiscrete, state.isInvisible, wallOpacity);
		int seeThroughAlpha = HeartPass.seeThroughAlpha(wallOpacity);
		final float containerZ = 0.0F;
		final float absorbingZ = zStep;
		final float familyBlinkingZ = 2 * zStep;
		final float familyZ = 3 * zStep;

		ResolvedSprite container = resolve(HeartType.CONTAINER, false, blinking, atlasManager);
		submitPass(collector, passes, poseStack, container, 0, containerZ, light, seeThroughAlpha, (pose, vertices, l, alpha) -> {
			for (int slot = 0; slot < layout.slots(); slot++) {
				emitHeart(pose, vertices, layout.x(slot), layout.yTop(slot), container, l, containerZ, alpha);
			}
		});
		if (hasAbsorption(layout, false)) {
			ResolvedSprite absorbingFull = resolve(absorptionFamily, false, false, atlasManager);
			submitPass(collector, passes, poseStack, absorbingFull, 1, absorbingZ, light, seeThroughAlpha, (pose, vertices, l, alpha) -> {
				for (int slot = layout.healthContainers(); slot < layout.slots(); slot++) {
					if (layout.hasAbsorptionHeart(slot) && !layout.isAbsorptionHalf(slot)) {
						emitHeart(pose, vertices, layout.x(slot), layout.yTop(slot), absorbingFull, l, absorbingZ, alpha);
					}
				}
			});
		}
		if (hasAbsorption(layout, true)) {
			ResolvedSprite absorbingHalf = resolve(absorptionFamily, true, false, atlasManager);
			submitPass(collector, passes, poseStack, absorbingHalf, 1, absorbingZ, light, seeThroughAlpha, (pose, vertices, l, alpha) -> {
				for (int slot = layout.healthContainers(); slot < layout.slots(); slot++) {
					if (layout.hasAbsorptionHeart(slot) && layout.isAbsorptionHalf(slot)) {
						emitHeart(pose, vertices, layout.x(slot), layout.yTop(slot), absorbingHalf, l, absorbingZ, alpha);
					}
				}
			});
		}
		if (blinking && overlayFrom < overlayTo) {
			// The damage flash: vanilla draws the blinking sprites while the
			// window's square wave is on (Gui.renderHearts: `blink && halves
			// < oldHealth`), visible only where no normal heart covers them —
			// the lost slots this interval enumerates. The half variant only
			// ever lands on the range's top slot (`halves + 1 == oldHealth`).
			int overlayFirstSlot = overlayFrom / 2;
			int overlayLastSlot = Math.min((overlayTo - 1) / 2, layout.slots() - 1);
			int overlayHalfSlot = overlayTo % 2 == 1 ? overlayLastSlot : -1;
			ResolvedSprite familyFullBlinking = resolve(family, false, true, atlasManager);
			submitPass(collector, passes, poseStack, familyFullBlinking, 2, familyBlinkingZ, light, seeThroughAlpha, (pose, vertices, l, alpha) -> {
				for (int slot = overlayFirstSlot; slot <= overlayLastSlot; slot++) {
					if (slot != overlayHalfSlot) {
						emitHeart(pose, vertices, layout.x(slot), layout.yTop(slot), familyFullBlinking, l, familyBlinkingZ, alpha);
					}
				}
			});
			if (overlayHalfSlot >= 0) {
				ResolvedSprite familyHalfBlinking = resolve(family, true, true, atlasManager);
				submitPass(collector, passes, poseStack, familyHalfBlinking, 2, familyBlinkingZ, light, seeThroughAlpha,
						(pose, vertices, l, alpha) -> emitHeart(pose, vertices, layout.x(overlayHalfSlot),
								layout.yTop(overlayHalfSlot), familyHalfBlinking, l, familyBlinkingZ, alpha));
			}
		}
		if (hasHealth(layout, false)) {
			ResolvedSprite familyFull = resolve(family, false, false, atlasManager);
			submitPass(collector, passes, poseStack, familyFull, 3, familyZ, light, seeThroughAlpha, (pose, vertices, l, alpha) -> {
				for (int slot = 0; slot < layout.slots(); slot++) {
					if (layout.hasHealthHeart(slot) && !layout.isHealthHalf(slot)) {
						emitHeart(pose, vertices, layout.x(slot), layout.yTop(slot), familyFull, l, familyZ, alpha);
					}
				}
			});
		}
		if (hasHealth(layout, true)) {
			ResolvedSprite familyHalf = resolve(family, true, false, atlasManager);
			submitPass(collector, passes, poseStack, familyHalf, 3, familyZ, light, seeThroughAlpha, (pose, vertices, l, alpha) -> {
				for (int slot = 0; slot < layout.slots(); slot++) {
					if (layout.hasHealthHeart(slot) && layout.isHealthHalf(slot)) {
						emitHeart(pose, vertices, layout.x(slot), layout.yTop(slot), familyHalf, l, familyZ, alpha);
					}
				}
			});
		}

		poseStack.popPose();
		if (!reportedFirstHeart) {
			reportedFirstHeart = true;
			LOGGER.info("Hearts submitted above a player for the first time ({} hearts, texture {})",
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
			LOGGER.info("Hearts above a player were skipped: {} [enabled={}, showOwnHearts={}, showInvisible={}, wallOpacity={}, vanillaTextures={}, scale={}, renderDistance={}]",
					reason, HeartsPlusConfig.isEnabled(), HeartsPlusConfig.isShowOwnHearts(),
					HeartsPlusConfig.isShowInvisiblePlayers(), HeartsPlusConfig.getWallOpacity(),
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

	/**
	 * Submits one sprite's geometry as the vanilla name tag passes selected by
	 * {@code passes}: the depth-tested bright one with the nametag's +2 light
	 * emission, and — when the pass set has it — the see-through copy without
	 * depth test, dimmed to {@code seeThroughAlpha} (the wall-opacity slider's
	 * percent of 255; the 0.4.8 default matched the nametag's
	 * half-transparent white, 0x80FFFFFF) and lit by the plain light coords,
	 * bit-exact with NameTagFeatureRenderer.Storage.add.
	 *
	 * <p>Each pass lands on its own SubmitNodeStorage order: the see-through
	 * layers below the bright pass, by construction of the execute loop. The
	 * see-through render types are depth-blind, so only draw order keeps the
	 * HUD's painter order among the layers and keeps the bright pass on top in
	 * direct sight; within one order the phase batches by render type through
	 * a HashMap whose iteration order is unspecified
	 * (CustomFeatureRenderer.Storage.customGeometrySubmits), so passes that
	 * must not be re-sorted must not share an order.</p>
	 */
	private static void submitPass(SubmitNodeCollector collector, List<HeartPass> passes, PoseStack poseStack,
			ResolvedSprite sprite, int layerIndex, float z, int light, int seeThroughAlpha, HeartEmitter renderer) {
		int emissiveLight = LightTexture.lightCoordsWithEmission(light, NAMETAG_EMISSION);
		if (passes.contains(HeartPass.SEE_THROUGH)) {
			submitCustomGeometry(collector, HeartPass.SEE_THROUGH.renderOrder(layerIndex), poseStack,
					RenderTypes.textSeeThrough(sprite.texture()),
					(pose, vertices) -> renderer.emit(pose, vertices, light, seeThroughAlpha));
		}
		if (passes.contains(HeartPass.NORMAL)) {
			submitCustomGeometry(collector, HeartPass.NORMAL.renderOrder(layerIndex), poseStack,
					RenderTypes.text(sprite.texture()),
					(pose, vertices) -> renderer.emit(pose, vertices, emissiveLight, 255));
		}
	}

	/**
	 * Routes one pass to {@code storage.order(order)}. The frame executes
	 * every phase of order N before every phase of order N+1
	 * (FeatureRenderDispatcher.renderAllFeatures iterates
	 * SubmitNodeStorage.getSubmitsPerOrder() — an Int2ObjectAVLTreeMap — around
	 * its fixed feature renderer list), which pins the pass order exactly
	 * where vanilla gets it from drawing its nameTagSubmitsSeethrough list
	 * before nameTagSubmitsNormal (NameTagFeatureRenderer.render). EyesLayer's
	 * order(1) submit is the vanilla precedent for the same trick.
	 */
	private static void submitCustomGeometry(SubmitNodeCollector collector, int order, PoseStack poseStack,
			RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer renderer) {
		if (collector instanceof SubmitNodeStorage storage) {
			storage.order(order).submitCustomGeometry(poseStack, renderType, renderer);
		} else {
			// Unknown collector: fall back to the plain route. The passes then
			// keep only their submission order — the see-through copy is
			// submitted first above, matching vanilla's phase order.
			collector.submitCustomGeometry(poseStack, renderType, renderer);
		}
	}

	/** One slot's quad emission, parameterised by the per-pass light and alpha. */
	@FunctionalInterface
	private interface HeartEmitter {
		void emit(PoseStack.Pose pose, VertexConsumer vertices, int light, int alpha);
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
			ResolvedSprite sprite, int light, float z, int alpha) {
		float endX = x + HeartBarLayout.HEART_SIZE;
		float endY = yTop + HeartBarLayout.HEART_SIZE;
		// The world-text vertex format is POSITION_TEX_LIGHTMAP_COLOR; every
		// element must be set or the BufferBuilder validation rejects the vertex.
		// The pipeline culls back faces, so the winding must match vanilla text
		// quads (BakedSheetGlyph): top-left, bottom-left, bottom-right, top-right.
		vertices.addVertex(pose, x, yTop, z).setUv(sprite.u0(), sprite.v0())
				.setLight(light).setColor(255, 255, 255, alpha);
		vertices.addVertex(pose, x, endY, z).setUv(sprite.u0(), sprite.v1())
				.setLight(light).setColor(255, 255, 255, alpha);
		vertices.addVertex(pose, endX, endY, z).setUv(sprite.u1(), sprite.v1())
				.setLight(light).setColor(255, 255, 255, alpha);
		vertices.addVertex(pose, endX, yTop, z).setUv(sprite.u1(), sprite.v0())
				.setLight(light).setColor(255, 255, 255, alpha);
	}
}
