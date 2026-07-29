package com.chaosthedude.explorerscompass.network;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.util.BiomeUtils;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.chaosthedude.explorerscompass.worker.SearchExecutor;
import com.chaosthedude.explorerscompass.worker.SearchScheduler;

import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

@EventBusSubscriber(modid = ExplorersCompass.MODID)
public class ServerEventHandler {

	@SubscribeEvent
	public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		// The record of what was synced to this client is per connection: after a relog the client
		// may have been on a different server in the meantime, so it has to be synced from scratch
		SyncPacket.forgetPlayer(event.getEntity().getUUID());
		if (ExplorersCompass.explorersCompass != null) {
			// Stops any search this player still had running, which nothing would otherwise end until
			// it had sampled its way to the configured limits
			ExplorersCompass.explorersCompass.forgetPlayer(event.getEntity().getUUID());
		}
	}

	@SubscribeEvent
	public static void onServerStopping(ServerStoppingEvent event) {
		// A biome search runs on a thread of its own, which would otherwise go on sampling a world
		// that is no longer there. Stopping the searches first is what lets those threads notice.
		if (ExplorersCompass.explorersCompass != null) {
			ExplorersCompass.explorersCompass.forgetAllPlayers();
		}
		SearchExecutor.shutdown();
		// Forge empties its own list of workers here without asking the scheduler, which has to know
		// so that it registers again for the next world
		SearchScheduler.shutdown();
		// Everything else derived from this server: its structure and biome data, and the record of
		// what was synced to each client
		StructureUtils.invalidateCache();
		BiomeUtils.invalidateCache();
		SyncPacket.forgetAllPlayers();
	}

}
