package com.heartsplus;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Bundled flat settings screen for the 1.21.x NeoForge line, the fallback
 * for a classpath without Cloth Config (the main screen since 0.4.9 is the
 * Cloth one). Toggles apply immediately; sliders write their value live but
 * only persist when the screen closes. Every setting of the Cloth screen
 * exists here too, in one flat list.
 */
public class HeartsPlusConfigScreen extends Screen {
	private static final int WIDGET_WIDTH = 150;
	private static final int WIDGET_HEIGHT = 20;
	private static final int ROW_STEP = 24;

	private final Screen parent;

	public HeartsPlusConfigScreen(Screen parent) {
		super(Component.translatable("heartsplus.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		int left = centerX - WIDGET_WIDTH - 10;
		int right = centerX + 10;
		int y = this.height / 2 - 5 * ROW_STEP;

		addToggle(left, y, "heartsplus.config.enabled", HeartsPlusConfig.isEnabled(), HeartsPlusConfig::setEnabled);
		addToggle(right, y, "heartsplus.config.show_own", HeartsPlusConfig.isShowOwnHearts(), HeartsPlusConfig::setShowOwnHearts);
		addToggle(left, y + ROW_STEP, "heartsplus.config.show_invisible", HeartsPlusConfig.isShowInvisiblePlayers(),
				HeartsPlusConfig::setShowInvisiblePlayers, "heartsplus.config.show_invisible.tooltip");
		addToggle(right, y + ROW_STEP, "heartsplus.config.animation", HeartsPlusConfig.isBlinkAnimationEnabled(),
				HeartsPlusConfig::setBlinkAnimation);
		addRenderableWidget(CycleButton.builder(
						(Boolean value) -> value
								? Component.translatable("option.heartsplus.textures.vanilla")
								: Component.translatable("option.heartsplus.textures.current"))
				.withInitialValue(HeartsPlusConfig.isVanillaTextures())
				.create(left, y + 2 * ROW_STEP, WIDGET_WIDTH, WIDGET_HEIGHT, Component.translatable("heartsplus.config.textures"),
						(button, value) -> HeartsPlusConfig.setVanillaTextures(value)));
		addRenderableWidget(slider(right, y + 2 * ROW_STEP, HeartsPlusConfig.MIN_FOLLOW_SMOOTHNESS, HeartsPlusConfig.MAX_FOLLOW_SMOOTHNESS,
				HeartsPlusConfig.getFollowSmoothness(),
				v -> HeartsPlusConfig.setFollowSmoothnessSilently((int) Math.round(v)),
				v -> percentLabel("heartsplus.config.follow_smoothing", (int) Math.round(v), false)));

		addRenderableWidget(slider(left, y + 3 * ROW_STEP, HeartsPlusConfig.MIN_SCALE, HeartsPlusConfig.MAX_SCALE,
				HeartsPlusConfig.getScale(), HeartsPlusConfig::setScaleSilently,
				v -> Component.translatable("heartsplus.config.scale", String.format(Locale.ROOT, "%.2f", v))));
		addRenderableWidget(slider(right, y + 3 * ROW_STEP, HeartsPlusConfig.MIN_RENDER_DISTANCE, HeartsPlusConfig.MAX_RENDER_DISTANCE,
				HeartsPlusConfig.getRenderDistance(), HeartsPlusConfig::setRenderDistanceSilently,
				v -> Component.translatable("heartsplus.config.render_distance", String.format(Locale.ROOT, "%.0f", v))));
		addRenderableWidget(slider(left, y + 4 * ROW_STEP, HeartsPlusConfig.MIN_WALL_OPACITY, HeartsPlusConfig.MAX_WALL_OPACITY,
				HeartsPlusConfig.getWallOpacity(),
				v -> HeartsPlusConfig.setWallOpacitySilently((int) Math.round(v)),
				v -> percentLabel("heartsplus.config.wall_opacity", (int) Math.round(v), true)));
		addRenderableWidget(slider(right, y + 4 * ROW_STEP, HeartsPlusConfig.MIN_HEART_OFFSET, HeartsPlusConfig.MAX_HEART_OFFSET,
				HeartsPlusConfig.getOffsetStanding(),
				v -> HeartsPlusConfig.setOffsetStandingSilently((int) Math.round(v)),
				v -> offsetLabel("heartsplus.config.height_standing", (int) Math.round(v))));

		addRenderableWidget(slider(left, y + 5 * ROW_STEP, HeartsPlusConfig.MIN_HEART_OFFSET, HeartsPlusConfig.MAX_HEART_OFFSET,
				HeartsPlusConfig.getOffsetSneaking(),
				v -> HeartsPlusConfig.setOffsetSneakingSilently((int) Math.round(v)),
				v -> offsetLabel("heartsplus.config.height_sneaking", (int) Math.round(v))));
		addRenderableWidget(slider(right, y + 5 * ROW_STEP, HeartsPlusConfig.MIN_HEART_OFFSET, HeartsPlusConfig.MAX_HEART_OFFSET,
				HeartsPlusConfig.getOffsetSwimming(),
				v -> HeartsPlusConfig.setOffsetSwimmingSilently((int) Math.round(v)),
				v -> offsetLabel("heartsplus.config.height_swimming", (int) Math.round(v))));
		addRenderableWidget(slider(left, y + 6 * ROW_STEP, HeartsPlusConfig.MIN_HEART_OFFSET, HeartsPlusConfig.MAX_HEART_OFFSET,
				HeartsPlusConfig.getOffsetFlying(),
				v -> HeartsPlusConfig.setOffsetFlyingSilently((int) Math.round(v)),
				v -> offsetLabel("heartsplus.config.height_flying", (int) Math.round(v))));

		addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
				.bounds(centerX - WIDGET_WIDTH - 5, y + 7 * ROW_STEP + 8, WIDGET_WIDTH, WIDGET_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.translatable("heartsplus.config.reset"), button -> resetAndRebuild())
				.bounds(centerX + 5, y + 7 * ROW_STEP + 8, WIDGET_WIDTH, WIDGET_HEIGHT).build());
	}

	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
	}

