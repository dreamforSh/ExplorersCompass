package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import com.chaosthedude.explorerscompass.preview.StructurePreview;
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
 * <p>What the loot says opens with the tier the chance falls in, as the icon the slots and the
 * overview mark it with and the tier's name, so that the tooltip and the grid it was pointed out of
 * speak the same words. Under it are the chances, each a labelled bar that stretches to the width
 * of the tooltip with its percentage held against the right edge, lined up in columns so that the
 * chance in one container and the chance across the whole structure can be compared without
 * reading either, and then how many come at once and how many containers can be expected to hold
 * the item.
 *
 * <p>Where else the item can be found waits behind Shift where there is more than one place, so
 * that the tooltip stays short for the player who only wanted to know what the item is. What a key
 * does is said beside a drawn key rather than in a grey sentence, which is quicker to find and
 * reads as something to do rather than as more about the item.
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
	 * How wide the rows that can give way may run before they do: a sentence wraps, a source's name
	 * is shortened and what a tier stands for is left out. The rows that cannot give way are never
	 * held to it, since a meter or a key cut short loses what its number or its key is for, so they
	 * take the width they need, and a sentence under them runs as wide as they do rather than
	 * wrapping short of the edge.
	 */
	private static final int MAX_WIDTH = 184;
	private static final int ROW = 10;
	/** A row led by an icon or a key, which stand a little taller than the text beside them. */
	private static final int TALL_ROW = 12;
	/**
	 * The first rule sits right under the room the game leaves below its own lines, and so needs
	 * none above it of its own; a later rule divides two groups of rows and is given room on both sides.
	 */
	private static final int FIRST_RULE_HEIGHT = 3;
	private static final int RULE_HEIGHT = 5;
	private static final int RULE_OFFSET = 2;
	/** Between the chances in one container and the chances across the structure. */
	private static final int GROUP_GAP = 3;
	/** Above the key that says what a click does, which closes the tooltip. */
	private static final int KEY_ROW_GAP = 2;
	/** The shortest a meter's bar is drawn, and so the least the meters ever widen the tooltip by. */
	private static final int MIN_BAR = 40;
	private static final int BAR_TOP = 2;
	private static final int BAR_BOTTOM = 6;
	/** Between a label and its bar or its value. */
	private static final int LABEL_GAP = 8;
	/** Between a bar and its value. */
	private static final int VALUE_GAP = 6;
	/** Between a value and the words after it, and between a key and what it does. */
	private static final int WORD_GAP = 4;
	/**
	 * Room left after a row written from the left where that row sets the width of the tooltip, so
	 * that its last letter does not end flush with the edge the right-aligned values end at.
	 */
	private static final int SLACK = 3;
	/** How far in a tier's name starts, past its larger icon. */
	private static final int TIER_INDENT = 12;
	/** Either side of the dot between a tier's name and what the tier stands for. */
	private static final int DOT_GAP = 3;
	private static final String DOT = "\u00B7";
	/** How far in a source's name starts, past the smaller icon of its tier. */
	private static final int SOURCE_INDENT = 10;
	/** Between a source's name and its container count. */
	private static final int SOURCE_NAME_GAP = 6;
	/** Between a source's container count and its chance, which is held against the right edge. */
	private static final int SOURCE_VALUE_GAP = 8;
	/** How many places an item is listed as found in before the rest are only counted. */
	private static final int MAX_SOURCES = 6;

	/**
	 * What a tooltip says about one slot, as rows ready to be measured and drawn, and how wide the
	 * game's own lines above them run, so that the rules, the bars and the chances held against the
	 * right edge reach the edge of the tooltip rather than stopping short of a long name.
	 */
	public record Data(List<Line> lines, int minWidth) implements TooltipComponent {
	}

	/** One row, or one piece of spacing, of what a tooltip says. */
	public sealed interface Line permits Rule, Gap, TierRow, Meter, Count, Expected, Text, Source, More, Key {
	}

	/**
	 * A thin line dividing one part of the tooltip from the next: the first divides what the loot
	 * says from what the game said above it, and a later one sets the places the item is found in
	 * apart from its chances.
	 */
	public record Rule() implements Line {
	}

	/** Room between one group of rows and the next. */
	public record Gap(int height) implements Line {
	}

	/**
	 * The tier a chance falls in, as its icon and its name, and after them what the tier stands for
	 * or what the chance it names is a chance of, left out where the tooltip has no room for it.
	 */
	public record TierRow(LootTier tier, Component hint) implements Line {
	}

	/**
	 * A chance as a labelled bar: the bar as long as the chance is likely and in the colour of the
	 * tier it falls in, and the chance written against the right edge.
	 */
	public record Meter(Component label, int barPermille, String value) implements Line {
	}

	/** A labelled amount, set where a meter's bar begins so that it lines up with the meters over it. */
	public record Count(Component label, String value) implements Line {
	}

	/**
	 * How many containers can be expected to hold the item, set as a count is, with how many
	 * containers could hold it after it in quieter words.
	 */
	public record Expected(Component label, String value, Component suffix) implements Line {
	}

	/** A sentence, wrapped onto as many rows as it takes. */
	public record Text(Component text, int colour, boolean italic) implements Line {
	}

	/**
	 * One table the item is found in: the smaller icon of the tier its chance there falls in, the
	 * table's name, how many containers name it, and the chance there against the right edge.
	 */
	public record Source(LootTier tier, Component name, String containers, String value) implements Line {
	}

	/** How many more tables the item is found in than are listed. */
	public record More(Component text) implements Line {
	}

	/** A key, drawn as a key, and what pressing it does. */
	public record Key(Component key, Component text) implements Line {
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
	 * <p>One container's slot leads with the tier and the chance in that container, since that is
	 * what the slot itself says, and follows them with the chance across the structure. A slot for
	 * the whole structure leads with the structure, and says so beside its tier, since the places
	 * listed under it each name the tier the item falls in there, which may well be another; it
	 * follows with the best single container, and ends by saying that a click finds that container.
	 */
	static Data build(LootStats stats, int variant, int table, int minCount, int maxCount, boolean shift, IntFunction<Component> tableName) {
		final LootStats.Variant found = stats.variant(variant);
		final int containers = found.containersWith();
		final int sourceCount = found.tables().length;
		final List<Line> lines = new ArrayList<Line>();
		lines.add(new Rule());
		if (table >= 0) {
			final int permille = permilleIn(found, table);
			final LootTier tier = LootTier.of(permille);
			lines.add(new TierRow(tier, tier.hint()));
			lines.add(new Meter(Component.translatable("string.explorerscompass.lootChancePerContainer"), permille, LootMath.formatChance(permille)));
			addCount(lines, minCount, maxCount);
			if (containers > 1) {
				lines.add(new Gap(GROUP_GAP));
				lines.add(structureMeter(found));
				lines.add(expected(found));
			}
			addWarning(lines, stats, found);
			if (sourceCount >= 2) {
				lines.add(new Rule());
				addSources(lines, stats, found, shift, tableName);
			}
		} else {
			lines.add(new TierRow(LootTier.of(found.structurePermille()), Component.translatable("string.explorerscompass.lootTierScopeStructure")));
			lines.add(structureMeter(found));
			if (containers > 1) {
				lines.add(expected(found));
				lines.add(new Gap(GROUP_GAP));
				lines.add(new Meter(Component.translatable("string.explorerscompass.lootChanceBestContainer"), found.bestPermille(), LootMath.formatChance(found.bestPermille())));
			}
			addCount(lines, found.minCount(), found.maxCount());
			addWarning(lines, stats, found);
			if (sourceCount >= 1) {
				lines.add(new Rule());
				// A single place is listed outright, since holding Shift to read one line would ask more than it gives
				addSources(lines, stats, found, shift || sourceCount == 1, tableName);
			}
			lines.add(new Gap(KEY_ROW_GAP));
			lines.add(new Key(Component.translatable("string.explorerscompass.lootKeyClick"), Component.translatable("string.explorerscompass.lootJumpToSource")));
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

	/** The chance of finding the variant at least once across the structure. */
	private static Meter structureMeter(LootStats.Variant variant) {
		return new Meter(Component.translatable("string.explorerscompass.lootChanceStructure"), variant.structurePermille(), LootMath.formatProbability(variant.structureChance(), variant.certain()));
	}

	/** How many of the containers that can hold the variant can be expected to, and out of how many. */
	private static Expected expected(LootStats.Variant variant) {
		final Component suffix = Component.translatable("string.explorerscompass.lootExpectedOf", String.valueOf(variant.containersWith()));
		return new Expected(Component.translatable("string.explorerscompass.lootExpectedLabel"), LootMath.formatExpected(variant.expected()), suffix);
	}

	/** How many come at once, where that is more than the one that needs no saying. */
	private static void addCount(List<Line> lines, int minCount, int maxCount) {
		if (maxCount > 1) {
			lines.add(new Count(Component.translatable("string.explorerscompass.lootPerStack"), LootSlots.formatCount(minCount, maxCount)));
		}
	}

	/**
	 * That some tables could not be read and may give the item too, which matters only where the
	 * item is not already certain.
	 */
	private static void addWarning(List<Line> lines, LootStats stats, LootStats.Variant variant) {
		if (stats.unresolvedTables() > 0 && !variant.certain()) {
			lines.add(new Text(Component.translatable("string.explorerscompass.lootUnresolvedWarning", String.valueOf(stats.unresolvedTables())), GuiTheme.TEXT_WARNING, false));
		}
	}

	/**
	 * The tables the variant is found in, likeliest first, as many as fit and a count of the rest,
	 * where they are to be listed, and otherwise the key that lists them with how many there are.
	 */
	private static void addSources(List<Line> lines, LootStats stats, LootStats.Variant variant, boolean listed, IntFunction<Component> tableName) {
		final int[] tables = variant.tables();
		if (!listed) {
			final Component text = Component.translatable("string.explorerscompass.lootShowSources", String.valueOf(tables.length));
			lines.add(new Key(Component.translatable("string.explorerscompass.lootKeyShift"), text));
			return;
		}
		lines.add(new Text(Component.translatable("string.explorerscompass.lootFoundIn"), GuiTheme.TEXT_MUTED, false));
		final int shown = Math.min(MAX_SOURCES, tables.length);
		for (int i = 0; i < shown; i++) {
			final int permille = variant.permilles()[i];
			final String containers = I18n.get("string.explorerscompass.lootTableCount", String.valueOf(stats.containers(tables[i])));
			lines.add(new Source(LootTier.of(permille), tableName.apply(tables[i]), containers, LootMath.formatChance(permille)));
		}
		if (tables.length > shown) {
			lines.add(new More(Component.translatable("string.explorerscompass.lootMoreContainers", String.valueOf(tables.length - shown))));
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
		/** How wide the labels of the meters, counts and expectations are, so that what follows each lines up under the one above. */
		private int labelWidth;
		/** How wide the chances of the meters are, held against the right edge. */
		private int valueWidth;
		/** How long the bars are drawn, which take up whatever room the rows leave between label and chance. */
		private int barWidth;
		/** How wide the container counts and the chances of the sources are, each a column of its own. */
		private int sourceCountWidth;
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
		 *
		 * <p>The rows that cannot give way set the least the tooltip is, which is never less than
		 * the game's own lines above. The rows that can, what a tier stands for, a source's name and
		 * a sentence, widen it further as far as the cap and give way past it, and whatever width
		 * that settles on, the bars stretch to fill.
		 */
		private void measure(Font font) {
			if (measured) {
				return;
			}
			measured = true;
			for (Line line : lines) {
				switch (line) {
					case Meter meter -> {
						labelWidth = Math.max(labelWidth, font.width(meter.label()));
						valueWidth = Math.max(valueWidth, font.width(meter.value()));
					}
					case Count count -> labelWidth = Math.max(labelWidth, font.width(count.label()));
					case Expected expected -> labelWidth = Math.max(labelWidth, font.width(expected.label()));
					case Source source -> {
						sourceCountWidth = Math.max(sourceCountWidth, font.width(source.containers()));
						sourceValueWidth = Math.max(sourceValueWidth, font.width(source.value()));
					}
					default -> {
					}
				}
			}
			final int barX = labelWidth + LABEL_GAP;
			int floor = minWidth;
			int soft = 0;
			for (Line line : lines) {
				switch (line) {
					case TierRow row -> {
						final int nameRight = TIER_INDENT + font.width(row.tier().displayName());
						floor = Math.max(floor, nameRight);
						soft = Math.max(soft, nameRight + DOT_GAP + font.width(DOT) + DOT_GAP + font.width(row.hint()));
					}
					case Meter meter -> floor = Math.max(floor, barX + MIN_BAR + VALUE_GAP + valueWidth);
					case Count count -> floor = Math.max(floor, barX + font.width(count.value()) + SLACK);
					case Expected expected -> floor = Math.max(floor, barX + font.width(expected.value()) + WORD_GAP + font.width(expected.suffix()) + SLACK);
					case Key key -> floor = Math.max(floor, LootSlots.keycapWidth(font, key.key()) + WORD_GAP + font.width(key.text()) + SLACK);
					case Source source -> soft = Math.max(soft, SOURCE_INDENT + font.width(source.name()) + SOURCE_NAME_GAP + sourceCountWidth + SOURCE_VALUE_GAP + sourceValueWidth);
					case More more -> soft = Math.max(soft, SOURCE_INDENT + font.width(more.text()));
					default -> {
					}
				}
			}
			final int wrapWidth = Math.max(MAX_WIDTH, floor);
			boolean ruled = false;
			height = 0;
			for (Line line : lines) {
				List<FormattedCharSequence> split = null;
				switch (line) {
					case Rule rule -> {
						height += ruled ? RULE_HEIGHT : FIRST_RULE_HEIGHT;
						ruled = true;
					}
					case Gap gap -> height += gap.height();
					case TierRow row -> height += TALL_ROW;
					case Key key -> height += TALL_ROW;
					case Text text -> {
						final Component styled = text.italic() ? text.text().copy().withStyle(ChatFormatting.ITALIC) : text.text();
						split = font.split(styled, wrapWidth);
						for (FormattedCharSequence row : split) {
							// A sentence wrapped wider than the cap is never wider than the floor that let it
							soft = Math.max(soft, font.width(row));
						}
						height += ROW * Math.max(1, split.size());
					}
					case Meter meter -> height += ROW;
					case Count count -> height += ROW;
					case Expected expected -> height += ROW;
					case Source source -> height += ROW;
					case More more -> height += ROW;
				}
				wrapped.add(split);
			}
			width = Math.max(floor, Math.min(MAX_WIDTH, soft));
			barWidth = width - barX - VALUE_GAP - valueWidth;
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
			boolean ruled = false;
			int top = y;
			for (int i = 0; i < lines.size(); i++) {
				switch (lines.get(i)) {
					case Rule rule -> {
						final int lineY = top + (ruled ? RULE_OFFSET : 0);
						guiGraphics.fill(x, lineY, right, lineY + 1, LootSlots.TIP_RULE);
						top += ruled ? RULE_HEIGHT : FIRST_RULE_HEIGHT;
						ruled = true;
					}
					case Gap gap -> top += gap.height();
					case TierRow row -> {
						row.tier().drawIcon(guiGraphics, x, top - 1, false);
						final Component name = row.tier().displayName();
						final int nameX = x + TIER_INDENT;
						guiGraphics.drawString(font, name, nameX, top, row.tier().colour, true);
						final int dotX = nameX + font.width(name) + DOT_GAP;
						final int hintX = dotX + font.width(DOT) + DOT_GAP;
						// All or nothing, since half of a range of chances would say another range
						if (hintX + font.width(row.hint()) <= right) {
							guiGraphics.drawString(font, DOT, dotX, top, GuiTheme.TEXT_MUTED, true);
							guiGraphics.drawString(font, row.hint(), hintX, top, GuiTheme.TEXT_SECONDARY, true);
						}
						top += TALL_ROW;
					}
					case Meter meter -> {
						guiGraphics.drawString(font, meter.label(), x, top, GuiTheme.TEXT_SECONDARY, true);
						drawBar(guiGraphics, barX, top, barWidth, meter.barPermille());
						guiGraphics.drawString(font, meter.value(), right - font.width(meter.value()), top, GuiTheme.TEXT_PRIMARY, true);
						top += ROW;
					}
					case Count count -> {
						guiGraphics.drawString(font, count.label(), x, top, GuiTheme.TEXT_SECONDARY, true);
						guiGraphics.drawString(font, count.value(), barX, top, GuiTheme.TEXT_PRIMARY, true);
						top += ROW;
					}
					case Expected expected -> {
						guiGraphics.drawString(font, expected.label(), x, top, GuiTheme.TEXT_SECONDARY, true);
						guiGraphics.drawString(font, expected.value(), barX, top, GuiTheme.TEXT_PRIMARY, true);
						guiGraphics.drawString(font, expected.suffix(), barX + font.width(expected.value()) + WORD_GAP, top, GuiTheme.TEXT_MUTED, true);
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
						source.tier().drawIcon(guiGraphics, x, top, true);
						final int nameX = x + SOURCE_INDENT;
						final int countRight = right - sourceValueWidth - SOURCE_VALUE_GAP;
						final String name = trim(font, source.name().getString(), countRight - sourceCountWidth - SOURCE_NAME_GAP - nameX);
						guiGraphics.drawString(font, name, nameX, top, GuiTheme.TEXT_PRIMARY, true);
						guiGraphics.drawString(font, source.containers(), countRight - font.width(source.containers()), top, GuiTheme.TEXT_MUTED, true);
						guiGraphics.drawString(font, source.value(), right - font.width(source.value()), top, GuiTheme.TEXT_SECONDARY, true);
						top += ROW;
					}
					case More more -> {
						guiGraphics.drawString(font, trim(font, more.text().getString(), width - SOURCE_INDENT), x + SOURCE_INDENT, top, GuiTheme.TEXT_MUTED, true);
						top += ROW;
					}
					case Key key -> {
						// The key stands a pixel above the words beside it, and its lip a pixel below them
						top += 1;
						final int keyWidth = LootSlots.drawKeycap(guiGraphics, font, x, top - 1, key.key());
						guiGraphics.drawString(font, key.text(), x + keyWidth + WORD_GAP, top + 1, GuiTheme.TEXT_SECONDARY, true);
						top += TALL_ROW - 1;
					}
				}
			}
		}

		/**
		 * A chance as a rounded bar on its track, as long as the chance is likely and in the colour of
		 * the tier it falls in, and never so short that a chance there is any of at all draws as none.
		 */
		private static void drawBar(GuiGraphics guiGraphics, int x, int top, int width, int permille) {
			final int barHeight = BAR_BOTTOM - BAR_TOP;
			UiShapes.pill(guiGraphics, x, top + BAR_TOP, width, barHeight, LootSlots.TIP_TRACK);
			// Never shorter than the bar is tall, so that a chance there is any of at all shows as a dot
			final int filled = Math.min(width, Math.max(permille > 0 ? barHeight : 0, Math.round(width * permille / 1000.0F)));
			if (filled > 0) {
				UiShapes.pill(guiGraphics, x, top + BAR_TOP, filled, barHeight, 0xFF000000 | LootTier.of(permille).colour);
			}
		}

		/**
		 * The given text cut short with an ellipsis to fit the given width, measured in the font the
		 * tooltip is drawn in rather than the game's own, which a mod's item may have set apart.
		 */
		private static String trim(Font font, String text, int width) {
			if (font.width(text) <= width) {
				return text;
			}
			return font.plainSubstrByWidth(text, Math.max(0, width - font.width("\u2026"))) + "\u2026";
		}

	}

}
