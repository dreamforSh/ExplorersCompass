package com.chaosthedude.explorerscompass.gui;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

import org.lwjgl.glfw.GLFW;

import com.chaosthedude.explorerscompass.util.RenderUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A column of places to go, one of them the current one, for the sidebar of a screen.
 *
 * <p>Drawn in one of two ways. As tabs, the column is a single track and the current tab is filled
 * with the accent, the way a row of options is; as a list, the current entry is picked out by a
 * faint band and a bar of the accent along its leading edge, and an entry can carry a dot saying
 * something in it differs from how it started. Either way the highlight slides from one entry to the
 * next, and the arrow keys move it while the column has the focus.
 */
@OnlyIn(Dist.CLIENT)
public class NavList extends AbstractWidget {

	public static final int ROW_HEIGHT = 14;
	private static final Object SELECTION = new Object();

	private final List<Component> items;
	private final boolean tabs;
	private final IntConsumer onSelect;
	/** Whether an entry is to carry the dot that says something in it has been changed. */
	private final IntPredicate marked;
	private final UiAnim anim = new UiAnim();
	/** The current entry, or -1 while none of them is, as while a filter shows results from all of them. */
	private int selected;
	private int scroll;

	public NavList(int x, int y, int width, int height, Component message, List<Component> items, boolean tabs, int selected, IntPredicate marked, IntConsumer onSelect) {
		super(x, y, width, height, message);
		this.items = List.copyOf(items);
		this.tabs = tabs;
		this.selected = selected;
		this.marked = marked;
		this.onSelect = onSelect;
	}

	/** How tall a column of tabs for the given number of entries comes out. */
	public static int tabsHeight(int count) {
		return count * ROW_HEIGHT + 2;
	}

	public void setSelected(int selected) {
		this.selected = selected;
	}

	/** Which entry the pointer is over, or -1 for none. */
	public int entryAt(double mouseX, double mouseY) {
		if (!visible || mouseX < getX() || mouseX >= getX() + width || mouseY < getY() || mouseY >= getY() + height) {
			return -1;
		}
		final int index = (int) Math.floor((mouseY - rowsTop()) / ROW_HEIGHT);
		return index >= 0 && index < items.size() ? index : -1;
	}

	private int rowsTop() {
		return getY() + (tabs ? 1 : 0) - scroll;
	}

	private int maxScroll() {
		return Math.max(0, items.size() * ROW_HEIGHT + (tabs ? 2 : 0) - height);
	}

	@Override
	protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		final Font font = Minecraft.getInstance().font;
		anim.frame();
		scroll = Mth.clamp(scroll, 0, maxScroll());
		final int x = getX();
		final int top = rowsTop();
		final boolean keyboardFocus = isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard();
		if (tabs) {
			UiShapes.roundRect(guiGraphics, x, getY(), width, height, GuiTheme.CONTROL_RADIUS + 1, GuiTheme.WIDGET_BACKGROUND);
			UiShapes.roundRectStroke(guiGraphics, x, getY(), width, height, GuiTheme.CONTROL_RADIUS + 1, UiShapes.stroke(), keyboardFocus ? GuiTheme.withAlpha(GuiTheme.ACCENT, 0xB0) : GuiTheme.WIDGET_BORDER);
		}

		guiGraphics.enableScissor(x, getY(), x + width, getY() + height);
		if (selected >= 0 && selected < items.size()) {
			// Eased as a place in the column rather than as a height on the screen, so that scrolling
			// the column carries the highlight with it instead of leaving it to catch up
			final float row = anim.approach(SELECTION, selected, UiAnim.SLIDE_SPEED);
			final float rowY = top + row * ROW_HEIGHT;
			if (tabs) {
				UiShapes.roundRect(guiGraphics, x + 1, rowY, width - 2, ROW_HEIGHT, GuiTheme.CONTROL_RADIUS, GuiTheme.opaque(GuiTheme.ACCENT));
			} else {
				UiShapes.roundRect(guiGraphics, x, rowY, width, ROW_HEIGHT - 1, GuiTheme.CONTROL_RADIUS, GuiTheme.ROW_SELECTED);
				UiShapes.pill(guiGraphics, x + 1, rowY + 3, 2.5F, ROW_HEIGHT - 7, GuiTheme.opaque(GuiTheme.ACCENT));
			}
		}
		for (int i = 0; i < items.size(); i++) {
			final int rowY = top + i * ROW_HEIGHT;
			if (rowY + ROW_HEIGHT <= getY() || rowY >= getY() + height) {
				continue;
			}
			final boolean current = i == selected;
			final boolean hovered = isHovered() && entryAt(mouseX, mouseY) == i;
			final float hover = anim.hover(i, hovered && !current);
			if (hover > 0.01F) {
				UiShapes.roundRect(guiGraphics, x + (tabs ? 1 : 0), rowY, width - (tabs ? 2 : 0), ROW_HEIGHT - (tabs ? 0 : 1), GuiTheme.CONTROL_RADIUS, GuiTheme.withAlpha(0xFFFFFF, Math.round(0x16 * hover)));
			}
			final boolean dot = !tabs && marked.test(i);
			final int textLeft = x + (tabs ? 7 : 8);
			final int textRight = x + width - (dot ? 12 : 6);
			final String label = RenderUtils.trimToWidth(items.get(i).getString(), textRight - textLeft);
			final int color = current ? (tabs ? GuiTheme.ACCENT_INK : GuiTheme.TEXT_PRIMARY) : GuiTheme.lerpColor(GuiTheme.TEXT_SECONDARY, GuiTheme.TEXT_PRIMARY, hover);
			guiGraphics.drawString(font, label, textLeft, rowY + (ROW_HEIGHT - 8) / 2, color, false);
			if (dot) {
				UiShapes.circle(guiGraphics, x + width - 7, rowY + ROW_HEIGHT / 2.0F - 0.5F, 2.0F, GuiTheme.opaque(GuiTheme.ACCENT));
			}
		}
		guiGraphics.disableScissor();
	}

	@Override
	public void onClick(double mouseX, double mouseY) {
		final int index = entryAt(mouseX, mouseY);
		if (index >= 0) {
			select(index);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (!isMouseOver(mouseX, mouseY) || maxScroll() <= 0) {
			return false;
		}
		scroll = Mth.clamp(scroll - (int) Math.signum(scrollY) * ROW_HEIGHT, 0, maxScroll());
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (!active || !visible || items.isEmpty()) {
			return false;
		}
		if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
			final int from = selected < 0 ? (keyCode == GLFW.GLFW_KEY_DOWN ? -1 : items.size()) : selected;
			select(Mth.clamp(from + (keyCode == GLFW.GLFW_KEY_DOWN ? 1 : -1), 0, items.size() - 1));
			return true;
		}
		return false;
	}

	private void select(int index) {
		// Picked again even where it is already current: picking the entry a filter is hiding the rest
		// of is how the filter is left
		selected = index;
		onSelect.accept(index);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
		if (selected >= 0 && selected < items.size()) {
			narrationElementOutput.add(NarratedElementType.TITLE, CommonComponents.optionNameValue(getMessage(), items.get(selected)));
		}
	}

}
