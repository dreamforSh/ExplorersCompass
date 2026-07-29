package com.chaosthedude.explorerscompass.gui;

import com.chaosthedude.explorerscompass.client.ClientEventHandler;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.BookmarkEntry;
import com.chaosthedude.explorerscompass.util.RenderUtils;
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
	private final Component narration;
	private final String displayName;
	private final String coordinateLine;
	// Where this entry sits in the compass's own list, which is what the packets refer to
	private final int index;
	private long lastClickTime;
	private int cachedPlayerX = Integer.MIN_VALUE;
	private int cachedPlayerZ = Integer.MIN_VALUE;
	private float cachedPlayerYaw = Float.NaN;
	private String cachedBadgeText;
	private boolean cachedBadgeHere;
	private int cachedRowWidth = -1;
	private String cachedBadge;
	private String cachedName;

	public BookmarkListEntry(BookmarkList bookmarkList, BookmarkEntry bookmark, int index) {
		this.bookmarkList = bookmarkList;
		this.bookmark = bookmark;
		this.index = index;
		parentScreen = bookmarkList.getParentScreen();
		mc = Minecraft.getInstance();
		displayName = bookmark.getSearchTarget().getPrettyName(bookmark.getTargetKey());
		narration = Component.literal(displayName + " (" + bookmark.getTargetKey() + ")");
		final int y = bookmark.getY();
		final String coordinates = y != ExplorersCompassItem.UNKNOWN_Y
				? bookmark.getX() + ", " + y + ", " + bookmark.getZ()
				: bookmark.getX() + ", " + bookmark.getZ();
		coordinateLine = I18n.get("string.explorerscompass.coordinates") + ": " + coordinates;
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
		final int left = par3 + 2;
		final int right = par3 + par4;

		// In another dimension the distance means nothing, so the dimension takes the place of the
		// badge that would otherwise say how far away this location is
		final boolean here = isInCurrentDimension();
		updateDynamicBadge(here);
		updateLayout(par4, left);
		final int badgeLeft = right - mc.font.width(cachedBadge) - 8;
		RenderUtils.drawChip(poseStack, cachedBadge, badgeLeft, par2,
				here ? GuiTheme.CHIP_BACKGROUND : GuiTheme.CHIP_ACCENT_BACKGROUND,
				here ? GuiTheme.TEXT_SECONDARY : GuiTheme.TEXT_WARNING);

		mc.font.draw(poseStack, cachedName, left, par2 + 2, GuiTheme.TEXT_PRIMARY);

		mc.font.draw(poseStack, coordinateLine, left, par2 + 14, GuiTheme.TEXT_MUTED);

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
		return narration;
	}

	public void pointAt() {
		mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
		parentScreen.pointAt(index);
	}

	private void updateDynamicBadge(boolean here) {
		if (!here) {
			if (cachedBadgeText == null || cachedBadgeHere) {
				cachedBadgeText = StructureUtils.getDimensionName(bookmark.getDimensionKey());
				cachedBadgeHere = false;
				cachedRowWidth = -1;
			}
			return;
		}

		final int playerX = parentScreen.getPlayer().getBlockX();
		final int playerZ = parentScreen.getPlayer().getBlockZ();
		final float playerYaw = parentScreen.getPlayer().getYRot();
		if (cachedBadgeHere && cachedPlayerX == playerX && cachedPlayerZ == playerZ
				&& cachedPlayerYaw == playerYaw) {
			return;
		}
		cachedBadgeHere = true;
		cachedPlayerX = playerX;
		cachedPlayerZ = playerZ;
		cachedPlayerYaw = playerYaw;
		cachedBadgeText = String.format("%,d", StructureUtils.getHorizontalDistanceToLocation(
				parentScreen.getPlayer(), bookmark.getX(), bookmark.getZ()))
				+ " " + ClientEventHandler.compassDirection(parentScreen.getPlayer(),
						bookmark.getX(), bookmark.getZ());
		cachedRowWidth = -1;
	}

	private void updateLayout(int rowWidth, int left) {
		if (cachedRowWidth == rowWidth) {
			return;
		}
		cachedRowWidth = rowWidth;
		cachedBadge = RenderUtils.trimToWidth(cachedBadgeText, Math.min(110, rowWidth / 2));
		final int badgeLeft = left + rowWidth - 2 - mc.font.width(cachedBadge) - 8;
		cachedName = RenderUtils.trimToWidth(displayName, badgeLeft - left - 6);
	}

}
