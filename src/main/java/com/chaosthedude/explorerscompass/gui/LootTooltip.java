package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import com.chaosthedude.explorerscompass.preview.StructurePreview;
import com.chaosthedude.explorerscompass.util.RenderUtils;
import com.mojang.datafixers.util.Either;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.ClientHooks;

/**
 * The tooltip for a slot being pointed at, on the card or in the overview: everything the game
 * itself says about the stack, as it says it for an item in a chest, and under it what the loot
 * says, drawn rather than written so that it reads at a glance.
 *
 * <p>What the loot says is laid out in rows, each with a label, a bar, a percentage and a word
 * after it, lined up in columns so that the chance in one container and the chance across the
 * whole structure can be compared without reading either. Under them are how many come at once,
 * how many containers can be expected to hold the item, and where else it can be found, which
 * waits for Shift where there is more than one place, so that the tooltip stays short for the
 * player who only wanted to know what the item is.
 *
 * <p>The rows are worked out before anything is drawn, as plain {@link Line}s handed to the game's
 * tooltip as a component of its own, so that the game places the tooltip, flips it at the edge of
 * the screen and draws its background exactly as it would for any item, and a mod that restyles
 * tooltips restyles this one with the rest. The component is built again every frame the tooltip
 * is drawn, which is what lets Shift take effect the moment it is pressed.
 */
@OnlyIn(Dist.CLIENT)
public final class LootTooltip {

	/**
	 * How wide a sentence may run before it is wrapped, and a source's name before it is shortened,
	 * where nothing else makes the tooltip wider. The meters are never held to it: a meter cut short
	 * loses the word that says what its number counts, so they take the width they need, and a
	 * sentence under them runs as wide as they do rather than wrapping short of the edge.
	 */
	private static final int MAX_WIDTH = 180;
	/** Room above the rows, so that they stand apart from whatever the game wrote over them. */
	private static final int PAD_TOP = 4;
	private static final int ROW = 10;
	private static final int RULE_HEIGHT = 3;
	private static final int GROUP_GAP = 2;
	private static final int BAR_WIDTH = 40;
	private static final int BAR_TOP = 2;
	private static final int BAR_BOTTOM = 7;
	/** Between a label and its bar. */
	private static final int LABEL_GAP = 6;
	/** Between a bar and its value, and between a value and the word after it. */
	private static final int COLUMN_GAP = 4;
	/** How far in a source's name starts, past the swatch of its tier. */
	private static final int SOURCE_INDENT = 6;
	/** Between a source's container count and its chance, which is held against the right edge. */
	private static final int SOURCE_VALUE_GAP = 8;
	/** How many places an item is listed as found in before the rest are only counted. */
	private static final int MAX_SOURCES = 6;

	private static final int RULE = 0x30FFFFFF;
	private static final int BAR_TRACK = 0x40FFFFFF;
	/** The colour of a line that says what the player can do rather than what the loot is, the grey the game hints in. */
	private static final int HINT = 0xFF555555;

	/**
	 * What a tooltip says about one slot, as rows ready to be measured and drawn, and how wide the
	 * game's own lines above them run, so that the rule and the chances held against the right edge
	 * reach the edge of the tooltip rather than stopping short of a long name.
	 */
	public record Data(List<Line> lines, int minWidth) implements TooltipComponent {
	}

	/** One row, or one piece of spacing, of what a tooltip says. */
	public sealed interface Line permits Rule, Gap, Meter, Count, Text, Source, More {
	}

	/** The thin line that divides what the loot says from what the game said above it. */
	public record Rule() implements Line {
	}

	/** Room between one group of rows and the next. */
	public record Gap(int height) implements Line {
	}

	/**
	 * A chance as a labelled bar: the bar as long as the chance is likely, the chance written after
	 * it, and a word after that, such as the tier the chance falls in or how many containers it
	 * counts.
	 */
	public record Meter(Component label, int barPermille, String value, int colour, Component suffix, int suffixColour) implements Line {
	}

	/** A labelled amount, set where a meter's bar begins so that it lines up with the meters over it. */
	public record Count(Component label, String value) implements Line {
	}

	/** A sentence, wrapped onto as many rows as it takes. */
	public record Text(Component text, int colour, boolean italic) implements Line {
	}

	/**
	 * One table the item is found in: a swatch of the tier its chance falls in, the table's name, how
	 * many containers name it, and the chance there against the right edge.
	 */
	public record Source(int swatchColour, Component name, String containers, String value, int valueColour) implements Line {
	}

