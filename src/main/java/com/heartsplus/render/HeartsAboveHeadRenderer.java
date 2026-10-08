package com.heartsplus.render;

import com.heartsplus.HeartsPlusConfig;
import com.heartsplus.HeartsPlusLog;
import com.heartsplus.mixin.EntityRenderDispatcherMixin;
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
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;

/**
 * Draws a row of vanilla heart sprites above a player's head (classic
 * 1.21.0/1.21.1 pipeline). Called at the end of LivingEntityRenderer.render,
 * so the incoming PoseStack is positioned at the entity origin and quads
 * can be emitted straight into the frame's MultiBufferSource.
 *
 * <p>The transform mirrors the vanilla name tag recipe from
 * EntityRenderer.renderNameTag: translate to the anchor, rotate by the
 * camera orientation, scale(0.025 * scale, -0.025 * scale, 0.025 * scale).
 * Hearts are drawn with the world-text render types, which — like name
 * tags — are shaded only by the lightmap, so they look identical from every
 * viewing angle. The anchor rides the vanilla name tag attachment point,
 * smoothed per player, so the bar sits right above the tag and glides with
 * it through pose changes. Sprites, pass structure and the animation all
 * mirror the
 * vanilla HUD (Gui.renderHearts): containers deepest, then absorption, then
 * the blink overlay, with the health hearts on top — the same painter order
 * the HUD uses, flattened onto z layers. The pass set mirrors
 * EntityRenderer.renderNameTag: everyone gets the depth-tested bright pass,
 * players who are not sneaking additionally get a see-through copy whose
 * opacity follows the See-Through Opacity setting (0 drops the pass
 * entirely), and a sneaking player gets only the bright pass (the label
 * gates its see-through copy on !isDiscrete) — bright in the open,
 * hidden behind walls at opacity 0. Geometry is
 * submitted in passes per texture (containers, absorption, blinking, health)
 * because default-texture mode binds individual sprites instead of the
 * shared GUI atlas.</p>
 */
