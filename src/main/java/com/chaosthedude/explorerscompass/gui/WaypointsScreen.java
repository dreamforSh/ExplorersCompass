package com.chaosthedude.explorerscompass.gui;

import java.util.List;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.client.CompassWaypoint;
import com.chaosthedude.explorerscompass.client.CompassWaypoints;
import com.chaosthedude.explorerscompass.client.XaeroMinimapIntegration;
import com.chaosthedude.explorerscompass.client.XaeroWaypointDisplay;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.network.PointAtPacket;
import com.chaosthedude.explorerscompass.network.TeleportPacket;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Lists the waypoints the compass has recorded in this world, so that any of them can be pointed
 * at, travelled to, taken off the direction strip or forgotten, and so that what the minimap does
 * with its copies of them can be changed from one place.
 *
 * <p>Every place the compass locates is recorded as one of these on this client, whichever compass
 * did the locating. That is what tells them apart from the locations a compass remembers on itself:
 * those follow the compass, and these follow the player.
 */
@OnlyIn(Dist.CLIENT)
public class WaypointsScreen extends Screen {

	/** What a waypoint that names nothing is pointed at as, which prettifies to a plain "waypoint". */
	private static final ResourceLocation UNNAMED_TARGET = new ResourceLocation(ExplorersCompass.MODID, "waypoint");

	private final Screen parentScreen;
	private final Player player;
	private List<CompassWaypoint> waypoints;
	private int lastRevision = -1;
	private WaypointList selectionList;
	private TransparentButton pointAtButton;
	private TransparentButton teleportButton;
	private TransparentButton hudButton;
	private TransparentButton removeButton;
	private TransparentButton clearButton;
	private TransparentButton xaeroButton;
	private TransparentButton syncButton;
	private TransparentButton backButton;
	/** Where the sidebar has room for the next control. */
	private int sidebarY;

	public WaypointsScreen(Screen parentScreen, Player player) {
		super(Component.translatable("string.explorerscompass.waypoints"));
		this.parentScreen = parentScreen;
		this.player = player;
		waypoints = CompassWaypoints.inCurrentWorld();
		lastRevision = CompassWaypoints.getRevision();
	}

	public Player getPlayer() {
		return player;
	}

	/** The waypoints of this world, newest first. */
	public List<CompassWaypoint> getWaypoints() {
		return waypoints;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		return selectionList.mouseScrolled(mouseX, mouseY, delta);
	}

	@Override
	protected void init() {
		setupWidgets();
	}

	@Override
	public void tick() {
		// The list can change under the screen: a copy deleted in the minimap takes its record with it
		if (CompassWaypoints.getRevision() != lastRevision) {
			refreshWaypoints();
		}
		updateButtons();
	}