	@Override
	public void onClose() {
		HeartsPlusConfig.save();
		this.minecraft.setScreen(this.parent);
	}

	private void resetAndRebuild() {
		HeartsPlusConfig.reset();
		this.minecraft.setScreen(new HeartsPlusConfigScreen(this.parent));
	}

	private void addToggle(int x, int y, String key, boolean initial, Consumer<Boolean> setter) {
		addToggle(x, y, key, initial, setter, null);
	}

	private void addToggle(int x, int y, String key, boolean initial, Consumer<Boolean> setter, String tooltipKey) {
		CycleButton<Boolean> button = CycleButton.onOffBuilder(initial)
				.create(x, y, WIDGET_WIDTH, WIDGET_HEIGHT, Component.translatable(key), (btn, value) -> setter.accept(value));
		if (tooltipKey != null) {
			button.setTooltip(Tooltip.create(Component.translatable(tooltipKey)));
		}
		addRenderableWidget(button);
	}

	private static AbstractSliderButton slider(int x, int y, double min, double max, double initial,
			DoubleConsumer setter, DoubleFunction<Component> message) {
		return new ConfigSlider(x, y, WIDGET_WIDTH, WIDGET_HEIGHT, min, max, initial, setter, message);
	}

	private static Component percentLabel(String labelKey, int value, boolean withPercentSign) {
		if (value == 0) {
			return Component.translatable("options.off");
		}
		return Component.translatable(labelKey).copy().append(": " + value + (withPercentSign ? "%" : ""));
	}

	private static Component offsetLabel(String labelKey, int value) {
		return Component.translatable(labelKey).copy().append(": " + value);
	}

	private static final class ConfigSlider extends AbstractSliderButton {
		private final double min;
		private final double max;
		private final DoubleConsumer setter;
		private final DoubleFunction<Component> message;

		ConfigSlider(int x, int y, int width, int height, double min, double max, double initial,
				DoubleConsumer setter, DoubleFunction<Component> message) {
			super(x, y, width, height, Component.empty(), Math.clamp((initial - min) / (max - min), 0.0, 1.0));
			this.min = min;
			this.max = max;
			this.setter = setter;
			this.message = message;
			this.updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(this.message.apply(this.min + this.value * (this.max - this.min)));
		}

		@Override
		protected void applyValue() {
			this.setter.accept(this.min + this.value * (this.max - this.min));
		}
	}
}
