package com.chaosthedude.explorerscompass.util;

import java.util.List;
import java.util.function.UnaryOperator;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.registry.ModDataComponents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Reading and writing what the compass carries on the stack.
 *
 * <p>Everything used to go through {@code verifyNBT(stack)}, which made sure the stack had a
 * {@code CompoundTag} and then let the caller poke at it key by key. A stack no longer carries
 * arbitrary NBT, so the tag is gone and this is the one place that knows which components stand in
 * for it.
 *
 * <p>A compass written before 1.21 is read across onto those components on first use. Every accessor
 * here accounts for one, including the writers: writing without first reading would otherwise leave
 * the old keys in place for a later read to resurrect, which is exactly what clearing the remembered
 * locations does.
 */
public class ItemUtils {

	private ItemUtils() {
	}

	public static boolean isCompass(ItemStack stack) {
		return !stack.isEmpty() && stack.getItem() == ExplorersCompass.explorersCompass;
	}

	/** What the compass is doing, or {@link CompassData#EMPTY} for anything that is not one. */
	public static CompassData getData(ItemStack stack) {
		if (!isCompass(stack)) {
			return CompassData.EMPTY;
		}
		final CompoundTag legacy = legacyTag(stack);
		if (legacy != null && !migrate(stack, legacy)) {
			return LegacyCompassData.readData(legacy);
		}
		return stack.getOrDefault(ModDataComponents.COMPASS_DATA.get(), CompassData.EMPTY);
	}

	public static void setData(ItemStack stack, CompassData data) {
		if (!isCompass(stack)) {
			return;
		}
		ensureMigrated(stack);
		stack.set(ModDataComponents.COMPASS_DATA.get(), data);
	}

	/** Reads what the compass is doing, changes part of it, and writes the result back. */
	public static void updateData(ItemStack stack, UnaryOperator<CompassData> update) {
		if (isCompass(stack)) {
			setData(stack, update.apply(getData(stack)));
		}
	}

	/** The keys of a multi-target search, empty for single and group searches. */
	public static List<ResourceLocation> getTargetKeys(ItemStack stack) {
		if (!isCompass(stack)) {
			return List.of();
		}
		final CompoundTag legacy = legacyTag(stack);
		if (legacy != null && !migrate(stack, legacy)) {
			return List.copyOf(LegacyCompassData.readTargetKeys(legacy));
		}
		return stack.getOrDefault(ModDataComponents.TARGET_KEYS.get(), List.of());
	}

	public static void setTargetKeys(ItemStack stack, List<ResourceLocation> targetKeys) {
		ensureMigrated(stack);
		setList(stack, ModDataComponents.TARGET_KEYS.get(), targetKeys, ModDataComponents.MAX_STREAMED_TARGET_KEYS);
	}

	/** The locations this compass has already located, which further searches pass over. */
	public static List<BlockPos> getPrevPos(ItemStack stack) {
		if (!isCompass(stack)) {
			return List.of();
		}
		final CompoundTag legacy = legacyTag(stack);
		if (legacy != null && !migrate(stack, legacy)) {
			return List.copyOf(LegacyCompassData.readPrevPos(legacy));
		}
		return stack.getOrDefault(ModDataComponents.PREV_POSITIONS.get(), List.of());
	}

	public static void setPrevPos(ItemStack stack, List<BlockPos> prevPos) {
		ensureMigrated(stack);
		setList(stack, ModDataComponents.PREV_POSITIONS.get(), prevPos, ModDataComponents.MAX_STREAMED_POSITIONS);
	}

	/** The locations this compass has collected, oldest first. */
	public static List<BookmarkEntry> getBookmarks(ItemStack stack) {
		if (!isCompass(stack)) {
			return List.of();
		}
		final CompoundTag legacy = legacyTag(stack);
		if (legacy != null && !migrate(stack, legacy)) {
			return List.copyOf(LegacyCompassData.readBookmarks(legacy));
		}
		return stack.getOrDefault(ModDataComponents.BOOKMARKS.get(), List.of());
	}

