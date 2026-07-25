package com.chaosthedude.explorerscompass.gui;

import java.util.List;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.network.BookmarkActionPacket;
import com.chaosthedude.explorerscompass.network.ShareLocationPacket;
import com.chaosthedude.explorerscompass.network.TeleportPacket;
import com.chaosthedude.explorerscompass.util.BookmarkEntry;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Lists the structures this compass has located, so that any of them can be pointed at, travelled
 * to, or shared again after the compass has moved on to something else.
 */
@OnlyIn(Dist.CLIENT)
public class BookmarksScreen extends Screen {

	private final Screen parentScreen;
	private final Player player;
	private final ExplorersCompassItem explorersCompass;
	private ItemStack stack;
	private List<BookmarkEntry> bookmarks;
	// The tag the list was read from, so that it is only re-read once the server has changed it
	private CompoundTag lastTag;
	private BookmarkList selectionList;
	private Button pointAtButton;
	private Button teleportButton;
	private Button shareButton;
	private Button removeButton;
	private Button clearButton;
	private Button backButton;

	public BookmarksScreen(Screen parentScreen, Player player, ItemStack stack, ExplorersCompassItem explorersCompass) {
		super(Component.translatable("string.explorerscompass.bookmarks"));
		this.parentScreen = parentScreen;
		this.player = player;
		this.stack = stack;
		this.explorersCompass = explorersCompass;

		bookmarks = explorersCompass.getBookmarks(stack);
		lastTag = stack.getTag();
	}

	public Player getPlayer() {
		return player;
	}

	/** The remembered locations, oldest first. Their positions here are what the packets refer to. */
	public List<BookmarkEntry> getBookmarks() {
		return bookmarks;
	}

	@Override
	public boolean mouseScrolled(double scroll1, double scroll2, double scroll3) {
		return selectionList.mouseScrolled(scroll1, scroll2, scroll3);
	}

	@Override
	protected void init() {
		setupWidgets();
	}

	@Override
	public void tick() {
		// The server replaces the whole stack when it applies a change, so the reference captured when
		// this screen opened goes stale and would never show a removal going through
		final ItemStack heldStack = ItemUtils.getHeldItem(player, ExplorersCompass.explorersCompass);
		if (!heldStack.isEmpty()) {
			stack = heldStack;
		}

		if (stack.getTag() != lastTag) {
			lastTag = stack.getTag();
			final List<BookmarkEntry> current = explorersCompass.getBookmarks(stack);
			// Rebuilding drops the selection, so only do it when the list really did change
			if (!current.equals(bookmarks)) {
				bookmarks = current;
				selectionList.refreshList();
			}
		}

		updateButtons();
	}

	@Override
	public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
		renderBackground(poseStack);
		drawCenteredString(poseStack, font, title.copy().append(Component.literal(" (" + bookmarks.size() + ")")), 65, 15, 0xffffff);
		super.render(poseStack, mouseX, mouseY, partialTicks);
		if (bookmarks.isEmpty()) {
			drawCenteredString(poseStack, font, Component.translatable("string.explorerscompass.noBookmarks"), width / 2 + 55, height / 2 - 4, 0x808080);
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parentScreen);
	}

	public void selectBookmark(BookmarkListEntry entry) {
		updateButtons();
	}

	/** Points the compass at a remembered location and closes, so that it can be followed. */
	public void pointAt(int index) {
		ExplorersCompass.network.sendToServer(new BookmarkActionPacket(BookmarkActionPacket.Action.SELECT, index));
		minecraft.setScreen(null);
	}

	public void teleportTo(int index) {
		// Teleporting acts on whatever the compass points at, so point it at this location first. Both
		// packets are handled on the server thread in the order they were sent.
		ExplorersCompass.network.sendToServer(new BookmarkActionPacket(BookmarkActionPacket.Action.SELECT, index));
		ExplorersCompass.network.sendToServer(new TeleportPacket());
		minecraft.setScreen(null);
	}

	public void share(int index) {
		ExplorersCompass.network.sendToServer(new ShareLocationPacket(index));
	}

	public void remove(int index) {
		ExplorersCompass.network.sendToServer(new BookmarkActionPacket(BookmarkActionPacket.Action.REMOVE, index));
	}

	public void clearAll() {
		ExplorersCompass.network.sendToServer(new BookmarkActionPacket(BookmarkActionPacket.Action.CLEAR, 0));
	}

	private void setupWidgets() {
		clearWidgets();
		pointAtButton = addRenderableWidget(new TransparentButton(10, 40, 110, 20, Component.translatable("string.explorerscompass.pointAt"), (onPress) -> {
			if (selectionList.hasSelection()) {
				pointAt(selectionList.getSelected().getIndex());
			}
		}));
		teleportButton = addRenderableWidget(new TransparentButton(10, 65, 110, 20, Component.translatable("string.explorerscompass.teleport"), (onPress) -> {
			if (selectionList.hasSelection()) {
				teleportTo(selectionList.getSelected().getIndex());
			}
		}));
		shareButton = addRenderableWidget(new TransparentButton(10, 90, 110, 20, Component.translatable("string.explorerscompass.share"), (onPress) -> {
			if (selectionList.hasSelection()) {
				share(selectionList.getSelected().getIndex());
			}
		}));
		removeButton = addRenderableWidget(new TransparentButton(10, 115, 110, 20, Component.translatable("string.explorerscompass.remove"), (onPress) -> {
			if (selectionList.hasSelection()) {
				remove(selectionList.getSelected().getIndex());
			}
		}));
		clearButton = addRenderableWidget(new TransparentButton(10, 140, 110, 20, Component.translatable("string.explorerscompass.clearAll"), (onPress) -> {
			clearAll();
		}));
		backButton = addRenderableWidget(new TransparentButton(10, height - 30, 110, 20, Component.translatable("string.explorerscompass.back"), (onPress) -> {
			onClose();
		}));

		// Recreated on every init so that it picks up the current screen dimensions
		selectionList = new BookmarkList(this, minecraft, width + 110, height, 40, height, 36);
		addRenderableWidget(selectionList);
		updateButtons();
	}

	private void updateButtons() {
		final BookmarkListEntry selected = selectionList != null ? selectionList.getSelected() : null;
		final boolean hasSelection = selected != null;

		pointAtButton.active = hasSelection;
		removeButton.active = hasSelection;
		clearButton.active = !bookmarks.isEmpty();
		// The coordinates of a location in another dimension cannot be travelled to from here
		teleportButton.visible = ExplorersCompass.canTeleport;
		teleportButton.active = hasSelection && selected.isInCurrentDimension();
		shareButton.visible = ConfigHandler.GENERAL.allowSharing.get();
		shareButton.active = hasSelection;
	}

}