	@Override
	public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
		renderBackground(poseStack);
		GuiTheme.drawHeader(width);
		GuiTheme.drawSidebar(height);
		GuiTheme.drawTitle(poseStack, font, title.getString(), String.valueOf(waypoints.size()), GuiTheme.SIDEBAR_CONTENT_X, 10);
		super.render(poseStack, mouseX, mouseY, partialTicks);
		if (waypoints.isEmpty()) {
			final int centerX = GuiTheme.contentLeft() + GuiTheme.contentWidth(width) / 2;
			drawCenteredString(poseStack, font, Component.translatable("string.explorerscompass.noWaypoints"), centerX, height / 2 - 10, GuiTheme.TEXT_SECONDARY);
			drawCenteredString(poseStack, font, Component.translatable("string.explorerscompass.noWaypointsHint"), centerX, height / 2 + 2, GuiTheme.TEXT_MUTED);
		}
		renderButtonTooltip(poseStack, mouseX, mouseY);
	}

	/** Explains whatever the pointer is resting on, above everything else on the screen. */
	private void renderButtonTooltip(PoseStack poseStack, int mouseX, int mouseY) {
		for (Object widget : renderables) {
			if (widget instanceof TransparentButton button && button.visible && button.isPointedAt() && !button.getTooltipLines().isEmpty()) {
				renderComponentTooltip(poseStack, button.getTooltipLines(), mouseX, mouseY);
				return;
			}
		}
		final WaypointListEntry hovered = selectionList.getHoveredEntry(mouseX, mouseY);
		if (hovered != null) {
			renderComponentTooltip(poseStack, hovered.getTooltipLines(), mouseX, mouseY);
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parentScreen);
	}

	public void selectWaypoint(WaypointListEntry entry) {
		updateButtons();
	}

	/** Points the held compass at a waypoint and closes, so that it can be followed. */
	public void pointAt(CompassWaypoint waypoint) {
		ExplorersCompass.network.sendToServer(pointAtPacket(waypoint));
		minecraft.setScreen(null);
	}

	public void teleportTo(CompassWaypoint waypoint) {
		// Teleporting acts on whatever the compass points at, so point it at this waypoint first. Both
		// packets are handled on the server thread in the order they were sent.
		ExplorersCompass.network.sendToServer(pointAtPacket(waypoint));
		ExplorersCompass.network.sendToServer(new TeleportPacket());
		minecraft.setScreen(null);
	}

	private static PointAtPacket pointAtPacket(CompassWaypoint waypoint) {
		// A record that no longer names what it marks still marks somewhere; the compass is told
		// something to call it
		final ResourceLocation targetKey = waypoint.getTargetKey() != null ? waypoint.getTargetKey() : UNNAMED_TARGET;
		return new PointAtPacket(waypoint.getSearchTarget(), targetKey, waypoint.getX(), waypoint.getY(), waypoint.getZ(), waypoint.getDimension());
	}

	/** Takes the waypoint off the direction strip, or puts it back. */
	public void toggleHud(CompassWaypoint waypoint) {
		CompassWaypoints.setShownOnHud(waypoint, !waypoint.isShownOnHud());
		updateButtons();
	}

	public void remove(CompassWaypoint waypoint) {
		CompassWaypoints.remove(waypoint);
		refreshWaypoints();
	}

	public void clearAll() {
		CompassWaypoints.clearCurrentWorld();
		refreshWaypoints();
	}

	private void refreshWaypoints() {
		waypoints = CompassWaypoints.inCurrentWorld();
		lastRevision = CompassWaypoints.getRevision();
		selectionList.refreshList();
		updateButtons();
	}

	private void setupWidgets() {
		clearWidgets();
		sidebarY = GuiTheme.HEADER_HEIGHT + 8;

		pointAtButton = addSidebarButton(Component.translatable("string.explorerscompass.pointAt"), (onPress) -> {
			if (selectionList.hasSelection()) {
				pointAt(selectionList.getSelected().getWaypoint());
			}
		});
		pointAtButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.pointAtWaypoint"));
		teleportButton = addSidebarButton(Component.translatable("string.explorerscompass.teleport"), (onPress) -> {
			if (selectionList.hasSelection()) {
				teleportTo(selectionList.getSelected().getWaypoint());
			}
		});
		teleportButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.teleport"));
		hudButton = addSidebarButton(Component.translatable("string.explorerscompass.showOnHud"), (onPress) -> {
			if (selectionList.hasSelection()) {
				toggleHud(selectionList.getSelected().getWaypoint());
			}
		});
		hudButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.showOnHud"), Component.translatable("string.explorerscompass.tooltip.showOnHud.rightClick"));
		removeButton = addSidebarButton(Component.translatable("string.explorerscompass.remove"), (onPress) -> {
			if (selectionList.hasSelection()) {
				remove(selectionList.getSelected().getWaypoint());
			}
		});
		removeButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.removeWaypoint"));
		clearButton = addSidebarButton(Component.translatable("string.explorerscompass.clearAll"), (onPress) -> {
			clearAll();
		});
		clearButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.clearAllWaypoints"), Component.translatable("string.explorerscompass.tooltip.clearAll.warning").withStyle(ChatFormatting.RED));

		// Both of these are about the minimap's copies, so neither is offered where there is no minimap
		xaeroButton = addSidebarButton(xaeroButtonLabel(), (onPress) -> {
			final XaeroWaypointDisplay display = ConfigHandler.CLIENT.xaeroWaypointDisplay.get().next();
			ConfigHandler.CLIENT.xaeroWaypointDisplay.set(display);
			ConfigHandler.CLIENT.xaeroWaypointDisplay.save();
			xaeroButton.setMessage(xaeroButtonLabel());
			// Applied to the copies in the world the minimap has open right away: this is the player
			// asking for it, which is the one time the copies are changed under them
			CompassWaypoints.reconcileWithXaero(true);
		});
		xaeroButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.xaeroDisplay"), Component.translatable("string.explorerscompass.tooltip.xaeroDisplay.modes"));
		syncButton = addSidebarButton(Component.translatable("string.explorerscompass.syncXaero"), (onPress) -> {
			CompassWaypoints.mirrorCurrentDimension();
			CompassWaypoints.reconcileWithXaero(true);
			refreshWaypoints();
		});
		syncButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.syncXaero"));

		backButton = addRenderableWidget(new TransparentButton(GuiTheme.SIDEBAR_CONTENT_X, height - 26, GuiTheme.SIDEBAR_CONTENT_WIDTH, GuiTheme.BUTTON_HEIGHT, Component.translatable("string.explorerscompass.back"), (onPress) -> {
			onClose();
		}));
		backButton.setTooltipLines(Component.translatable("string.explorerscompass.tooltip.back"));

		// Recreated on every init so that it picks up the current screen dimensions
		selectionList = new WaypointList(this, minecraft, GuiTheme.contentLeft(), GuiTheme.contentWidth(width), height, GuiTheme.HEADER_HEIGHT + 2, height - 10, 30);
		addRenderableWidget(selectionList);
		updateButtons();
	}

	/** Adds a control to the bottom of the sidebar column, and moves the column down past it. */
	private TransparentButton addSidebarButton(Component label, Button.OnPress onPress) {
		final TransparentButton button = addRenderableWidget(new TransparentButton(GuiTheme.SIDEBAR_CONTENT_X, sidebarY, GuiTheme.SIDEBAR_CONTENT_WIDTH, GuiTheme.BUTTON_HEIGHT, label, onPress));
		sidebarY += GuiTheme.BUTTON_SPACING;
		return button;
	}

	private Component xaeroButtonLabel() {
		return Component.translatable("string.explorerscompass.xaero").append(Component.literal(": ")).append(Component.translatable(ConfigHandler.CLIENT.xaeroWaypointDisplay.get().getTranslationKey()));
	}

	private void updateButtons() {
		final WaypointListEntry selected = selectionList != null ? selectionList.getSelected() : null;
		final boolean hasSelection = selected != null;

		pointAtButton.active = hasSelection;
		removeButton.active = hasSelection;
		clearButton.active = !waypoints.isEmpty();
		hudButton.active = hasSelection;
		hudButton.setMessage(Component.translatable(hasSelection && selected.getWaypoint().isShownOnHud() ? "string.explorerscompass.hideFromHud" : "string.explorerscompass.showOnHud"));
		// The teleport changes dimension when the waypoint is not in this one, so it is available for
		// any of them
		teleportButton.visible = ExplorersCompass.canTeleport;
		teleportButton.active = hasSelection;

		final boolean xaero = XaeroMinimapIntegration.isInstalled();
		xaeroButton.visible = xaero;
		syncButton.visible = xaero;
		xaeroButton.active = XaeroMinimapIntegration.isActive();
		syncButton.active = XaeroMinimapIntegration.isActive() && !waypoints.isEmpty();
	}

}
