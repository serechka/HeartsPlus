package com.heartsplus.render;

import com.heartsplus.HeartsPlusConfig;
import com.heartsplus.HeartsPlusLog;
import com.heartsplus.mixin.EntityRenderDispatcherMixin;
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
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.texture.GuiAtlasManager;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.ResourceTexture;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.slf4j.Logger;

/**
 * Draws a row of vanilla heart sprites above a player's head (classic
 * 1.21.0/1.21.1 pipeline). Called at the end of LivingEntityRenderer.render,
 * so the incoming MatrixStack is positioned at the entity origin and quads
 * can be emitted straight into the frame's VertexConsumerProvider.
 *
 * <p>The transform mirrors the vanilla name tag recipe from
 * EntityRenderer.renderLabelIfPresent: translate to the anchor, rotate by the
 * camera, scale(0.025 * scale, -0.025 * scale, 0.025 * scale). Hearts are
 * drawn with the world-text render layers, which — like name tags — are
 * shaded only by the lightmap, so they look identical from every viewing
 * angle. The anchor height is a fixed constant so the bar never jumps with
 * pose changes. Sprites, pass structure and the animation all mirror the
 * vanilla HUD (InGameHud.renderHealthBar): containers deepest, then
 * absorption, then the blink overlay, with the health hearts on top — the
 * same painter order the HUD uses, flattened onto z layers. The pass set
 * mirrors EntityRenderer.renderLabelIfPresent: everyone gets the depth-tested
 * bright pass, players who are not sneaking additionally get a dimmed
 * half-transparent see-through copy when "show behind blocks" is on, and a
 * sneaking player gets only the bright pass (the label gates its see-through
 * copy on !isSneaking) — bright in the open, hidden behind blocks. Geometry
 * is submitted in passes per texture (containers, absorption, blinking,
 * health) because default-texture mode binds individual files while pack
 * mode draws sprite regions of the shared GUI atlas.</p>
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
	 * tunes up and down symmetrically. Deliberately not derived from the
	 * name tag attachment: the nameplate position sags in the sneak pose with
	 * interpolation lag, which made the hearts jump.
	 */
	private static final float HEART_ANCHOR_HEIGHT = 2.35F;
	private static final float PIXELS_PER_BLOCK = 0.025F;
	/** Alpha of the name tag's see-through copy: vanilla colour 0x20FFFFFF (EntityRenderer.renderLabelIfPresent). */
	private static final int SEE_THROUGH_ALPHA = 0x20;

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

	public static void render(PlayerEntity player, EntityRenderDispatcher dispatcher, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light) {
		try {
			renderHearts(player, dispatcher, matrices, vertexConsumers, light);
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

	private static void renderHearts(PlayerEntity player, EntityRenderDispatcher dispatcher, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light) {
		if (!HeartsPlusConfig.isEnabled() || player.isSpectator()) {
			skipOnce("mod disabled or player is a spectator");
			return;
		}
		if (player == MinecraftClient.getInstance().player && !HeartsPlusConfig.isShowOwnHearts()) {
			skipOnce("own player hidden (enable 'Show above yourself')");
			return;
		}
		double maxDistance = HeartsPlusConfig.getRenderDistance();
		if (dispatcher.getSquaredDistanceToCamera(player) > maxDistance * maxDistance) {
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
		if (!((EntityRenderDispatcherMixin) dispatcher).heartsplus$renderShadows()) {
			// Embedded entity previews (the inventory screen model) render
			// through the same code path with shadows disabled; the heart bar
			// is a world overlay and must not appear there.
			skipOnce("embedded entity preview (shadows disabled)");
			return;
		}

		float health = player.getHealth();
		// The animation state is fed before the pass selection below, so the
		// vanilla animation keeps ticking for every rendered player.
		HeartAnimationState animationState = PlayerBlinkTracker.stateFor(player.getUuid());
		animationState.tick(MathHelper.ceil(health), player.age, player.timeUntilRegen > 0,
				HeartsPlusConfig.isBlinkAnimationEnabled());

		// The bar always shows the current health instantly (no vanilla
		// renderHealthValue lag); the blink window from the animation state
		// only adds the flash overlays. With the animation off the state arms
		// no windows, so `blinking` stays false and the blinking sprites are
		// never resolved (the resolve calls sit inside the `blinking` gate).
		boolean blinking = animationState.isBlinking();
		HeartBarLayout layout = HeartBarLayout.of(health, player.getMaxHealth(), player.getAbsorptionAmount());
		if (layout.slots() <= 0) {
			// Degenerate health values — nothing to draw, skip all geometry work.
			return;
		}
		GuiAtlasManager atlasManager = MinecraftClient.getInstance().getGuiAtlasManager();
		HeartType family = HeartType.forStatus(player.hasStatusEffect(StatusEffects.POISON),
				player.hasStatusEffect(StatusEffects.WITHER), player.isFrozen());
		// Withered players keep their black absorption hearts, exactly like the vanilla HUD.
		HeartType absorptionFamily = family == HeartType.WITHERED ? HeartType.WITHERED : HeartType.ABSORBING;
		// Lost-slot overlay of the damage window, in half-hearts; slot bounds
		// are resolved once per player per frame below. Vanilla also swaps the
		// container sprite to its blinking variant on every slot while any
		// window is live (InGameHud.renderHealthBar draws the container with
		// the blink flag) — the container pass does that via `blinking`, and
		// that flash is the whole heal pop, since acquired slots get no
		// blinking heart.
		int overlayFrom = animationState.blinkOverlayStart();
		int overlayTo = animationState.blinkOverlayEnd();

		matrices.push();
		matrices.translate(0.0F, HEART_ANCHOR_HEIGHT, 0.0F);
		// The vanilla name tag recipe: billboard the bar towards the camera.
		matrices.multiply(dispatcher.getRotation());
		float pixelScale = PIXELS_PER_BLOCK * (float) HeartsPlusConfig.getScale();
		matrices.scale(pixelScale, -pixelScale, pixelScale);
		matrices.translate(0.0F, -HeartsPlusConfig.getHeartOffset(), 0.0F);

		// Sprites are resolved lazily per pass so empty passes (no blinking, no
		// absorption) cost nothing at all. Each family sits on its own z layer
		// in the HUD's painter order: containers deepest, then absorption, then
		// the blink overlay, with the health hearts on top — without the
		// offsets the depth test z-fights the quads apart. The layer index
		// doubles as the see-through submit order (HeartPass.renderOrder).
		float zStep = HeartLayerSpacing.zStep(MathHelper.sqrt((float) dispatcher.getSquaredDistanceToCamera(player)));
		final float containerZ = 0.0F;
		final float absorbingZ = zStep;
		final float familyBlinkingZ = 2 * zStep;
		final float familyZ = 3 * zStep;

		List<HeartSubmit> submits = new ArrayList<>();
		ResolvedSprite container = resolve(HeartType.CONTAINER, false, blinking, atlasManager);
		submits.add(new HeartSubmit(container, 0, (vertices, matrix, l, alpha) -> {
			for (int slot = 0; slot < layout.slots(); slot++) {
				emitHeart(vertices, matrix, layout.x(slot), layout.yTop(slot), container, l, containerZ, alpha);
			}
		}));
		if (hasAbsorption(layout, false)) {
			ResolvedSprite absorbingFull = resolve(absorptionFamily, false, false, atlasManager);
			submits.add(new HeartSubmit(absorbingFull, 1, (vertices, matrix, l, alpha) -> {
				for (int slot = layout.healthContainers(); slot < layout.slots(); slot++) {
					if (layout.hasAbsorptionHeart(slot) && !layout.isAbsorptionHalf(slot)) {
						emitHeart(vertices, matrix, layout.x(slot), layout.yTop(slot), absorbingFull, l, absorbingZ, alpha);
					}
				}
			}));
		}
		if (hasAbsorption(layout, true)) {
			ResolvedSprite absorbingHalf = resolve(absorptionFamily, true, false, atlasManager);
			submits.add(new HeartSubmit(absorbingHalf, 1, (vertices, matrix, l, alpha) -> {
				for (int slot = layout.healthContainers(); slot < layout.slots(); slot++) {
					if (layout.hasAbsorptionHeart(slot) && layout.isAbsorptionHalf(slot)) {
						emitHeart(vertices, matrix, layout.x(slot), layout.yTop(slot), absorbingHalf, l, absorbingZ, alpha);
					}
				}
			}));
		}
		if (blinking && overlayFrom < overlayTo) {
			// The damage flash: vanilla draws the blinking sprites while the
			// window's square wave is on (InGameHud.renderHealthBar: `blink &&
			// halves < lastHealth`), visible only where no normal heart covers
			// them — the lost slots this interval enumerates. The half variant
			// only ever lands on the range's top slot (`halves + 1 ==
			// lastHealth`).
			int overlayFirstSlot = overlayFrom / 2;
			int overlayLastSlot = Math.min((overlayTo - 1) / 2, layout.slots() - 1);
			int overlayHalfSlot = overlayTo % 2 == 1 ? overlayLastSlot : -1;
			ResolvedSprite familyFullBlinking = resolve(family, false, true, atlasManager);
			submits.add(new HeartSubmit(familyFullBlinking, 2, (vertices, matrix, l, alpha) -> {
				for (int slot = overlayFirstSlot; slot <= overlayLastSlot; slot++) {
					if (slot != overlayHalfSlot) {
						emitHeart(vertices, matrix, layout.x(slot), layout.yTop(slot), familyFullBlinking, l, familyBlinkingZ, alpha);
					}
				}
			}));
			if (overlayHalfSlot >= 0) {
				ResolvedSprite familyHalfBlinking = resolve(family, true, true, atlasManager);
				submits.add(new HeartSubmit(familyHalfBlinking, 2, (vertices, matrix, l, alpha) ->
						emitHeart(vertices, matrix, layout.x(overlayHalfSlot), layout.yTop(overlayHalfSlot),
								familyHalfBlinking, l, familyBlinkingZ, alpha)));
			}
		}
		if (hasHealth(layout, false)) {
			ResolvedSprite familyFull = resolve(family, false, false, atlasManager);
			submits.add(new HeartSubmit(familyFull, 3, (vertices, matrix, l, alpha) -> {
				for (int slot = 0; slot < layout.slots(); slot++) {
					if (layout.hasHealthHeart(slot) && !layout.isHealthHalf(slot)) {
						emitHeart(vertices, matrix, layout.x(slot), layout.yTop(slot), familyFull, l, familyZ, alpha);
					}
				}
			}));
		}
		if (hasHealth(layout, true)) {
			ResolvedSprite familyHalf = resolve(family, true, false, atlasManager);
			submits.add(new HeartSubmit(familyHalf, 3, (vertices, matrix, l, alpha) -> {
				for (int slot = 0; slot < layout.slots(); slot++) {
					if (layout.hasHealthHeart(slot) && layout.isHealthHalf(slot)) {
						emitHeart(vertices, matrix, layout.x(slot), layout.yTop(slot), familyHalf, l, familyZ, alpha);
					}
				}
			}));
		}

		// HeartPass.renderOrder pins every see-through layer (orders 0-3, the
		// list order above) below the bright pass (order 4). This era has no
		// submit orders to route the passes to: the text render layers are not
		// in the seeded layerBuffers of the entity VertexConsumerProvider
		// (BufferBuilderStorage), so Immediate.getBuffer falls back to the
		// shared allocator and draws the previous batch the moment a different
		// layer is requested — the draw order is the submission order. Two
		// sweeps therefore realize the orders: all see-through layers first,
		// the bright pass last. Vanilla name tags get the same order by
		// submitting their see-through copy before the bright text
		// (EntityRenderer.renderLabelIfPresent); independently, the world pass
		// quad-sorts every batch back-to-front (BuiltBuffer.sortQuads on the
		// translucent text layers, VertexSorter.BY_DISTANCE), which also
		// restores the painter order inside each batch.
		List<HeartPass> passes = HeartPass.passesFor(player.isSneaking(), HeartsPlusConfig.isShowBehindBlocks());
		if (passes.contains(HeartPass.SEE_THROUGH)) {
			for (HeartSubmit submit : submits) {
				submitPass(vertexConsumers, matrices, submit.sprite(), light, HeartPass.SEE_THROUGH, submit.geometry());
			}
		}
		if (passes.contains(HeartPass.NORMAL)) {
			for (HeartSubmit submit : submits) {
				submitPass(vertexConsumers, matrices, submit.sprite(), light, HeartPass.NORMAL, submit.geometry());
			}
		}

		matrices.pop();
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
			LOGGER.info("Hearts above a player were skipped: {} [enabled={}, showOwnHearts={}, showInvisible={}, showBehindBlocks={}, vanillaTextures={}, scale={}, renderDistance={}]",
					reason, HeartsPlusConfig.isEnabled(), HeartsPlusConfig.isShowOwnHearts(),
					HeartsPlusConfig.isShowInvisiblePlayers(), HeartsPlusConfig.isShowBehindBlocks(),
					HeartsPlusConfig.isVanillaTextures(), HeartsPlusConfig.getScale(), HeartsPlusConfig.getRenderDistance());
		}
	}

	private static boolean hasVisibleArmour(PlayerEntity player) {
		return !player.getEquippedStack(EquipmentSlot.HEAD).isEmpty()
				|| !player.getEquippedStack(EquipmentSlot.CHEST).isEmpty()
				|| !player.getEquippedStack(EquipmentSlot.LEGS).isEmpty()
				|| !player.getEquippedStack(EquipmentSlot.FEET).isEmpty();
	}

	private static ResolvedSprite resolve(HeartType type, boolean half, boolean blinking,
			GuiAtlasManager atlasManager) {
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
			Sprite sprite = atlasManager.getSprite(spriteId);
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
	 * Submits one sprite's geometry as the single vanilla name tag pass
	 * selected by {@code pass} (EntityRenderer.renderLabelIfPresent): the
	 * depth-tested bright one with the plain light coords, or the see-through
	 * copy without depth test, dimmed to the name tag's half-transparent
	 * white (0x20FFFFFF) and lit by the same plain light coords.
	 *
	 * <p>The passes must draw in a fixed order — see-through layers below the
	 * bright pass — which this era gets from the submission sweeps in
	 * {@link #renderHearts}: Immediate.getBuffer falls back to the shared
	 * allocator for the text layers (they are absent from the seeded
	 * layerBuffers) and immediately draws the previous batch when a different
	 * layer is requested, so the draw order is the submission order.</p>
	 */
	private static void submitPass(VertexConsumerProvider vertexConsumers, MatrixStack matrices,
			ResolvedSprite sprite, int light, HeartPass pass, PassGeometry geometry) {
		Matrix4f matrix = matrices.peek().getPositionMatrix();
		if (pass == HeartPass.SEE_THROUGH) {
			VertexConsumer seeThrough = vertexConsumers.getBuffer(RenderLayer.getTextSeeThrough(sprite.texture()));
			geometry.emit(seeThrough, matrix, light, SEE_THROUGH_ALPHA);
		} else {
			VertexConsumer vertices = vertexConsumers.getBuffer(RenderLayer.getText(sprite.texture()));
			geometry.emit(vertices, matrix, light, 255);
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
	 * resource pack. {@link ResourceManager#getAllResources} lists every
	 * pack's copy top-down (the active pack first), and the bottom-most entry
	 * is always the built-in default pack — reading that entry pins the look
	 * to the unmodified default sprites even when the player stacks override
	 * packs on top. Registered with NEAREST magnification and minification
	 * (no mipmaps): the 9x9 pixel art must stay crisp, while vanilla leaves
	 * freshly loaded textures on the linear GL defaults.
	 */
	private static final class DefaultPackTexture extends ResourceTexture {
		private DefaultPackTexture(Identifier location) {
			super(location);
		}

		@Override
		protected TextureData loadTextureData(ResourceManager resourceManager) {
			try {
				List<Resource> stack = resourceManager.getAllResources(this.location);
				// An empty stack should not happen for vanilla-owned sprites;
				// fall back to the ordinary lookup (topmost pack) instead of
				// failing.
				Resource bottom = stack.isEmpty()
						? resourceManager.getResourceOrThrow(this.location)
						: stack.getLast();
				// Heart sprite files carry no .mcmeta metadata.
				try (InputStream stream = bottom.getInputStream()) {
					return new TextureData(null, NativeImage.read(stream));
				}
			} catch (IOException e) {
				return new TextureData(e);
			}
		}

		@Override
		public void load(ResourceManager resourceManager) throws IOException {
			super.load(resourceManager);
			this.setFilter(false, false);
		}
	}

	private record ResolvedSprite(Identifier texture, float u0, float v0, float u1, float v1) {
	}

	private static void emitHeart(VertexConsumer vertices, Matrix4f matrix, float x, float yTop,
			ResolvedSprite sprite, int light, float z, int alpha) {
		float endX = x + HeartBarLayout.HEART_SIZE;
		float endY = yTop + HeartBarLayout.HEART_SIZE;
		// The world-text vertex format is POSITION_COLOR_TEXTURE_LIGHT; every
		// element must be set or the BufferBuilder validation rejects the vertex.
		// The pipeline culls back faces, so the winding must match vanilla text
		// quads (GlyphRenderer.draw): top-left, bottom-left, bottom-right, top-right.
		vertices.vertex(matrix, x, yTop, z).color(255, 255, 255, alpha).texture(sprite.u0(), sprite.v0()).light(light);
		vertices.vertex(matrix, x, endY, z).color(255, 255, 255, alpha).texture(sprite.u0(), sprite.v1()).light(light);
		vertices.vertex(matrix, endX, endY, z).color(255, 255, 255, alpha).texture(sprite.u1(), sprite.v1()).light(light);
		vertices.vertex(matrix, endX, yTop, z).color(255, 255, 255, alpha).texture(sprite.u1(), sprite.v0()).light(light);
	}
}
