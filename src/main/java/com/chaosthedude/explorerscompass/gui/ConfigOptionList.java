package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.util.RenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;

/**
 * The settings of one config file, laid out as rows under group headings: the name of each setting
 * on the left, and on the right whatever it is changed with — a switch, a choice, a number to type,
 * or a button into the list it holds — with a control beside that to put it back to its default.
 *
 * <p>Every change is written to the file as it is made, so there is nothing to save and nothing to
 * lose to a crash. The rows are rebuilt from the values whenever the list is, so a value changed
 * from elsewhere shows up on the next rebuild rather than being overwritten.
 */
@OnlyIn(Dist.CLIENT)
public class ConfigOptionList extends ContainerObjectSelectionList<ConfigOptionList.Row> {

	/** How much of the width is left over for the scrollbar and the margins beside the rows. */
	public static final int ROW_INSET = 20;
	public static final int ROW_HEIGHT = 24;
	private static final int SCROLLBAR_WIDTH = 6;
	private static final int CONTROL_WIDTH = 116;
	private static final int CONTROL_HEIGHT = 18;
	private static final int RESET_WIDTH = 18;
	private static final int GAP = 4;
	private static final String RESET_GLYPH = "↺";
	private static final int INVALID_BORDER = 0xC0E07A5F;

	private final ConfigScreen parentScreen;

	public ConfigOptionList(ConfigScreen parentScreen, Minecraft mc, int left, int width, int height, int top, int bottom) {
		super(mc, width, height, top, bottom, ROW_HEIGHT);
		this.parentScreen = parentScreen;
		setLeftPos(left);
	}

