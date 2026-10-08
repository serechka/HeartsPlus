package com.heartsplus;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

/**
 * Settings screen for the 1.21.x line, the bundled fallback for a classpath
 * without Cloth Config (the main screen since 0.4.9 is the Cloth one).
 * Toggles apply immediately; sliders write their value live but only persist
 * when the screen closes. Every setting of the Cloth screen exists here too,
 * in one flat list.
 */
public class HeartsPlusConfigScreen extends Screen {
	private static final int WIDGET_WIDTH = 150;
	private static final int WIDGET_HEIGHT = 20;
	private static final int ROW_STEP = 24;

	private final Screen parent;

	public HeartsPlusConfigScreen(Screen parent) {
		super(Text.translatable("heartsplus.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		int left = centerX - WIDGET_WIDTH - 10;
		int right = centerX + 10;
		int y = this.height / 2 - 4 * ROW_STEP;

		addToggle(left, y, "heartsplus.config.enabled", HeartsPlusConfig.isEnabled(), HeartsPlusConfig::setEnabled);
		addToggle(right, y, "heartsplus.config.show_own", HeartsPlusConfig.isShowOwnHearts(), HeartsPlusConfig::setShowOwnHearts);
		addToggle(left, y + ROW_STEP, "heartsplus.config.show_invisible", HeartsPlusConfig.isShowInvisiblePlayers(),
				HeartsPlusConfig::setShowInvisiblePlayers, "heartsplus.config.show_invisible.tooltip");
		addToggle(right, y + ROW_STEP, "heartsplus.config.animation", HeartsPlusConfig.isBlinkAnimationEnabled(),
				HeartsPlusConfig::setBlinkAnimation);
		addDrawableChild(CyclingButtonWidget.onOffBuilder(
				Text.translatable("option.heartsplus.textures.vanilla"),
				Text.translatable("option.heartsplus.textures.current"))
				.initially(HeartsPlusConfig.isVanillaTextures())
				.build(left, y + 2 * ROW_STEP, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("heartsplus.config.textures"),
						(button, value) -> HeartsPlusConfig.setVanillaTextures(value)));
		addDrawableChild(slider(right, y + 2 * ROW_STEP, HeartsPlusConfig.MIN_FOLLOW_SMOOTHNESS, HeartsPlusConfig.MAX_FOLLOW_SMOOTHNESS,
				HeartsPlusConfig.getFollowSmoothness(), v -> HeartsPlusConfig.setFollowSmoothnessSilently((int) Math.round(v)),
				v -> percentLabel("heartsplus.config.follow_smoothing", (int) Math.round(v), false)));

		addDrawableChild(slider(left, y + 3 * ROW_STEP, HeartsPlusConfig.MIN_SCALE, HeartsPlusConfig.MAX_SCALE,
				HeartsPlusConfig.getScale(), HeartsPlusConfig::setScaleSilently,
				v -> Text.translatable("heartsplus.config.scale", String.format(Locale.ROOT, "%.2f", v))));
		addDrawableChild(slider(right, y + 3 * ROW_STEP, HeartsPlusConfig.MIN_RENDER_DISTANCE, HeartsPlusConfig.MAX_RENDER_DISTANCE,
				HeartsPlusConfig.getRenderDistance(), HeartsPlusConfig::setRenderDistanceSilently,
				v -> Text.translatable("heartsplus.config.render_distance", String.format(Locale.ROOT, "%.0f", v))));
		addDrawableChild(slider(left, y + 4 * ROW_STEP, HeartsPlusConfig.MIN_WALL_OPACITY, HeartsPlusConfig.MAX_WALL_OPACITY,
				HeartsPlusConfig.getWallOpacity(), v -> HeartsPlusConfig.setWallOpacitySilently((int) Math.round(v)),
				v -> percentLabel("heartsplus.config.wall_opacity", (int) Math.round(v), true)));
		addDrawableChild(slider(right, y + 4 * ROW_STEP, HeartsPlusConfig.MIN_HEART_OFFSET, HeartsPlusConfig.MAX_HEART_OFFSET,
				HeartsPlusConfig.getOffsetStanding(), v -> HeartsPlusConfig.setOffsetStandingSilently((int) Math.round(v)),
				v -> offsetLabel("heartsplus.config.height_standing", (int) Math.round(v))));
		addDrawableChild(slider(left, y + 5 * ROW_STEP, HeartsPlusConfig.MIN_HEART_OFFSET, HeartsPlusConfig.MAX_HEART_OFFSET,
				HeartsPlusConfig.getOffsetSneaking(), v -> HeartsPlusConfig.setOffsetSneakingSilently((int) Math.round(v)),
				v -> offsetLabel("heartsplus.config.height_sneaking", (int) Math.round(v))));
		addDrawableChild(slider(right, y + 5 * ROW_STEP, HeartsPlusConfig.MIN_HEART_OFFSET, HeartsPlusConfig.MAX_HEART_OFFSET,
				HeartsPlusConfig.getOffsetSwimming(), v -> HeartsPlusConfig.setOffsetSwimmingSilently((int) Math.round(v)),
				v -> offsetLabel("heartsplus.config.height_swimming", (int) Math.round(v))));
		addDrawableChild(slider(left, y + 6 * ROW_STEP, HeartsPlusConfig.MIN_HEART_OFFSET, HeartsPlusConfig.MAX_HEART_OFFSET,
				HeartsPlusConfig.getOffsetFlying(), v -> HeartsPlusConfig.setOffsetFlyingSilently((int) Math.round(v)),
				v -> offsetLabel("heartsplus.config.height_flying", (int) Math.round(v))));

		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> close())
				.dimensions(centerX - WIDGET_WIDTH - 5, y + 7 * ROW_STEP + 8, WIDGET_WIDTH, WIDGET_HEIGHT).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("heartsplus.config.reset"), button -> resetAndRebuild())
				.dimensions(centerX + 5, y + 7 * ROW_STEP + 8, WIDGET_WIDTH, WIDGET_HEIGHT).build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 20, 0xFFFFFF);
	}

	@Override
	public void close() {
		HeartsPlusConfig.save();
		this.client.setScreen(this.parent);
	}

	private void resetAndRebuild() {
		HeartsPlusConfig.reset();
		this.client.setScreen(new HeartsPlusConfigScreen(this.parent));
	}

	private void addToggle(int x, int y, String key, boolean initial, Consumer<Boolean> setter) {
		addToggle(x, y, key, initial, setter, null);
	}

	private void addToggle(int x, int y, String key, boolean initial, Consumer<Boolean> setter, String tooltipKey) {
		CyclingButtonWidget<Boolean> button = CyclingButtonWidget.onOffBuilder(initial)
				.build(x, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable(key), (btn, value) -> setter.accept(value));
		if (tooltipKey != null) {
			button.setTooltip(Tooltip.of(Text.translatable(tooltipKey)));
		}
		addDrawableChild(button);
	}

	private static SliderWidget slider(int x, int y, double min, double max, double initial,
			DoubleConsumer setter, DoubleFunction<Text> message) {
		return new ConfigSlider(x, y, WIDGET_WIDTH, WIDGET_HEIGHT, min, max, initial, setter, message);
	}

	/** "Label: N" with an optional percent sign; zero reads as OFF. */
	private static Text percentLabel(String labelKey, int value, boolean withPercentSign) {
		if (value == 0) {
			return Text.translatable("options.off");
		}
		return Text.translatable(labelKey).copy().append(": " + value + (withPercentSign ? "%" : ""));
	}

	/** "Label: N" for the per-pose bar lift sliders. */
	private static Text offsetLabel(String labelKey, int value) {
		return Text.translatable(labelKey).copy().append(": " + value);
	}

	private static final class ConfigSlider extends SliderWidget {
		private final double min;
		private final double max;
		private final DoubleConsumer setter;
		private final DoubleFunction<Text> message;

		ConfigSlider(int x, int y, int width, int height, double min, double max, double initial,
				DoubleConsumer setter, DoubleFunction<Text> message) {
			super(x, y, width, height, Text.empty(), Math.clamp((initial - min) / (max - min), 0.0, 1.0));
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
