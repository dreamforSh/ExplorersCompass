package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.network.ClearCachePacket;
import com.chaosthedude.explorerscompass.network.CompassSearchForNextPacket;
import com.chaosthedude.explorerscompass.network.CompassSearchPacket;
import com.chaosthedude.explorerscompass.network.TeleportPacket;
import com.chaosthedude.explorerscompass.sorting.ISorting;
import com.chaosthedude.explorerscompass.sorting.NameSorting;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class ExplorersCompassScreen extends Screen {

	private Level level;
	private Player player;
	private List<ResourceLocation> allowedStructureKeys;
	private List<ResourceLocation> structureKeysMatchingSearch;
	private ItemStack stack;
	private ExplorersCompassItem explorersCompass;
	private Button searchButton;
	private Button searchGroupButton;
	private Button searchNextButton;
	private Button clearCacheButton;
	private Button sortByButton;
	private Button dimensionFilterButton;
	private Button teleportButton;
	private Button cancelButton;
	private TransparentTextField searchTextField;
	private StructureSearchList selectionList;
	private ISorting sortingCategory;
	private boolean sortDescending;
	private boolean filterByCurrentDimension;
	private int cachedLocations;
	private String lastSearchTerm = "";
	private int panelLineY;

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
	public boolean mouseScrolled(double scroll1, double scroll2, double scroll3) {
		return selectionList.mouseScrolled(scroll1, scroll2, scroll3);
	}

	@Override
	protected void init() {
		minecraft.keyboardHandler.setSendRepeatsToGui(true);
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
			// Re-apply whatever is already typed in the search field, rather than resetting the filter
			selectionList = null;
			processSearchTerm();
			selectionList = new StructureSearchList(this, minecraft, width + 110, height, 40, height, 45);
			addRenderableWidget(selectionList);
		}
	}

	@Override
	public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
		renderBackground(poseStack);
		// Showing how many entries the list holds makes a filtered-down search term obvious
		drawCenteredString(poseStack, font, title.copy().append(Component.literal(" (" + structureKeysMatchingSearch.size() + ")")), 65, 15, 0xffffff);
		super.render(poseStack, mouseX, mouseY, partialTicks);
		renderCompassStatePanel(poseStack);
	}

	/**
	 * Draws what the compass is currently doing below the buttons, so that the player does not have
	 * to close this screen to read it off the HUD.
	 */
	private void renderCompassStatePanel(PoseStack poseStack) {
		panelLineY = 195;
		final CompassState state = explorersCompass.getState(stack);
		if (state == null) {
			return;
		}

		if (state == CompassState.SEARCHING) {
			drawPanelLine(poseStack, I18n.get("string.explorerscompass.status") + ": " + I18n.get("string.explorerscompass.searching"), 0xFFFFFF);
			drawPanelLine(poseStack, StructureUtils.getPrettyStructureName(explorersCompass.getStructureKey(stack)), 0xAAAAAA);
			drawPanelLine(poseStack, I18n.get("string.explorerscompass.radius") + ": " + explorersCompass.getSearchRadius(stack), 0xAAAAAA);
		} else if (state == CompassState.FOUND) {
			drawPanelLine(poseStack, I18n.get("string.explorerscompass.status") + ": " + I18n.get("string.explorerscompass.found"), 0xFFFFFF);
			drawPanelLine(poseStack, StructureUtils.getPrettyStructureName(explorersCompass.getStructureKey(stack)), 0xAAAAAA);
			final ResourceLocation foundDimension = explorersCompass.getFoundDimension(stack);
			final boolean inFoundDimension = foundDimension == null || foundDimension.equals(player.level.dimension().location());
			if (!inFoundDimension) {
				drawPanelLine(poseStack, I18n.get("string.explorerscompass.dimension") + ": " + StructureUtils.getDimensionName(foundDimension), 0xFF5555);
			} else if (explorersCompass.shouldDisplayCoordinates(stack)) {
				final int x = explorersCompass.getFoundStructureX(stack);
				final int z = explorersCompass.getFoundStructureZ(stack);
				drawPanelLine(poseStack, I18n.get("string.explorerscompass.coordinates") + ": " + x + ", " + z, 0xAAAAAA);
				drawPanelLine(poseStack, I18n.get("string.explorerscompass.distance") + ": " + StructureUtils.getHorizontalDistanceToLocation(player, x, z), 0xAAAAAA);
			}
			final int previousLocations = cachedLocations - 1;
			if (previousLocations > 0) {
				drawPanelLine(poseStack, I18n.get("string.explorerscompass.previousLocations") + ": " + previousLocations, 0xAAAAAA);
			}
		} else if (state == CompassState.NOT_FOUND) {
			drawPanelLine(poseStack, I18n.get("string.explorerscompass.status") + ": " + I18n.get("string.explorerscompass.notFound"), 0xFFFFFF);
			drawPanelLine(poseStack, StructureUtils.getPrettyStructureName(explorersCompass.getStructureKey(stack)), 0xAAAAAA);
			drawPanelLine(poseStack, I18n.get("string.explorerscompass.radius") + ": " + explorersCompass.getSearchRadius(stack), 0xAAAAAA);
			drawPanelLine(poseStack, I18n.get("string.explorerscompass.samples") + ": " + explorersCompass.getSamples(stack), 0xAAAAAA);
		} else {
			drawPanelLine(poseStack, I18n.get("string.explorerscompass.status") + ": " + I18n.get("string.explorerscompass.inactive"), 0xFFFFFF);
		}
	}

	private void drawPanelLine(PoseStack poseStack, String text, int color) {
		// Stop above the cancel button rather than drawing over it on short screens
		if (panelLineY > height - 41) {
			return;
		}
		font.drawShadow(poseStack, font.plainSubstrByWidth(text, 110), 10, panelLineY, color);
		panelLineY += font.lineHeight + 1;
	}

	@Override
	public boolean keyPressed(int par1, int par2, int par3) {
		boolean ret = super.keyPressed(par1, par2, par3);
		if (searchTextField.isFocused()) {
			// Refreshing the list resets its scroll and selection, so only do it when the filter actually
			// changed: this is also called for keys that cannot change it, like the modifier keys
			if (!searchTextField.getValue().equals(lastSearchTerm)) {
				processSearchTerm();
			}
			return true;
		}
		return ret;
	}

	@Override
	public boolean charTyped(char typedChar, int keyCode) {
		boolean ret = super.charTyped(typedChar, keyCode);
		if (searchTextField.isFocused()) {
			if (!searchTextField.getValue().equals(lastSearchTerm)) {
				processSearchTerm();
			}
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

	public void searchForStructure(ResourceLocation key) {
		ExplorersCompass.network.sendToServer(new CompassSearchPacket(key, false, player.blockPosition()));
		minecraft.setScreen(null);
	}

	public void searchForGroup(ResourceLocation key) {
		if (key == null) {
			return;
		}
		ExplorersCompass.network.sendToServer(new CompassSearchPacket(key, true, player.blockPosition()));
		minecraft.setScreen(null);
	}

	public void searchForNextStructure() {
		ExplorersCompass.network.sendToServer(new CompassSearchForNextPacket(player.blockPosition()));
		minecraft.setScreen(null);
	}

	public void clearCache() {
		ExplorersCompass.network.sendToServer(new ClearCachePacket());
		cachedLocations = 0;
		updateButtons();
	}

	public void teleport() {
		ExplorersCompass.network.sendToServer(new TeleportPacket());
		minecraft.setScreen(null);
	}

	public void processSearchTerm() {
		lastSearchTerm = searchTextField.getValue();
		final String[] tokens = lastSearchTerm.toLowerCase().split("\\s+");
		structureKeysMatchingSearch = new ArrayList<ResourceLocation>();
		for (ResourceLocation key : allowedStructureKeys) {
			if (matchesDimensionFilter(key) && matchesSearchTokens(key, tokens)) {
				structureKeysMatchingSearch.add(key);
			}
		}
		if (selectionList != null) {
			selectionList.refreshList();
		}
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
		Collections.sort(structures, new NameSorting());
		Collections.sort(structures, sortingCategory);
		if (sortDescending) {
			Collections.reverse(structures);
		}
		return structures;
	}

	private void setupWidgets() {
		clearWidgets();
		searchButton = addRenderableWidget(new TransparentButton(10, 40, 110, 20, Component.translatable("string.explorerscompass.search"), (onPress) -> {
			if (selectionList.hasSelection()) {
				selectionList.getSelected().searchForStructure();
			}
		}));
		searchGroupButton = addRenderableWidget(new TransparentButton(10, 65, 110, 20, Component.translatable("string.explorerscompass.searchForGroup"), (onPress) -> {
			if (selectionList.hasSelection()) {
				selectionList.getSelected().searchForGroup();
			}
		}));
		searchNextButton = addRenderableWidget(new TransparentButton(10, 90, 110, 20, Component.translatable("string.explorerscompass.searchForNext"), (onPress) -> {
			searchForNextStructure();
		}));
		clearCacheButton = addRenderableWidget(new TransparentButton(10, 115, 110, 20, Component.translatable("string.explorerscompass.clearCache"), (onPress) -> {
			clearCache();
		}));
		sortByButton = addRenderableWidget(new TransparentButton(10, 140, 110, 20, sortByButtonLabel(), (onPress) -> {
			sortingCategory = sortingCategory.next();
			sortByButton.setMessage(sortByButtonLabel());
			selectionList.refreshList();
		}) {
			@Override
			public boolean mouseClicked(double mouseX, double mouseY, int button) {
				// Right click reverses the order instead of cycling the category
				if (button == 1 && clicked(mouseX, mouseY)) {
					sortDescending = !sortDescending;
					setMessage(sortByButtonLabel());
					selectionList.refreshList();
					playDownSound(minecraft.getSoundManager());
					return true;
				}
				return super.mouseClicked(mouseX, mouseY, button);
			}
		});
		dimensionFilterButton = addRenderableWidget(new TransparentButton(10, 165, 110, 20, dimensionFilterButtonLabel(), (onPress) -> {
			filterByCurrentDimension = !filterByCurrentDimension;
			dimensionFilterButton.setMessage(dimensionFilterButtonLabel());
			processSearchTerm();
		}));
		cancelButton = addRenderableWidget(new TransparentButton(10, height - 30, 110, 20, Component.translatable("gui.cancel"), (onPress) -> {
			minecraft.setScreen(null);
		}));
		teleportButton = addRenderableWidget(new TransparentButton(width - 120, 10, 110, 20, Component.translatable("string.explorerscompass.teleport"), (onPress) -> {
			teleport();
		}));

		// Carry the filter and the selection over, so that resizing the window does not lose them
		final String previousSearchTerm = searchTextField != null ? searchTextField.getValue() : "";
		final ResourceLocation previousSelectionKey = selectionList != null && selectionList.hasSelection() ? selectionList.getSelected().getStructureKey() : null;

		searchTextField = new TransparentTextField(font, width / 2 - 82, 10, 140, 20, Component.translatable("string.explorerscompass.searchHint"));
		searchTextField.setValue(previousSearchTerm);
		addRenderableWidget(searchTextField);

		// The list is recreated on every init so that it picks up the current screen dimensions
		selectionList = null;
		processSearchTerm();
		selectionList = new StructureSearchList(this, minecraft, width + 110, height, 40, height, 45);
		addRenderableWidget(selectionList);
		if (previousSelectionKey != null) {
			selectionList.selectByKey(previousSelectionKey);
		}
		updateButtons();
	}

	private Component sortByButtonLabel() {
		return Component.translatable("string.explorerscompass.sortBy").append(Component.literal(": " + sortingCategory.getLocalizedName() + (sortDescending ? " ↓" : " ↑")));
	}

	private Component dimensionFilterButtonLabel() {
		return Component.translatable(filterByCurrentDimension ? "string.explorerscompass.currentDimension" : "string.explorerscompass.allDimensions");
	}

	/** Keeps the buttons in step with what the compass is currently able to do. */
	private void updateButtons() {
		final boolean hasSelection = selectionList != null && selectionList.hasSelection();
		final boolean located = explorersCompass.getState(stack) == CompassState.FOUND;

		searchButton.active = hasSelection;
		searchGroupButton.active = hasSelection;
		// canTeleport arrives with the first sync, which may be after this screen was opened. The
		// located coordinates only mean something in the dimension the search ran in.
		final ResourceLocation foundDimension = explorersCompass.getFoundDimension(stack);
		final boolean inFoundDimension = foundDimension == null || foundDimension.equals(player.level.dimension().location());
		teleportButton.visible = ExplorersCompass.canTeleport;
		teleportButton.active = located && inFoundDimension;

		// Searching for a further instance needs something to have been located to look past
		searchNextButton.visible = ConfigHandler.GENERAL.maxNextSearches.get() > 0;
		searchNextButton.active = located;
		clearCacheButton.visible = searchNextButton.visible;
		clearCacheButton.active = cachedLocations > 0;
		clearCacheButton.setMessage(Component.translatable("string.explorerscompass.clearCache").append(Component.literal(cachedLocations > 0 ? " (" + cachedLocations + ")" : "")));
	}

}
