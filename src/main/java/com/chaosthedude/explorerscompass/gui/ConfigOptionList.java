package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.util.RenderUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;

/**
 * The settings of one config file, laid out as rows: the name of each setting on the left, and on
 * the right whatever it is changed with. A setting that is on or off is a switch; a choice between a
 * few options lays them side by side, or steps through them where they do not fit; a number in a
 * short range is picked off a slider and any other is typed; a list opens a screen of its own. A
 * setting that is not at its default carries a dot before its name and a round button before its
 * control that puts the default back.
 *
 * <p>Every change is written to the file as it is made, so there is nothing to save and nothing to
 * lose to a crash; a slider writes once it is let go of rather than for every value it passes. The
 * rows are rebuilt from the values whenever the list is, so a value changed from elsewhere shows up
 * on the next rebuild rather than being overwritten.
 */
@OnlyIn(Dist.CLIENT)
public class ConfigOptionList extends ContainerObjectSelectionList<ConfigOptionList.Row> {

	/** How much of the width is left over for the scrollbar and the margins beside the rows. */
	public static final int ROW_INSET = 20;
	public static final int ROW_HEIGHT = 22;
	/**
	 * The widest the rows run, which the screen holds the list to. On a wide window a name and the
	 * control that changes it would otherwise end up a screen apart, which is further than the eye
	 * carries a line across.
	 */
	public static final int MAX_ROW_WIDTH = 460;
	private static final int SCROLLBAR_WIDTH = 6;
	private static final int CONTROL_HEIGHT = 14;
	/** How wide a field, a list's button, or a choice too wide to lay its options side by side stands. */
	private static final int CONTROL_WIDTH = 116;
	private static final int NUMBER_WIDTH = 84;
	private static final int SLIDER_TRACK = 84;
	/** The widest a range may span and still be picked off a slider rather than typed. */
	private static final int SLIDER_MAX_SPAN = 360;
	private static final int GAP = 6;
	/** Where the name of a setting starts, past the dot that marks it as changed. */
	private static final int LABEL_INDENT = 10;
	private static final String RESET_GLYPH = "↺";
	private static final String NEXT_GLYPH = "›";

	private final ConfigScreen parentScreen;
	private final ListChrome chrome = new ListChrome();

	public ConfigOptionList(ConfigScreen parentScreen, Minecraft mc, int left, int width, int top, int bottom) {
		super(mc, width, bottom - top, top, ROW_HEIGHT);
		this.parentScreen = parentScreen;
		setX(left);
	}

	/**
	 * Lays the given groups out again, leaving out every setting whose name does not contain the
	 * filter, and every group that is left with nothing under it.
	 *
	 * @param headed whether each group is shown under a heading of its own, which a single group
	 *               shown on its own does not need, its name standing in the sidebar already
	 */
	public void rebuild(List<ConfigHandler.Group> groups, String filter, boolean headed) {
		clearEntries();
		chrome.reset();
		final String needle = filter.trim().toLowerCase(Locale.ROOT);
		for (ConfigHandler.Group group : groups) {
			final List<OptionRow> rows = new ArrayList<OptionRow>();
			for (ConfigValue<?> value : group.values()) {
				if (needle.isEmpty() || matches(value, needle)) {
					rows.add(OptionRow.create(this, value));
				}
			}
			if (rows.isEmpty()) {
				continue;
			}
			if (headed) {
				addEntry(new GroupRow(ConfigTexts.groupLabel(group.key())));
			}
			for (OptionRow row : rows) {
				addEntry(row);
			}
		}
		setScrollAmount(Math.min(getScrollAmount(), getMaxScroll()));
	}

	static boolean matches(ConfigValue<?> value, String needle) {
		return ConfigTexts.label(value).getString().toLowerCase(Locale.ROOT).contains(needle)
				|| ConfigTexts.keyOf(value).toLowerCase(Locale.ROOT).contains(needle);
	}

	/** Puts every setting in the rows back to its default, and writes the lot out once. */
	public void resetAll() {
		for (Row row : children()) {
			if (row instanceof OptionRow option) {
				option.resetQuietly();
			}
		}
		save();
	}

