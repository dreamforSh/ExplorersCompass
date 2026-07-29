package com.chaosthedude.explorerscompass.client;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.util.CompassState;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ClampedItemPropertyFunction;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Everything about the mod that only exists on a client.
 *
 * <p>This used to sit in the main mod class behind {@code DistExecutor}, which is gone. A mod class
 * of its own marked for the client is what replaces it: on a dedicated server this class is never
 * constructed, so nothing it touches is ever loaded there.
 */
@Mod(value = ExplorersCompass.MODID, dist = Dist.CLIENT)
public class ExplorersCompassClient {

	public ExplorersCompassClient(IEventBus modEventBus, ModContainer modContainer) {
		modEventBus.addListener(this::clientSetup);
		modEventBus.addListener(this::registerClientReloadListeners);
	}

	private void clientSetup(FMLClientSetupEvent event) {
		NeoForge.EVENT_BUS.register(new ClientEventHandler());

		event.enqueueWork(() -> {
			ItemProperties.register(ExplorersCompass.explorersCompass, ResourceLocation.withDefaultNamespace("angle"), new ClampedItemPropertyFunction() {
				private double rotation;
				private double rota;
				private long lastUpdateTick;

				@Override
				public float unclampedCall(ItemStack stack, ClientLevel world, LivingEntity entityLiving, int seed) {
					if (entityLiving == null && !stack.isFramed()) {
						return 0.0F;
					} else {
						final boolean entityExists = entityLiving != null;
						final Entity entity = (Entity) (entityExists ? entityLiving : stack.getFrame());
						if (world == null && entity.level() instanceof ClientLevel) {
							world = (ClientLevel) entity.level();
						}

						double rotation = entityExists ? (double) entity.getYRot() : getFrameRotation((ItemFrame) entity);
						rotation = rotation % 360.0D;
						double adjusted = Math.PI - ((rotation - 90.0D) * 0.01745329238474369D - getAngle(world, entity, stack));

						if (entityExists) {
							adjusted = wobble(world, adjusted);
						}

						final float f = (float) (adjusted / (Math.PI * 2D));
						return Mth.positiveModulo(f, 1.0F);
					}
				}

				private double wobble(ClientLevel world, double amount) {
					if (world.getGameTime() != lastUpdateTick) {
						lastUpdateTick = world.getGameTime();
						double d0 = amount - rotation;
						d0 = d0 % (Math.PI * 2D);
						d0 = Mth.clamp(d0, -1.0D, 1.0D);
						rota += d0 * 0.1D;
						rota *= 0.8D;
						rotation += rota;
					}

					return rotation;
				}

				private double getFrameRotation(ItemFrame itemFrame) {
					Direction direction = itemFrame.getDirection();
					int i = direction.getAxis().isVertical() ? 90 * direction.getAxisDirection().getStep() : 0;
					return (double) Mth.wrapDegrees(180 + direction.get2DDataValue() * 90 + itemFrame.getRotation() * 45 + i);
				}

				private double getAngle(ClientLevel world, Entity entity, ItemStack stack) {
					if (stack.getItem() == ExplorersCompass.explorersCompass) {
						ExplorersCompassItem compassItem = (ExplorersCompassItem) stack.getItem();
						BlockPos pos;
						if (compassItem.getState(stack) == CompassState.FOUND) {
							pos = new BlockPos(compassItem.getFoundStructureX(stack), 0, compassItem.getFoundStructureZ(stack));
						} else {
							pos = world.getSharedSpawnPos();
						}
						return Math.atan2((double) pos.getZ() - entity.position().z(), (double) pos.getX() - entity.position().x());
					}
					return 0.0D;
				}
			});
		});
	}

	private void registerClientReloadListeners(RegisterClientReloadListenersEvent event) {
		event.registerReloadListener((ResourceManagerReloadListener) (resourceManager) -> {
			ExplorersCompass.clientSearchDataRevision++;
		});
	}

}
