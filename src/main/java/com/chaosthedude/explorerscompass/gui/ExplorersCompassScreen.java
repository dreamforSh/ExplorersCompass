package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.glfw.GLFW;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.client.ClientEventHandler;
import com.chaosthedude.explorerscompass.client.SearchHistory;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.network.CancelSearchPacket;
import com.chaosthedude.explorerscompass.network.ClearCachePacket;
import com.chaosthedude.explorerscompass.network.CompassSearchForNextPacket;
import com.chaosthedude.explorerscompass.network.CompassSearchPacket;
import com.chaosthedude.explorerscompass.network.ShareLocationPacket;
import com.chaosthedude.explorerscompass.network.TeleportPacket;
import com.chaosthedude.explorerscompass.sorting.ISorting;
import com.chaosthedude.explorerscompass.sorting.NameSorting;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import com.chaosthedude.explorerscompass.util.RenderUtils;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.Util;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class ExplorersCompassScreen extends Screen {

	/** The strip along the bottom that says what the compass is doing, and acts on what it found. */
	private static final int STATUS_BAR_HEIGHT = 34;
	// Narrow enough that all three of the controls standing in the strip still fit inside it on the
	// smallest screen the game scales itself down to; their labels are shortened to fit either way
	private static final int STATUS_BUTTON_WIDTH = 52;
	private static final int STATUS_BUTTON_HEIGHT = 18;
	private static final String DOT_GLYPH = "●";
	private static final String CLEAR_GLYPH = "✕";

	private Level level;
	private Player player;
	private List<ResourceLocation> allowedStructureKeys;
	private List<ResourceLocation> structureKeysMatchingSearch;
	private ItemStack stack;
	private ExplorersCompassItem explorersCompass;
	private TransparentButton searchButton;
	private TransparentButton searchGroupButton;
	private TransparentButton searchNextButton;
	private TransparentButton clearCacheButton;
	private TransparentButton sortByButton;
	private TransparentButton dimensionFilterButton;
	private TransparentButton modFilterButton;
	private TransparentButton bookmarksButton;
	private TransparentButton teleportButton;
	private TransparentButton shareButton;
	private TransparentButton stopButton;
	private TransparentButton closeButton;
	private TransparentTextField searchTextField;
	private StructureSearchList selectionList;
	private ModFilterPanel modFilterPanel;
	private ISorting sortingCategory;
	private boolean sortDescending;
	private boolean filterByCurrentDimension;
	/** The mod the list is narrowed down to, or null while structures from every mod are listed. */
	private String modFilter;
	private int cachedLocations;
	// How many rows the list is holding at its top rather than sorting into place
	private int pinnedCount;
	// Where the sidebar has room for the next control, and where the status text has to stop
	private int sidebarY;
	private int statusTextRight;
	// The structures picked with Ctrl-click, to search for the nearest of them all at once
	private final Set<ResourceLocation> multiSelectedKeys = new LinkedHashSet<ResourceLocation>();
	// Where the chips that lift a filter were last drawn, rebuilt on every frame
	private final List<FilterChip> filterChips = new ArrayList<FilterChip>();

	/** A filter that is in force, drawn as a chip that lifts it again when clicked. */
	private static class FilterChip {

		private final String label;
		private final Runnable clear;
		private int left;
		private int right;
		private int top;
		private int bottom;

		private FilterChip(String label, Runnable clear) {
			this.label = label;
			this.clear = clear;
		}

	}

	public ExplorersCompassScreen(Level level, Player player, ItemStack stack, ExplorersCompassItem explorersCompass, List<ResourceLocation> allowedStructureKeys) {
		super(Component.translatable("string.explorerscompass.selectStructure"));
		this.level = level;
		this.player = player;
		this.stack = stack;
		this.explorersCompass = explorersCompass;

		this.allowedStructureKeys = new ArrayList<ResourceLocation>(allowedStructureKeys);
		structureKeysMatchingSearch = new ArrayList<ResourceLocation>(this.allowedStructureKeys);
		sortingCategory = new NameSorting();
		cachedLocations = explorersCompass.getPrevPos(stack).size();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (modFilterPanel.mouseScrolled(mouseX, mouseY, amount)) {
			return true;
		}
		return selectionList.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// The mod list floats above everything else, so it gets the click before the widgets under it
		if (modFilterPanel.isOpen()) {
			return modFilterPanel.mouseClicked(mouseX, mouseY, button);
		}
		if (button == 0) {
			for (FilterChip chip : filterChips) {
				if (mouseX >= chip.left && mouseX < chip.right && mouseY >= chip.top && mouseY < chip.bottom) {
					chip.clear.run();
					return true;
				}
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	protected void init() {
		minecraft.keyboardHandler.setSendRepeatsToGui(true);
		if (modFilterPanel == null) {
			modFilterPanel = new ModFilterPanel(this, minecraft);
		}
		modFilterPanel.close();
		setupWidgets();
	}

	@Override
	public void tick() {
		searchTextField.tick();
		// The server replaces the whole stack object when it syncs NBT changes, so the reference
		// captured when this screen opened goes stale; without this, the state panel and the buttons
		// would never notice a search finishing while the screen is open
		final ItemStack heldStack = ItemUtils.getHeldItem(player, ExplorersCompass.explorersCompass);
		if (!heldStack.isEmpty()) {
			stack = heldStack;
		}
		cachedLocations = explorersCompass.getPrevPos(stack).size();
		updateButtons();

		// Check if the allowed structure list has synced. Comparing contents rather than sizes matters
		// when opening the compass in a world whose structure list differs from the one synced last:
		// searching for a structure that does not exist here would be rejected by the server.
		if (!allowedStructureKeys.equals(ExplorersCompass.allowedStructureKeys)) {
			removeWidget(selectionList);
			allowedStructureKeys = new ArrayList<ResourceLocation>(ExplorersCompass.allowedStructureKeys);
			multiSelectedKeys.retainAll(allowedStructureKeys);
			// A mod whose structures are all gone is no longer something to be filtered by
			if (modFilter != null && allowedStructureKeys.stream().noneMatch((key) -> key.getNamespace().equals(modFilter))) {
				modFilter = null;
				modFilterButton.setMessage(modFilterButtonLabel());
			}
			// Re-apply whatever is already typed in the search field, rather than resetting the filter
			selectionList = null;
			processSearchTerm();
			selectionList = addRenderableWidget(createSelectionList());
		}
	}

	@Override
	public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
		renderBackground(poseStack);
		GuiTheme.drawHeader(width);
		GuiTheme.drawSidebar(height);
		// The status strip is drawn before the widgets so that the two buttons standing in it are not
		// covered by its own background
		renderStatusBar(poseStack);
		renderHeaderContents(poseStack, mouseX, mouseY);

		super.render(poseStack, mouseX, mouseY, partialTicks);

		if (structureKeysMatchingSearch.isEmpty()) {
			final int listCenterX = GuiTheme.contentLeft() + GuiTheme.contentWidth(width) / 2;
			drawCenteredString(poseStack, font, Component.translatable("string.explorerscompass.noStructuresMatch"), listCenterX, height / 2 - 10, GuiTheme.TEXT_SECONDARY);
			drawCenteredString(poseStack, font, Component.translatable("string.explorerscompass.clearFiltersHint"), listCenterX, height / 2 + 2, GuiTheme.TEXT_MUTED);
		}

		modFilterPanel.render(poseStack, mouseX, mouseY, modFilter);
		renderButtonTooltip(poseStack, mouseX, mouseY);
	}

	/** Draws the title, how much of the list is showing, and the filters that are cutting it down. */
	private void renderHeaderContents(PoseStack poseStack, int mouseX, int mouseY) {
		GuiTheme.drawTitle(poseStack, font, title.getString(), structureKeysMatchingSearch.size() + " / " + allowedStructureKeys.size(), GuiTheme.SIDEBAR_CONTENT_X, 10);

		filterChips.clear();
		if (modFilter != null) {
			filterChips.add(new FilterChip(I18n.get("string.explorerscompass.modFilter") + ": " + StructureUtils.getPrettyStructureSource(new ResourceLocation(modFilter, "any")), () -> setModFilter(null)));
		}
		if (filterByCurrentDimension) {
			filterChips.add(new FilterChip(I18n.get("string.explorerscompass.currentDimension"), () -> toggleDimensionFilter()));
		}
		if (!multiSelectedKeys.isEmpty()) {
			filterChips.add(new FilterChip(I18n.get("string.explorerscompass.multiSelected", multiSelectedKeys.size()), () -> clearMultiSelect()));
		}

		final int chipsY = 28;
		final int chipsLeft = columnLeft();
		final int chipsRight = chipsLeft + columnWidth();
		if (filterChips.isEmpty()) {
			// The space the chips would take is worth more as a reminder of what the list can do than
			// as empty header
			font.draw(poseStack, RenderUtils.trimToWidth(I18n.get("string.explorerscompass.listHint"), chipsRight - chipsLeft), chipsLeft + 1, chipsY + 2, GuiTheme.TEXT_MUTED);
			return;
		}

		int chipX = chipsLeft;
		for (FilterChip chip : filterChips) {
			final String label = chip.label + " " + CLEAR_GLYPH;
			final int chipWidth = font.width(label) + 8;
			if (chipX + chipWidth > chipsRight) {
				break;
			}
			chip.left = chipX;
			chip.right = chipX + chipWidth;
			chip.top = chipsY;
			chip.bottom = chipsY + 12;
			final boolean hovered = mouseX >= chip.left && mouseX < chip.right && mouseY >= chip.top && mouseY < chip.bottom;
			RenderUtils.drawChip(poseStack, label, chipX, chipsY, GuiTheme.CHIP_ACCENT_BACKGROUND, hovered ? GuiTheme.TEXT_PRIMARY : GuiTheme.ACCENT);
			chipX += chipWidth + 4;
		}
	}

	/**
	 * Draws what the compass is currently doing along the bottom, so that the player does not have to
	 * close this screen to read it off the HUD.
	 */
	private void renderStatusBar(PoseStack poseStack) {
		final int top = statusBarTop();
		final int left = GuiTheme.contentLeft();
		final int right = left + GuiTheme.contentWidth(width);
		GuiTheme.drawScreenPanel(left, top, right, top + STATUS_BAR_HEIGHT, ConfigHandler.CLIENT.guiStatusBarBackground.get());

		final CompassState state = explorersCompass.getState(stack);
		final ResourceLocation foundDimension = explorersCompass.getFoundDimension(stack);
		final boolean inFoundDimension = foundDimension == null || foundDimension.equals(player.level.dimension().location());

		String headline;
		int dotColor;
		if (state == CompassState.SEARCHING) {
			headline = I18n.get("string.explorerscompass.searching");
			// Fading the marker in and out is what says the search is still running, on a screen where
			// nothing else about it changes until it finishes
			final float pulse = (Mth.sin(Util.getMillis() / 200.0F) + 1.0F) / 2.0F;
			dotColor = (GuiTheme.ACCENT & 0xFFFFFF) | ((int) (110 + 145 * pulse) << 24);
		} else if (state == CompassState.FOUND) {
			headline = I18n.get("string.explorerscompass.found");
			dotColor = inFoundDimension ? GuiTheme.TEXT_SUCCESS : GuiTheme.TEXT_WARNING;
		} else if (state == CompassState.NOT_FOUND) {
			headline = I18n.get("string.explorerscompass.notFound");
			dotColor = GuiTheme.TEXT_WARNING;
		} else {
			headline = I18n.get("string.explorerscompass.inactive");
			dotColor = GuiTheme.TEXT_MUTED;
		}

		font.draw(poseStack, DOT_GLYPH, left + 6, top + 6, dotColor);
		int headlineX = left + 6 + font.width(DOT_GLYPH) + 4;
		font.drawShadow(poseStack, headline, headlineX, top + 6, GuiTheme.TEXT_PRIMARY);
		headlineX += font.width(headline) + 6;

		// An inactive compass still remembers the last structure it was pointed at, which would read as
		// if it were still doing something with it
		if (state == CompassState.SEARCHING || state == CompassState.FOUND || state == CompassState.NOT_FOUND) {
			final String target = state == CompassState.SEARCHING ? ClientEventHandler.searchTargetName(explorersCompass, stack) : StructureUtils.getPrettyStructureName(explorersCompass.getStructureKey(stack));
			if (!target.isEmpty()) {
				font.draw(poseStack, RenderUtils.trimToWidth(target, statusTextRight - headlineX), headlineX, top + 6, GuiTheme.TEXT_SECONDARY);
			}
		}

		final String details = statusDetails(state, foundDimension, inFoundDimension);
		if (!details.isEmpty()) {
			font.draw(poseStack, RenderUtils.trimToWidth(details, statusTextRight - left - 6), left + 6, top + 19, state == CompassState.FOUND && !inFoundDimension ? GuiTheme.TEXT_WARNING : GuiTheme.TEXT_MUTED);
		}
	}

	/** The second line of the status strip: whatever there is to know about the current state. */
	private String statusDetails(CompassState state, ResourceLocation foundDimension, boolean inFoundDimension) {
		final List<String> parts = new ArrayList<String>();
		if (state == CompassState.SEARCHING) {
			parts.add(I18n.get("string.explorerscompass.radius") + ": " + String.format("%,d", explorersCompass.getSearchRadius(stack)));
		} else if (state == CompassState.FOUND) {
			if (!inFoundDimension) {
				parts.add(I18n.get("string.explorerscompass.dimension") + ": " + StructureUtils.getDimensionName(foundDimension));
			} else if (explorersCompass.shouldDisplayCoordinates(stack)) {
				final int x = explorersCompass.getFoundStructureX(stack);
				final int z = explorersCompass.getFoundStructureZ(stack);
				final int y = explorersCompass.getFoundStructureY(stack);
				parts.add(y != ExplorersCompassItem.UNKNOWN_Y ? x + ", " + y + ", " + z : x + ", " + z);
				parts.add(I18n.get("string.explorerscompass.distance") + ": " + String.format("%,d", StructureUtils.getHorizontalDistanceToLocation(player, x, z)) + " (" + ClientEventHandler.compassDirection(player, x, z) + ")");
			}
			final int previousLocations = cachedLocations - 1;
			if (previousLocations > 0) {
				parts.add(I18n.get("string.explorerscompass.previousLocations") + ": " + previousLocations);
			}
		} else if (state == CompassState.NOT_FOUND) {
			parts.add(I18n.get("string.explorerscompass.radius") + ": " + String.format("%,d", explorersCompass.getSearchRadius(stack)));
			parts.add(I18n.get("string.explorerscompass.samples") + ": " + String.format("%,d", explorersCompass.getSamples(stack)));
		}
		return String.join(" · ", parts);
	}

	/** Explains whatever button the pointer is resting on, above everything else on the screen. */
	private void renderButtonTooltip(PoseStack poseStack, int mouseX, int mouseY) {
		if (modFilterPanel.isOver(mouseX, mouseY)) {
			return;
		}
		for (Object widget : renderables) {
			if (widget instanceof TransparentButton button && button.visible && button.isHoveredOrFocused() && !button.getTooltipLines().isEmpty()) {
				renderComponentTooltip(poseStack, button.getTooltipLines(), mouseX, mouseY);
				return;
			}
		}
	}

	private int statusBarTop() {
		return height - STATUS_BAR_HEIGHT - 6;
	}

	/**
	 * The left edge of the column the rows of the list are laid out in. The filter field and the
	 * chips under it line up with the rows rather than with the panel the rows sit in, so that
	 * everything to do with the list shares one edge down the screen.
	 */
	private int columnLeft() {
		return GuiTheme.contentLeft() + StructureSearchList.ROW_INSET / 2;
	}

	private int columnWidth() {
		return GuiTheme.contentWidth(width) - StructureSearchList.ROW_INSET;
	}

	@Override
	public boolean keyPressed(int par1, int par2, int par3) {
		if (par1 == 256 && modFilterPanel.isOpen()) {
			modFilterPanel.close();
			return true;
		}

		// The screen opens with the filter field focused, so the keys that work the list have to reach
		// it from there: typing narrows the list, the arrows walk it, and return searches for what they
		// land on, without the mouse being needed at any point
		if (!modFilterPanel.isOpen()) {
			if (par1 == GLFW.GLFW_KEY_DOWN || par1 == GLFW.GLFW_KEY_UP) {
				selectionList.moveSelectionBy(par1 == GLFW.GLFW_KEY_DOWN ? 1 : -1);
				return true;
			}
			if (par1 == GLFW.GLFW_KEY_ENTER || par1 == GLFW.GLFW_KEY_KP_ENTER) {
				return searchFromKeyboard();
			}
		}

		boolean ret = super.keyPressed(par1, par2, par3);
		if (searchTextField.isFocused()) {
			return true;
		}
		return ret;
	}

	/**
	 * Searches for whatever return should act on: everything picked with Ctrl-click, the selected
	 * structure, or, when nothing has been selected yet, the first one the filter left in the list.
	 */
	private boolean searchFromKeyboard() {
		if (multiSelectedKeys.size() > 1) {
			searchForMultiSelection();
			return true;
		}
		if (!selectionList.hasSelection()) {
			selectionList.selectFirst();
		}
		if (selectionList.hasSelection()) {
			selectionList.getSelected().searchForStructure();
			return true;
		}
		return false;
	}

	@Override
	public boolean charTyped(char typedChar, int keyCode) {
		boolean ret = super.charTyped(typedChar, keyCode);
		if (searchTextField.isFocused()) {
			return true;
		}
		return ret;
	}

	@Override
	public void onClose() {
		super.onClose();
		minecraft.keyboardHandler.setSendRepeatsToGui(false);
	}

	public void selectStructure(StructureSearchEntry entry) {
		updateButtons();
	}

	public boolean isMultiSelected(ResourceLocation key) {
		return multiSelectedKeys.contains(key);
	}

	public void toggleMultiSelect(ResourceLocation key) {
		if (!multiSelectedKeys.remove(key)) {
			multiSelectedKeys.add(key);
		}
		updateButtons();
	}

	public void clearMultiSelect() {
		multiSelectedKeys.clear();
		updateButtons();
	}

	/** The mod the list is narrowed down to, or null while structures from every mod are listed. */
	public String getModFilter() {
		return modFilter;
	}

	/** Narrows the list down to one mod, or lists every mod again when given null. */
	public void setModFilter(String namespace) {
		modFilter = namespace;
		modFilterButton.setMessage(modFilterButtonLabel());
		modFilterButton.setHighlighted(modFilter != null);
		processSearchTerm();
	}

	private void toggleDimensionFilter() {
		filterByCurrentDimension = !filterByCurrentDimension;
		dimensionFilterButton.setMessage(dimensionFilterButtonLabel());
		dimensionFilterButton.setHighlighted(filterByCurrentDimension);
		processSearchTerm();
	}

	/** Stars or unstars a structure, keeping the selection where it was. */
	public void toggleFavorite(ResourceLocation key) {
		SearchHistory.toggleFavorite(key);
		refreshListKeepingSelection();
	}

	/**
	 * Rebuilds the rows, holding on to whatever was selected if the change left it in the list. A
	 * structure picked out of hundreds should survive narrowing the list down around it, or sorting
	 * it differently.
	 */
	private void refreshListKeepingSelection() {
		final ResourceLocation selectedKey = selectionList.hasSelection() ? selectionList.getSelected().getStructureKey() : null;
		selectionList.refreshList();
		if (selectedKey != null) {
			selectionList.selectByKey(selectedKey);
		}
	}

	public void searchForStructure(ResourceLocation key) {
		SearchHistory.pushRecent(key);
		ExplorersCompass.network.sendToServer(CompassSearchPacket.forStructures(List.of(key), player.blockPosition()));
		minecraft.setScreen(null);
	}

	/** Searches for the nearest of all the structures picked with Ctrl-click. */
	public void searchForMultiSelection() {
		final List<ResourceLocation> keys = new ArrayList<ResourceLocation>(multiSelectedKeys);
		for (ResourceLocation key : keys) {
			SearchHistory.pushRecent(key);
		}
		ExplorersCompass.network.sendToServer(CompassSearchPacket.forStructures(keys, player.blockPosition()));
		minecraft.setScreen(null);
	}

	public void searchForGroup(ResourceLocation key) {
		if (key == null) {
			return;
		}
		ExplorersCompass.network.sendToServer(CompassSearchPacket.forGroup(key, player.blockPosition()));
		minecraft.setScreen(null);
	}

	public void searchForNextStructure() {
		ExplorersCompass.network.sendToServer(new CompassSearchForNextPacket(player.blockPosition()));
		minecraft.setScreen(null);
	}

	public void clearCache() {
		ExplorersCompass.network.sendToServer(new ClearCachePacket());
		// Applied to this copy of the stack as well as asked for, so that the count on the button, the
		// status strip and the marks on the HUD all drop away at once. Waiting for the server to send
		// its copy back would leave them showing locations that have already been forgotten, and would
		// put the old count back on the button on the very next tick.
		explorersCompass.clearPrevPos(stack);
		cachedLocations = 0;
		updateButtons();
	}

	/**
	 * Stops the search and takes the structure the compass was aimed at back off it, leaving this
	 * screen open so that something else can be picked straight away.
	 */
	public void cancelSearch() {
		ExplorersCompass.network.sendToServer(new CancelSearchPacket());
		explorersCompass.cancelSearch(level, player, stack);
		cachedLocations = 0;
		updateButtons();
	}

	public void teleport() {
		ExplorersCompass.network.sendToServer(new TeleportPacket());
		minecraft.setScreen(null);
	}

	/** Announces the located structure to the other players, leaving this screen open. */
	public void share() {
		ExplorersCompass.network.sendToServer(new ShareLocationPacket(ShareLocationPacket.CURRENT_TARGET));
	}

	public void processSearchTerm() {
		final String[] tokens = searchTextField.getValue().toLowerCase().split("\\s+");
		structureKeysMatchingSearch = new ArrayList<ResourceLocation>();
		for (ResourceLocation key : allowedStructureKeys) {
			if (matchesModFilter(key) && matchesDimensionFilter(key) && matchesSearchTokens(key, tokens)) {
				structureKeysMatchingSearch.add(key);
			}
		}
		if (selectionList != null) {
			refreshListKeepingSelection();
		}
	}

	private boolean matchesModFilter(ResourceLocation key) {
		return modFilter == null || key.getNamespace().equals(modFilter);
	}

	private boolean matchesDimensionFilter(ResourceLocation key) {
		if (!filterByCurrentDimension) {
			return true;
		}
		// Structures with no known dimensions are kept: absent data is not proof they cannot generate here
		final List<ResourceLocation> dimensionKeys = ExplorersCompass.dimensionKeysForAllowedStructureKeys.get(key);
		return dimensionKeys.isEmpty() || dimensionKeys.contains(player.level.dimension().location());
	}

	/**
	 * Matches the typed filter. Every whitespace-separated token has to match: plain tokens against
	 * the structure name, and tokens starting with {@code @} against its source, by mod id or by mod
	 * name. A lone {@code @} matches everything, so nothing vanishes while the id is being typed.
	 */
	private boolean matchesSearchTokens(ResourceLocation key, String[] tokens) {
		for (String token : tokens) {
			if (token.isEmpty()) {
				continue;
			}
			if (token.charAt(0) == '@') {
				final String source = token.substring(1);
				if (!source.isEmpty() && !key.getNamespace().toLowerCase().contains(source) && !StructureUtils.getPrettyStructureSource(key).toLowerCase().contains(source)) {
					return false;
				}
			} else if (!StructureUtils.getPrettyStructureName(key).toLowerCase().contains(token)) {
				return false;
			}
		}
		return true;
	}

	public List<ResourceLocation> sortStructures() {
		final List<ResourceLocation> structures = structureKeysMatchingSearch;
		// Sort on values computed once per structure instead of once per comparison: the names and
		// sources behind them do translation and mod list lookups, and O(n log n) comparisons would
		// repeat them thousands of times on every refresh while typing in the filter
		final Map<ResourceLocation, String> names = new HashMap<ResourceLocation, String>();
		for (ResourceLocation key : structures) {
			names.put(key, StructureUtils.getPrettyStructureName(key));
		}
		structures.sort(Comparator.comparing(names::get));
		if (!(sortingCategory instanceof NameSorting)) {
			final Map<ResourceLocation, Object> values = new HashMap<ResourceLocation, Object>();
			for (ResourceLocation key : structures) {
				values.put(key, sortingCategory.getValue(key));
			}
			structures.sort((key1, key2) -> compareSortValues(values.get(key1), values.get(key2)));
		}
		if (sortDescending) {
			Collections.reverse(structures);
		}
		return partitionByHistory(structures);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static int compareSortValues(Object value1, Object value2) {
		return ((Comparable) value1).compareTo(value2);
	}

	/** Puts favorites first and recent searches right after them, keeping everything else sorted. */
	private List<ResourceLocation> partitionByHistory(List<ResourceLocation> structures) {
		final List<ResourceLocation> favorites = new ArrayList<ResourceLocation>();
		final List<ResourceLocation> rest = new ArrayList<ResourceLocation>();
		final Set<ResourceLocation> recentSet = new HashSet<ResourceLocation>(SearchHistory.getRecents());
		for (ResourceLocation key : structures) {
			if (SearchHistory.isFavorite(key)) {
				favorites.add(key);
			} else if (!recentSet.contains(key)) {
				rest.add(key);
			}
		}

		final Set<ResourceLocation> present = new HashSet<ResourceLocation>(structures);
		final List<ResourceLocation> result = new ArrayList<ResourceLocation>(structures.size());
		result.addAll(favorites);
		// Recents keep their own order: the most recently searched first
		for (ResourceLocation key : SearchHistory.getRecents()) {
			if (present.contains(key) && !SearchHistory.isFavorite(key)) {
				result.add(key);
			}
		}
		// Everything up to here was held at the top rather than sorted there, which is what the list
		// draws a rule under
		pinnedCount = result.size();
		result.addAll(rest);
		return result;
	}

	/** How many rows at the top of the list are favorites or recent searches. */
	public int getPinnedCount() {
		return pinnedCount;
	}

	private void setupWidgets() {
		clearWidgets();
		searchNextButton = null;
		clearCacheButton = null;
		bookmarksButton = null;
		sidebarY = GuiTheme.HEADER_HEIGHT + 8;

		searchButton = addSidebarButton(Component.translatable("string.explorerscompass.search"), (onPress) -> {
			if (multiSelectedKeys.size() > 1) {
				searchForMultiSelection();
			} else if (selectionList.hasSelection()) {
				selectionList.getSelected().searchForStructure();
			}
		});
		searchButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.search"), Component.translatable("string.explorerscompass.tooltip.multiSelect"));
		searchGroupButton = addSidebarButton(Component.translatable("string.explorerscompass.searchForGroup"), (onPress) -> {
			if (selectionList.hasSelection()) {
				selectionList.getSelected().searchForGroup();
			}
		});
		searchGroupButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.searchForGroup"));

		// Both of these only mean anything while searching past a location is allowed at all, so they
		// are left out of the layout entirely rather than taking up a slot they cannot be used from
		if (ConfigHandler.GENERAL.maxNextSearches.get() > 0) {
			searchNextButton = addSidebarButton(Component.translatable("string.explorerscompass.searchForNext"), (onPress) -> {
				searchForNextStructure();
			});
			searchNextButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.searchForNext"));
			clearCacheButton = addSidebarButton(Component.translatable("string.explorerscompass.clearCache"), (onPress) -> {
				clearCache();
			});
			clearCacheButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.clearCache"));
		}

		sortByButton = addSidebarButton(new TransparentButton(GuiTheme.SIDEBAR_CONTENT_X, sidebarY, GuiTheme.SIDEBAR_CONTENT_WIDTH, GuiTheme.BUTTON_HEIGHT, sortByButtonLabel(), (onPress) -> {
			sortingCategory = sortingCategory.next();
			sortByButton.setMessage(sortByButtonLabel());
			refreshListKeepingSelection();
		}) {
			@Override
			public boolean mouseClicked(double mouseX, double mouseY, int button) {
				// Right click reverses the order instead of cycling the category
				if (button == 1 && clicked(mouseX, mouseY)) {
					sortDescending = !sortDescending;
					setMessage(sortByButtonLabel());
					refreshListKeepingSelection();
					playDownSound(minecraft.getSoundManager());
					return true;
				}
				return super.mouseClicked(mouseX, mouseY, button);
			}
		});
		sortByButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.sortBy"));
		dimensionFilterButton = addSidebarButton(dimensionFilterButtonLabel(), (onPress) -> {
			toggleDimensionFilter();
		});
		dimensionFilterButton.setHighlighted(filterByCurrentDimension);
		dimensionFilterButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.dimensionFilter"));
		modFilterButton = addSidebarButton(modFilterButtonLabel(), (onPress) -> {
			modFilterPanel.toggle(allowedStructureKeys, GuiTheme.SIDEBAR_X + GuiTheme.SIDEBAR_WIDTH + 2, modFilterButton.y, width, height, modFilter);
		});
		modFilterButton.setHighlighted(modFilter != null);
		modFilterButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.modFilter"));

		if (ConfigHandler.GENERAL.maxBookmarks.get() > 0) {
			bookmarksButton = addSidebarButton(Component.translatable("string.explorerscompass.bookmarks"), (onPress) -> {
				minecraft.setScreen(new BookmarksScreen(this, player, stack, explorersCompass));
			});
			bookmarksButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.bookmarks"));
		}

		// Named for what it does, since the strip below holds a control that stops the search, and one
		// button reading "cancel" beside another would say nothing about which of the two is which
		closeButton = addRenderableWidget(new TransparentButton(GuiTheme.SIDEBAR_CONTENT_X, height - 26, GuiTheme.SIDEBAR_CONTENT_WIDTH, GuiTheme.BUTTON_HEIGHT, Component.translatable("string.explorerscompass.close"), (onPress) -> {
			minecraft.setScreen(null);
		}));

		// These three act on what the compass is doing, so they stand in the strip that reports it
		final int statusButtonY = statusBarTop() + (STATUS_BAR_HEIGHT - STATUS_BUTTON_HEIGHT) / 2;
		teleportButton = addRenderableWidget(new TransparentButton(0, statusButtonY, STATUS_BUTTON_WIDTH, STATUS_BUTTON_HEIGHT, Component.translatable("string.explorerscompass.teleport"), (onPress) -> {
			teleport();
		}));
		shareButton = addRenderableWidget(new TransparentButton(0, statusButtonY, STATUS_BUTTON_WIDTH, STATUS_BUTTON_HEIGHT, Component.translatable("string.explorerscompass.share"), (onPress) -> {
			share();
		}));
		shareButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.share"));
		stopButton = addRenderableWidget(new TransparentButton(0, statusButtonY, STATUS_BUTTON_WIDTH, STATUS_BUTTON_HEIGHT, stopButtonLabel(), (onPress) -> {
			cancelSearch();
		}));
		stopButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.stopSearch"));

		// Carry the filter and the selection over, so that resizing the window does not lose them
		final String previousSearchTerm = searchTextField != null ? searchTextField.getValue() : "";
		final ResourceLocation previousSelectionKey = selectionList != null && selectionList.hasSelection() ? selectionList.getSelected().getStructureKey() : null;
		selectionList = null;

		searchTextField = new TransparentTextField(font, columnLeft(), 8, columnWidth(), 18, Component.translatable("string.explorerscompass.searchHint"));
		searchTextField.setValue(previousSearchTerm);
		// Filtering as the field changes covers every way it can: typing, pasting, and the button that
		// empties it
		searchTextField.setResponder((text) -> processSearchTerm());
		addRenderableWidget(searchTextField);
		// Opening straight into the filter is what makes the keyboard alone enough: type, arrow down,
		// return. Nothing else on this screen wants the keys more than the filter does.
		setInitialFocus(searchTextField);
		searchTextField.setFocused(true);

		// The list is recreated on every init so that it picks up the current screen dimensions
		processSearchTerm();
		selectionList = addRenderableWidget(createSelectionList());
		if (previousSelectionKey != null) {
			selectionList.selectByKey(previousSelectionKey);
		}
		updateButtons();
	}

	private StructureSearchList createSelectionList() {
		return new StructureSearchList(this, minecraft, GuiTheme.contentLeft(), GuiTheme.contentWidth(width), height, GuiTheme.HEADER_HEIGHT + 2, statusBarTop() - 4, 38);
	}

	/** Adds a control to the bottom of the sidebar column, and moves the column down past it. */
	private TransparentButton addSidebarButton(Component label, Button.OnPress onPress) {
		return addSidebarButton(new TransparentButton(GuiTheme.SIDEBAR_CONTENT_X, sidebarY, GuiTheme.SIDEBAR_CONTENT_WIDTH, GuiTheme.BUTTON_HEIGHT, label, onPress));
	}

	private TransparentButton addSidebarButton(TransparentButton button) {
		addRenderableWidget(button);
		sidebarY += GuiTheme.BUTTON_SPACING;
		return button;
	}

	private Component sortByButtonLabel() {
		return Component.translatable("string.explorerscompass.sortBy").append(Component.literal(": " + sortingCategory.getLocalizedName() + (sortDescending ? " ↓" : " ↑")));
	}

	private Component dimensionFilterButtonLabel() {
		return Component.translatable(filterByCurrentDimension ? "string.explorerscompass.currentDimension" : "string.explorerscompass.allDimensions");
	}

	/** What stopping means where the compass currently is: give up the search, or drop the target. */
	private Component stopButtonLabel() {
		return Component.translatable(explorersCompass.getState(stack) == CompassState.SEARCHING ? "string.explorerscompass.stopSearch" : "string.explorerscompass.clearTarget");
	}

	private Component modFilterButtonLabel() {
		final String name = modFilter == null ? I18n.get("string.explorerscompass.allMods") : StructureUtils.getPrettyStructureSource(new ResourceLocation(modFilter, "any"));
		return Component.translatable("string.explorerscompass.modFilter").append(Component.literal(": " + name));
	}

	/** Keeps the buttons in step with what the compass is currently able to do. */
	private void updateButtons() {
		final boolean hasSelection = selectionList != null && selectionList.hasSelection();
		final int multiSelected = multiSelectedKeys.size();
		final CompassState state = explorersCompass.getState(stack);
		final boolean located = state == CompassState.FOUND;

		searchButton.active = hasSelection || multiSelected > 0;
		searchButton.setMessage(multiSelected > 1 ? Component.translatable("string.explorerscompass.search").append(Component.literal(" (" + multiSelected + ")")) : Component.translatable("string.explorerscompass.search"));
		// A group search applies to a single structure's group
		searchGroupButton.active = hasSelection && multiSelected <= 1;

		// Searching for a further instance needs something to have been located to look past
		if (searchNextButton != null) {
			searchNextButton.active = located;
			clearCacheButton.active = cachedLocations > 0;
			clearCacheButton.setMessage(Component.translatable("string.explorerscompass.clearCache").append(Component.literal(cachedLocations > 0 ? " (" + cachedLocations + ")" : "")));
		}

		// canTeleport arrives with the first sync, which may be after this screen was opened. The
		// located coordinates only mean something in the dimension the search ran in.
		final ResourceLocation foundDimension = explorersCompass.getFoundDimension(stack);
		final boolean inFoundDimension = foundDimension == null || foundDimension.equals(player.level.dimension().location());
		teleportButton.visible = ExplorersCompass.canTeleport;
		teleportButton.active = located && inFoundDimension;
		shareButton.visible = ConfigHandler.GENERAL.allowSharing.get();
		shareButton.active = located;

		// A compass that is doing nothing has nothing to stop, and nothing aimed at it to take back off
		stopButton.active = state != null && state != CompassState.INACTIVE;
		stopButton.setMessage(stopButtonLabel());

		// Whichever of them are actually there sit against the right edge of the status strip, and the
		// text beside them stops where they begin
		statusTextRight = GuiTheme.contentLeft() + GuiTheme.contentWidth(width) - 6;
		if (shareButton.visible) {
			shareButton.x = statusTextRight - STATUS_BUTTON_WIDTH;
			statusTextRight -= STATUS_BUTTON_WIDTH + 4;
		}
		if (teleportButton.visible) {
			teleportButton.x = statusTextRight - STATUS_BUTTON_WIDTH;
			statusTextRight -= STATUS_BUTTON_WIDTH + 4;
		}
		stopButton.x = statusTextRight - STATUS_BUTTON_WIDTH;
		statusTextRight -= STATUS_BUTTON_WIDTH + 4;
	}

}
