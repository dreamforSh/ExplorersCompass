package com.chaosthedude.explorerscompass.gui;

import java.util.List;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.client.SearchHistory;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class StructureSearchEntry extends ObjectSelectionList.Entry<StructureSearchEntry> {

	private final Minecraft mc;
	private final ExplorersCompassScreen parentScreen;
	private final ResourceLocation structureKey;
	private final StructureSearchList structuresList;
	private long lastClickTime;

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
		int nameX = par3 + 1;
		if (SearchHistory.isFavorite(structureKey)) {
			mc.font.draw(poseStack, "★", nameX, par2 + 1, 0xFFAA00);
			nameX += 10;
		}
		mc.font.draw(poseStack, Component.literal(StructureUtils.getPrettyStructureName(structureKey)), nameX, par2 + 1, 0xffffff);
		mc.font.draw(poseStack, Component.translatable(("string.explorerscompass.source")).append(Component.literal(": " + StructureUtils.getPrettyStructureSource(structureKey))), par3 + 1, par2 + mc.font.lineHeight + 3, 0x808080);
		// The group name is a display name, not a translation key, so it must not go through translatable
		mc.font.draw(poseStack, Component.translatable(("string.explorerscompass.group")).append(Component.literal(": " + StructureUtils.getPrettyGroupName(ExplorersCompass.structureKeysToTypeKeys.get(structureKey)))), par3 + 1, par2 + mc.font.lineHeight + 14, 0x808080);
		// Flag structures that cannot generate in the dimension the player is in, since searching for
		// one of them here can only fail. An empty list means the dimensions could not be determined,
		// so nothing is flagged.
		final List<ResourceLocation> dimensionKeys = ExplorersCompass.dimensionKeysForAllowedStructureKeys.get(structureKey);
		final boolean inCurrentDimension = dimensionKeys.isEmpty() || mc.level == null || dimensionKeys.contains(mc.level.dimension().location());
		mc.font.draw(poseStack, Component.translatable(("string.explorerscompass.dimension")).append(Component.literal(": " + StructureUtils.dimensionKeysToString(dimensionKeys))), par3 + 1, par2 + mc.font.lineHeight + 25, inCurrentDimension ? 0x808080 : 0xCC6666);
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0) {
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