	/** Writes the file the rows belong to. */
	void save() {
		parentScreen.saveCurrentSpec();
	}

	public ConfigScreen getParentScreen() {
		return parentScreen;
	}

	@Override
	protected int getScrollbarPosition() {
		return getRight() - SCROLLBAR_WIDTH;
	}

	@Override
	public int getRowWidth() {
		return width - ROW_INSET;
	}

	@Override
	public int getRowLeft() {
		return getX() + width / 2 - getRowWidth() / 2;
	}

	@Override
	protected void renderListBackground(GuiGraphics guiGraphics) {
	}

	@Override
	protected void renderListSeparators(GuiGraphics guiGraphics) {
	}

	@Override
	protected boolean scrollbarVisible() {
		return false;
	}

	/**
	 * Scrolling over a number turns the number rather than the list, which is the quickest way to
	 * nudge a value by one.
	 */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		final Row row = getHoveredRow(mouseX, mouseY);
		if (row instanceof OptionRow option && option.control instanceof NumberField field && field.isMouseOver(mouseX, mouseY)) {
			return field.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	protected void renderListItems(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		final int bandLeft = getRowLeft() - 3;
		final int bandRight = getRowLeft() + getRowWidth() + 3;
		final Row hoveredRow = getHoveredRow(mouseX, mouseY);
		chrome.beginFrame();

		final int firstRow = Math.max(0, (int) Math.floor(getScrollAmount() / itemHeight) - 1);
		final int visibleRowCount = (int) Math.ceil((double) height / itemHeight) + 3;
		final int lastRow = Math.min(getItemCount(), firstRow + visibleRowCount);
		for (int j = firstRow; j < lastRow; ++j) {
			final int rowTop = getRowTop(j);
			final int bandTop = rowTop - 2;
			final int bandBottom = bandTop + itemHeight;
			if (bandBottom < getY() || bandTop > getBottom()) {
				continue;
			}

			final Row row = getEntry(j);
			final boolean hovered = Objects.equals(row, hoveredRow);
			if (row instanceof OptionRow) {
				chrome.drawRowBand(guiGraphics, row, bandLeft, bandTop, bandRight, bandBottom, hovered, false);
			}
			row.render(guiGraphics, j, rowTop, getRowLeft(), getRowWidth(), itemHeight - 4, mouseX, mouseY, hovered, partialTicks);
		}
	}

	@Override
	protected void renderDecorations(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		ListChrome.drawScrollbar(guiGraphics, getScrollbarPosition(), getY(), getBottom(), SCROLLBAR_WIDTH, getScrollAmount(), getMaxScroll(), getMaxPosition(), mouseX, mouseY);
	}

	/** The row under the pointer, or null when the pointer is not over the list at all. */
	public Row getHoveredRow(double mouseX, double mouseY) {
		return isMouseOver(mouseX, mouseY) ? getEntryAtPosition(mouseX, mouseY) : null;
	}

	/** One row of the list: a heading, or a setting with its controls. */
	@OnlyIn(Dist.CLIENT)
	public abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {

		/** What to explain about this row while the pointer rests on it, or null for nothing. */
		public List<Component> getTooltipLines(double mouseX, double mouseY) {
			return null;
		}

	}

	/** A heading over a run of settings that belong together, shown while a filter gathers settings from several. */
	@OnlyIn(Dist.CLIENT)
	public static class GroupRow extends Row {

		private final Component title;

		GroupRow(Component title) {
			this.title = title;
		}

		@Override
		public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float partialTicks) {
			final Font font = Minecraft.getInstance().font;
			// Sits at the bottom of its row, with a rule running on from it, so that it reads as
			// belonging to what follows rather than to what came before
			final int textY = top + height - font.lineHeight - 1;
			guiGraphics.drawString(font, title, left + 2, textY, GuiTheme.ACCENT_LIGHT, false);
			UiShapes.hairline(guiGraphics, left + 2 + font.width(title) + 6, left + width - 2, textY + 4, GuiTheme.withAlpha(GuiTheme.PANEL_BORDER, 0x40));
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return List.of();
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return List.of();
		}

	}

