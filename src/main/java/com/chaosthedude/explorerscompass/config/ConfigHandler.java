package com.chaosthedude.explorerscompass.config;

import java.util.ArrayList;
import java.util.List;

import com.chaosthedude.explorerscompass.client.OverlaySide;
import com.chaosthedude.explorerscompass.client.TooltipDetail;

import net.minecraftforge.common.ForgeConfigSpec;

public class ConfigHandler {

	private static final ForgeConfigSpec.Builder GENERAL_BUILDER = new ForgeConfigSpec.Builder();
	private static final ForgeConfigSpec.Builder CLIENT_BUILDER = new ForgeConfigSpec.Builder();

	public static final General GENERAL = new General(GENERAL_BUILDER);
	public static final Client CLIENT = new Client(CLIENT_BUILDER);

	public static final ForgeConfigSpec GENERAL_SPEC = GENERAL_BUILDER.build();
	public static final ForgeConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();

	private ConfigHandler() {
	}

	public static class General {
		public final ForgeConfigSpec.BooleanValue allowTeleport;
		public final ForgeConfigSpec.IntValue teleportCooldownMillis;
		public final ForgeConfigSpec.IntValue maxNextSearches;
		public final ForgeConfigSpec.BooleanValue displayCoordinates;
		public final ForgeConfigSpec.IntValue maxRadius;
		public final ForgeConfigSpec.ConfigValue<List<String>> structureBlacklist;
		public final ForgeConfigSpec.ConfigValue<List<String>> biomeBlacklist;
		public final ForgeConfigSpec.BooleanValue hideStructuresThatCannotGenerate;
		public final ForgeConfigSpec.IntValue maxSamples;
		public final ForgeConfigSpec.IntValue maxBiomeSamples;
		public final ForgeConfigSpec.IntValue biomeSampleSpacing;
		public final ForgeConfigSpec.IntValue biomeVerticalSampleSpacing;
		public final ForgeConfigSpec.IntValue biomeDepthSampleInterval;
		public final ForgeConfigSpec.BooleanValue asyncBiomeSearch;
		public final ForgeConfigSpec.BooleanValue asyncStructureSearch;
		public final ForgeConfigSpec.IntValue maxSearchTimePerTick;
		public final ForgeConfigSpec.IntValue searchRequestCooldownMillis;
		public final ForgeConfigSpec.IntValue maxBookmarks;
		public final ForgeConfigSpec.BooleanValue allowSharing;
		public final ForgeConfigSpec.IntValue shareCooldownMillis;
		public final ForgeConfigSpec.BooleanValue allowStructurePreview;
		public final ForgeConfigSpec.IntValue structurePreviewResolution;
		public final ForgeConfigSpec.IntValue structurePreviewMaxBlocks;

