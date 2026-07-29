package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.chaosthedude.explorerscompass.util.RenderUtils;
import com.chaosthedude.explorerscompass.util.StructureUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * The list of mods whatever the compass is listing comes from, dropped down beside the sidebar so
 * that the list can be narrowed to one of them. It is drawn and clicked by the screen that owns it
 * rather than being a widget of its own, which is what lets it sit above the selection list instead
 * of being covered by it.
 */
@OnlyIn(Dist.CLIENT)
public class ModFilterPanel {

	private static final int WIDTH = 150;
	private static final int ROW_HEIGHT = 13;
	private static final int HEADER_HEIGHT = 14;
	private static final int PADDING = 3;
	private static final int SCROLLBAR_WIDTH = 4;

	private static final int BACKGROUND_TOP = 0xF2121418;
	private static final int BACKGROUND_BOTTOM = 0xF20A0B0E;

	/** One mod, or, when the namespace is null, every mod at once. */
	public static class ModOption {

		private final String namespace;
		private final String displayName;
		private final int entryCount;

		public ModOption(String namespace, String displayName, int entryCount) {
			this.namespace = namespace;
			this.displayName = displayName;
			this.entryCount = entryCount;
		}

		public String getNamespace() {
			return namespace;
		}

		public String getDisplayName() {
			return displayName;
		}

		/** How many of the listed structures or biomes this mod accounts for. */
		public int getEntryCount() {
			return entryCount;
		}

	}

	private final ExplorersCompassScreen parentScreen;
	private final Minecraft mc;
	private List<ModOption> options = new ArrayList<ModOption>();
	private boolean open;
	private int x;
	private int y;
	private int height;
	private int scrollRow;

	public ModFilterPanel(ExplorersCompassScreen parentScreen, Minecraft mc) {
		this.parentScreen = parentScreen;
		this.mc = mc;
	}

	/**
	 * Collects the mods that contribute the given keys, most prolific first among the mods and with
	 * Minecraft itself always at the top, each carrying how many of them it accounts for.
	 */
	public static List<ModOption> collectOptions(List<ResourceLocation> keys) {
		final Map<String, Integer> countsByNamespace = new LinkedHashMap<String, Integer>();
		for (ResourceLocation key : keys) {
			countsByNamespace.merge(key.getNamespace(), 1, Integer::sum);
		}

		final List<ModOption> options = new ArrayList<ModOption>(countsByNamespace.size() + 1);
		for (Map.Entry<String, Integer> entry : countsByNamespace.entrySet()) {
			// The source is resolved from a key rather than from the namespace, so that the mod's own
			// display name is what shows wherever it is known
			final String displayName = StructureUtils.getPrettySourceName(new ResourceLocation(entry.getKey(), "any"));
			options.add(new ModOption(entry.getKey(), displayName, entry.getValue()));
		}
		options.sort(Comparator.comparing((ModOption option) -> !option.getNamespace().equals("minecraft")).thenComparing(ModOption::getDisplayName, String.CASE_INSENSITIVE_ORDER));
		options.add(0, new ModOption(null, I18n.get("string.explorerscompass.allMods"), keys.size()));
		return options;
	}

	public boolean isOpen() {
		return open;
	}

	/**
	 * Drops the panel down beside the given point, sized to what is left of the screen below it and
	 * scrolled to whichever mod is currently picked.
	 */
	public void open(List<ResourceLocation> keys, int anchorX, int anchorY, int screenWidth, int screenHeight, String selectedNamespace) {
		options = collectOptions(keys);
		open = true;
		x = Math.min(anchorX, Math.max(0, screenWidth - WIDTH - 4));

		// The panel drops down from the anchor when there is room for it, and rises from it when there
		// is more room above, rather than being cut off at the bottom of the screen
		final int spaceBelow = screenHeight - anchorY - 8;
		final int spaceAbove = anchorY - 8;
		final int fullHeight = HEADER_HEIGHT + options.size() * ROW_HEIGHT + PADDING * 2;
		height = Math.min(fullHeight, Math.max(spaceBelow, spaceAbove));
		y = spaceBelow >= height ? anchorY : Math.max(4, anchorY - height);

		scrollRow = 0;
		scrollToSelected(selectedNamespace);
	}

	public void close() {
		open = false;
	}

	public void toggle(List<ResourceLocation> keys, int anchorX, int anchorY, int screenWidth, int screenHeight, String selectedNamespace) {
		if (open) {
			close();
		} else {
			open(keys, anchorX, anchorY, screenWidth, screenHeight, selectedNamespace);
		}
	}

	private void scrollToSelected(String selectedNamespace) {
		if (selectedNamespace == null) {
			return;
		}
		for (int i = 0; i < options.size(); i++) {
			if (selectedNamespace.equals(options.get(i).getNamespace())) {
				scrollRow = Mth.clamp(i - visibleRows() / 2, 0, maxScrollRow());
				return;
			}
		}
	}

