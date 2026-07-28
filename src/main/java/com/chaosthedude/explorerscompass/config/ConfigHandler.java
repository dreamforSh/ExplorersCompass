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
		public final ForgeConfigSpec.ConfigValue<List<String>> biomeBlacklist;
		public final ForgeConfigSpec.IntValue maxSamples;
		public final ForgeConfigSpec.IntValue maxBiomeSamples;
		public final ForgeConfigSpec.IntValue biomeSampleSpacing;
		public final ForgeConfigSpec.IntValue biomeVerticalSampleSpacing;
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

			desc = "A list of biomes that the compass will not display in the GUI and will not be able to search for. Wildcards work the same way they do for the structure blacklist. Ex: [\"minecraft:deep_dark\", \"minecraft:*ocean*\"]";
			biomeBlacklist = builder.comment(desc).define("biomeBlacklist", new ArrayList<String>());

			desc = "The maximum number of samples to be taken when searching for a structure.";
			maxSamples = builder.comment(desc).defineInRange("maxSamples", 100000, 0, 100000000);

			desc = "The maximum number of samples to be taken when searching for a biome. A biome can lie anywhere, where a structure can only lie where its placement allows one, so a biome search takes far more samples than a structure search does. Each of them is much cheaper, since which biome generates somewhere follows from the world seed alone and needs no part of the world to have been generated.";
			maxBiomeSamples = builder.comment(desc).defineInRange("maxBiomeSamples", 2000000, 0, 100000000);

			desc = "How far apart in blocks the locations sampled by a biome search are. Lower values make the search less likely to step over a small biome entirely, higher values let it cover the same area in fewer samples.";
			biomeSampleSpacing = builder.comment(desc).defineInRange("biomeSampleSpacing", 32, 4, 512);

			desc = "How far apart in blocks the heights sampled by a biome search are. Sampling the whole height of a dimension is what makes the biomes that fill the caves findable from the surface, and costs a search as many samples per location as there are heights to sample. Set to 0 to sample only the height the search was started at, which searches several times faster but only finds the biomes reaching it.";
			biomeVerticalSampleSpacing = builder.comment(desc).defineInRange("biomeVerticalSampleSpacing", 64, 0, 512);

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
		public final ForgeConfigSpec.BooleanValue translateBiomeNames;
		public final ForgeConfigSpec.BooleanValue createXaeroWaypoints;
		public final ForgeConfigSpec.IntValue xaeroWaypointColor;
		public final ForgeConfigSpec.EnumValue<OverlaySide> overlaySide;
		public final ForgeConfigSpec.IntValue overlayLineOffset;
		public final ForgeConfigSpec.BooleanValue overlayBackground;
		public final ForgeConfigSpec.BooleanValue guiHeaderBackground;
		public final ForgeConfigSpec.BooleanValue guiSidebarBackground;
		public final ForgeConfigSpec.BooleanValue guiStatusBarBackground;
		public final ForgeConfigSpec.BooleanValue showDirectionBar;
		public final ForgeConfigSpec.IntValue directionBarY;
		public final ForgeConfigSpec.IntValue directionBarWidth;
		public final ForgeConfigSpec.IntValue directionBarSpan;
		public final ForgeConfigSpec.BooleanValue directionBarBackground;

		Client(ForgeConfigSpec.Builder builder) {
			String desc;
			builder.push("Client");

			desc = "Displays Explorer's Compass information on the HUD even while chat is open.";
			displayWithChatOpen = builder.comment(desc).define("displayWithChatOpen", true);

			desc = "Attempts to translate structure names before fixing the unlocalized names. Translations may not be available for all structures.";
			translateStructureNames = builder.comment(desc).define("translateStructureNames", true);

			desc = "Attempts to translate biome names before fixing the unlocalized names. Unlike structures, almost every biome is named by the game itself or by the mod that adds it, so there is rarely anything left to fix up.";
			translateBiomeNames = builder.comment(desc).define("translateBiomeNames", true);

			desc = "Creates a waypoint in Xaero's Minimap for each located structure. Has no effect when that mod is not installed.";
			createXaeroWaypoints = builder.comment(desc).define("createXaeroWaypoints", true);

			desc = "The color of the waypoints created in Xaero's Minimap, as an index into its own color list.";
			xaeroWaypointColor = builder.comment(desc).defineInRange("xaeroWaypointColor", 0, 0, 15);

			desc = "The line offset for information rendered on the HUD.";
			overlayLineOffset = builder.comment(desc).defineInRange("overlayLineOffset", 1, 0, 50);

			desc = "The side for information rendered on the HUD. Ex: LEFT, RIGHT";
			overlaySide = builder.comment(desc).defineEnum("overlaySide", OverlaySide.LEFT);

			desc = "Draws the compass information on the HUD on a panel of its own, which keeps it legible against a bright sky or a snowy landscape. Turn this off for plain text.";
			overlayBackground = builder.comment(desc).define("overlayBackground", true);

			// Each panel of the compass screens is filled in or left see through on its own, so that a
			// player who only wants the world visible behind part of the screen can have exactly that
			desc = "Fills in the strip along the top of the compass screens, which holds the title, the filter field and the filters in force. Turn this off to have it outlined but see through.";
			guiHeaderBackground = builder.comment(desc).define("guiHeaderBackground", true);

			desc = "Fills in the column of controls beside the list on the compass screens. Turn this off to have it outlined but see through.";
			guiSidebarBackground = builder.comment(desc).define("guiSidebarBackground", true);

			desc = "Fills in the bar along the bottom of the structure screen, which reports what the compass is doing. Turn this off to have it outlined but see through.";
			guiStatusBarBackground = builder.comment(desc).define("guiStatusBarBackground", true);

			desc = "Displays a compass strip at the top of the screen marking the direction of the located structure.";
			showDirectionBar = builder.comment(desc).define("showDirectionBar", true);

			desc = "How far down from the top of the screen the direction strip is drawn.";
			directionBarY = builder.comment(desc).defineInRange("directionBarY", 4, 0, 200);

			desc = "How wide the direction strip is, in pixels of the scaled interface. A strip wider than the screen is cut down to it, so any large value here makes the strip span the whole width of whatever screen it is drawn on.";
			directionBarWidth = builder.comment(desc).defineInRange("directionBarWidth", 240, 60, 4096);

			desc = "How much of the horizon the direction strip covers, in degrees. Lower values spread the marks further apart and make small changes in direction easier to see, higher values keep more of the horizon in view at once. Pair a full width strip with a large span to have the whole horizon on screen at once.";
			directionBarSpan = builder.comment(desc).defineInRange("directionBarSpan", 120, 60, 360);

			desc = "Draws the direction strip on a panel of its own. Turn this off to leave only the marks, the compass points and the readout, with nothing behind them: they carry their own shadows, and fade out towards the ends of the strip on their own.";
			directionBarBackground = builder.comment(desc).define("directionBarBackground", true);

			builder.pop();
		}
	}

}