	/** One setting: its name, the control that changes it, and the control that puts it back. */
	@OnlyIn(Dist.CLIENT)
	public static class OptionRow extends Row {

		private final ConfigOptionList list;
		private final ConfigValue<Object> value;
		private final Component label;
		private final AbstractWidget control;
		private final GlyphButton reset;
		private final List<AbstractWidget> widgets;
		private String cachedLabel;
		private int cachedLabelWidth = -1;

		@SuppressWarnings("unchecked")
		static OptionRow create(ConfigOptionList list, ConfigValue<?> value) {
			return new OptionRow(list, (ConfigValue<Object>) value);
		}

		private OptionRow(ConfigOptionList list, ConfigValue<Object> value) {
			this.list = list;
			this.value = value;
			label = ConfigTexts.label(value);
			control = createControl();
			reset = new GlyphButton(0, 0, RESET_GLYPH, Component.translatable(ConfigTexts.PREFIX + "reset", ConfigTexts.format(value.getDefault())), () -> {
				resetQuietly();
				list.save();
			});
			widgets = List.of(control, reset);
			refresh();
		}

		/** The control a value of this kind is changed with. */
		private AbstractWidget createControl() {
			final Font font = Minecraft.getInstance().font;
			final Object current = value.get();
			if (current instanceof Boolean flag) {
				return new ToggleSwitch(0, 0, label, flag, this::apply);
			}
			if (current instanceof Enum<?> constant) {
				final Enum<?>[] constants = constant.getDeclaringClass().getEnumConstants();
				final List<Component> names = new ArrayList<Component>();
				for (Enum<?> option : constants) {
					names.add(ConfigTexts.enumLabel(option));
				}
				// Side by side where they fit beside the name, which leaves every option in view
				if (SegmentedControl.naturalWidth(font, names) <= Math.min(200, list.getRowWidth() * 0.55F)) {
					return new SegmentedControl(0, 0, label, names, constant.ordinal(), (index) -> apply(constants[index]));
				}
				// The left button goes forward through the choices and the right button back, since a
				// choice passed by should not take a trip round the rest to reach again
				return new TransparentButton(0, 0, CONTROL_WIDTH, CONTROL_HEIGHT, Component.empty(), (button) -> {
					apply(step((Enum<?>) value.get(), 1));
				}) {
					@Override
					public boolean mouseClicked(double mouseX, double mouseY, int button) {
						if (button == 1 && clicked(mouseX, mouseY)) {
							apply(step((Enum<?>) value.get(), -1));
							playDownSound(Minecraft.getInstance().getSoundManager());
							return true;
						}
						return super.mouseClicked(mouseX, mouseY, button);
					}
				};
			}
			final ModConfigSpec.Range<?> range = value.getSpec().getRange();
			if ((current instanceof Integer || current instanceof Long) && range != null
					&& range.getMin() instanceof Number min && range.getMax() instanceof Number max
					&& max.longValue() - min.longValue() <= SLIDER_MAX_SPAN) {
				final int low = min.intValue();
				final int high = max.intValue();
				final boolean swatch = ConfigTexts.swatch(value, low) != 0;
				return new ValueSlider(0, 0, ValueSlider.widthFor(font, low, high, SLIDER_TRACK, swatch), label, low, high, ((Number) current).intValue(),
						(v) -> ConfigTexts.swatch(value, v), (v) -> applyQuietly(current instanceof Long ? (Object) Long.valueOf(v) : (Object) Integer.valueOf(v)), list::save);
			}
			if (current instanceof Number) {
				return new NumberField(this, font, NUMBER_WIDTH, CONTROL_HEIGHT);
			}
			if (current instanceof List<?>) {
				return new TransparentButton(0, 0, CONTROL_WIDTH, CONTROL_HEIGHT, Component.empty(), (button) -> {
					list.getParentScreen().openListEditor(value, label);
				});
			}
			// A plain string, of which the mod has none, is still editable rather than left out
			return new TextField(this, font, CONTROL_WIDTH, CONTROL_HEIGHT);
		}

