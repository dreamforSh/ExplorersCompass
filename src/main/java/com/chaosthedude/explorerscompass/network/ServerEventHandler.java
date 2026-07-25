package com.chaosthedude.explorerscompass.network;

import com.chaosthedude.explorerscompass.ExplorersCompass;

import net.minecraftforge.event.entity.player.PlayerEvent;
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

}
