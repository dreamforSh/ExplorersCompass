package com.chaosthedude.explorerscompass.registry;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;

import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@EventBusSubscriber(modid = ExplorersCompass.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ExplorersCompassRegistry {

	public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ExplorersCompass.MODID);

	public static final DeferredItem<ExplorersCompassItem> EXPLORERS_COMPASS = ITEMS.register(ExplorersCompassItem.NAME, () -> new ExplorersCompassItem());

	private ExplorersCompassRegistry() {
	}

	/**
	 * Puts the compass in a creative tab. An item no longer names the tab it belongs to; the tabs are
	 * filled as they are built instead, which is why this is here rather than on the item.
	 */
	@SubscribeEvent
	public static void buildCreativeTabContents(BuildCreativeModeTabContentsEvent e) {
		if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
			e.accept(EXPLORERS_COMPASS.get());
		}
	}

}