		/** The next or previous of the choices a setting offers, wrapping round at either end. */
		private static Enum<?> step(Enum<?> current, int by) {
			final Enum<?>[] constants = current.getDeclaringClass().getEnumConstants();
			return constants[Math.floorMod(current.ordinal() + by, constants.length)];
		}

		/** Sets the value, writes it out, and brings the row up to date. */
		void apply(Object newValue) {
			if (applyQuietly(newValue)) {
				list.save();
			}
		}

		/** Sets the value and brings the row up to date without writing it out, and answers whether it changed. */
		boolean applyQuietly(Object newValue) {
			if (Objects.equals(newValue, value.get())) {
				return false;
			}
			value.set(newValue);
			refresh();
			return true;
		}

		/** Puts the value back to its default without writing, for a reset of many at once. */
		void resetQuietly() {
			if (!isModified()) {
				return;
			}
			value.set(value.getDefault());
			refresh();
		}

		boolean isModified() {
			return !Objects.equals(value.get(), value.getDefault());
		}

		/** Brings what the controls show into line with the value. */
		void refresh() {
			final Object current = value.get();
			if (control instanceof NumberField field) {
				field.showValue(current);
			} else if (control instanceof TextField field) {
				field.showValue(current);
			} else if (control instanceof ToggleSwitch toggle) {
				toggle.setOn(Boolean.TRUE.equals(current));
			} else if (control instanceof SegmentedControl segmented) {
				segmented.setSelected(((Enum<?>) current).ordinal());
			} else if (control instanceof ValueSlider slider) {
				slider.setValue(((Number) current).intValue());
			} else if (control instanceof TransparentButton button) {
				if (current instanceof Enum<?> constant) {
					button.setMessage(ConfigTexts.enumLabel(constant).copy().append("  " + NEXT_GLYPH));
				} else if (current instanceof List<?> entries) {
					button.setMessage(Component.translatable(ConfigTexts.PREFIX + "edit", String.valueOf(entries.size())).append("  " + NEXT_GLYPH));
				}
			}
			reset.visible = isModified();
		}

		/** Whether the control holds text that is not something the setting can take. */
		private boolean isInvalid() {
			return control instanceof TransparentTextField field && field.isInvalid();
		}

		@Override
		public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float partialTicks) {
			final Font font = Minecraft.getInstance().font;
			final int right = left + width;
			final int midY = top + height / 2;

			control.setX(right - control.getWidth());
			control.setY(midY - control.getHeight() / 2);
			reset.setX(control.getX() - GAP - GlyphButton.SIZE);
			reset.setY(midY - GlyphButton.SIZE / 2);

			// The name, cut down to the room the controls leave it, with a dot in front of it while the
			// value is not the one it started as
			final boolean modified = isModified();
			if (modified) {
				UiShapes.circle(guiGraphics, left + 4, midY, 1.75F, GuiTheme.opaque(GuiTheme.ACCENT));
			}
			final int labelWidth = reset.getX() - GAP - (left + LABEL_INDENT);
			if (labelWidth != cachedLabelWidth) {
				cachedLabelWidth = labelWidth;
				cachedLabel = RenderUtils.trimToWidth(label.getString(), labelWidth);
			}
			guiGraphics.drawString(font, cachedLabel, left + LABEL_INDENT, midY - 4, isInvalid() ? GuiTheme.TEXT_DANGER : GuiTheme.TEXT_PRIMARY, false);

			control.render(guiGraphics, mouseX, mouseY, partialTicks);
			reset.render(guiGraphics, mouseX, mouseY, partialTicks);
		}

