package com.heartsplus;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Bundled flat settings screen, the fallback for a classpath without Cloth
 * Config (the main screen since 0.4.9 is the Cloth one). Toggles apply
 * immediately; sliders write their value live but only persist when the
 * screen closes. Every setting of the Cloth screen exists here too, in one
 * flat list.
 */
public class HeartsPlusConfigScreen extends Screen {
	private static final int WIDGET_WIDTH = 155;
	private static final int WIDGET_HEIGHT = 20;

	private final Screen parent;
	private final LinearLayout rootLayout = LinearLayout.vertical();
	private final GridLayout grid = new GridLayout(0, 0);

	public HeartsPlusConfigScreen(Screen parent) {
		super(Component.translatable("heartsplus.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.rootLayout.defaultCellSetting().alignHorizontallyCenter().padding(4);
		this.rootLayout.addChild(new StringWidget(this.title, this.font));

		this.grid.rowSpacing(6).columnSpacing(10);
		addToggle(0, 0, "heartsplus.config.enabled", HeartsPlusConfig.isEnabled(), HeartsPlusConfig::setEnabled);
		addToggle(0, 1, "heartsplus.config.show_own", HeartsPlusConfig.isShowOwnHearts(), HeartsPlusConfig::setShowOwnHearts);
		addToggle(1, 0, "heartsplus.config.show_invisible", HeartsPlusConfig.isShowInvisiblePlayers(),
				HeartsPlusConfig::setShowInvisiblePlayers, "heartsplus.config.show_invisible.tooltip");
		addToggle(1, 1, "heartsplus.config.animation", HeartsPlusConfig.isBlinkAnimationEnabled(),
				HeartsPlusConfig::setBlinkAnimation);

		CycleButton<Boolean> textures = CycleButton.booleanBuilder(
				Component.translatable("option.heartsplus.textures.vanilla"),
				Component.translatable("option.heartsplus.textures.current"),
				HeartsPlusConfig.isVanillaTextures())
				.create(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Component.translatable("heartsplus.config.textures"),
						(button, value) -> HeartsPlusConfig.setVanillaTextures(value));
		this.grid.addChild(textures, 2, 0);
		this.grid.addChild(slider("heartsplus.config.follow_smoothing",
				HeartsPlusConfig.MIN_FOLLOW_SMOOTHNESS, HeartsPlusConfig.MAX_FOLLOW_SMOOTHNESS,
				HeartsPlusConfig.getFollowSmoothness(),
				v -> HeartsPlusConfig.setFollowSmoothnessSilently((int) Math.round(v)),
				v -> percentLabel("heartsplus.config.follow_smoothing", (int) Math.round(v), false)), 2, 1);

		this.grid.addChild(slider("heartsplus.config.scale", HeartsPlusConfig.MIN_SCALE, HeartsPlusConfig.MAX_SCALE, HeartsPlusConfig.getScale(),
				HeartsPlusConfig::setScaleSilently, v -> Component.translatable("heartsplus.config.scale",
						String.format(Locale.ROOT, "%.2f", v))), 3, 0);
		this.grid.addChild(slider("heartsplus.config.render_distance", HeartsPlusConfig.MIN_RENDER_DISTANCE, HeartsPlusConfig.MAX_RENDER_DISTANCE, HeartsPlusConfig.getRenderDistance(),
				HeartsPlusConfig::setRenderDistanceSilently, v -> Component.translatable("heartsplus.config.render_distance",
						String.format(Locale.ROOT, "%.0f", v))), 3, 1);

		this.grid.addChild(slider("heartsplus.config.wall_opacity",
				HeartsPlusConfig.MIN_WALL_OPACITY, HeartsPlusConfig.MAX_WALL_OPACITY,
				HeartsPlusConfig.getWallOpacity(),
				v -> HeartsPlusConfig.setWallOpacitySilently((int) Math.round(v)),
				v -> percentLabel("heartsplus.config.wall_opacity", (int) Math.round(v), true)), 4, 0);
		this.grid.addChild(slider("heartsplus.config.height_standing",
				HeartsPlusConfig.MIN_HEART_OFFSET, HeartsPlusConfig.MAX_HEART_OFFSET, HeartsPlusConfig.getOffsetStanding(),
				v -> HeartsPlusConfig.setOffsetStandingSilently((int) Math.round(v)),
				v -> offsetLabel("heartsplus.config.height_standing", (int) Math.round(v))), 4, 1);
		this.grid.addChild(slider("heartsplus.config.height_sneaking",
				HeartsPlusConfig.MIN_HEART_OFFSET, HeartsPlusConfig.MAX_HEART_OFFSET, HeartsPlusConfig.getOffsetSneaking(),
				v -> HeartsPlusConfig.setOffsetSneakingSilently((int) Math.round(v)),
				v -> offsetLabel("heartsplus.config.height_sneaking", (int) Math.round(v))), 5, 0);
		this.grid.addChild(slider("heartsplus.config.height_swimming",
				HeartsPlusConfig.MIN_HEART_OFFSET, HeartsPlusConfig.MAX_HEART_OFFSET, HeartsPlusConfig.getOffsetSwimming(),
				v -> HeartsPlusConfig.setOffsetSwimmingSilently((int) Math.round(v)),
				v -> offsetLabel("heartsplus.config.height_swimming", (int) Math.round(v))), 5, 1);
		this.grid.addChild(slider("heartsplus.config.height_flying",
				HeartsPlusConfig.MIN_HEART_OFFSET, HeartsPlusConfig.MAX_HEART_OFFSET, HeartsPlusConfig.getOffsetFlying(),
				v -> HeartsPlusConfig.setOffsetFlyingSilently((int) Math.round(v)),
				v -> offsetLabel("heartsplus.config.height_flying", (int) Math.round(v))), 6, 0);
		this.rootLayout.addChild(this.grid);

		LinearLayout buttons = LinearLayout.horizontal().spacing(10);
		buttons.addChild(Button.builder(Component.translatable("gui.done"), button -> onClose()).width(WIDGET_WIDTH).build());
		buttons.addChild(Button.builder(Component.translatable("heartsplus.config.reset"), button -> resetAndRebuild())
				.width(WIDGET_WIDTH).build());
		this.rootLayout.addChild(buttons);

		this.rootLayout.arrangeElements();
		this.rootLayout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	@Override
	protected void repositionElements() {
		FrameLayout.centerInRectangle(this.rootLayout, this.getRectangle());
	}

	@Override
	public void onClose() {
		HeartsPlusConfig.save();
		if (this.minecraft != null) {
			this.minecraft.setScreenAndShow(this.parent);
		}
	}

	private void resetAndRebuild() {
		HeartsPlusConfig.reset();
		// init() appends to the layout fields, so the screen is rebuilt as a
		// fresh instance instead of re-initializing this one.
		if (this.minecraft != null) {
			this.minecraft.setScreenAndShow(new HeartsPlusConfigScreen(this.parent));
		}
	}

	private void addToggle(int row, int column, String key, boolean initial, Consumer<Boolean> setter) {
		addToggle(row, column, key, initial, setter, null);
	}

	private void addToggle(int row, int column, String key, boolean initial, Consumer<Boolean> setter,
			String tooltipKey) {
		CycleButton<Boolean> button = CycleButton.onOffBuilder(initial)
				.create(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Component.translatable(key), (btn, value) -> setter.accept(value));
		if (tooltipKey != null) {
			button.setTooltip(Tooltip.create(Component.translatable(tooltipKey)));
		}
		this.grid.addChild(button, row, column);
	}

	private static AbstractSliderButton slider(String key, double min, double max, double initial,
			DoubleConsumer setter, DoubleFunction<Component> message) {
		return new ConfigSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, min, max, initial, setter, message);
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
			super(x, y, width, height, Component.empty(), Mth.clamp((initial - min) / (max - min), 0.0, 1.0));
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
