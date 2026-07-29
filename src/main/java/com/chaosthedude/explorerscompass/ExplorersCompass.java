package com.chaosthedude.explorerscompass;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.config.CustomModelDataConfig;
import com.chaosthedude.explorerscompass.config.StructureGroupsConfig;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.network.BookmarkActionPacket;
import com.chaosthedude.explorerscompass.network.CancelSearchPacket;
import com.chaosthedude.explorerscompass.network.ClearCachePacket;
import com.chaosthedude.explorerscompass.network.CompassSearchForNextPacket;
import com.chaosthedude.explorerscompass.network.CompassSearchPacket;
import com.chaosthedude.explorerscompass.network.ShareLocationPacket;
import com.chaosthedude.explorerscompass.network.SyncPacket;
import com.chaosthedude.explorerscompass.network.TeleportPacket;
import com.chaosthedude.explorerscompass.registry.ExplorersCompassRegistry;
import com.chaosthedude.explorerscompass.registry.ModDataComponents;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(ExplorersCompass.MODID)
public class ExplorersCompass {

	public static final String MODID = "explorerscompass";

	/**
	 * Bump this whenever the packets change. Both sides have to declare the same version: a mismatch
	 * is then refused during the handshake with a clear message, instead of connecting and failing
	 * to decode later.
	 */
	public static final String PROTOCOL_VERSION = "2.3";

	public static final Logger LOGGER = LogManager.getLogger(MODID);

	public static ExplorersCompassItem explorersCompass;

	public static boolean canTeleport;
	public static List<ResourceLocation> allowedStructureKeys;
	public static ListMultimap<ResourceLocation, ResourceLocation> dimensionKeysForAllowedStructureKeys;
	public static Map<ResourceLocation, ResourceLocation> structureKeysToTypeKeys;
	public static Map<ResourceLocation, String> groupNames;
	public static List<ResourceLocation> allowedBiomeKeys;
	public static ListMultimap<ResourceLocation, ResourceLocation> dimensionKeysForAllowedBiomeKeys;
	public static Map<ResourceLocation, ResourceLocation> biomeKeysToGroupKeys;
	/** Changes whenever a complete set of searchable client data is published. */
	public static volatile int clientSearchDataRevision;

	/**
	 * The bus and the container are handed to the mod now rather than being asked for, which is what
	 * the two removed context lookups used to do.
	 */
	public ExplorersCompass(IEventBus modEventBus, ModContainer modContainer) {
		ExplorersCompassRegistry.ITEMS.register(modEventBus);
		ModDataComponents.COMPONENTS.register(modEventBus);

		modEventBus.addListener(this::commonSetup);
		modEventBus.addListener(this::registerPayloads);

		modContainer.registerConfig(ModConfig.Type.COMMON, ConfigHandler.GENERAL_SPEC);
		modContainer.registerConfig(ModConfig.Type.CLIENT, ConfigHandler.CLIENT_SPEC);
	}

	private void commonSetup(FMLCommonSetupEvent event) {
		// The registries have all been filled by the time setup runs, so the item is safe to resolve
		// here. Keeping it in a field of its own spares every user of it from going through the
		// deferred holder.
		explorersCompass = ExplorersCompassRegistry.EXPLORERS_COMPASS.get();

		CustomModelDataConfig.load();
		StructureGroupsConfig.load();

		allowedStructureKeys = new ArrayList<ResourceLocation>();
		dimensionKeysForAllowedStructureKeys = ArrayListMultimap.create();
		structureKeysToTypeKeys = new HashMap<ResourceLocation, ResourceLocation>();
		groupNames = new HashMap<ResourceLocation, String>();
		allowedBiomeKeys = new ArrayList<ResourceLocation>();
		dimensionKeysForAllowedBiomeKeys = ArrayListMultimap.create();
		biomeKeysToGroupKeys = new HashMap<ResourceLocation, ResourceLocation>();
		clientSearchDataRevision = 0;
	}

	/**
	 * A channel is no longer built by hand: each packet declares its own type and codec, and is
	 * registered here in the direction it travels.
	 *
	 * <p>The registrar is marked optional, which is what the old channel's "accept a matching version
	 * or the absence of the channel altogether" meant: joining a server without this mod still works,
	 * rather than being refused during the handshake.
	 */
	private void registerPayloads(RegisterPayloadHandlersEvent event) {
		final PayloadRegistrar registrar = event.registrar(MODID).versioned(PROTOCOL_VERSION).optional();

		// Server packets
		registrar.playToServer(CompassSearchPacket.TYPE, CompassSearchPacket.STREAM_CODEC, CompassSearchPacket::handle);
		registrar.playToServer(TeleportPacket.TYPE, TeleportPacket.STREAM_CODEC, TeleportPacket::handle);
		registrar.playToServer(CompassSearchForNextPacket.TYPE, CompassSearchForNextPacket.STREAM_CODEC, CompassSearchForNextPacket::handle);
		registrar.playToServer(ClearCachePacket.TYPE, ClearCachePacket.STREAM_CODEC, ClearCachePacket::handle);
		registrar.playToServer(BookmarkActionPacket.TYPE, BookmarkActionPacket.STREAM_CODEC, BookmarkActionPacket::handle);
		registrar.playToServer(ShareLocationPacket.TYPE, ShareLocationPacket.STREAM_CODEC, ShareLocationPacket::handle);
		registrar.playToServer(CancelSearchPacket.TYPE, CancelSearchPacket.STREAM_CODEC, CancelSearchPacket::handle);

		// Client packet
		registrar.playToClient(SyncPacket.TYPE, SyncPacket.STREAM_CODEC, SyncPacket::handle);
	}

}
