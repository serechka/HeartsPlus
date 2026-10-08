package com.heartsplus;

import java.util.function.Consumer;
import java.util.function.IntFunction;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * The Cloth Config settings screen (0.4.9): six tabs in a fixed order -
 * Behavior, Animation, Invisibility &amp; Walls, Position, Distance,
 * Appearance - one entry per setting, the library's search field on top and
 * its standard per-entry reset button on every entry with a default. Entry
 * save consumers write straight into the clamped config accessors; Cloth
 * Config only runs them on the screen's save action, so cancelling drops
 * every change.
 *
 * <p>Sliders are integer-based because this Cloth Config version ships int
 * and long sliders only: Scale and the two percent settings travel as
 * percent points, the distance as blocks, and the text getter shows the
 * real value.</p>
 */
final class HeartsPlusClothConfigScreen {
	private HeartsPlusClothConfigScreen() {
	}

	static Screen create(Screen parent) {
		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Text.translatable("heartsplus.config.title"))
				.setSavingRunnable(HeartsPlusConfig::save);
		ConfigEntryBuilder entries = builder.entryBuilder();

		// 1. Behavior - the master switch.
		ConfigCategory behavior = builder.getOrCreateCategory(Text.translatable("heartsplus.config.tab.behavior"));
		behavior.addEntry(entries.startBooleanToggle(Text.translatable("heartsplus.config.enabled"), HeartsPlusConfig.isEnabled())
				.setDefaultValue(true)
				.setSaveConsumer(HeartsPlusConfig::setEnabled)
				.build());

		// 2. Animation.
		ConfigCategory animation = builder.getOrCreateCategory(Text.translatable("heartsplus.config.tab.animation"));
		animation.addEntry(entries.startBooleanToggle(Text.translatable("heartsplus.config.animation"), HeartsPlusConfig.isBlinkAnimationEnabled())
				.setDefaultValue(true)
				.setSaveConsumer(HeartsPlusConfig::setBlinkAnimation)
				.build());

		// 3. Invisibility & Walls.
		ConfigCategory invisibility = builder.getOrCreateCategory(Text.translatable("heartsplus.config.tab.invisibility"));
		invisibility.addEntry(entries.startBooleanToggle(Text.translatable("heartsplus.config.show_invisible"), HeartsPlusConfig.isShowInvisiblePlayers())
				.setDefaultValue(false)
				.setTooltip(Text.translatable("heartsplus.config.show_invisible.tooltip"))
				.setSaveConsumer(HeartsPlusConfig::setShowInvisiblePlayers)
				.build());
		invisibility.addEntry(intSlider(entries, Text.translatable("heartsplus.config.wall_opacity"),
				HeartsPlusConfig.getWallOpacity(), HeartsPlusConfig.DEFAULT_WALL_OPACITY,
				HeartsPlusConfig.MIN_WALL_OPACITY, HeartsPlusConfig.MAX_WALL_OPACITY,
				HeartsPlusConfig::setWallOpacity, HeartsPlusClothConfigScreen::opacityLabel));

		// 4. Position.
		ConfigCategory position = builder.getOrCreateCategory(Text.translatable("heartsplus.config.tab.position"));
		position.addEntry(intSlider(entries, Text.translatable("heartsplus.config.follow_smoothing"),
				HeartsPlusConfig.getFollowSmoothness(), HeartsPlusConfig.DEFAULT_FOLLOW_SMOOTHNESS,
				HeartsPlusConfig.MIN_FOLLOW_SMOOTHNESS, HeartsPlusConfig.MAX_FOLLOW_SMOOTHNESS,
				HeartsPlusConfig::setFollowSmoothness, HeartsPlusClothConfigScreen::smoothnessLabel));
		position.addEntry(heightSlider(entries, Text.translatable("heartsplus.config.height_standing"),
				HeartsPlusConfig.getOffsetStanding(), HeartsPlusConfig::setOffsetStanding));
		position.addEntry(heightSlider(entries, Text.translatable("heartsplus.config.height_sneaking"),
				HeartsPlusConfig.getOffsetSneaking(), HeartsPlusConfig::setOffsetSneaking));
		position.addEntry(heightSlider(entries, Text.translatable("heartsplus.config.height_swimming"),
				HeartsPlusConfig.getOffsetSwimming(), HeartsPlusConfig::setOffsetSwimming));
		position.addEntry(heightSlider(entries, Text.translatable("heartsplus.config.height_flying"),
				HeartsPlusConfig.getOffsetFlying(), HeartsPlusConfig::setOffsetFlying));

