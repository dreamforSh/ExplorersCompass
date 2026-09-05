package com.chaosthedude.explorerscompass.gui;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.chaosthedude.explorerscompass.client.ClientEventHandler;
import com.chaosthedude.explorerscompass.client.CompassWaypoint;
import com.chaosthedude.explorerscompass.client.XaeroMinimapIntegration;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.util.RenderUtils;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.sounds.SoundEvents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** One of the waypoints the compass keeps, in the list of them. */
@OnlyIn(Dist.CLIENT)
public class WaypointListEntry extends ObjectSelectionList.Entry<WaypointListEntry> {

	private static final String HUD_CHIP = "HUD";
	/** The room the mark beside the name takes: the widest of the shapes, and the height of the name line. */
	private static final int MARK_WIDTH = WaypointMarkers.HALF_WIDTH * 2 + 2;
	private static final int MARK_HEIGHT = 12;

	private final Minecraft mc;
	private final WaypointsScreen parentScreen;
	private final WaypointList waypointList;
	private final CompassWaypoint waypoint;
	private final Component narration;
	private final String coordinateLine;
	private long lastClickTime;
	private int cachedPlayerX = Integer.MIN_VALUE;
	private int cachedPlayerZ = Integer.MIN_VALUE;
	private float cachedPlayerYaw = Float.NaN;
	private String cachedBadgeText;
	private boolean cachedBadgeHere;
	private int cachedRowWidth = -1;
	private String cachedBadge;
	private String cachedName;
	private String cachedCoordinates;
	/** Whether the minimap still has its copy, asked once: asking walks the minimap's waypoints. */
	private XaeroMinimapIntegration.MirrorState mirrorState;

	public WaypointListEntry(WaypointList waypointList, CompassWaypoint waypoint) {
		this.waypointList = waypointList;
		this.waypoint = waypoint;
		parentScreen = waypointList.getParentScreen();
		mc = Minecraft.getInstance();
		narration = Component.literal(waypoint.getName() + " (" + StructureUtils.formatCoordinates(waypoint.getX(), waypoint.getY(), waypoint.getZ()) + ")");
		coordinateLine = labeled("string.explorerscompass.coordinates", StructureUtils.formatCoordinates(waypoint.getX(), waypoint.getY(), waypoint.getZ()));
	}

	public CompassWaypoint getWaypoint() {
		return waypoint;
	}

	/** Whether the coordinates of this waypoint mean anything where the player currently is. */
	public boolean isInCurrentDimension() {
		return mc.level == null || waypoint.getDimension().equals(mc.level.dimension().location());
	}