		/** A switch takes a click anywhere along its row, which is a far larger target than the switch. */
		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (super.mouseClicked(mouseX, mouseY, button)) {
				return true;
			}
			if (button == 0 && control instanceof ToggleSwitch toggle && toggle.active) {
				toggle.playDownSound(Minecraft.getInstance().getSoundManager());
				toggle.onPress();
				setFocused(toggle);
				return true;
			}
			return false;
		}

		@Override
		public List<Component> getTooltipLines(double mouseX, double mouseY) {
			if (reset.visible && reset.isMouseOver(mouseX, mouseY)) {
				return List.of(Component.translatable(ConfigTexts.PREFIX + "reset", ConfigTexts.format(value.getDefault())));
			}
			return ConfigTexts.tooltip(value, isInvalid());
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return widgets;
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return widgets;
		}

	}

	/**
	 * A number typed in, held to the range the setting allows. A value outside it, or not a number at
	 * all, is shown with a red border and not applied; letting go of the field puts the last good
	 * value back. Scrolling over the field nudges it by one, or by ten with shift held.
	 */
	@OnlyIn(Dist.CLIENT)
	static class NumberField extends TransparentTextField {

		private final OptionRow row;
		private final Class<?> type;
		private final ModConfigSpec.Range<?> range;
		private boolean showing;

		NumberField(OptionRow row, Font font, int width, int height) {
			super(font, 0, 0, width, height, Component.empty());
			this.row = row;
			type = row.value.get().getClass();
			range = row.value.getSpec().getRange();
			setMaxLength(24);
			// A number cannot be left empty, so there is nothing for the cross to do
			setClearable(false);
			setResponder(this::onEdited);
		}

		void showValue(Object value) {
			showing = true;
			setValue(ConfigTexts.format(value));
			showing = false;
			setInvalid(false);
		}

		private void onEdited(String text) {
			if (showing) {
				return;
			}
			final Object parsed = parse(text.trim());
			final boolean invalid = parsed == null || (range != null && !range.test(parsed));
			setInvalid(invalid);
			if (!invalid) {
				row.apply(parsed);
			}
		}

		/** The text as the kind of number the setting holds, or null where it is not one. */
		private Object parse(String text) {
			try {
				if (type == Integer.class) {
					return Integer.valueOf(text);
				}
				if (type == Long.class) {
					return Long.valueOf(text);
				}
				return Double.valueOf(text);
			} catch (NumberFormatException e) {
				return null;
			}
		}

		@Override
		public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
			if (!isMouseOver(mouseX, mouseY) || scrollY == 0.0D) {
				return false;
			}
			final double step = (Screen.hasShiftDown() ? 10.0D : 1.0D) * Math.signum(scrollY);
			final Object current = row.value.get();
			Object next;
			if (type == Integer.class) {
				next = Integer.valueOf((int) clamp(((Integer) current).intValue() + step));
			} else if (type == Long.class) {
				next = Long.valueOf((long) clamp(((Long) current).longValue() + step));
			} else {
				next = Double.valueOf(clamp(((Double) current).doubleValue() + step));
			}
			row.apply(next);
			return true;
		}

		/** Held inside the range, where the setting has one. */
		private double clamp(double candidate) {
			if (range == null) {
				return candidate;
			}
			final double min = ((Number) range.getMin()).doubleValue();
			final double max = ((Number) range.getMax()).doubleValue();
			return Mth.clamp(candidate, min, max);
		}

		@Override
		public void setFocused(boolean focused) {
			super.setFocused(focused);
			if (!focused && isInvalid()) {
				// Whatever was half typed is let go of in favour of the value that is actually in force
				showValue(row.value.get());
			}
		}

	}

	/** Free text, applied as it is typed. */
	@OnlyIn(Dist.CLIENT)
	static class TextField extends TransparentTextField {

		private final OptionRow row;
		private boolean showing;

		TextField(OptionRow row, Font font, int width, int height) {
			super(font, 0, 0, width, height, Component.empty());
			this.row = row;
			setMaxLength(256);
			setResponder((text) -> {
				if (!showing) {
					row.apply(text);
				}
			});
		}

		void showValue(Object value) {
			showing = true;
			setValue(String.valueOf(value));
			showing = false;
		}

	}

}