	/** How many more tables the item is found in than are listed. */
	public record More(Component text) implements Line {
	}

	private LootTooltip() {
	}

	/**
	 * Draws the tooltip for a slot: what the game says about the stack, its bundle picture where it
	 * has one in the place the game puts it, that it comes enchanted where it does, and what the loot
	 * says under all of it.
	 *
	 * <p>The slot is the given item of the given table where it stands for one container's loot, and
	 * a table of -1 where it stands for the variant across the whole structure. Drawn from wherever
	 * the screen draws its topmost layer, since the game puts a tooltip a set way in front of
	 * whatever it is called over, and the slots have items standing in front of them.
	 */
	static void render(GuiGraphics guiGraphics, Font font, StructurePreview preview, LootStats stats, int variant, int table, int item, int mouseX, int mouseY) {
		final Minecraft mc = Minecraft.getInstance();
		final LootStats.Variant found = stats.variant(variant);
		final ItemStack stack = stats.stack(variant);
		final boolean advanced = mc.options.advancedItemTooltips;
		final List<Either<FormattedText, TooltipComponent>> elements = new ArrayList<Either<FormattedText, TooltipComponent>>();
		if (!stack.isEmpty()) {
			for (Component line : stack.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, advanced ? TooltipFlag.ADVANCED : TooltipFlag.NORMAL)) {
				elements.add(Either.<FormattedText, TooltipComponent>left(line));
			}
			// Under the name, where the game itself puts a bundle's contents
			stack.getTooltipImage().ifPresent((image) -> elements.add(Math.min(1, elements.size()), Either.<FormattedText, TooltipComponent>right(image)));
		} else {
			final MutableComponent unknown = Component.translatable("string.explorerscompass.lootUnknownItem").withStyle(ChatFormatting.GRAY);
			if (advanced) {
				unknown.append(" #" + found.itemId());
			}
			elements.add(Either.<FormattedText, TooltipComponent>left(unknown));
		}
		if (found.enchanted()) {
			elements.add(Either.<FormattedText, TooltipComponent>left(Component.translatable("string.explorerscompass.lootTooltipEnchanted").withColor(GuiTheme.ACCENT)));
		}
		final int minCount = table >= 0 ? preview.getLootTableMinCounts(table)[item] : found.minCount();
		final int maxCount = table >= 0 ? preview.getLootTableMaxCounts(table)[item] : found.maxCount();
		final Data data = build(stats, variant, table, minCount, maxCount, Screen.hasShiftDown(), (source) -> tableName(preview, source));
		// Measured in the font the game will draw this stack's tooltip in, which a mod's item may set
		elements.add(Either.<FormattedText, TooltipComponent>right(new Data(data.lines(), textWidth(ClientHooks.getTooltipFont(stack, font), elements, mouseX, guiGraphics.guiWidth()))));
		guiGraphics.renderComponentTooltipFromElements(font, elements, mouseX, mouseY, stack);
	}

	/**
	 * How wide the game will draw the lines of text given so far: as wide as the widest of them,
	 * unless the tooltip would then fit on neither side of the pointer, in which case the game wraps
	 * them to the room on the wider side, worked out here the way it works it out. Stretching the
	 * rows to more than that would push the tooltip off the screen the wrapping was meant to keep it on.
	 */
	private static int textWidth(Font font, List<Either<FormattedText, TooltipComponent>> elements, int mouseX, int screenWidth) {
		int widest = 0;
		for (Either<FormattedText, TooltipComponent> element : elements) {
			widest = Math.max(widest, element.map(font::width, (image) -> 0));
		}
		if (mouseX + 12 + widest + 4 <= screenWidth || mouseX - 16 - widest >= 4) {
			return widest;
		}
		return Math.max(0, Math.min(widest, mouseX > screenWidth / 2 ? mouseX - 12 - 8 : screenWidth - 16 - mouseX));
	}

	/** What a table is called in the list of places an item is found in, the way the card heads it. */
	private static Component tableName(StructurePreview preview, int table) {
		final String id = preview.getLootTableId(table);
		if (id.isEmpty()) {
			return Component.translatable("string.explorerscompass.lootFixedItems");
		}
		return LootSlots.displayName(id);
	}

	/**
	 * What the loot says about a slot, as rows. For one container's slot the given table is the one
	 * it stands for, and the counts are how many of the item that table gives at once; for a slot
	 * standing for the whole structure the table is -1, and the counts given are not read, since the
	 * variant's own span across every table that gives it is the one that applies.
	 *
	 * <p>One container's slot leads with the chance in that container, since that is what the slot
	 * itself says, and follows it with the chance across the structure. A slot for the whole
	 * structure leads with the structure, follows it with the best single container, and ends by
	 * saying that a click finds that container.
	 */
	static Data build(LootStats stats, int variant, int table, int minCount, int maxCount, boolean shift, IntFunction<Component> tableName) {
		final LootStats.Variant found = stats.variant(variant);
		final int containers = found.containersWith();
		final List<Line> lines = new ArrayList<Line>();
		lines.add(new Rule());
		if (table >= 0) {
			final int permille = permilleIn(found, table);
			lines.add(tierMeter("string.explorerscompass.lootChancePerContainer", permille));
			if (containers > 1) {
				lines.add(structureMeter(found));
			}
			addCount(lines, minCount, maxCount);
		} else {
			lines.add(structureMeter(found));
			if (containers > 1) {
				lines.add(tierMeter("string.explorerscompass.lootChanceBestContainer", found.bestPermille()));
			}
			addCount(lines, found.minCount(), found.maxCount());
		}
		if (containers > 1) {
			lines.add(new Gap(GROUP_GAP));
			lines.add(new Text(Component.translatable("string.explorerscompass.lootExpected", LootMath.formatExpected(found.expected()), String.valueOf(containers)), GuiTheme.TEXT_MUTED, false));
		}
		// A table that could not be read may give the item too, which matters only where the item is not already certain
		if (stats.unresolvedTables() > 0 && !found.certain()) {
			lines.add(new Text(Component.translatable("string.explorerscompass.lootUnresolvedWarning", String.valueOf(stats.unresolvedTables())), GuiTheme.TEXT_WARNING, false));
		}
		final int sourceCount = found.tables().length;
		if (sourceCount >= 2 || (table < 0 && sourceCount == 1)) {
			lines.add(new Gap(GROUP_GAP));
			// A single place is listed outright, since holding Shift to read one line would ask more than it gives
			if (shift || sourceCount == 1) {
				lines.add(new Text(Component.translatable("string.explorerscompass.lootFoundIn"), GuiTheme.TEXT_SECONDARY, false));
				addSources(lines, stats, found, tableName);
			} else {
				lines.add(new Text(Component.translatable("string.explorerscompass.tooltip.holdShift"), HINT, true));
			}
		}
		if (table < 0) {
			lines.add(new Gap(GROUP_GAP));
			lines.add(new Text(Component.translatable("string.explorerscompass.lootClickToLocate"), HINT, true));
		}
		return new Data(List.copyOf(lines), 0);
	}

	/**
	 * The chance the given table gives the variant with, merged where the table names it more than
	 * once. Every slot of a table is a variant that table gives, so the best chance is only fallen
	 * back on for a table the variant does not list, which no slot can stand for.
	 */
	private static int permilleIn(LootStats.Variant variant, int table) {
		final int[] tables = variant.tables();
		for (int i = 0; i < tables.length; i++) {
			if (tables[i] == table) {
				return variant.permilles()[i];
			}
		}
		return variant.bestPermille();
	}

	/** A chance in one container, named by the tier it falls in. */
	private static Meter tierMeter(String labelKey, int permille) {
		final LootTier tier = LootMath.tier(permille);
		return new Meter(Component.translatable(labelKey), permille, LootMath.formatChance(permille), tier.colour, tier.displayName(), tier.colour);
	}

	/**
	 * The chance of finding the variant at least once across the structure, with how many containers
	 * that counts. Where only one container can hold it, that one container is the whole structure,
	 * and the meter is named by its tier instead, as the meter for one container is.
	 */
	private static Meter structureMeter(LootStats.Variant variant) {
		final int permille = variant.structurePermille();
		final LootTier tier = LootMath.tier(permille);
		final Component label = Component.translatable("string.explorerscompass.lootChanceStructure");
		final String value = LootMath.formatProbability(variant.structureChance(), variant.certain());
		if (variant.containersWith() <= 1) {
			return new Meter(label, permille, value, tier.colour, tier.displayName(), tier.colour);
		}
		final Component suffix = Component.translatable("string.explorerscompass.lootStructureContainers", String.valueOf(variant.containersWith()));
		return new Meter(label, permille, value, tier.colour, suffix, GuiTheme.TEXT_MUTED);
	}

	/** How many come at once, where that is more than the one that needs no saying. */
	private static void addCount(List<Line> lines, int minCount, int maxCount) {
		if (maxCount > 1) {
			lines.add(new Count(Component.translatable("string.explorerscompass.lootPerStack"), LootSlots.formatCount(minCount, maxCount)));
		}
	}

	/** The tables the variant is found in, likeliest first, as many as fit and a count of the rest. */
	private static void addSources(List<Line> lines, LootStats stats, LootStats.Variant variant, IntFunction<Component> tableName) {
		final int[] tables = variant.tables();
		final int listed = Math.min(MAX_SOURCES, tables.length);
		for (int i = 0; i < listed; i++) {
			final int permille = variant.permilles()[i];
			final int colour = LootMath.tier(permille).colour;
			lines.add(new Source(colour, tableName.apply(tables[i]), I18n.get("string.explorerscompass.lootTableCount", String.valueOf(stats.containers(tables[i]))), LootMath.formatChance(permille), colour));
		}
		if (tables.length > listed) {
			lines.add(new More(Component.translatable("string.explorerscompass.lootMoreContainers", String.valueOf(tables.length - listed))));
		}
	}

	/**
	 * Draws a tooltip's rows. The columns are measured once, the first time the game asks how big
	 * the rows are, which it does every frame the tooltip is drawn, before drawing them.
	 */
	public static final class Client implements ClientTooltipComponent {

		private final List<Line> lines;
		/** How wide the game's own lines above run, which the rows stretch to so that they end where the tooltip does. */
		private final int minWidth;
		/** The rows each sentence wraps onto, parallel to the lines, and null for any line that is not a sentence. */
		private final List<List<FormattedCharSequence>> wrapped;
		private boolean measured;
		private int width;
		private int height;
		/** How wide the labels of the meters and counts are, and the values of the meters, so that each lines up under the one above. */
		private int labelWidth;
		private int valueWidth;
		/** How wide the chances of the sources are, held against the right edge. */
		private int sourceValueWidth;

		public Client(Data data) {
			this.lines = data.lines();
			this.minWidth = data.minWidth();
			this.wrapped = new ArrayList<List<FormattedCharSequence>>(lines.size());
		}

		/**
		 * Measures the rows, wrapping each sentence and settling every column. The game only hands a
		 * font to the call that asks for the width, and asks for the height without one, so the
		 * height falls back to the game's own font, which is the one the width is measured with too
		 * in any tooltip a mod has not given a font of its own.
		 */
		private void measure(Font font) {
			if (measured) {
				return;
			}
			measured = true;
			// The meters and counts first, since they are the one kind of row never cut to fit, and how
			// wide they make the rows is how wide a sentence under them may run before it wraps
			int meterWidest = 0;
			for (Line line : lines) {
				if (line instanceof Meter meter) {
					labelWidth = Math.max(labelWidth, font.width(meter.label()));
					valueWidth = Math.max(valueWidth, font.width(meter.value()));
				} else if (line instanceof Count count) {
					labelWidth = Math.max(labelWidth, font.width(count.label()));
				} else if (line instanceof Source source) {
					sourceValueWidth = Math.max(sourceValueWidth, font.width(source.value()));
				}
			}
			final int barX = labelWidth + LABEL_GAP;
			for (Line line : lines) {
				if (line instanceof Meter meter) {
					meterWidest = Math.max(meterWidest, barX + BAR_WIDTH + COLUMN_GAP + valueWidth + COLUMN_GAP + font.width(meter.suffix()));
				} else if (line instanceof Count count) {
					meterWidest = Math.max(meterWidest, barX + font.width(count.value()));
				}
			}
			final int floor = Math.max(meterWidest, minWidth);
			final int wrapWidth = Math.max(MAX_WIDTH, floor);
			int widest = 0;
			height = PAD_TOP;
			for (Line line : lines) {
				List<FormattedCharSequence> split = null;
				switch (line) {
					case Rule rule -> height += RULE_HEIGHT;
					case Gap gap -> height += gap.height();
					case Meter meter -> height += ROW;
					case Count count -> height += ROW;
					case Text text -> {
						final Component styled = text.italic() ? text.text().copy().withStyle(ChatFormatting.ITALIC) : text.text();
						split = font.split(styled, wrapWidth);
						for (FormattedCharSequence row : split) {
							widest = Math.max(widest, font.width(row));
						}
						height += ROW * Math.max(1, split.size());
					}
					case Source source -> {
						widest = Math.max(widest, SOURCE_INDENT + font.width(source.name()) + COLUMN_GAP + font.width(source.containers()) + SOURCE_VALUE_GAP + sourceValueWidth);
						height += ROW;
					}
					case More more -> {
						widest = Math.max(widest, SOURCE_INDENT + font.width(more.text()));
						height += ROW;
					}
				}
				wrapped.add(split);
			}
			// A sentence wrapped wider than the cap is never wider than the floor that let it
			width = Math.max(floor, Math.min(MAX_WIDTH, widest));
		}

		@Override
		public int getHeight() {
			measure(Minecraft.getInstance().font);
			return height;
		}

		@Override
		public int getWidth(Font font) {
			measure(font);
			return width;
		}

		@Override
		public void renderImage(Font font, int x, int y, GuiGraphics guiGraphics) {
			measure(font);
			final int right = x + width;
			final int barX = x + labelWidth + LABEL_GAP;
			final int valueX = barX + BAR_WIDTH + COLUMN_GAP;
			int top = y + PAD_TOP;
			for (int i = 0; i < lines.size(); i++) {
				switch (lines.get(i)) {
					case Rule rule -> {
						guiGraphics.fill(x, top + 1, right, top + 2, RULE);
						top += RULE_HEIGHT;
					}
					case Gap gap -> top += gap.height();
					case Meter meter -> {
						guiGraphics.drawString(font, meter.label(), x, top, GuiTheme.TEXT_SECONDARY, true);
						drawBar(guiGraphics, barX, top, meter.barPermille(), meter.colour());
						guiGraphics.drawString(font, meter.value(), valueX + valueWidth - font.width(meter.value()), top, meter.colour(), true);
						final int suffixX = valueX + valueWidth + COLUMN_GAP;
						guiGraphics.drawString(font, RenderUtils.trimToWidth(meter.suffix().getString(), right - suffixX), suffixX, top, meter.suffixColour(), true);
						top += ROW;
					}
					case Count count -> {
						guiGraphics.drawString(font, count.label(), x, top, GuiTheme.TEXT_SECONDARY, true);
						guiGraphics.drawString(font, count.value(), barX, top, GuiTheme.TEXT_PRIMARY, true);
						top += ROW;
					}
					case Text text -> {
						final List<FormattedCharSequence> rows = wrapped.get(i);
						for (FormattedCharSequence row : rows) {
							guiGraphics.drawString(font, row, x, top, text.colour(), true);
							top += ROW;
						}
						if (rows.isEmpty()) {
							top += ROW;
						}
					}
					case Source source -> {
						guiGraphics.fill(x + 1, top + 3, x + 4, top + 6, source.swatchColour());
						final int nameX = x + SOURCE_INDENT;
						final int countWidth = font.width(source.containers());
						final String name = RenderUtils.trimToWidth(source.name().getString(), right - sourceValueWidth - SOURCE_VALUE_GAP - countWidth - COLUMN_GAP - nameX);
						final int nameRight = nameX + font.width(name);
						guiGraphics.drawString(font, name, nameX, top, GuiTheme.TEXT_PRIMARY, true);
						guiGraphics.drawString(font, source.containers(), nameRight + COLUMN_GAP, top, GuiTheme.TEXT_MUTED, true);
						guiGraphics.drawString(font, source.value(), right - font.width(source.value()), top, source.valueColour(), true);
						top += ROW;
					}
					case More more -> {
						guiGraphics.drawString(font, RenderUtils.trimToWidth(more.text().getString(), width - SOURCE_INDENT), x + SOURCE_INDENT, top, GuiTheme.TEXT_MUTED, true);
						top += ROW;
					}
				}
			}
		}

		/**
		 * A chance as a bar on its track, as long as the chance is likely, and never so short that a
		 * chance there is any of at all draws as none.
		 */
		private static void drawBar(GuiGraphics guiGraphics, int x, int top, int permille, int colour) {
			guiGraphics.fill(x, top + BAR_TOP, x + BAR_WIDTH, top + BAR_BOTTOM, BAR_TRACK);
			final int filled = Math.min(BAR_WIDTH, Math.max(permille > 0 ? 1 : 0, Math.round(BAR_WIDTH * permille / 1000.0F)));
			if (filled > 0) {
				guiGraphics.fill(x, top + BAR_TOP, x + filled, top + BAR_BOTTOM, colour);
			}
		}

	}

}