		General(ForgeConfigSpec.Builder builder) {
			String desc;
			builder.push("General");

			desc = "Allows a player to teleport to a located structure when in creative mode, opped, or in cheat mode.";
			allowTeleport = builder.comment(desc).define("allowTeleport", true);

			desc = "The minimum time in milliseconds between teleports requested by the same player. Teleporting loads, and usually generates, the destination chunk, so this keeps a modified client from queueing up that work as fast as it can send packets. Set to 0 to disable.";
			teleportCooldownMillis = builder.comment(desc).defineInRange("teleportCooldownMillis", 2000, 0, 60000);

			desc = "The maximum number of times a player can search for the next instance of a located structure, skipping the locations already found. The collected locations are forgotten and the search starts over from the closest instance again once this many have been collected, and likewise once every instance within the search radius has been collected. Set to 0 to disable searching for further instances and make the compass always locate the nearest one.";
			maxNextSearches = builder.comment(desc).defineInRange("maxNextSearches", 100, 0, 10000);
			
			desc = "Allows players to view the precise coordinates and distance of a located structure on the HUD, rather than relying on the direction the compass is pointing.";
			displayCoordinates = builder.comment(desc).define("displayCoordinates", true);

			desc = "The maximum radius that will be searched for a structure. Raising this value will increase search accuracy but will potentially make the process more resource intensive.";
			maxRadius = builder.comment(desc).defineInRange("maxRadius", 10000, 0, 1000000);

			desc = "A list of structures that the compass will not display in the GUI and will not be able to search for. Wildcard character * can be used to match any number of characters, and ? can be used to match one character. Ex: [\"minecraft:stronghold\", \"minecraft:endcity\", \"minecraft:*village*\"]";
			structureBlacklist = builder.comment(desc).define("structureBlacklist", new ArrayList<String>());

			desc = "A list of biomes that the compass will not display in the GUI and will not be able to search for. Wildcards work the same way they do for the structure blacklist. Ex: [\"minecraft:deep_dark\", \"minecraft:*ocean*\"]";
			biomeBlacklist = builder.comment(desc).define("biomeBlacklist", new ArrayList<String>());

			desc = "Leaves the structures this world cannot generate out of the compass, rather than offering them and having every search for one report that nothing was found. A structure is only ever placed by a structure set that names it and whose biomes this world actually has, so what this drops is the structures no dimension of this world could place: the ones whose biome tag a data pack has emptied out, the ones a data pack has taken out of every structure set, the ones belonging to no set in the first place, and every structure at once in a superflat world configured without any or in a world generating no structures at all. This is the same rule the compass already searches by, so what it leaves out is exactly what a search could never find. Turn it off to be offered every structure this world's registries hold, whether it can generate or not.";
			hideStructuresThatCannotGenerate = builder.comment(desc).define("hideStructuresThatCannotGenerate", true);

			desc = "The maximum number of samples to be taken when searching for a structure.";
			maxSamples = builder.comment(desc).defineInRange("maxSamples", 100000, 0, 100000000);

			desc = "The maximum number of samples to be taken when searching for a biome. A biome can lie anywhere, where a structure can only lie where its placement allows one, so a biome search takes far more samples than a structure search does. Each of them is much cheaper, since which biome generates somewhere follows from the world seed alone and needs no part of the world to have been generated.";
			maxBiomeSamples = builder.comment(desc).defineInRange("maxBiomeSamples", 2000000, 0, 100000000);

			desc = "How far apart in blocks the locations sampled by a biome search are. Lower values make the search less likely to step over a small biome entirely, higher values let it cover the same area in fewer samples.";
			biomeSampleSpacing = builder.comment(desc).defineInRange("biomeSampleSpacing", 32, 4, 512);

			desc = "How far apart in blocks the heights sampled by a biome search are. Sampling the whole height of a dimension is what makes the biomes that fill the caves findable from the surface. Set to 0 to sample only the height the search was started at, which searches several times faster but only finds the biomes reaching it.";
			biomeVerticalSampleSpacing = builder.comment(desc).defineInRange("biomeVerticalSampleSpacing", 64, 0, 512);

			desc = "How many locations apart, along each axis, a biome search looks at the heights other than the one it was started at. The height a search starts at is sampled everywhere, since that is where the biome being looked for usually is; the rest of the dimension is sampled on a coarser grid, since a biome that fills the caves covers far more ground than the spacing between two locations. Raising this searches faster but can step over a small cave biome, and 1 samples every height at every location.";
			biomeDepthSampleInterval = builder.comment(desc).defineInRange("biomeDepthSampleInterval", 4, 1, 64);

			desc = "Runs biome searches on threads of their own rather than in slices of the server tick, which finishes them several times sooner and costs the server nothing while they run. A search is shared out over as many threads as searching is allowed, each taking its own share of the ground. This is safe because which biome generates somewhere follows from the world seed and the generator's noise alone, which the game itself samples from its own worldgen threads. Turn it off if a mod that adds a biome source of its own turns out not to be safe to sample from another thread.";
			asyncBiomeSearch = builder.comment(desc).define("asyncBiomeSearch", true);

			desc = "Runs structure searches off the server thread, shared out over as many threads as searching is allowed, by working out where a structure would generate the same way world generation decides it instead of asking chunk storage about every location the search looks at. Almost all of what a structure search costs is that question, and asking world generation instead reads no part of the world, so the search leaves the server thread free while it runs and finishes far sooner. The location it settles on is still put to chunk storage before the compass is pointed at it. What this can differ over is ground that was generated under settings that have since changed, such as an old world or a data pack that has been edited: there the compass answers with where a structure would generate now. Turn it off to have every location answered by chunk storage on the server thread, as it was before, and also if a mod that adds structures of its own turns out not to be safe to generate from another thread.";
			asyncStructureSearch = builder.comment(desc).define("asyncStructureSearch", true);

			desc = "The maximum amount of time in milliseconds that searches may spend on the server thread during a single tick. Sampling a location can be expensive, so this caps how much of each tick searching is allowed to consume. This is shared by every search running on the server, which take turns within it, so raising it does not let one player's search crowd out another's. Lower values keep the game responsive while a search is running, higher values complete searches sooner.";
			maxSearchTimePerTick = builder.comment(desc).defineInRange("maxSearchTimePerTick", 10, 1, 50);

			desc = "The minimum time in milliseconds between searches started by the same player. Search requests arriving faster than this are ignored. This guards against modified clients restarting expensive searches as fast as they can send packets. Set to 0 to disable.";
			searchRequestCooldownMillis = builder.comment(desc).defineInRange("searchRequestCooldownMillis", 500, 0, 10000);

			desc = "How many located structures a compass remembers, so that they can be pointed at again later. The oldest is dropped once this many have been collected. Set to 0 to disable remembering them.";
			maxBookmarks = builder.comment(desc).defineInRange("maxBookmarks", 64, 0, 1024);

			desc = "Allows players to announce a located structure to everyone else on the server.";
			allowSharing = builder.comment(desc).define("allowSharing", true);

			desc = "The minimum time in milliseconds between locations shared by the same player, so that sharing cannot be used to flood chat. Set to 0 to disable.";
			shareCooldownMillis = builder.comment(desc).defineInRange("shareCooldownMillis", 3000, 0, 60000);

			desc = "Allows players to see what a structure looks like before searching for one. The server assembles the structure the way world generation would, without placing any of it anywhere, off the server thread, and sends back a small model of it. The most recently viewed structures are kept assembled, so looking at one again costs nothing while it stays in use. Turn it off to have the compass answer that there is nothing to show.";
			allowStructurePreview = builder.comment(desc).define("allowStructurePreview", true);

			desc = "How many cells across a structure preview may be. The default is large enough that no structure is shrunk to fit it, so a preview is one cell to one block and shows the structure at its own size; lower it to cap how large a preview may be however large the structure is. What actually decides whether a preview is shown one to one is the cell budget below.";
			structurePreviewResolution = builder.comment(desc).defineInRange("structurePreviewResolution", 1024, 8, 1024);

			desc = "How many cells of a structure preview may be sent. Only the cells that can be seen from outside the structure are ever sent, and a structure whose surface does not fit inside this is shrunk until it does, at which point it is no longer one cell to one block. The default clears every structure the game itself adds by several times over: the largest of them, a bastion, comes to under 60000 solid blocks even counting every piece it could possibly be assembled from, and only part of that is ever visible from outside. A structure that does not fit is named in the log. Previews are sent in pieces and each is built once and then kept, so this is what one costs the first time it is opened rather than what it costs to show.";
			structurePreviewMaxBlocks = builder.comment(desc).defineInRange("structurePreviewMaxBlocks", 200000, 512, 400000);

			builder.pop();
		}
	}