	@Override
	public void render(PoseStack poseStack, int par1, int par2, int par3, int par4, int par5, int par6, int par7, boolean par8, float par9) {
		final int left = par3 + 2;
		final int right = par3 + par4;

		// In another dimension the distance means nothing, so the dimension takes the place of the
		// badge that would otherwise say how far away this waypoint is
		final boolean here = isInCurrentDimension();
		updateDynamicBadge(here);
		updateLayout(par4, left);
		int badgeLeft = right - mc.font.width(cachedBadge) - 8;
		RenderUtils.drawChip(poseStack, cachedBadge, badgeLeft, par2,
				here ? GuiTheme.CHIP_BACKGROUND : GuiTheme.CHIP_ACCENT_BACKGROUND,
				here ? GuiTheme.TEXT_SECONDARY : GuiTheme.TEXT_WARNING);

		// Whether the strip marks this one, said with a chip lit or dimmed rather than left to be found
		// out by looking at the strip
		final boolean onHud = waypoint.isShownOnHud();
		badgeLeft -= mc.font.width(HUD_CHIP) + 8 + 4;
		RenderUtils.drawChip(poseStack, HUD_CHIP, badgeLeft, par2,
				onHud ? GuiTheme.CHIP_ACCENT_BACKGROUND : 0x20FFFFFF,
				onHud ? GuiTheme.ACCENT : GuiTheme.TEXT_DISABLED);

		// The mark the strip draws for this waypoint, in its colour, so that the row and the strip can be
		// matched up by eye; dimmed along with the name when the strip is not drawing it
		final int markX = left + WaypointMarkers.HALF_WIDTH;
		final int markColor = (onHud ? 0xFF000000 : 0x80000000) | waypoint.getColor();
		WaypointMarkers.drawWithShadow(ConfigHandler.CLIENT.directionBarWaypointStyle.get(), markX, par2, par2 + MARK_HEIGHT, markColor, 0x60000000);
		mc.font.draw(poseStack, cachedName, left + MARK_WIDTH + 4, par2 + 2, onHud ? GuiTheme.TEXT_PRIMARY : GuiTheme.TEXT_SECONDARY);

		mc.font.draw(poseStack, cachedCoordinates, left, par2 + 14, GuiTheme.TEXT_MUTED);

		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0) {
			waypointList.selectWaypoint(this);
			if (Util.getMillis() - lastClickTime < 250L) {
				pointAt();
				return true;
			}
			lastClickTime = Util.getMillis();
			return false;
		}
		if (button == 1) {
			// Right click takes the waypoint off the strip, or puts it back, without a trip to the sidebar
			waypointList.selectWaypoint(this);
			parentScreen.toggleHud(waypoint);
			return true;
		}
		return false;
	}

	/**
	 * Everything about this waypoint: whichever of the distance and the dimension the badge had no
	 * room for, when it was recorded, whether the strip marks it, and where the minimap stands on it.
	 */
	public List<Component> getTooltipLines() {
		final List<Component> lines = new ArrayList<Component>();
		lines.add(Component.literal(waypoint.getName()).withStyle((style) -> style.withColor(TextColor.fromRgb(GuiTheme.TEXT_PRIMARY))));
		lines.add(muted(coordinateLine, GuiTheme.TEXT_SECONDARY));

		final boolean here = isInCurrentDimension();
		if (here) {
			lines.add(muted(labeled("string.explorerscompass.distance",
					String.format("%,d", StructureUtils.getHorizontalDistanceToLocation(parentScreen.getPlayer(), waypoint.getX(), waypoint.getZ()))
							+ " (" + ClientEventHandler.compassDirection(parentScreen.getPlayer(), waypoint.getX(), waypoint.getZ()) + ")"),
					GuiTheme.TEXT_SECONDARY));
		}
		lines.add(muted(labeled("string.explorerscompass.dimension", StructureUtils.getDimensionName(waypoint.getDimension())),
				here ? GuiTheme.TEXT_SECONDARY : GuiTheme.TEXT_WARNING));
		if (waypoint.getCreatedAt() > 0L) {
			lines.add(muted(labeled("string.explorerscompass.waypointRecorded", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(waypoint.getCreatedAt()))), GuiTheme.TEXT_SECONDARY));
		}
		lines.add(muted(I18n.get(waypoint.isShownOnHud() ? "string.explorerscompass.waypointOnHud" : "string.explorerscompass.waypointOffHud"), waypoint.isShownOnHud() ? GuiTheme.ACCENT : GuiTheme.TEXT_SECONDARY));

		if (XaeroMinimapIntegration.isInstalled()) {
			lines.add(muted(labeled("string.explorerscompass.xaero", I18n.get(xaeroStateKey())), GuiTheme.TEXT_SECONDARY));
		}
		if (waypoint.getTargetKey() != null) {
			lines.add(Component.literal(waypoint.getTargetKey().toString()).withStyle(ChatFormatting.DARK_GRAY));
		}
		return lines;
	}

	/** Where the minimap stands on this waypoint, asked once for the life of the row. */
	private String xaeroStateKey() {
		if (waypoint.getXaeroWorld() == null) {
			return "string.explorerscompass.xaero.notMirrored";
		}
		if (mirrorState == null) {
			mirrorState = XaeroMinimapIntegration.mirrorState(waypoint);
		}
		switch (mirrorState) {
			case PRESENT:
				return "string.explorerscompass.xaero.mirrored";
			case MISSING:
				return "string.explorerscompass.xaero.missing";
			default:
				return "string.explorerscompass.xaero.otherWorld";
		}
	}

	/** A labelled value, punctuated the way the player's own language punctuates one. */
	private static String labeled(String labelKey, String value) {
		return I18n.get("string.explorerscompass.labeledValue", I18n.get(labelKey), value);
	}

	private static Component muted(String text, int color) {
		return Component.literal(text).withStyle((style) -> style.withColor(TextColor.fromRgb(color)));
	}

	@Override
	public Component getNarration() {
		return narration;
	}

	public void pointAt() {
		mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
		parentScreen.pointAt(waypoint);
	}

	private void updateDynamicBadge(boolean here) {
		if (!here) {
			if (cachedBadgeText == null || cachedBadgeHere) {
				cachedBadgeText = StructureUtils.getDimensionName(waypoint.getDimension());
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
				parentScreen.getPlayer(), waypoint.getX(), waypoint.getZ()))
				+ " " + ClientEventHandler.compassDirection(parentScreen.getPlayer(),
						waypoint.getX(), waypoint.getZ());
		cachedRowWidth = -1;
	}

	private void updateLayout(int rowWidth, int left) {
		if (cachedRowWidth == rowWidth) {
			return;
		}
		cachedRowWidth = rowWidth;
		cachedBadge = RenderUtils.trimToWidth(cachedBadgeText, Math.min(110, rowWidth / 2));
		// Both chips end level with the name, so the name stops short of the second of them
		final int chipsWidth = mc.font.width(cachedBadge) + 8 + 4 + mc.font.width(HUD_CHIP) + 8;
		final int badgeLeft = left + rowWidth - 2 - chipsWidth;
		cachedName = RenderUtils.trimToWidth(waypoint.getName(), badgeLeft - left - MARK_WIDTH - 4 - 6);
		cachedCoordinates = RenderUtils.trimToWidth(coordinateLine, rowWidth - 6);
	}

}