		// 5. Distance.
		ConfigCategory distance = builder.getOrCreateCategory(Text.translatable("heartsplus.config.tab.distance"));
		distance.addEntry(intSlider(entries, Text.translatable("heartsplus.config.distance_name"),
				(int) HeartsPlusConfig.getRenderDistance(), 128,
				(int) HeartsPlusConfig.MIN_RENDER_DISTANCE, (int) HeartsPlusConfig.MAX_RENDER_DISTANCE,
				blocks -> HeartsPlusConfig.setRenderDistance(blocks),
				value -> Text.literal(String.format("%d m", value))));

		// 6. Appearance.
		ConfigCategory appearance = builder.getOrCreateCategory(Text.translatable("heartsplus.config.tab.appearance"));
		appearance.addEntry(intSlider(entries, Text.translatable("heartsplus.config.scale_name"),
				(int) Math.round(HeartsPlusConfig.getScale() * 100.0), 100,
				(int) Math.round(HeartsPlusConfig.MIN_SCALE * 100.0), (int) Math.round(HeartsPlusConfig.MAX_SCALE * 100.0),
				percent -> HeartsPlusConfig.setScale(percent / 100.0),
				value -> Text.literal(String.format("%.2f", value / 100.0))));
		appearance.addEntry(entries.startBooleanToggle(Text.translatable("heartsplus.config.textures"), HeartsPlusConfig.isVanillaTextures())
				.setDefaultValue(false)
				.setYesNoTextSupplier(value -> Text.translatable(value
						? "option.heartsplus.textures.vanilla" : "option.heartsplus.textures.current"))
				.setSaveConsumer(HeartsPlusConfig::setVanillaTextures)
				.build());

		return builder.build();
	}

	/** One int slider entry wired to a config setter, with the standard reset default. */
	private static AbstractConfigListEntry<?> intSlider(ConfigEntryBuilder entries, Text label, int value,
			int defaultValue, int min, int max, Consumer<Integer> save, IntFunction<Text> text) {
		return entries.startIntSlider(label, value, min, max)
				.setDefaultValue(defaultValue)
				.setTextGetter(text::apply)
				.setSaveConsumer(save)
				.build();
	}

	/** Bar lift slider over the shared -40..+40 GUI pixel range, shown as a plain number. */
	private static AbstractConfigListEntry<?> heightSlider(ConfigEntryBuilder entries, Text label, int value,
			Consumer<Integer> save) {
		return intSlider(entries, label, value, HeartsPlusConfig.DEFAULT_HEART_OFFSET,
				HeartsPlusConfig.MIN_HEART_OFFSET, HeartsPlusConfig.MAX_HEART_OFFSET, save,
				HeartsPlusClothConfigScreen::plainNumber);
	}

	/** Zero reads as OFF, everything else as the plain number. */
	private static Text smoothnessLabel(int smoothness) {
		return smoothness == 0
				? Text.translatable("options.off")
				: plainNumber(smoothness);
	}

	/** Zero reads as OFF, everything else as a percentage. */
	private static Text opacityLabel(int opacity) {
		return opacity == 0
				? Text.translatable("options.off")
				: Text.literal(String.format("%d%%", opacity));
	}

	private static Text plainNumber(int value) {
		return Text.literal(String.format("%d", value));
	}
}
