package com.chaosthedude.explorerscompass.gui;

import java.util.List;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.client.SearchHistory;
import com.chaosthedude.explorerscompass.util.RenderUtils;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class StructureSearchEntry extends ObjectSelectionList.Entry<StructureSearchEntry> {

	private static final String FAVORITE_GLYPH = "★";

	private final Minecraft mc;
	private final ExplorersCompassScreen parentScreen;
	private final ResourceLocation structureKey;
	private final StructureSearchList structuresList;
	private long lastClickTime;
	// Where the mod badge was last drawn, so that clicking it can narrow the list to that mod
	private int badgeLeft;
	private int badgeRight;
	private int badgeTop;
	private int badgeBottom;

	public StructureSearchEntry(StructureSearchList structuresList, ResourceLocation structureKey) {
		this.structuresList = structuresList;
		this.structureKey = structureKey;
		parentScreen = structuresList.getParentScreen();
		mc = Minecraft.getInstance();
	}

	public ResourceLocation getStructureKey() {
		return structureKey;
	}

	@Override
	public void render(PoseStack poseStack, int par1, int par2, int par3, int par4, int par5, int par6, int par7, boolean par8, float par9) {
		final int left = par3 + 2;
		final int right = par3 + par4;

		// The mod the structure comes from rides along as a badge on the right, both because it is what
		// tells apart the several mods that add a structure of the same name, and because clicking it
		// narrows the list down to that mod
		final String source = StructureUtils.getPrettyStructureSource(structureKey);
		final String badgeText = RenderUtils.trimToWidth(source, Math.min(90, par4 / 3));
		final boolean filteredToThisMod = structureKey.getNamespace().equals(parentScreen.getModFilter());
		final int badgeWidth = mc.font.width(badgeText) + 8;
		badgeLeft = right - badgeWidth;
		badgeRight = right;
		badgeTop = par2;
		badgeBottom = par2 + 12;
		final boolean overBadge = par8 && par6 >= badgeLeft && par6 < badgeRight && par7 >= badgeTop && par7 < badgeBottom;
		RenderUtils.drawChip(poseStack, badgeText, badgeLeft, badgeTop, filteredToThisMod || overBadge ? GuiTheme.CHIP_ACCENT_BACKGROUND : GuiTheme.CHIP_BACKGROUND, filteredToThisMod || overBadge ? GuiTheme.ACCENT : GuiTheme.TEXT_SECONDARY);

		int nameX = left;
		if (SearchHistory.isFavorite(structureKey)) {
			mc.font.draw(poseStack, FAVORITE_GLYPH, nameX, par2 + 2, GuiTheme.ACCENT);
			nameX += mc.font.width(FAVORITE_GLYPH) + 2;
		}
		final String name = RenderUtils.trimToWidth(StructureUtils.getPrettyStructureName(structureKey), badgeLeft - nameX - 6);
		mc.font.draw(poseStack, name, nameX, par2 + 2, GuiTheme.TEXT_PRIMARY);

		mc.font.draw(poseStack, I18n.get("string.explorerscompass.group") + ": " + StructureUtils.getPrettyGroupName(ExplorersCompass.structureKeysToTypeKeys.get(structureKey)), left, par2 + 14, GuiTheme.TEXT_MUTED);

		// Flag structures that cannot generate in the dimension the player is in, since searching for
		// one of them here can only fail. An empty list means the dimensions could not be determined,
		// so nothing is flagged.
		final List<ResourceLocation> dimensionKeys = ExplorersCompass.dimensionKeysForAllowedStructureKeys.get(structureKey);
		final boolean inCurrentDimension = dimensionKeys.isEmpty() || mc.level == null || dimensionKeys.contains(mc.level.dimension().location());
		final String dimensions = I18n.get("string.explorerscompass.dimension") + ": " + StructureUtils.dimensionKeysToString(dimensionKeys);
		mc.font.draw(poseStack, RenderUtils.trimToWidth(dimensions, right - left - 4), left, par2 + 25, inCurrentDimension ? GuiTheme.TEXT_MUTED : GuiTheme.TEXT_WARNING);

		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0) {
			if (mouseX >= badgeLeft && mouseX < badgeRight && mouseY >= badgeTop && mouseY < badgeBottom) {
				// Clicking the badge filters by that mod, and clicking the badge of the mod already
				// filtered for lifts the filter again
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
				parentScreen.setModFilter(structureKey.getNamespace().equals(parentScreen.getModFilter()) ? null : structureKey.getNamespace());
				return true;
			}
			if (Screen.hasControlDown()) {
				// Ctrl-click picks several structures, to search for the nearest of them all at once
				parentScreen.toggleMultiSelect(structureKey);
				structuresList.selectStructure(this);
				lastClickTime = 0;
				return true;
			}
			parentScreen.clearMultiSelect();
			structuresList.selectStructure(this);
			if (Util.getMillis() - lastClickTime < 250L) {
				searchForStructure();
				return true;
			} else {
				lastClickTime = Util.getMillis();
				return false;
			}
		} else if (button == 1) {
			// Right click stars a structure, pinning it to the top of the list
			parentScreen.toggleFavorite(structureKey);
			return true;
		}
		return false;
	}

	@Override
	public Component getNarration() {
		return Component.literal(StructureUtils.getPrettyStructureName(structureKey));
	}

	public void searchForStructure() {
		mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
		parentScreen.searchForStructure(structureKey);
	}

	public void searchForGroup() {
		mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
		parentScreen.searchForGroup(ExplorersCompass.structureKeysToTypeKeys.get(structureKey));
	}

}
