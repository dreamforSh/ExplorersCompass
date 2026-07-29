package com.chaosthedude.explorerscompass.gui;

import java.util.List;

import com.chaosthedude.explorerscompass.client.SearchHistory;
import com.chaosthedude.explorerscompass.util.RenderUtils;
import com.chaosthedude.explorerscompass.util.SearchTarget;
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

/** One structure or biome in the selection list, whichever of the two the screen is showing. */
@OnlyIn(Dist.CLIENT)
public class StructureSearchEntry extends ObjectSelectionList.Entry<StructureSearchEntry> {

	private static final String FAVORITE_GLYPH = "★";

	private final Minecraft mc;
	private final ExplorersCompassScreen parentScreen;
	private final ResourceLocation key;
	private final StructureSearchList searchList;
	private final SearchDocument document;
	private final Component narration;
	private final String groupLine;
	private final String dimensionLine;
	private long lastClickTime;
	// Where the mod badge was last drawn, so that clicking it can narrow the list to that mod
	private int badgeLeft;
	private int badgeRight;
	private int badgeTop;
	private int badgeBottom;
	private int cachedRowWidth = -1;
	private String cachedBadgeText;
	private String cachedName;
	private String cachedDimensions;

	public StructureSearchEntry(StructureSearchList searchList, ResourceLocation key) {
		this.searchList = searchList;
		this.key = key;
		parentScreen = searchList.getParentScreen();
		mc = Minecraft.getInstance();
		document = parentScreen.getSearchDocument(key);
		narration = Component.literal(document.getDisplayName() + " (" + key + ")");
		groupLine = I18n.get("string.explorerscompass.group") + ": " + document.getGroupName();
		dimensionLine = I18n.get("string.explorerscompass.dimension") + ": "
				+ document.getDimensions();
	}

	public ResourceLocation getKey() {
		return key;
	}

	@Override
	public void render(PoseStack poseStack, int par1, int par2, int par3, int par4, int par5, int par6, int par7, boolean par8, float par9) {
		final int left = par3 + 2;
		final int right = par3 + par4;
		final SearchTarget searchTarget = parentScreen.getSearchTarget();
		updateLayout(par4, left);

		// The mod it comes from rides along as a badge on the right, both because it is what tells
		// apart the several mods that add something of the same name, and because clicking it narrows
		// the list down to that mod
		final boolean filteredToThisMod = key.getNamespace().equals(parentScreen.getModFilter());
		final int badgeWidth = mc.font.width(cachedBadgeText) + 8;
		badgeLeft = right - badgeWidth;
		badgeRight = right;
		badgeTop = par2;
		badgeBottom = par2 + 12;
		final boolean overBadge = par8 && par6 >= badgeLeft && par6 < badgeRight && par7 >= badgeTop && par7 < badgeBottom;
		RenderUtils.drawChip(poseStack, cachedBadgeText, badgeLeft, badgeTop,
				filteredToThisMod || overBadge
						? GuiTheme.CHIP_ACCENT_BACKGROUND : GuiTheme.CHIP_BACKGROUND,
				filteredToThisMod || overBadge ? GuiTheme.ACCENT : GuiTheme.TEXT_SECONDARY);

		int nameX = left;
		if (SearchHistory.isFavorite(searchTarget, key)) {
			mc.font.draw(poseStack, FAVORITE_GLYPH, nameX, par2 + 2, GuiTheme.ACCENT);
			nameX += mc.font.width(FAVORITE_GLYPH) + 2;
		}
		mc.font.draw(poseStack, cachedName, nameX, par2 + 2, GuiTheme.TEXT_PRIMARY);

		mc.font.draw(poseStack, groupLine, left, par2 + 14, GuiTheme.TEXT_MUTED);

		// Flag whatever cannot generate in the dimension the player is in, since searching for it here
		// can only fail. An empty list means the dimensions could not be determined, so nothing is
		// flagged.
		final List<ResourceLocation> dimensionKeys = document.getDimensionKeys();
		final boolean inCurrentDimension = dimensionKeys.isEmpty() || mc.level == null || dimensionKeys.contains(mc.level.dimension().location());
		mc.font.draw(poseStack, cachedDimensions, left, par2 + 25,
				inCurrentDimension ? GuiTheme.TEXT_MUTED : GuiTheme.TEXT_WARNING);

		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0) {
			if (mouseX >= badgeLeft && mouseX < badgeRight && mouseY >= badgeTop && mouseY < badgeBottom) {
				// Clicking the badge filters by that mod, and clicking the badge of the mod already
				// filtered for lifts the filter again
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
				parentScreen.setModFilter(key.getNamespace().equals(parentScreen.getModFilter()) ? null : key.getNamespace());
				return true;
			}
			if (Screen.hasControlDown()) {
				// Ctrl-click picks several, to search for the nearest of them all at once
				parentScreen.toggleMultiSelect(key);
				searchList.selectEntry(this);
				lastClickTime = 0;
				return true;
			}
			parentScreen.clearMultiSelect();
			searchList.selectEntry(this);
			if (Util.getMillis() - lastClickTime < 250L) {
				search();
				return true;
			} else {
				lastClickTime = Util.getMillis();
				return false;
			}
		} else if (button == 1) {
			// Right click stars an entry, pinning it to the top of the list
			parentScreen.toggleFavorite(key);
			return true;
		}
		return false;
	}

	@Override
	public Component getNarration() {
		return narration;
	}

	public Component getIdTooltip() {
		return Component.literal(key.toString());
	}

	public void search() {
		mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
		parentScreen.searchForTarget(key);
	}

	public void searchForGroup() {
		mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
		parentScreen.searchForGroup(parentScreen.getSearchTarget().getGroupKey(key));
	}

	private void updateLayout(int rowWidth, int left) {
		if (cachedRowWidth == rowWidth) {
			return;
		}
		cachedRowWidth = rowWidth;
		cachedBadgeText = RenderUtils.trimToWidth(document.getSourceName(),
				Math.min(90, rowWidth / 3));
		final int badgeLeft = left + rowWidth - 2 - mc.font.width(cachedBadgeText) - 8;
		int nameLeft = left;
		if (SearchHistory.isFavorite(parentScreen.getSearchTarget(), key)) {
			nameLeft += mc.font.width(FAVORITE_GLYPH) + 2;
		}
		cachedName = RenderUtils.trimToWidth(document.getDisplayName(),
				badgeLeft - nameLeft - 6);
		cachedDimensions = RenderUtils.trimToWidth(dimensionLine, rowWidth - 6);
	}

}
