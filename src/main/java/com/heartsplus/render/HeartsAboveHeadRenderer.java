package com.heartsplus.render;

import com.heartsplus.HeartsPlusConfig;
import com.heartsplus.HeartsPlusLog;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.texture.GuiAtlasManager;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.ResourceTexture;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.TextureContents;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
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
 * viewing angle. The anchor rides the vanilla name tag attachment point,
 * smoothed per player, so the bar sits right above the tag and glides with
 * it through pose changes. Sprites, pass structure and the animation all
 * mirror the vanilla HUD (InGameHud.renderHealthBar): containers deepest,
 * then absorption, then the blink overlay, with the health hearts on top —
 * the same painter order the HUD uses, flattened onto z layers. The pass set
 * mirrors EntityRenderer.renderLabelIfPresent: everyone gets the depth-tested
 * bright pass, players who are not sneaking additionally get a dimmed
 * half-transparent see-through copy when "show behind blocks" is on, and a
 * sneaking player gets only the bright pass (renderLabelIfPresent passes
 * !sneaking as the label's TextLayerType) — bright in the open, hidden
 * behind blocks. Geometry is drawn in passes per texture (containers,
 * health, blinking, absorption) because default-texture mode binds individual
 * sprites instead of the shared GUI atlas.</p>
 */
public final class HeartsAboveHeadRenderer {
	private static final Logger LOGGER = HeartsPlusLog.LOGGER;
	/** Default-pack textures that failed to load; those hearts fall back to atlas sprites. */
	private static final Set<Identifier> unavailableFileTextures = new HashSet<>();
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
	 * declares no explicit NAME_TAG attachment, so it falls back to the
	 * bounding box height of the standing dimensions — 0.6 × 1.8 — i.e. 1.8.
	 * The pose-specific dimensions (crouching 1.5, swimming/gliding 0.6)
	 * lower the same attachment point, which is what the bar now glides along.
	 */
	private static final float STANDING_ATTACHMENT_Y = 1.8F;
	/**
	 * Vanilla lifts the name tag text half a block above the attachment point
	 * (EntityRenderer.renderLabelIfPresent: {@code translate(..., y + 0.5, ...)}),
	 * so 2.3 is the tag's top edge for a standing player. The owner-tuned
	 * anchor 2.35 floats exactly {@code 2.35 − (1.8 + 0.5) = 0.05} above it.
	 */
	private static final float NAMETAG_TEXT_LIFT = 0.5F;
	/** Fixed gap between the name tag's top edge and the heart bar's bottom edge. */
	private static final float NAMETAG_GAP = HEART_ANCHOR_HEIGHT - STANDING_ATTACHMENT_Y - NAMETAG_TEXT_LIFT;
	private static final float PIXELS_PER_BLOCK = 0.025F;
	/** Vanilla name tag text is drawn with this much extra light emission (EntityRenderer.renderLabelIfPresent). */
	private static final int NAMETAG_EMISSION = 2;
	/** Alpha of the nametag's see-through copy: vanilla colour 0x80FFFFFF — half-transparent white. */
	private static final int SEE_THROUGH_ALPHA = 0x80;

	private HeartsAboveHeadRenderer() {
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
	 * frame needs them. Lazy registration from inside render produces
	 * textures that are never actually uploaded, which makes the hearts
	 * invisible, so warm-up is done from the first client tick. The built-in
	 * default pack is static — these textures never need reload invalidation,
	 * unlike the atlas sprites. Textures are registered with forced NEAREST
	 * filtering: they are 9x9 pixel art and must stay crisp at any scale.
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
					unavailableFileTextures.add(texture);
					LOGGER.warn("Default-pack heart texture {} is missing; falling back to atlas sprites", texture);
					continue;
				}
				try {
					textureManager.registerTexture(texture, new DefaultPackTexture(texture));
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
		GuiAtlasManager guiAtlasManager = MinecraftClient.getInstance().getGuiAtlasManager();
		HeartType family = HeartType.forStatus(health.heartsplus$isPoisoned(), health.heartsplus$isWithered(),
				health.heartsplus$isFrozen());
		// Withered players keep their black absorption hearts, exactly like the vanilla HUD.
		HeartType absorptionFamily = family == HeartType.WITHERED ? HeartType.WITHERED : HeartType.ABSORBING;
		// Lost-slot overlay of the damage window, in half-hearts; slot bounds
		// are resolved once per player per frame below. Vanilla also swaps the
		// container sprite to its blinking variant on every slot while any
		// window is live (InGameHud.renderHealthBar draws the container with
		// the blink flag) — the container pass does that via `blinking`, and
		// that flash is the whole heal pop, since acquired slots get no
		// blinking heart.
		int overlayFrom = health.heartsplus$getBlinkOverlayStart();
		int overlayTo = health.heartsplus$getBlinkOverlayEnd();

		matrices.push();
		// The bar rides the vanilla name tag attachment point (smoothed per
		// player in BlinkTracker), so it sits right above the tag and glides
		// with it through sneak/swim/fly poses. The state update always
		// precedes the render for a rendered state, so the value is fresh for
		// this frame.
		matrices.translate(0.0F, health.heartsplus$getHeartAnchorY(), 0.0F);
		// multiply(x, 0, 0, 0) around the origin equals a plain rotation; this
		// MatrixStack overload (the Yarn name of 26.x rotateAround) is the only
		// quaternion-rotation call shared by every supported game version.
		matrices.multiply(cameraRotation, 0.0F, 0.0F, 0.0F);
		float pixelScale = PIXELS_PER_BLOCK * (float) HeartsPlusConfig.getScale();
		matrices.scale(pixelScale, -pixelScale, pixelScale);
		matrices.translate(0.0F, -HeartsPlusConfig.getHeartOffset(), 0.0F);
		Matrix4f positionMatrix = matrices.peek().getPositionMatrix();

		// Passes are drawn lazily per family so empty passes (no blinking, no
		// absorption) cost nothing at all. Each family sits on its own z layer
		// in the HUD's painter order: containers deepest, then absorption, then
		// the blink overlay, with the health hearts on top — without the
		// offsets the depth test z-fights the quads apart. The painter order
		// doubles as the see-through submit order (HeartPass.renderOrder):
		// drawPhases emits every see-through layer before any bright pass.
		float zStep = HeartLayerSpacing.zStep(MathHelper.sqrt((float) state.squaredDistanceToCamera));
		final float containerZ = 0.0F;
		final float absorbingZ = zStep;
		final float familyBlinkingZ = 2 * zStep;
		final float familyZ = 3 * zStep;

		List<HeartPass> passes = HeartPass.passesFor(state.sneaking, state.invisible, HeartsPlusConfig.isShowBehindBlocks());
		List<FamilyDraw> barDraws = new ArrayList<>();
		int emissiveLight = LightmapTextureManager.applyEmission(light, NAMETAG_EMISSION);

		ResolvedSprite container = resolve(HeartType.CONTAINER, false, blinking, guiAtlasManager);
		barDraws.add(new FamilyDraw(container, (vertices, passLight, alpha) -> {
			for (int slot = 0; slot < layout.slots(); slot++) {
				emitHeart(vertices, positionMatrix, layout.x(slot), layout.yTop(slot), container, passLight, containerZ, alpha);
			}
		}));
		if (hasAbsorption(layout, false)) {
			ResolvedSprite absorbingFull = resolve(absorptionFamily, false, false, guiAtlasManager);
			barDraws.add(new FamilyDraw(absorbingFull, (vertices, passLight, alpha) -> {
				for (int slot = layout.healthContainers(); slot < layout.slots(); slot++) {
					if (layout.hasAbsorptionHeart(slot) && !layout.isAbsorptionHalf(slot)) {
						emitHeart(vertices, positionMatrix, layout.x(slot), layout.yTop(slot), absorbingFull, passLight, absorbingZ, alpha);
					}
				}
			}));
		}
		if (hasAbsorption(layout, true)) {
			ResolvedSprite absorbingHalf = resolve(absorptionFamily, true, false, guiAtlasManager);
			barDraws.add(new FamilyDraw(absorbingHalf, (vertices, passLight, alpha) -> {
				for (int slot = layout.healthContainers(); slot < layout.slots(); slot++) {
					if (layout.hasAbsorptionHeart(slot) && layout.isAbsorptionHalf(slot)) {
						emitHeart(vertices, positionMatrix, layout.x(slot), layout.yTop(slot), absorbingHalf, passLight, absorbingZ, alpha);
					}
				}
			}));
		}
		if (blinking && overlayFrom < overlayTo) {
			// The damage flash: vanilla draws the blinking sprites while the
			// window's square wave is on (InGameHud.renderHealthBar:
			// `blinking && q < health`, the lagging display copy — here the
			// lost-slot interval of the instant bar), visible only where no
			// normal heart covers them. The half variant only ever lands on
			// the range's top slot (`q + 1 == health`).
			int overlayFirstSlot = overlayFrom / 2;
			int overlayLastSlot = Math.min((overlayTo - 1) / 2, layout.slots() - 1);
			int overlayHalfSlot = overlayTo % 2 == 1 ? overlayLastSlot : -1;
			ResolvedSprite familyFullBlinking = resolve(family, false, true, guiAtlasManager);
			barDraws.add(new FamilyDraw(familyFullBlinking, (vertices, passLight, alpha) -> {
				for (int slot = overlayFirstSlot; slot <= overlayLastSlot; slot++) {
					if (slot != overlayHalfSlot) {
						emitHeart(vertices, positionMatrix, layout.x(slot), layout.yTop(slot), familyFullBlinking, passLight, familyBlinkingZ, alpha);
					}
				}
			}));
			if (overlayHalfSlot >= 0) {
				ResolvedSprite familyHalfBlinking = resolve(family, true, true, guiAtlasManager);
				barDraws.add(new FamilyDraw(familyHalfBlinking, (vertices, passLight, alpha) ->
						emitHeart(vertices, positionMatrix, layout.x(overlayHalfSlot), layout.yTop(overlayHalfSlot),
								familyHalfBlinking, passLight, familyBlinkingZ, alpha)));
			}
		}
		if (hasHealth(layout, false)) {
			ResolvedSprite familyFull = resolve(family, false, false, guiAtlasManager);
			barDraws.add(new FamilyDraw(familyFull, (vertices, passLight, alpha) -> {
				for (int slot = 0; slot < layout.slots(); slot++) {
					if (layout.hasHealthHeart(slot) && !layout.isHealthHalf(slot)) {
						emitHeart(vertices, positionMatrix, layout.x(slot), layout.yTop(slot), familyFull, passLight, familyZ, alpha);
					}
				}
			}));
		}
		if (hasHealth(layout, true)) {
			ResolvedSprite familyHalf = resolve(family, true, false, guiAtlasManager);
			barDraws.add(new FamilyDraw(familyHalf, (vertices, passLight, alpha) -> {
				for (int slot = 0; slot < layout.slots(); slot++) {
					if (layout.hasHealthHeart(slot) && layout.isHealthHalf(slot)) {
						emitHeart(vertices, positionMatrix, layout.x(slot), layout.yTop(slot), familyHalf, passLight, familyZ, alpha);
					}
				}
			}));
		}

		drawPhases(vertexConsumers, barDraws, passes, light, emissiveLight);

		matrices.pop();
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

	private static ResolvedSprite resolve(HeartType type, boolean half, boolean blinking, GuiAtlasManager guiAtlasManager) {
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

	/**
	 * One sprite family's geometry in HUD painter order, drawn once per
	 * selected {@link HeartPass} in {@link #drawPhases}.
	 */
	private record FamilyDraw(ResolvedSprite sprite, HeartEmitter renderer) {
	}

	/**
	 * Emits the bar's passes in the vanilla name tag's two phases: every
	 * dimmed see-through copy first, then every depth-tested bright pass —
	 * the order EntityRenderer.renderLabelIfPresent itself submits (its
	 * TextLayerType.SEE_THROUGH draw precedes the bright TextLayerType.NORMAL
	 * draw). All world-text layers run on the frame Immediate's fallback
	 * allocator (VertexConsumerProvider.Immediate draws the current layer as
	 * soon as a different one is requested), and text layers are never
	 * re-sorted, so the depth-blind see-through layers keep exactly this
	 * submission order — the HUD's painter order (containers, absorption,
	 * blinking, health) stays stable frame to frame, which is
	 * HeartPass.renderOrder's contract realised without a submit-order API.
	 * The bright passes order themselves by depth: the text pipeline
	 * depth-tests with LEQUAL and writes depth, so in direct sight the
	 * closest z layer always wins over anything drawn earlier.
	 */
	private static void drawPhases(VertexConsumerProvider vertexConsumers, List<FamilyDraw> barDraws,
			List<HeartPass> passes, int light, int emissiveLight) {
		if (passes.contains(HeartPass.SEE_THROUGH)) {
			for (FamilyDraw draw : barDraws) {
				draw.renderer().emit(vertexConsumers.getBuffer(RenderLayer.getTextSeeThrough(draw.sprite().texture())), light, SEE_THROUGH_ALPHA);
			}
		}
		if (passes.contains(HeartPass.NORMAL)) {
			for (FamilyDraw draw : barDraws) {
				draw.renderer().emit(vertexConsumers.getBuffer(RenderLayer.getText(draw.sprite().texture())), emissiveLight, 255);
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
	 * resource pack. {@link ResourceManager#getAllResources} lists every
	 * pack's copy top-down (the active pack first), and the bottom-most entry
	 * is always the built-in default pack — reading that entry pins the look
	 * to the unmodified default sprites even when the player stacks override
	 * packs on top. Forced NEAREST magnification and minification (no
	 * mipmaps): the 9x9 pixel art must stay crisp, while the metadata-less
	 * default can resolve to a linear sampler.
	 */
	private static final class DefaultPackTexture extends ResourceTexture {
		private DefaultPackTexture(Identifier location) {
			super(location);
		}

		@Override
		public TextureContents loadContents(ResourceManager resourceManager) throws IOException {
			List<Resource> stack = resourceManager.getAllResources(this.getId());
			// An empty stack should not happen for vanilla-owned sprites; fall
			// back to the ordinary lookup (topmost pack) instead of failing.
			InputStream stream = stack.isEmpty()
					? resourceManager.open(this.getId())
					: stack.getLast().getInputStream();
			try (stream) {
				return new TextureContents(NativeImage.read(stream), null);
			}
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
			ResolvedSprite sprite, int light, float z, int alpha) {
		float endX = x + HeartBarLayout.HEART_SIZE;
		float endY = yTop + HeartBarLayout.HEART_SIZE;
		// The world-text vertex format is POSITION_TEX_LIGHTMAP_COLOR; every
		// element must be set or the BufferBuilder validation rejects the vertex.
		// The pipeline culls back faces, so the winding must match vanilla text
		// quads (BakedQuad of the font renderer): top-left, bottom-left,
		// bottom-right, top-right.
		vertices.vertex(matrix, x, yTop, z).color(255, 255, 255, alpha).texture(sprite.u0(), sprite.v0()).light(light);
		vertices.vertex(matrix, x, endY, z).color(255, 255, 255, alpha).texture(sprite.u0(), sprite.v1()).light(light);
		vertices.vertex(matrix, endX, endY, z).color(255, 255, 255, alpha).texture(sprite.u1(), sprite.v1()).light(light);
		vertices.vertex(matrix, endX, yTop, z).color(255, 255, 255, alpha).texture(sprite.u1(), sprite.v0()).light(light);
	}
}