	public static class Client {
		public final ForgeConfigSpec.BooleanValue displayWithChatOpen;
		public final ForgeConfigSpec.EnumValue<TooltipDetail> tooltipDetail;
		public final ForgeConfigSpec.BooleanValue translateStructureNames;
		public final ForgeConfigSpec.BooleanValue translateBiomeNames;
		public final ForgeConfigSpec.BooleanValue createXaeroWaypoints;
		public final ForgeConfigSpec.IntValue xaeroWaypointColor;
		public final ForgeConfigSpec.BooleanValue showOverlayWhileCarried;
		public final ForgeConfigSpec.EnumValue<OverlaySide> overlaySide;
		public final ForgeConfigSpec.IntValue overlayLineOffset;
		public final ForgeConfigSpec.BooleanValue overlayBackground;
		public final ForgeConfigSpec.BooleanValue guiHeaderBackground;
		public final ForgeConfigSpec.BooleanValue guiSidebarBackground;
		public final ForgeConfigSpec.BooleanValue guiStatusBarBackground;
		public final ForgeConfigSpec.BooleanValue showDirectionBar;
		public final ForgeConfigSpec.BooleanValue showDirectionBarWhileCarried;
		public final ForgeConfigSpec.IntValue directionBarY;
		public final ForgeConfigSpec.IntValue directionBarWidth;
		public final ForgeConfigSpec.IntValue directionBarSpan;
		public final ForgeConfigSpec.BooleanValue directionBarBackground;
		public final ForgeConfigSpec.BooleanValue structurePreviewAutoSpin;
		public final ForgeConfigSpec.IntValue structurePreviewDetailLimit;