	public static void setBookmarks(ItemStack stack, List<BookmarkEntry> bookmarks) {
		ensureMigrated(stack);
		setList(stack, ModDataComponents.BOOKMARKS.get(), bookmarks, ModDataComponents.MAX_STREAMED_BOOKMARKS);
	}

	/**
	 * An empty list is removed rather than stored, so that a compass carrying nothing compares equal
	 * to a fresh one and does not keep an empty component around on every stack.
	 *
	 * <p>The limit is the one the component can be synced within. Exceeding it does not merely drop
	 * the excess on the way out, it makes the whole stack fail to encode, so the newest entries that
	 * fit are kept rather than letting the list grow past what can be sent.
	 */
	private static <T> void setList(ItemStack stack, DataComponentType<List<T>> type, List<T> values, int limit) {
		if (!isCompass(stack)) {
			return;
		}
		if (values.isEmpty()) {
			stack.remove(type);
			return;
		}
		if (values.size() > limit) {
			ExplorersCompass.LOGGER.warn("Keeping only the newest " + limit + " of " + values.size() + " entries for " + type + "; a compass cannot carry more than that across the network");
			values = values.subList(values.size() - limit, values.size());
		}
		stack.set(type, List.copyOf(values));
	}

	/**
	 * The tag a compass written before 1.21 left behind, or null for one that has already been read
	 * across or never had any. The overwhelmingly common case is the latter, which is why this is a
	 * component presence check before anything is copied.
	 */
	private static CompoundTag legacyTag(ItemStack stack) {
		if (stack.has(ModDataComponents.COMPASS_DATA.get()) || !stack.has(DataComponents.CUSTOM_DATA)) {
			return null;
		}
		final CompoundTag tag = stack.get(DataComponents.CUSTOM_DATA).copyTag();
		return LegacyCompassData.isLegacy(tag) ? tag : null;
	}

	private static void ensureMigrated(ItemStack stack) {
		if (!isCompass(stack)) {
			return;
		}
		final CompoundTag legacy = legacyTag(stack);
		if (legacy != null) {
			migrate(stack, legacy);
		}
	}

	/**
	 * Moves a compass written before 1.21 over to the components that replaced its tag, once.
	 *
	 * @return whether the stack was changed. It is left alone anywhere it is not ours to change: the
	 *         client is handed a copy of the server's stack, so writing to that one would only be
	 *         undone by the next sync while making a getter mutate whatever it was asked about,
	 *         render thread included. The caller reads the tag directly in that case.
	 */
	private static boolean migrate(ItemStack stack, CompoundTag legacy) {
		if (!isOnServerThread()) {
			return false;
		}

		// Written before the lists so that the accessors below see a migrated stack and do not come
		// back through here
		stack.set(ModDataComponents.COMPASS_DATA.get(), LegacyCompassData.readData(legacy));
		setList(stack, ModDataComponents.TARGET_KEYS.get(), LegacyCompassData.readTargetKeys(legacy), ModDataComponents.MAX_STREAMED_TARGET_KEYS);
		setList(stack, ModDataComponents.PREV_POSITIONS.get(), LegacyCompassData.readPrevPos(legacy), ModDataComponents.MAX_STREAMED_POSITIONS);
		setList(stack, ModDataComponents.BOOKMARKS.get(), LegacyCompassData.readBookmarks(legacy), ModDataComponents.MAX_STREAMED_BOOKMARKS);

		LegacyCompassData.removeKeys(legacy);
		if (legacy.isEmpty()) {
			stack.remove(DataComponents.CUSTOM_DATA);
		} else {
			stack.set(DataComponents.CUSTOM_DATA, CustomData.of(legacy));
		}

		ExplorersCompass.LOGGER.debug("Migrated a compass written before 1.21 onto data components");
		return true;
	}

	private static boolean isOnServerThread() {
		final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
		return server != null && server.isSameThread();
	}

	public static ItemStack getHeldItem(Player player, Item item) {
		if (!player.getMainHandItem().isEmpty() && player.getMainHandItem().getItem() == item) {
			return player.getMainHandItem();
		} else if (!player.getOffhandItem().isEmpty() && player.getOffhandItem().getItem() == item) {
			return player.getOffhandItem();
		}

		return ItemStack.EMPTY;
	}

}
