package com.chaosthedude.explorerscompass.registry;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;

@EventBusSubscriber(modid = ExplorersCompass.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ExplorersCompassRegistry {

	private ExplorersCompassRegistry() {
	}

	@SubscribeEvent
	public static void registerItems(RegisterEvent e) {
		e.register(ForgeRegistries.Keys.ITEMS, helper -> {
			ExplorersCompass.explorersCompass = new ExplorersCompassItem();
            helper.register(new ResourceLocation(ExplorersCompass.MODID, ExplorersCompassItem.NAME), ExplorersCompass.explorersCompass);
        });
	}

	/**
	 * Puts the compass in a creative tab. An item no longer names the tab it belongs to; the tabs are
	 * filled as they are built instead, which is why this is here rather than on the item.
	 */
	@SubscribeEvent
	public static void buildCreativeTabContents(BuildCreativeModeTabContentsEvent e) {
		if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
			e.accept(ExplorersCompass.explorersCompass);
		}
	}

}
