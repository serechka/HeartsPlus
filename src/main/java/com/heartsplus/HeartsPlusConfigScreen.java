package com.heartsplus;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Mod Menu compatible settings screen. Toggles apply immediately;
 * sliders write their value live but only persist when the screen closes.
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
		addToggle(1, 0, "heartsplus.config.absorption", HeartsPlusConfig.isShowAbsorption(), HeartsPlusConfig::setShowAbsorption);
		addToggle(1, 1, "heartsplus.config.stack", HeartsPlusConfig.isStackHearts(), HeartsPlusConfig::setStackHearts);
		addToggle(2, 0, "heartsplus.config.hide_invisible", HeartsPlusConfig.isHideWhenInvisible(), HeartsPlusConfig::setHideWhenInvisible);
		addToggle(2, 1, "heartsplus.config.hide_sneaking", HeartsPlusConfig.isHideWhenSneaking(), HeartsPlusConfig::setHideWhenSneaking);

		CycleButton<Boolean> textures = CycleButton.booleanBuilder(
				Component.translatable("option.heartsplus.textures.vanilla"),
				Component.translatable("option.heartsplus.textures.pack"),
				HeartsPlusConfig.isUseVanillaTextures())
				.create(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Component.translatable("heartsplus.config.textures"),
						(button, value) -> HeartsPlusConfig.setUseVanillaTextures(value));
		this.grid.addChild(textures, 3, 0);

		this.grid.addChild(slider("heartsplus.config.scale", HeartsPlusConfig.MIN_SCALE, HeartsPlusConfig.MAX_SCALE, HeartsPlusConfig.getScale(),
				HeartsPlusConfig::setScaleSilently, v -> Component.translatable("heartsplus.config.scale",
						String.format(Locale.ROOT, "%.2f", v))), 4, 0);
		this.grid.addChild(slider("heartsplus.config.render_distance", HeartsPlusConfig.MIN_RENDER_DISTANCE, HeartsPlusConfig.MAX_RENDER_DISTANCE, HeartsPlusConfig.getRenderDistance(),
				HeartsPlusConfig::setRenderDistanceSilently, v -> Component.translatable("heartsplus.config.render_distance",
						String.format(Locale.ROOT, "%.0f", v))), 4, 1);
		this.grid.addChild(slider("heartsplus.config.heart_offset", HeartsPlusConfig.MIN_HEART_OFFSET, HeartsPlusConfig.MAX_HEART_OFFSET, HeartsPlusConfig.getHeartOffset(),
				v -> HeartsPlusConfig.setHeartOffsetSilently((int) Math.round(v)),
				v -> Component.translatable("heartsplus.config.heart_offset",
						String.format(Locale.ROOT, "%.0f", v))), 5, 0);
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
		CycleButton<Boolean> button = CycleButton.onOffBuilder(initial)
				.create(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Component.translatable(key), (btn, value) -> setter.accept(value));
		this.grid.addChild(button, row, column);
	}

	private static AbstractSliderButton slider(String key, double min, double max, double initial,
			DoubleConsumer setter, DoubleFunction<Component> message) {
		return new ConfigSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, min, max, initial, setter, message);
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
