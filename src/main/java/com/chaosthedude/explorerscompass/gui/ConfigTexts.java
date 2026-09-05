package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;

import net.minecraft.ChatFormatting;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;
import net.minecraftforge.common.ForgeConfigSpec.ValueSpec;
import net.minecraftforge.fml.config.ModConfig;

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
	 * What the file knows about a setting beyond its value: the comment, the range, whether a world
	 * has to be reopened for a change to take. A value does not carry its own here, so it is looked
	 * up by its path in whichever of the two files it belongs to. Null for a value in neither.
	 */
	public static ValueSpec valueSpec(ConfigValue<?> value) {
		for (ForgeConfigSpec spec : List.of(ConfigHandler.CLIENT_SPEC, ConfigHandler.GENERAL_SPEC)) {
			final Object raw = spec.getSpec().get(value.getPath());
			if (raw instanceof ValueSpec valueSpec) {
				return valueSpec;
			}
		}
		return null;
	}

	/** The bounds a number is held to, or null where the setting has none or is not a number. */
	public static ForgeConfigSpec.Range<?> rangeOf(ConfigValue<?> value) {
		final ValueSpec spec = valueSpec(value);
		return spec != null ? spec.getRange() : null;
	}

	/**
	 * Everything worth knowing about a setting, for the tooltip: its name, what it does, what it may be
	 * set to, and what it starts as.
	 */
	public static List<Component> tooltip(ConfigValue<?> value) {
		final List<Component> lines = new ArrayList<Component>();
		lines.add(label(value).copy().withStyle((style) -> style.withColor(TextColor.fromRgb(GuiTheme.TEXT_PRIMARY))));

		final ValueSpec spec = valueSpec(value);
		final String tooltipKey = PREFIX + keyOf(value) + ".tooltip";
		final String comment = spec != null ? spec.getComment() : null;
		if (I18n.exists(tooltipKey) || (comment != null && !comment.isBlank())) {
			lines.add(Component.translatableWithFallback(tooltipKey, comment == null ? "" : comment).withStyle((style) -> style.withColor(TextColor.fromRgb(GuiTheme.TEXT_SECONDARY))));
		}

		final ForgeConfigSpec.Range<?> range = spec != null ? spec.getRange() : null;
		if (range != null) {
			lines.add(muted(I18n.get(PREFIX + "range", format(range.getMin()), format(range.getMax()))));
		}
		lines.add(muted(I18n.get(PREFIX + "default", format(value.getDefault()))));
		if (spec != null && spec.needsWorldRestart()) {
			lines.add(Component.translatable(PREFIX + "restartWorld").withStyle(ChatFormatting.RED));
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
