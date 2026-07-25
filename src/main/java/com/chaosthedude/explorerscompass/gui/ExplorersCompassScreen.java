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
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
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
	private Button teleportButton;
	private Button cancelButton;
	private TransparentTextField searchTextField;
	private StructureSearchList selectionList;
	private ISorting sortingCategory;
	private int cachedLocations;

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
		cachedLocations = explorersCompass.getPrevPos(stack).size();
		updateButtons();

		// Check if the allowed structure list has synced. Comparing contents rather than sizes matters
		// when opening the compass in a world whose structure list differs from the one synced last:
		// searching for a structure that does not exist here would be rejected by the server.
		if (!allowedStructureKeys.equals(ExplorersCompass.allowedStructureKeys)) {
			removeWidget(selectionList);
			allowedStructureKeys = new ArrayList<ResourceLocation>(ExplorersCompass.allowedStructureKeys);
			structureKeysMatchingSearch = new ArrayList<ResourceLocation>(allowedStructureKeys);
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
	}

	@Override
	public boolean keyPressed(int par1, int par2, int par3) {
		boolean ret = super.keyPressed(par1, par2, par3);
		if (searchTextField.isFocused()) {
			processSearchTerm();
			return true;
		}
		return ret;
	}

	@Override
	public boolean charTyped(char typedChar, int keyCode) {
		boolean ret = super.charTyped(typedChar, keyCode);
		if (searchTextField.isFocused()) {
			processSearchTerm();
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
		structureKeysMatchingSearch = new ArrayList<ResourceLocation>();
		for (ResourceLocation key : allowedStructureKeys) {
			if (StructureUtils.getPrettyStructureName(key).toLowerCase().contains(searchTextField.getValue().toLowerCase())) {
				structureKeysMatchingSearch.add(key);
			}
		}
		selectionList.refreshList();
	}

	public List<ResourceLocation> sortStructures() {
		final List<ResourceLocation> structures = structureKeysMatchingSearch;
		Collections.sort(structures, new NameSorting());
		Collections.sort(structures, sortingCategory);
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
		sortByButton = addRenderableWidget(new TransparentButton(10, 140, 110, 20, Component.translatable("string.explorerscompass.sortBy").append(Component.literal(": " + sortingCategory.getLocalizedName())), (onPress) -> {
			sortingCategory = sortingCategory.next();
			sortByButton.setMessage(Component.translatable("string.explorerscompass.sortBy").append(Component.literal(": " + sortingCategory.getLocalizedName())));
			selectionList.refreshList();
		}));
		cancelButton = addRenderableWidget(new TransparentButton(10, height - 30, 110, 20, Component.translatable("gui.cancel"), (onPress) -> {
			minecraft.setScreen(null);
		}));
		teleportButton = addRenderableWidget(new TransparentButton(width - 120, 10, 110, 20, Component.translatable("string.explorerscompass.teleport"), (onPress) -> {
			teleport();
		}));

		teleportButton.visible = ExplorersCompass.canTeleport;
		updateButtons();

		searchTextField = new TransparentTextField(font, width / 2 - 82, 10, 140, 20, Component.translatable("string.explorerscompass.search"));
		addRenderableWidget(searchTextField);

		if (selectionList == null) {
			selectionList = new StructureSearchList(this, minecraft, width + 110, height, 40, height, 45);
		}
		addRenderableWidget(selectionList);
	}

	/** Keeps the buttons in step with what the compass is currently able to do. */
	private void updateButtons() {
		final boolean hasSelection = selectionList != null && selectionList.hasSelection();
		final boolean located = explorersCompass.getState(stack) == CompassState.FOUND;

		searchButton.active = hasSelection;
		searchGroupButton.active = hasSelection;
		teleportButton.active = located;

		// Searching for a further instance needs something to have been located to look past
		searchNextButton.visible = ConfigHandler.GENERAL.maxNextSearches.get() > 0;
		searchNextButton.active = located;
		clearCacheButton.visible = searchNextButton.visible;
		clearCacheButton.active = cachedLocations > 0;
		clearCacheButton.setMessage(Component.translatable("string.explorerscompass.clearCache").append(Component.literal(cachedLocations > 0 ? " (" + cachedLocations + ")" : "")));
	}

}