		Client(ForgeConfigSpec.Builder builder) {
			String desc;
			builder.push("Client");

			desc = "Displays Explorer's Compass information on the HUD even while chat is open.";
			displayWithChatOpen = builder.comment(desc).define("displayWithChatOpen", true);

			desc = "How much the compass says about itself in its own tooltip. COMPACT keeps it to what the compass is doing and what follows from that, with the rest a held shift key away; FULL shows all of it at once; NONE leaves the tooltip empty. Whether the coordinates are among what it may show is the server's to decide, not this. Ex: NONE, COMPACT, FULL";
			tooltipDetail = builder.comment(desc).defineEnum("tooltipDetail", TooltipDetail.COMPACT);

			desc = "Attempts to translate structure names before fixing the unlocalized names. Translations may not be available for all structures.";
			translateStructureNames = builder.comment(desc).define("translateStructureNames", true);

			desc = "Attempts to translate biome names before fixing the unlocalized names. Unlike structures, almost every biome is named by the game itself or by the mod that adds it, so there is rarely anything left to fix up.";
			translateBiomeNames = builder.comment(desc).define("translateBiomeNames", true);

			desc = "Creates a waypoint in Xaero's Minimap for each located structure. Has no effect when that mod is not installed.";
			createXaeroWaypoints = builder.comment(desc).define("createXaeroWaypoints", true);

			// The first entry in that list is black, which a waypoint marker is not readable in, so the
			// gold this mod picks things out in elsewhere is used instead
			desc = "The color of the waypoints created in Xaero's Minimap, as an index into its own color list.";
			xaeroWaypointColor = builder.comment(desc).defineInRange("xaeroWaypointColor", 6, 0, 15);

			desc = "Keeps the panel of compass information on the HUD while the compass is only carried, rather than only while one is held. A carried compass reports what it is doing exactly as a held one does: how far the search it is running has got, where the place it points at lies, or that its last search came back empty. Where several are carried, the one pointing at a place located in this dimension is the one the panel speaks for.";
			showOverlayWhileCarried = builder.comment(desc).define("showOverlayWhileCarried", false);

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

			desc = "Keeps the direction strip on the HUD while the compass is only carried, rather than only while one is held, so long as the carried one is pointing at a place located in this dimension. Turn this off to have the strip appear only while the compass is in hand. What the panel of compass information does while one is only carried is showOverlayWhileCarried's to say.";
			showDirectionBarWhileCarried = builder.comment(desc).define("showDirectionBarWhileCarried", true);

			desc = "How far down from the top of the screen the direction strip is drawn.";
			directionBarY = builder.comment(desc).defineInRange("directionBarY", 4, 0, 200);

			desc = "How wide the direction strip is, in pixels of the scaled interface. A strip wider than the screen is cut down to it, so any large value here makes the strip span the whole width of whatever screen it is drawn on.";
			directionBarWidth = builder.comment(desc).defineInRange("directionBarWidth", 240, 60, 4096);

			desc = "How much of the horizon the direction strip covers, in degrees. Lower values spread the marks further apart and make small changes in direction easier to see, higher values keep more of the horizon in view at once. Pair a full width strip with a large span to have the whole horizon on screen at once.";
			directionBarSpan = builder.comment(desc).defineInRange("directionBarSpan", 120, 60, 360);

			desc = "Draws the direction strip on a panel of its own. Turn this off to leave only the marks, the compass points and the readout, with nothing behind them: they carry their own shadows, and fade out towards the ends of the strip on their own.";
			directionBarBackground = builder.comment(desc).define("directionBarBackground", true);

			desc = "Turns a structure preview slowly on its own, so that it is seen from more than one side without being dragged around. The button on the preview screen switches this on and off as well.";
			structurePreviewAutoSpin = builder.comment(desc).define("structurePreviewAutoSpin", true);

			desc = "How many cells a structure preview may hold before it is drawn as coloured blocks instead of real ones. Both are one cell to one block; what the coloured tier gives up is the textures, not the detail. Real blocks take far longer to assemble into a model, so a large structure is shown as its shape and its colours rather than after a long wait. Raise this to see real blocks on larger structures, at the cost of that wait when a preview opens; set it to 0 to always show colours.";
			structurePreviewDetailLimit = builder.comment(desc).defineInRange("structurePreviewDetailLimit", 20000, 0, 400000);

			builder.pop();
		}
	}

}
