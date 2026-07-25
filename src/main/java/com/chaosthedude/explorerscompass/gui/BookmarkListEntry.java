package com.chaosthedude.explorerscompass.gui;

import com.chaosthedude.explorerscompass.client.ClientEventHandler;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.BookmarkEntry;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class BookmarkListEntry extends ObjectSelectionList.Entry<BookmarkListEntry> {

	private final Minecraft mc;
	private final BookmarksScreen parentScreen;
	private final BookmarkList bookmarkList;
	private final BookmarkEntry bookmark;
	// Where this entry sits in the compass's own list, which is what the packets refer to
	private final int index;
	private long lastClickTime;

	public BookmarkListEntry(BookmarkList bookmarkList, BookmarkEntry bookmark, int index) {
		this.bookmarkList = bookmarkList;
		this.bookmark = bookmark;
		this.index = index;
		parentScreen = bookmarkList.getParentScreen();
		mc = Minecraft.getInstance();
	}

	public int getIndex() {
		return index;
	}

	public BookmarkEntry getBookmark() {
		return bookmark;
	}

	/** Whether the coordinates of this entry mean anything where the player currently is. */
	public boolean isInCurrentDimension() {
		return bookmark.getDimensionKey() == null || mc.level == null || bookmark.getDimensionKey().equals(mc.level.dimension().location());
	}

	@Override
	public void render(PoseStack poseStack, int par1, int par2, int par3, int par4, int par5, int par6, int par7, boolean par8, float par9) {
		mc.font.draw(poseStack, Component.literal(StructureUtils.getPrettyStructureName(bookmark.getStructureKey())), par3 + 1, par2 + 1, 0xffffff);

		final int y = bookmark.getY();
		final String coordinates = y != ExplorersCompassItem.UNKNOWN_Y ? bookmark.getX() + ", " + y + ", " + bookmark.getZ() : bookmark.getX() + ", " + bookmark.getZ();
		mc.font.draw(poseStack, Component.translatable("string.explorerscompass.coordinates").append(Component.literal(": " + coordinates)), par3 + 1, par2 + mc.font.lineHeight + 3, 0x808080);

		// In another dimension the distance means nothing, so the dimension is shown in its place
		if (isInCurrentDimension()) {
			final String distance = String.format("%,d", StructureUtils.getHorizontalDistanceToLocation(parentScreen.getPlayer(), bookmark.getX(), bookmark.getZ()));
			final String direction = ClientEventHandler.compassDirection(parentScreen.getPlayer(), bookmark.getX(), bookmark.getZ());
			mc.font.draw(poseStack, I18n.get("string.explorerscompass.distance") + ": " + distance + " (" + direction + ")", par3 + 1, par2 + mc.font.lineHeight + 14, 0x808080);
		} else {
			mc.font.draw(poseStack, I18n.get("string.explorerscompass.dimension") + ": " + StructureUtils.getDimensionName(bookmark.getDimensionKey()), par3 + 1, par2 + mc.font.lineHeight + 14, 0xCC6666);
		}

		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0) {
			bookmarkList.selectBookmark(this);
			if (Util.getMillis() - lastClickTime < 250L) {
				pointAt();
				return true;
			}
			lastClickTime = Util.getMillis();
			return false;
		}
		return false;
	}

	@Override
	public Component getNarration() {
		return Component.literal(StructureUtils.getPrettyStructureName(bookmark.getStructureKey()));
	}

	public void pointAt() {
		mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
		parentScreen.pointAt(index);
	}

}