	private int visibleRows() {
		return Math.max(1, (height - HEADER_HEIGHT - PADDING * 2) / ROW_HEIGHT);
	}

	private int maxScrollRow() {
		return Math.max(0, options.size() - visibleRows());
	}

	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, String selectedNamespace) {
		if (!open) {
			return;
		}

		RenderUtils.drawPanel(x, y, x + WIDTH, y + height, BACKGROUND_TOP, BACKGROUND_BOTTOM, GuiTheme.PANEL_BORDER);
		guiGraphics.drawString(mc.font, I18n.get("string.explorerscompass.filterByMod"), x + PADDING + 2, y + PADDING + 1, GuiTheme.TEXT_MUTED, false);
		RenderUtils.drawRect(x + PADDING, y + HEADER_HEIGHT - 1, x + WIDTH - PADDING, y + HEADER_HEIGHT, GuiTheme.ROW_SEPARATOR);

		final boolean scrollable = maxScrollRow() > 0;
		final int rowsLeft = x + PADDING;
		final int rowsRight = x + WIDTH - PADDING - (scrollable ? SCROLLBAR_WIDTH + 2 : 0);
		final int lastRow = Math.min(options.size(), scrollRow + visibleRows());
		for (int i = scrollRow; i < lastRow; i++) {
			final ModOption option = options.get(i);
			final int rowTop = rowTop(i);
			final boolean selected = option.getNamespace() == null ? selectedNamespace == null : option.getNamespace().equals(selectedNamespace);
			final boolean hovered = mouseX >= rowsLeft && mouseX < rowsRight && mouseY >= rowTop && mouseY < rowTop + ROW_HEIGHT;

			if (selected) {
				RenderUtils.drawHorizontalGradient(rowsLeft, rowTop, rowsRight, rowTop + ROW_HEIGHT, GuiTheme.ROW_SELECTED_LEFT, GuiTheme.ROW_SELECTED_RIGHT);
			} else if (hovered) {
				RenderUtils.drawRect(rowsLeft, rowTop, rowsRight, rowTop + ROW_HEIGHT, GuiTheme.ROW_HOVER);
			}

			final String count = String.valueOf(option.getEntryCount());
			final int countWidth = mc.font.width(count);
			final String name = RenderUtils.trimToWidth(option.getDisplayName(), rowsRight - rowsLeft - countWidth - 12);
			guiGraphics.drawString(mc.font, name, rowsLeft + 4, rowTop + 3, selected ? GuiTheme.ACCENT : (hovered ? GuiTheme.TEXT_PRIMARY : GuiTheme.TEXT_SECONDARY), false);
			guiGraphics.drawString(mc.font, count, rowsRight - countWidth - 4, rowTop + 3, GuiTheme.TEXT_MUTED, false);
		}

		if (scrollable) {
			final int trackTop = y + HEADER_HEIGHT + PADDING;
			final int trackBottom = y + height - PADDING;
			final int trackLeft = x + WIDTH - PADDING - SCROLLBAR_WIDTH;
			RenderUtils.drawRect(trackLeft, trackTop, trackLeft + SCROLLBAR_WIDTH, trackBottom, GuiTheme.SCROLLBAR_TRACK);
			final int thumbHeight = Math.max(12, (trackBottom - trackTop) * visibleRows() / options.size());
			final int thumbTop = trackTop + (trackBottom - trackTop - thumbHeight) * scrollRow / maxScrollRow();
			RenderUtils.drawRect(trackLeft, thumbTop, trackLeft + SCROLLBAR_WIDTH, thumbTop + thumbHeight, GuiTheme.SCROLLBAR_THUMB);
		}
	}

	private int rowTop(int index) {
		return y + HEADER_HEIGHT + PADDING + (index - scrollRow) * ROW_HEIGHT;
	}

	public boolean isOver(double mouseX, double mouseY) {
		return open && mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + height;
	}

	/** Answers whether the click belonged to this panel, picking a mod when it landed on one. */
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!open) {
			return false;
		}
		if (!isOver(mouseX, mouseY)) {
			// A click anywhere else dismisses the panel, and is spent doing so rather than also acting
			// on whatever it landed on
			close();
			return true;
		}
		if (button != 0) {
			return true;
		}

		final int lastRow = Math.min(options.size(), scrollRow + visibleRows());
		for (int i = scrollRow; i < lastRow; i++) {
			final int rowTop = rowTop(i);
			if (mouseY >= rowTop && mouseY < rowTop + ROW_HEIGHT) {
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
				parentScreen.setModFilter(options.get(i).getNamespace());
				close();
				return true;
			}
		}
		return true;
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (!isOver(mouseX, mouseY)) {
			return false;
		}
		scrollRow = Mth.clamp(scrollRow - (int) Math.signum(amount), 0, maxScrollRow());
		return true;
	}

}
