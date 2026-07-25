package com.chaosthedude.explorerscompass.config;

import java.util.ArrayList;
import java.util.List;

import com.chaosthedude.explorerscompass.client.OverlaySide;

import net.minecraftforge.common.ForgeConfigSpec;

public class ConfigHandler {

	private static final ForgeConfigSpec.Builder GENERAL_BUILDER = new ForgeConfigSpec.Builder();
	private static final ForgeConfigSpec.Builder CLIENT_BUILDER = new ForgeConfigSpec.Builder();

	public static final General GENERAL = new General(GENERAL_BUILDER);
	public static final Client CLIENT = new Client(CLIENT_BUILDER);

	public static final ForgeConfigSpec GENERAL_SPEC = GENERAL_BUILDER.build();
	public static final ForgeConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();

	public static class General {
		public final ForgeConfigSpec.BooleanValue allowTeleport;
		public final ForgeConfigSpec.IntValue maxNextSearches;
		public final ForgeConfigSpec.BooleanValue displayCoordinates;
		public final ForgeConfigSpec.IntValue maxRadius;
		public final ForgeConfigSpec.ConfigValue<List<String>> structureBlacklist;
		public final ForgeConfigSpec.IntValue maxSamples;
		public final ForgeConfigSpec.IntValue maxSearchTimePerTick;
		public final ForgeConfigSpec.IntValue searchRequestCooldownMillis;
		public final ForgeConfigSpec.IntValue maxBookmarks;
		public final ForgeConfigSpec.BooleanValue allowSharing;
		public final ForgeConfigSpec.IntValue shareCooldownMillis;

		General(ForgeConfigSpec.Builder builder) {
			String desc;
			builder.push("General");

			desc = "Allows a player to teleport to a located structure when in creative mode, opped, or in cheat mode.";
			allowTeleport = builder.comment(desc).define("allowTeleport", true);

			desc = "The maximum number of times a player can search for the next instance of a located structure, skipping the locations already found. Once this many locations have been collected the next search starts over from the closest one again. Set to 0 to disable searching for further instances and make the compass always locate the nearest one.";
			maxNextSearches = builder.comment(desc).defineInRange("maxNextSearches", 100, 0, 10000);
			
			desc = "Allows players to view the precise coordinates and distance of a located structure on the HUD, rather than relying on the direction the compass is pointing.";
			displayCoordinates = builder.comment(desc).define("displayCoordinates", true);

			desc = "The maximum radius that will be searched for a structure. Raising this value will increase search accuracy but will potentially make the process more resource intensive.";
			maxRadius = builder.comment(desc).defineInRange("maxRadius", 10000, 0, 1000000);

			desc = "A list of structures that the compass will not display in the GUI and will not be able to search for. Wildcard character * can be used to match any number of characters, and ? can be used to match one character. Ex: [\"minecraft:stronghold\", \"minecraft:endcity\", \"minecraft:*village*\"]";
			structureBlacklist = builder.comment(desc).define("structureBlacklist", new ArrayList<String>());

			desc = "The maximum number of samples to be taken when searching for a structure.";
			maxSamples = builder.comment(desc).defineInRange("maxSamples", 100000, 0, 100000000);

			desc = "The maximum amount of time in milliseconds that a search may spend on the server thread during a single tick. Sampling a location can be expensive, so this caps how much of each tick a search is allowed to consume. Lower values keep the game responsive while a search is running, higher values complete searches sooner.";
			maxSearchTimePerTick = builder.comment(desc).defineInRange("maxSearchTimePerTick", 10, 1, 50);

			desc = "The minimum time in milliseconds between searches started by the same player. Search requests arriving faster than this are ignored. This guards against modified clients restarting expensive searches as fast as they can send packets. Set to 0 to disable.";
			searchRequestCooldownMillis = builder.comment(desc).defineInRange("searchRequestCooldownMillis", 500, 0, 10000);

			desc = "How many located structures a compass remembers, so that they can be pointed at again later. The oldest is dropped once this many have been collected. Set to 0 to disable remembering them.";
			maxBookmarks = builder.comment(desc).defineInRange("maxBookmarks", 64, 0, 1024);

			desc = "Allows players to announce a located structure to everyone else on the server.";
			allowSharing = builder.comment(desc).define("allowSharing", true);

			desc = "The minimum time in milliseconds between locations shared by the same player, so that sharing cannot be used to flood chat. Set to 0 to disable.";
			shareCooldownMillis = builder.comment(desc).defineInRange("shareCooldownMillis", 3000, 0, 60000);

			builder.pop();
		}
	}

	public static class Client {
		public final ForgeConfigSpec.BooleanValue displayWithChatOpen;
		public final ForgeConfigSpec.BooleanValue translateStructureNames;
		public final ForgeConfigSpec.BooleanValue createXaeroWaypoints;
		public final ForgeConfigSpec.IntValue xaeroWaypointColor;
		public final ForgeConfigSpec.EnumValue<OverlaySide> overlaySide;
		public final ForgeConfigSpec.IntValue overlayLineOffset;
		public final ForgeConfigSpec.BooleanValue showDirectionBar;
		public final ForgeConfigSpec.IntValue directionBarY;
		public final ForgeConfigSpec.IntValue directionBarWidth;
		public final ForgeConfigSpec.IntValue directionBarSpan;

		Client(ForgeConfigSpec.Builder builder) {
			String desc;
			builder.push("Client");

			desc = "Displays Explorer's Compass information on the HUD even while chat is open.";
			displayWithChatOpen = builder.comment(desc).define("displayWithChatOpen", true);

			desc = "Attempts to translate structure names before fixing the unlocalized names. Translations may not be available for all structures.";
			translateStructureNames = builder.comment(desc).define("translateStructureNames", true);

			desc = "Creates a waypoint in Xaero's Minimap for each located structure. Has no effect when that mod is not installed.";
			createXaeroWaypoints = builder.comment(desc).define("createXaeroWaypoints", true);

			desc = "The color of the waypoints created in Xaero's Minimap, as an index into its own color list.";
			xaeroWaypointColor = builder.comment(desc).defineInRange("xaeroWaypointColor", 0, 0, 15);

			desc = "The line offset for information rendered on the HUD.";
			overlayLineOffset = builder.comment(desc).defineInRange("overlayLineOffset", 1, 0, 50);

			desc = "The side for information rendered on the HUD. Ex: LEFT, RIGHT";
			overlaySide = builder.comment(desc).defineEnum("overlaySide", OverlaySide.LEFT);

			desc = "Displays a compass strip at the top of the screen marking the direction of the located structure.";
			showDirectionBar = builder.comment(desc).define("showDirectionBar", true);

			desc = "How far down from the top of the screen the direction strip is drawn.";
			directionBarY = builder.comment(desc).defineInRange("directionBarY", 4, 0, 200);

			desc = "How wide the direction strip is, in pixels of the scaled interface.";
			directionBarWidth = builder.comment(desc).defineInRange("directionBarWidth", 240, 120, 480);

			desc = "How much of the horizon the direction strip covers, in degrees. Lower values spread the marks further apart and make small changes in direction easier to see, higher values keep more of the horizon in view at once.";
			directionBarSpan = builder.comment(desc).defineInRange("directionBarSpan", 120, 60, 360);

			builder.pop();
		}
	}

}
