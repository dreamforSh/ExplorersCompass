package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.chaosthedude.explorerscompass.ExplorersCompass;

import net.minecraft.ChatFormatting;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;
import net.neoforged.neoforge.common.ModConfigSpec.ValueSpec;

/**
 * What the settings screen calls things: the name of a setting, what it does, and how a value is
 * written out. The names and descriptions live in the language files under
 * {@code explorerscompass.configuration.<setting>}, which is the same shape the game's own
 * configuration screen looks them up under, so that either screen finds them; a description that has
 * not been translated falls back to the comment written into the config file itself.
 */
public final class ConfigTexts {

	public static final String PREFIX = ExplorersCompass.MODID + ".configuration.";

	private ConfigTexts() {
	}

	/** The last part of a setting's path, which is what it is written to the file as and translated under. */
	public static String keyOf(ConfigValue<?> value) {
		final List<String> path = value.getPath();
		return path.get(path.size() - 1);
	}

	public static Component label(ConfigValue<?> value) {
		return Component.translatableWithFallback(PREFIX + keyOf(value), keyOf(value));
	}

	/** The heading a group of settings is shown under. */
	public static Component groupLabel(String groupKey) {
		return Component.translatableWithFallback(PREFIX + "group." + groupKey, groupKey);
	}

	/**
	 * Everything worth knowing about a setting, for the tooltip: its name, what it does, what it may be
	 * set to, and what it starts as.
	 */
	public static List<Component> tooltip(ConfigValue<?> value) {
		final List<Component> lines = new ArrayList<Component>();
		lines.add(label(value).copy().withStyle((style) -> style.withColor(TextColor.fromRgb(GuiTheme.TEXT_PRIMARY))));

		final ValueSpec spec = value.getSpec();
		final String tooltipKey = PREFIX + keyOf(value) + ".tooltip";
		final String comment = spec.getComment();
		if (I18n.exists(tooltipKey) || (comment != null && !comment.isBlank())) {
			lines.add(Component.translatableWithFallback(tooltipKey, comment == null ? "" : comment).withStyle((style) -> style.withColor(TextColor.fromRgb(GuiTheme.TEXT_SECONDARY))));
		}

		final ModConfigSpec.Range<?> range = spec.getRange();
		if (range != null) {
			lines.add(muted(I18n.get(PREFIX + "range", format(range.getMin()), format(range.getMax()))));
		}
		lines.add(muted(I18n.get(PREFIX + "default", format(value.getDefault()))));
		if (spec.restartType() != ModConfigSpec.RestartType.NONE) {
			lines.add(Component.translatable(PREFIX + (spec.restartType() == ModConfigSpec.RestartType.GAME ? "restartGame" : "restartWorld")).withStyle(ChatFormatting.RED));
		}
		lines.add(Component.literal(keyOf(value)).withStyle(ChatFormatting.DARK_GRAY));
		return lines;
	}

	/** A value as the screen writes it: switches as on or off, choices by their names, lists by their length. */
	public static String format(Object value) {
		if (value instanceof Boolean flag) {
			return I18n.get(PREFIX + (flag ? "on" : "off"));
		}
		if (value instanceof Enum<?> constant) {
			return enumLabel(constant).getString();
		}
		if (value instanceof List<?> list) {
			return I18n.get(PREFIX + "entries", String.valueOf(list.size()));
		}
		if (value instanceof Double number) {
			// Written the way it was written into the file, without a tail of noise digits
			return number == Math.rint(number) ? String.valueOf(number.longValue()) : String.format(Locale.ROOT, "%.3f", number);
		}
		return String.valueOf(value);
	}

	/** What one of the choices of a setting is called: translated under the choice's own type and name. */
	public static Component enumLabel(Enum<?> constant) {
		return Component.translatableWithFallback(PREFIX + "enum." + constant.getDeclaringClass().getSimpleName() + "." + constant.name().toLowerCase(Locale.ROOT), constant.name());
	}

	/** Which of the files a set of settings is written to, by the kind of config it is. */
	public static String fileName(ModConfig.Type type) {
		return ExplorersCompass.MODID + "-" + type.extension() + ".toml";
	}

	private static Component muted(String text) {
		return Component.literal(text).withStyle((style) -> style.withColor(TextColor.fromRgb(GuiTheme.TEXT_MUTED)));
	}

}