public final class HeartsAboveHeadRenderer {
	private static final Logger LOGGER = HeartsPlusLog.LOGGER;
	/** Default-pack textures that failed to load; those hearts fall back to atlas sprites. */
	private static final Set<ResourceLocation> unavailableFileTextures = new HashSet<>();
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
	 * Height above the entity origin where the heart bar sits for a standing
	 * player — the owner-tuned 0.4.7 anchor, which includes the old default
	 * 10px lift (10 GUI px × 0.025), so the Height Offset setting reads 0
	 * exactly at that height and tunes up and down symmetrically. Since 0.4.8
	 * it is the fallback and the gap calibration point: the live anchor rides
	 * the vanilla name tag attachment point via {@link #heartAnchorForAttachment}.
	 */
	private static final float HEART_ANCHOR_HEIGHT = 2.35F;
	/**
	 * Name tag attachment height of a standing player. The player type
	 * declares no explicit NAME_TAG attachment, so {@code getNullable}
	 * returns null and the bar reads the bounding box height instead —
	 * {@code Player.STANDING_DIMENSIONS = EntityDimensions.scalable(0.6F, 1.8F)}
	 * — i.e. 1.8. The pose-specific dimensions (crouching 1.5, swimming/
	 * fall-flying 0.6) lower the same fallback, which is what the bar now
	 * glides along.
	 */
	private static final float STANDING_ATTACHMENT_Y = 1.8F;
	/**
	 * Vanilla lifts the name tag text half a block above the attachment point
	 * (EntityRenderer.renderNameTag: {@code translate(..., y + 0.5, ...)}),
	 * so 2.3 is the tag's top edge for a standing player. The owner-tuned
	 * anchor 2.35 floats exactly {@code 2.35 − (1.8 + 0.5) = 0.05} above it.
	 */
	private static final float NAMETAG_TEXT_LIFT = 0.5F;
	/** Fixed gap between the name tag's top edge and the heart bar's bottom edge. */
	private static final float NAMETAG_GAP = HEART_ANCHOR_HEIGHT - STANDING_ATTACHMENT_Y - NAMETAG_TEXT_LIFT;
	private static final float PIXELS_PER_BLOCK = 0.025F;
	/**
	 * Base alpha of the name tag's see-through copy: vanilla colour 0x20FFFFFF
	 * (EntityRenderer.renderNameTag). Since 0.4.9 it is the reference the
	 * wall-opacity slider scales around: the default 50 renders exactly this
	 * dimming, 100 doubles it.
	 */
	private static final int SEE_THROUGH_ALPHA = 0x20;

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
	 * Drops every per-player render state (blink windows and height
	 * smoothers). Must run when the play connection ends: a rejoin hands the
	 * same UUID a fresh entity whose tick counter restarted, and stale state
	 * would replay old blink windows. Runs on the render thread via
	 * {@code Minecraft.execute} (the disconnect hook can fire on a network
	 * thread).
	 */
	public static void clearPerPlayerState() {
		PlayerBlinkTracker.clear();
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
	public static void warmUpVanillaTextures(TextureManager textureManager, ResourceManager resourceManager) {
		if (vanillaTexturesWarmed) {
			return;
		}
		vanillaTexturesWarmed = true;
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

	public static void render(Player player, EntityRenderDispatcher dispatcher, PoseStack poseStack,
			MultiBufferSource bufferSource, int packedLight, float partialTick) {
		try {
			renderHearts(player, dispatcher, poseStack, bufferSource, packedLight, partialTick);
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

	private static void renderHearts(Player player, EntityRenderDispatcher dispatcher, PoseStack poseStack,
			MultiBufferSource bufferSource, int packedLight, float partialTick) {
		if (!HeartsPlusConfig.isEnabled() || player.isSpectator()) {
			skipOnce("mod disabled or player is a spectator");
			return;
		}
		if (player == Minecraft.getInstance().player && !HeartsPlusConfig.isShowOwnHearts()) {
			skipOnce("own player hidden (enable 'Show above yourself')");
			return;
		}
		double maxDistance = HeartsPlusConfig.getRenderDistance();
		if (dispatcher.distanceToSqr(player) > maxDistance * maxDistance) {
			skipOnce("player beyond the render distance");
			return;
		}
		if (player.isInvisible()
				&& !(HeartsPlusConfig.isShowInvisiblePlayers() && hasVisibleArmour(player))) {
			// Hidden by default; even when enabled, armour is the only thing
			// that betrays an invisible player.
			skipOnce("player is invisible");
			return;
		}
		if (!((EntityRenderDispatcherMixin) dispatcher).heartsplus$shouldRenderShadow()) {
			// Embedded entity previews (the inventory screen model) render
			// through the same code path with shadows disabled; the heart bar
			// is a world overlay and must not appear there.
			skipOnce("embedded entity preview (shadows disabled)");
			return;
		}

		float health = player.getHealth();
		// The tracker (animation + height smoothing) is fed before the pass
		// selection below, so the vanilla animation keeps ticking for every
		// rendered player; the returned anchor is the smoothed name tag
		// attachment height for this frame.
		float anchorY = PlayerBlinkTracker.update(player.getUUID(), health, player.tickCount, player.invulnerableTime > 0,
				nameTagAttachmentY(player, partialTick));

		// The bar always shows the current health instantly (no vanilla
		// displayHealth lag); the blink window from the animation state only
		// adds the flash overlays. With the animation off the state arms no
		// windows, so `blinking` stays false and the blinking sprites are
		// never resolved (the resolve calls sit inside the `blinking` gate).
		HeartAnimationState animationState = PlayerBlinkTracker.stateFor(player.getUUID());
		boolean blinking = animationState.isBlinking();
		HeartBarLayout layout = HeartBarLayout.of(health, player.getMaxHealth(), player.getAbsorptionAmount());
		if (layout.slots() <= 0) {
			// Degenerate health values — nothing to draw, skip all geometry work.
			return;
		}
		GuiSpriteManager spriteManager = Minecraft.getInstance().getGuiSprites();
		HeartType family = HeartType.forStatus(player.hasEffect(MobEffects.POISON),
				player.hasEffect(MobEffects.WITHER), player.isFullyFrozen());
		// Withered players keep their black absorption hearts, exactly like the vanilla HUD.
		HeartType absorptionFamily = family == HeartType.WITHERED ? HeartType.WITHERED : HeartType.ABSORBING;
		// Lost-slot overlay of the damage window, in half-hearts; slot bounds
		// are resolved once per player per frame below. Vanilla also swaps the
		// container sprite to its blinking variant on every slot while any
		// window is live (Gui.renderHearts draws the container with the blink
		// flag) — the container pass does that via `blinking`, and that flash
		// is the whole heal pop, since acquired slots get no blinking heart.
		int overlayFrom = animationState.blinkOverlayStart();
		int overlayTo = animationState.blinkOverlayEnd();

		poseStack.pushPose();
		// The bar rides the vanilla name tag attachment point (smoothed per
		// player in PlayerBlinkTracker), so it sits right above the tag and
		// glides with it through sneak/swim/fly poses.
		poseStack.translate(0.0F, anchorY, 0.0F);
		// The vanilla name tag recipe: billboard the bar towards the camera.
		poseStack.mulPose(dispatcher.cameraOrientation());
		float pixelScale = PIXELS_PER_BLOCK * (float) HeartsPlusConfig.getScale();
		poseStack.scale(pixelScale, -pixelScale, pixelScale);
		// The per-pose lift rides along in GUI pixels: it lands inside the
		// already-scaled space, exactly like the old single offset did.
		poseStack.translate(0.0F, -heartOffsetForPose(player.getPose()), 0.0F);

		// Sprites are resolved lazily per pass so empty passes (no blinking, no
		// absorption) cost nothing at all. Each family sits on its own z layer
		// in the HUD's painter order: containers deepest, then absorption, then
		// the blink overlay, with the health hearts on top — without the
		// offsets the depth test z-fights the quads apart. The layer index
		// doubles as the see-through submit order (HeartPass.renderOrder).
		float zStep = HeartLayerSpacing.zStep(Mth.sqrt((float) dispatcher.distanceToSqr(player)));
		final float containerZ = 0.0F;
		final float absorbingZ = zStep;
		final float familyBlinkingZ = 2 * zStep;
		final float familyZ = 3 * zStep;

		List<HeartSubmit> submits = new ArrayList<>();
		ResolvedSprite container = resolve(HeartType.CONTAINER, false, blinking, spriteManager);
		submits.add(new HeartSubmit(container, 0, (vertices, matrix, light, alpha) -> {
			for (int slot = 0; slot < layout.slots(); slot++) {
				emitHeart(vertices, matrix, layout.x(slot), layout.yTop(slot), container, light, containerZ, alpha);
			}
		}));
		if (hasAbsorption(layout, false)) {
			ResolvedSprite absorbingFull = resolve(absorptionFamily, false, false, spriteManager);
			submits.add(new HeartSubmit(absorbingFull, 1, (vertices, matrix, light, alpha) -> {
				for (int slot = layout.healthContainers(); slot < layout.slots(); slot++) {
					if (layout.hasAbsorptionHeart(slot) && !layout.isAbsorptionHalf(slot)) {
						emitHeart(vertices, matrix, layout.x(slot), layout.yTop(slot), absorbingFull, light, absorbingZ, alpha);
					}
				}
			}));
		}
		if (hasAbsorption(layout, true)) {
			ResolvedSprite absorbingHalf = resolve(absorptionFamily, true, false, spriteManager);
			submits.add(new HeartSubmit(absorbingHalf, 1, (vertices, matrix, light, alpha) -> {
				for (int slot = layout.healthContainers(); slot < layout.slots(); slot++) {
					if (layout.hasAbsorptionHeart(slot) && layout.isAbsorptionHalf(slot)) {
						emitHeart(vertices, matrix, layout.x(slot), layout.yTop(slot), absorbingHalf, light, absorbingZ, alpha);
					}
				}
			}));
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
			ResolvedSprite familyFullBlinking = resolve(family, false, true, spriteManager);
			submits.add(new HeartSubmit(familyFullBlinking, 2, (vertices, matrix, light, alpha) -> {
				for (int slot = overlayFirstSlot; slot <= overlayLastSlot; slot++) {
					if (slot != overlayHalfSlot) {
						emitHeart(vertices, matrix, layout.x(slot), layout.yTop(slot), familyFullBlinking, light, familyBlinkingZ, alpha);
					}
				}
			}));
			if (overlayHalfSlot >= 0) {
				ResolvedSprite familyHalfBlinking = resolve(family, true, true, spriteManager);
				submits.add(new HeartSubmit(familyHalfBlinking, 2, (vertices, matrix, light, alpha) ->
						emitHeart(vertices, matrix, layout.x(overlayHalfSlot), layout.yTop(overlayHalfSlot),
								familyHalfBlinking, light, familyBlinkingZ, alpha)));
			}
		}
		if (hasHealth(layout, false)) {
			ResolvedSprite familyFull = resolve(family, false, false, spriteManager);
			submits.add(new HeartSubmit(familyFull, 3, (vertices, matrix, light, alpha) -> {
				for (int slot = 0; slot < layout.slots(); slot++) {
					if (layout.hasHealthHeart(slot) && !layout.isHealthHalf(slot)) {
						emitHeart(vertices, matrix, layout.x(slot), layout.yTop(slot), familyFull, light, familyZ, alpha);
					}
				}
			}));
		}
		if (hasHealth(layout, true)) {
			ResolvedSprite familyHalf = resolve(family, true, false, spriteManager);
			submits.add(new HeartSubmit(familyHalf, 3, (vertices, matrix, light, alpha) -> {
				for (int slot = 0; slot < layout.slots(); slot++) {
					if (layout.hasHealthHeart(slot) && layout.isHealthHalf(slot)) {
						emitHeart(vertices, matrix, layout.x(slot), layout.yTop(slot), familyHalf, light, familyZ, alpha);
					}
				}
			}));
		}

		// HeartPass.renderOrder pins every see-through layer (orders 0-3, the
		// list order above) below the bright pass (order 4). This era has no
		// submit orders to route the passes to: the text render types are not
		// in the seeded fixedBuffers of the entity MultiBufferSource
		// (RenderBuffers), so BufferSource.getBuffer falls back to the shared
		// buffer and draws the previous batch the moment a different type is
		// requested — the draw order is the submission order. Two sweeps
		// therefore realize the orders: all see-through layers first, the
		// bright pass last. Vanilla name tags get the same order by submitting
		// their see-through copy before the bright text
		// (EntityRenderer.renderNameTag); independently, the world pass
		// quad-sorts every batch back-to-front (MeshData.sortQuads on the
		// translucent text types, the projection's distance sorting), which
		// also restores the painter order inside each batch.
		int wallOpacity = HeartsPlusConfig.getWallOpacity();
		List<HeartPass> passes = HeartPass.passesFor(player.isDiscrete(), player.isInvisible(), wallOpacity);
		// This era's see-through dimming is vanilla's own 0x20
		// (EntityRenderer.renderNameTag, plain light coords). The wall-opacity
		// slider scales around it: the default 50 renders the untouched 0.4.8
		// dimming, 100 doubles it, 0 drops the pass entirely
		// (HeartPass.passesFor).
		int seeThroughAlpha = (int) Math.round(wallOpacity * (double) SEE_THROUGH_ALPHA
				/ HeartsPlusConfig.DEFAULT_WALL_OPACITY);
		if (passes.contains(HeartPass.SEE_THROUGH)) {
			for (HeartSubmit submit : submits) {
				submitPass(bufferSource, poseStack, submit.sprite(), packedLight, HeartPass.SEE_THROUGH, seeThroughAlpha,
						submit.geometry());
			}
		}
		if (passes.contains(HeartPass.NORMAL)) {
			for (HeartSubmit submit : submits) {
				submitPass(bufferSource, poseStack, submit.sprite(), packedLight, HeartPass.NORMAL, seeThroughAlpha,
						submit.geometry());
			}
		}

		poseStack.popPose();
		if (!reportedFirstHeart) {
			reportedFirstHeart = true;
			LOGGER.info("Hearts submitted above a player for the first time ({} hearts, texture {})",
					layout.slots(), container.texture());
		}
	}

	/**
	 * The Y of the vanilla name tag attachment point, computed with the same
	 * call EntityRenderer.renderNameTag makes for its own name tag — the
	 * player type declares no explicit NAME_TAG attachment, so the build
	 * fills it with the AT_HEIGHT fallback (current pose dimensions); the
	 * null branch is only a guard. The
	 * point follows the pose (standing 1.8, crouching 1.5, swimming 0.6)
	 * because {@code getAttachments} serves the current pose dimensions.
	 */
	private static float nameTagAttachmentY(Player player, float partialTick) {
		Vec3 attachment = player.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0,
				player.getViewYRot(partialTick));
		return attachment == null ? player.getBbHeight() : (float) attachment.y;
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

	private static boolean hasVisibleArmour(Player player) {
		return !player.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
				|| !player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
				|| !player.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
				|| !player.getItemBySlot(EquipmentSlot.FEET).isEmpty();
	}

	private static ResolvedSprite resolve(HeartType type, boolean half, boolean blinking,
			GuiSpriteManager spriteManager) {
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
			TextureAtlasSprite sprite = spriteManager.getSprite(spriteId);
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
	 * Submits one sprite's geometry as the single vanilla name tag pass
	 * selected by {@code pass} (EntityRenderer.renderNameTag): the
	 * depth-tested bright one with the plain light coords, or the see-through
	 * copy without depth test, dimmed to {@code seeThroughAlpha} (the
	 * wall-opacity slider scaled around this era's vanilla dimming, 0x20 —
	 * the 0.4.8 default) and lit by the same plain light coords.
	 *
	 * <p>The passes must draw in a fixed order — see-through layers below the
	 * bright pass — which this era gets from the submission sweeps in
	 * {@link #renderHearts}: BufferSource.getBuffer falls back to the shared
	 * buffer for the text types (they are absent from the seeded
	 * fixedBuffers) and immediately draws the previous batch when a different
	 * type is requested, so the draw order is the submission order.</p>
	 */
	private static void submitPass(MultiBufferSource bufferSource, PoseStack poseStack,
			ResolvedSprite sprite, int packedLight, HeartPass pass, int seeThroughAlpha, PassGeometry geometry) {
		Matrix4f matrix = poseStack.last().pose();
		if (pass == HeartPass.SEE_THROUGH) {
			VertexConsumer seeThrough = bufferSource.getBuffer(RenderType.textSeeThrough(sprite.texture()));
			geometry.emit(seeThrough, matrix, packedLight, seeThroughAlpha);
		} else {
			VertexConsumer vertices = bufferSource.getBuffer(RenderType.text(sprite.texture()));
			geometry.emit(vertices, matrix, packedLight, 255);
		}
	}

	/** One sprite family's geometry plan, in the see-through submit order of {@link HeartPass#renderOrder}. */
	private record HeartSubmit(ResolvedSprite sprite, int layerIndex, PassGeometry geometry) {
	}

	/** One emitted quad set for a single texture pass, parameterised by the per-pass light and alpha. */
	private interface PassGeometry {
		void emit(VertexConsumer vertices, Matrix4f matrix, int light, int alpha);
	}

	/**
	 * A heart sprite loaded straight from Minecraft's built-in default
	 * resource pack. {@link ResourceManager#getResourceStack} lists every
	 * pack's copy top-down (the active pack first), and the bottom-most entry
	 * is always the built-in default pack — reading that entry pins the look
	 * to the unmodified default sprites even when the player stacks override
	 * packs on top. Registered with NEAREST magnification and minification
	 * (no mipmaps): the 9x9 pixel art must stay crisp, while vanilla leaves
	 * freshly loaded textures on the linear GL defaults.
	 */
	private static final class DefaultPackTexture extends SimpleTexture {
		private DefaultPackTexture(ResourceLocation location) {
			super(location);
		}

		@Override
		protected TextureImage getTextureImage(ResourceManager resourceManager) {
			try {
				List<Resource> stack = resourceManager.getResourceStack(this.location);
				// An empty stack should not happen for vanilla-owned sprites;
				// fall back to the ordinary lookup (topmost pack) instead of
				// failing.
				Resource bottom = stack.isEmpty()
						? resourceManager.getResourceOrThrow(this.location)
						: stack.getLast();
				NativeImage image;
				// Heart sprite files carry no .mcmeta metadata.
				try (InputStream stream = bottom.open()) {
					image = NativeImage.read(stream);
				}
				return new TextureImage(null, image);
			} catch (IOException e) {
				return new TextureImage(e);
			}
		}

		@Override
		public void load(ResourceManager resourceManager) throws IOException {
			super.load(resourceManager);
			this.setFilter(false, false);
		}
	}

	private record ResolvedSprite(ResourceLocation texture, float u0, float v0, float u1, float v1) {
	}

	private static void emitHeart(VertexConsumer vertices, Matrix4f matrix, float x, float yTop,
			ResolvedSprite sprite, int packedLight, float z, int alpha) {
		float endX = x + HeartBarLayout.HEART_SIZE;
		float endY = yTop + HeartBarLayout.HEART_SIZE;
		// The world-text vertex format is POSITION_COLOR_TEX_LIGHTMAP; every
		// element must be set or the BufferBuilder validation rejects the vertex.
		// The pipeline culls back faces, so the winding must match vanilla text
		// quads (GlyphRenderTypes): top-left, bottom-left, bottom-right, top-right.
		vertices.addVertex(matrix, x, yTop, z).setColor(255, 255, 255, alpha).setUv(sprite.u0(), sprite.v0()).setLight(packedLight);
		vertices.addVertex(matrix, x, endY, z).setColor(255, 255, 255, alpha).setUv(sprite.u0(), sprite.v1()).setLight(packedLight);
		vertices.addVertex(matrix, endX, endY, z).setColor(255, 255, 255, alpha).setUv(sprite.u1(), sprite.v1()).setLight(packedLight);
		vertices.addVertex(matrix, endX, yTop, z).setColor(255, 255, 255, alpha).setUv(sprite.u1(), sprite.v0()).setLight(packedLight);
	}
}