	/**
	 * Lays the given groups out again, leaving out every setting whose name does not contain the
	 * filter, and every group that is left with nothing under it.
	 */
	public void rebuild(List<ConfigHandler.Group> groups, String filter) {
		clearEntries();
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
			addEntry(new GroupRow(ConfigTexts.groupLabel(group.key())));
			for (OptionRow row : rows) {
				addEntry(row);
			}
		}
		setScrollAmount(Math.min(getScrollAmount(), getMaxScroll()));
	}

	private static boolean matches(ConfigValue<?> value, String needle) {
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

	/** Hands the tick on to the fields in the rows, which blink their cursors by it. */
	public void tick() {
		for (Row row : children()) {
			if (row instanceof OptionRow option && option.control instanceof TransparentTextField field) {
				field.tick();
			}
		}
	}

	/**
	 * Takes the focus off whichever control in the rows holds it. The screen moving its focus
	 * elsewhere does not reach down this far on its own, so a field that had been typed in would go
	 * on drawing itself as focused after the keys had stopped arriving. In this version a container
	 * letting go of a child does not tell the child either, so the fields are told outright.
	 */
	public void clearFocus() {
		for (Row row : children()) {
			if (row instanceof OptionRow option && option.control instanceof TransparentTextField field) {
				field.setFocused(false);
			}
			row.setFocused(null);
		}
		setFocused(null);
	}

	public ConfigScreen getParentScreen() {
		return parentScreen;
	}

	@Override
	protected int getScrollbarPosition() {
		return x1 - SCROLLBAR_WIDTH;
	}

	@Override
	public int getRowWidth() {
		return width - ROW_INSET;
	}

	@Override
	public int getRowLeft() {
		return x0 + width / 2 - getRowWidth() / 2;
	}

	/**
	 * Scrolling over a number turns the number rather than the list, which is the quickest way to
	 * nudge a value by one.
	 */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		final Row row = getHoveredRow(mouseX, mouseY);
		if (row instanceof OptionRow option && option.control instanceof NumberField field && field.isMouseOver(mouseX, mouseY)) {
			return field.mouseScrolled(mouseX, mouseY, delta);
		}
		return super.mouseScrolled(mouseX, mouseY, delta);
	}

	/**
	 * The base class draws a tiled backdrop behind the rows and rules across the top and bottom
	 * edges. This list sits on the screen's own panel, so both are left out, and the rows are clipped
	 * to the list so that the ones being scrolled past do not spill over the filter above it.
	 */
	@Override
	public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
		RenderUtils.enableScissor(x0, y0, x1, y1);
		renderList(poseStack, mouseX, mouseY, partialTicks);
		RenderUtils.disableScissor();
	}

	@Override
	protected void renderList(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
		final int bandLeft = getRowLeft() - 3;
		final int bandRight = getRowLeft() + getRowWidth() + 3;
		final Row hoveredRow = getHoveredRow(mouseX, mouseY);

		final int firstRow = Math.max(0, (int) Math.floor(getScrollAmount() / itemHeight) - 1);
		final int visibleRowCount = (int) Math.ceil((double) (y1 - y0) / itemHeight) + 3;
		final int lastRow = Math.min(getItemCount(), firstRow + visibleRowCount);
		for (int j = firstRow; j < lastRow; ++j) {
			final int rowTop = getRowTop(j);
			final int bandTop = rowTop - 2;
			final int bandBottom = bandTop + itemHeight;
			if (bandBottom < y0 || bandTop > y1) {
				continue;
			}

			final Row row = getEntry(j);
			final boolean hovered = Objects.equals(row, hoveredRow);
			if (hovered && row instanceof OptionRow) {
				RenderUtils.drawRect(bandLeft, bandTop, bandRight, bandBottom, GuiTheme.ROW_HOVER);
			}
			row.render(poseStack, j, rowTop, getRowLeft(), getRowWidth(), itemHeight - 4, mouseX, mouseY, hovered, partialTicks);
		}

		if (getMaxScroll() > 0) {
			final int left = getScrollbarPosition();
			final int right = left + SCROLLBAR_WIDTH;
			int thumbHeight = (int) ((float) ((y1 - y0) * (y1 - y0)) / (float) getMaxPosition());
			thumbHeight = Mth.clamp(thumbHeight, 32, y1 - y0 - 8);
			int thumbTop = (int) getScrollAmount() * (y1 - y0 - thumbHeight) / getMaxScroll() + y0;
			if (thumbTop < y0) {
				thumbTop = y0;
			}
			RenderUtils.drawRect(left, y0, right, y1, GuiTheme.SCROLLBAR_TRACK);
			RenderUtils.drawRect(left + 1, thumbTop, right - 1, thumbTop + thumbHeight, GuiTheme.SCROLLBAR_THUMB);
		}
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

	/** A heading over a run of settings that belong together. */
	@OnlyIn(Dist.CLIENT)
	public static class GroupRow extends Row {

		private final Component title;

		GroupRow(Component title) {
			this.title = title;
		}

		@Override
		public void render(PoseStack poseStack, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float partialTicks) {
			final Minecraft mc = Minecraft.getInstance();
			// Sits at the bottom of its row, with a rule under it, so that it reads as belonging to
			// what follows rather than to what came before
			final int textY = top + height - mc.font.lineHeight - 1;
			mc.font.draw(poseStack, title, left + 2, textY, GuiTheme.ACCENT);
			RenderUtils.drawRect(left + 2, top + height + 1, left + width - 2, top + height + 2, GuiTheme.ROW_SECTION_SEPARATOR);
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
		private final TransparentButton reset;
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
			reset = new TransparentButton(0, 0, RESET_WIDTH, CONTROL_HEIGHT, Component.literal(RESET_GLYPH), (button) -> {
				resetQuietly();
				list.save();
			});
			widgets = List.of(control, reset);
			refresh();
		}

		/** The control a value of this kind is changed with. */
		private AbstractWidget createControl() {
			final Object current = value.get();
			if (current instanceof Boolean) {
				return new TransparentButton(0, 0, CONTROL_WIDTH, CONTROL_HEIGHT, Component.empty(), (button) -> {
					apply(!(Boolean) value.get());
				});
			}
			if (current instanceof Enum<?>) {
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
			if (current instanceof Number) {
				return new NumberField(this, Minecraft.getInstance().font, CONTROL_WIDTH, CONTROL_HEIGHT);
			}
			if (current instanceof List<?>) {
				return new TransparentButton(0, 0, CONTROL_WIDTH, CONTROL_HEIGHT, Component.empty(), (button) -> {
					list.getParentScreen().openListEditor(value, label);
				});
			}
			// A plain string, of which the mod has none, is still editable rather than left out
			return new TextField(this, Minecraft.getInstance().font, CONTROL_WIDTH, CONTROL_HEIGHT);
		}

		/** The next or previous of the choices a setting offers, wrapping round at either end. */
		private static Enum<?> step(Enum<?> current, int by) {
			final Enum<?>[] constants = current.getDeclaringClass().getEnumConstants();
			return constants[Math.floorMod(current.ordinal() + by, constants.length)];
		}

		/** Sets the value, writes it out, and brings the row up to date. */
		void apply(Object newValue) {
			if (Objects.equals(newValue, value.get())) {
				return;
			}
			value.set(newValue);
			list.save();
			refresh();
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
			} else if (control instanceof TransparentButton button) {
				if (current instanceof Boolean flag) {
					button.setMessage(Component.translatable(ConfigTexts.PREFIX + (flag ? "on" : "off")));
					button.setHighlighted(flag);
				} else if (current instanceof Enum<?> constant) {
					button.setMessage(ConfigTexts.enumLabel(constant));
				} else if (current instanceof List<?> entries) {
					button.setMessage(Component.translatable(ConfigTexts.PREFIX + "edit", String.valueOf(entries.size())));
				}
			}
			reset.active = isModified();
		}

		@Override
		public void render(PoseStack poseStack, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float partialTicks) {
			final Minecraft mc = Minecraft.getInstance();
			final int controlLeft = left + width - RESET_WIDTH - GAP - CONTROL_WIDTH;
			final int widgetTop = top + (height - CONTROL_HEIGHT) / 2;

			// The name, cut down to the room the controls leave it, and picked out while the value is
			// not the one it started as
			final int labelWidth = controlLeft - left - 4 - GAP;
			if (labelWidth != cachedLabelWidth) {
				cachedLabelWidth = labelWidth;
				cachedLabel = RenderUtils.trimToWidth(label.getString(), labelWidth);
			}
			mc.font.draw(poseStack, cachedLabel, left + 4, top + (height - mc.font.lineHeight) / 2 + 1, isModified() ? GuiTheme.ACCENT : GuiTheme.TEXT_PRIMARY);

			control.x = controlLeft;
			control.y = widgetTop;
			control.render(poseStack, mouseX, mouseY, partialTicks);
			reset.x = left + width - RESET_WIDTH;
			reset.y = widgetTop;
			reset.render(poseStack, mouseX, mouseY, partialTicks);
		}

		@Override
		public List<Component> getTooltipLines(double mouseX, double mouseY) {
			if (reset.isMouseOver(mouseX, mouseY)) {
				return List.of(Component.translatable(ConfigTexts.PREFIX + "reset", ConfigTexts.format(value.getDefault())));
			}
			return ConfigTexts.tooltip(value);
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
		private final ForgeConfigSpec.Range<?> range;
		private boolean invalid;
		private boolean showing;

		NumberField(OptionRow row, Font font, int width, int height) {
			super(font, 0, 0, width, height, Component.empty());
			this.row = row;
			type = row.value.get().getClass();
			range = ConfigTexts.rangeOf(row.value);
			setMaxLength(24);
			setResponder(this::onEdited);
		}

		void showValue(Object value) {
			showing = true;
			setValue(ConfigTexts.format(value));
			showing = false;
			invalid = false;
		}

		private void onEdited(String text) {
			if (showing) {
				return;
			}
			final Object parsed = parse(text.trim());
			invalid = parsed == null || (range != null && !range.test(parsed));
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
		public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
			if (!isMouseOver(mouseX, mouseY) || delta == 0.0D) {
				return false;
			}
			final double step = (Screen.hasShiftDown() ? 10.0D : 1.0D) * Math.signum(delta);
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
			if (!focused && invalid) {
				// Whatever was half typed is let go of in favour of the value that is actually in force
				showValue(row.value.get());
			}
		}

		@Override
		public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
			super.render(poseStack, mouseX, mouseY, partialTicks);
			if (invalid) {
				RenderUtils.drawInnerOutline(x, y, x + width, y + height, INVALID_BORDER);
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
