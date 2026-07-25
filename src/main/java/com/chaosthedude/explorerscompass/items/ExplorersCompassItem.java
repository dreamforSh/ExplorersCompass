package com.chaosthedude.explorerscompass.items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.config.CustomModelDataConfig;
import com.chaosthedude.explorerscompass.gui.GuiWrapper;
import com.chaosthedude.explorerscompass.network.SyncPacket;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import com.chaosthedude.explorerscompass.util.PlayerUtils;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.chaosthedude.explorerscompass.worker.SearchWorkerManager;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraftforge.network.NetworkDirection;

public class ExplorersCompassItem extends Item {

	public static final String NAME = "explorerscompass";

	/** Marks a structure height the search could not determine. */
	public static final int UNKNOWN_Y = Integer.MIN_VALUE;

	// One worker manager per player, so that one player starting, finishing, or cancelling a search
	// cannot stop another's: this item is a singleton, and a single manager here would be shared by
	// every player on the server. Only ever touched on the server thread.
	private final Map<UUID, SearchWorkerManager> workerManagers = new HashMap<UUID, SearchWorkerManager>();

	// When each player last started a search, for rate limiting. Only ever touched on the server thread.
	private final Map<UUID, Long> lastSearchStartTimes = new HashMap<UUID, Long>();

	public ExplorersCompassItem() {
		super(new Properties().stacksTo(1).tab(CreativeModeTab.TAB_TOOLS));
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		if (!player.isCrouching()) {
			if (level.isClientSide()) {
				final ItemStack stack = ItemUtils.getHeldItem(player, ExplorersCompass.explorersCompass);
				GuiWrapper.openGUI(level, player, stack);
			} else {
				final ServerLevel serverLevel = (ServerLevel) level;
				final ServerPlayer serverPlayer = (ServerPlayer) player;
				final boolean canTeleport = ConfigHandler.GENERAL.allowTeleport.get() && PlayerUtils.canTeleport(player.getServer(), player);
				for (SyncPacket packet : SyncPacket.createForPlayer(serverPlayer, canTeleport, serverLevel)) {
					ExplorersCompass.network.sendTo(packet, serverPlayer.connection.getConnection(), NetworkDirection.PLAY_TO_CLIENT);
				}
			}
		} else {
			// Only the server runs searches; the client-side use of this item has no workers
			if (!level.isClientSide()) {
				final SearchWorkerManager workerManager = getWorkerManager(player);
				workerManager.stop();
				workerManager.clear();
			}
			setState(player.getItemInHand(hand), null, CompassState.INACTIVE, player);
			clearPrevPos(player.getItemInHand(hand));
		}
		return new InteractionResultHolder<ItemStack>(InteractionResult.PASS, player.getItemInHand(hand));
	}
	
	@Override
 	public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
 		if (getState(oldStack) == getState(newStack)) {
 			return false;
 		}
 		return super.shouldCauseReequipAnimation(oldStack, newStack, slotChanged);
 	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		final CompassState state = getState(stack);
		if (state == null || state == CompassState.INACTIVE) {
			return;
		}

		// The basic name works on both sides; the translated one is only available on the client
		final String structureName = StructureUtils.getBasicStructureName(getStructureKey(stack));
		if (state == CompassState.SEARCHING) {
			// While a group is being searched, the stored key is the group's, which may have a
			// configured display name; a multi-structure search shows how many more it considers
			String targetName = getIsGroup(stack) ? ExplorersCompass.groupNames.getOrDefault(getStructureKey(stack), structureName) : structureName;
			final int targetCount = getTargetCount(stack);
			if (targetCount > 1) {
				targetName += " (+" + (targetCount - 1) + ")";
			}
			tooltip.add(Component.translatable("string.explorerscompass.searching").append(Component.literal(": " + targetName)).withStyle(ChatFormatting.GRAY));
		} else if (state == CompassState.FOUND) {
			tooltip.add(Component.translatable("string.explorerscompass.found").append(Component.literal(": " + structureName)).withStyle(ChatFormatting.GRAY));
			if (shouldDisplayCoordinates(stack)) {
				final int foundY = getFoundStructureY(stack);
				final String coordinates = foundY != UNKNOWN_Y ? getFoundStructureX(stack) + ", " + foundY + ", " + getFoundStructureZ(stack) : getFoundStructureX(stack) + ", " + getFoundStructureZ(stack);
				tooltip.add(Component.translatable("string.explorerscompass.coordinates").append(Component.literal(": " + coordinates)).withStyle(ChatFormatting.DARK_GRAY));
			}
			// The location being pointed at is part of the cached list, but is not a previous one
			final int previousLocations = getPrevPos(stack).size() - 1;
			if (previousLocations > 0) {
				tooltip.add(Component.translatable("string.explorerscompass.previousLocations").append(Component.literal(": " + previousLocations)).withStyle(ChatFormatting.DARK_GRAY));
			}
		} else if (state == CompassState.NOT_FOUND) {
			tooltip.add(Component.translatable("string.explorerscompass.notFound").append(Component.literal(": " + structureName)).withStyle(ChatFormatting.GRAY));
		}
	}

	/**
	 * Starts a fresh search for the nearest of the given structures, forgetting the locations any
	 * earlier search had collected.
	 */
	public void searchForStructures(Level level, Player player, List<ResourceLocation> structureKeys, BlockPos pos, ItemStack stack) {
		if (!(level instanceof ServerLevel) || structureKeys.isEmpty() || !tryAcquireSearchSlot(player)) {
			return;
		}

		setIsGroup(stack, false);
		clearPrevPos(stack);
		search((ServerLevel) level, player, structureKeys, structureKeys.get(0), false, pos, stack, new ArrayList<BlockPos>(), false);
	}

	/**
	 * Starts a fresh search for the nearest member of the given group, forgetting the locations any
	 * earlier search had collected.
	 */
	public void searchForGroup(Level level, Player player, ResourceLocation groupKey, BlockPos pos, ItemStack stack) {
		if (!(level instanceof ServerLevel) || !tryAcquireSearchSlot(player)) {
			return;
		}

		setIsGroup(stack, true);
		clearPrevPos(stack);
		search((ServerLevel) level, player, StructureUtils.getStructureKeysForTypeKey((ServerLevel) level, groupKey), groupKey, true, pos, stack, new ArrayList<BlockPos>(), false);
	}

	/**
	 * Searches for another instance of the structure the compass has already located, skipping the
	 * ones it has collected so far. Once the configured number of instances has been collected the
	 * list is dropped and the search starts over from the closest one again.
	 */
	public void searchForNextStructure(Level level, Player player, BlockPos pos, ItemStack stack) {
		if (!(level instanceof ServerLevel) || ConfigHandler.GENERAL.maxNextSearches.get() <= 0 || !tryAcquireSearchSlot(player)) {
			return;
		}

		ServerLevel serverLevel = (ServerLevel) level;
		ResourceLocation structureKey = getStructureKey(stack);
		if (structureKey == null || getState(stack) != CompassState.FOUND) {
			return;
		}

		List<BlockPos> prevPos = getPrevPos(stack);
		if (prevPos.size() >= ConfigHandler.GENERAL.maxNextSearches.get()) {
			prevPos.clear();
		}

		// The compass stores the key of the structure that was found, so a group search has to be
		// widened back out to the group it belongs to
		if (getIsGroup(stack)) {
			ResourceLocation groupKey = StructureUtils.getStructureKeysToTypeKeys(serverLevel).get(structureKey);
			if (groupKey != null) {
				search(serverLevel, player, StructureUtils.getStructureKeysForTypeKey(serverLevel, groupKey), groupKey, true, pos, stack, prevPos, true);
				return;
			}
			setIsGroup(stack, false);
		}

		// A multi-structure selection is remembered, so that searching for the next instance keeps
		// considering the whole selection rather than only the structure that happened to be found
		List<ResourceLocation> targetKeys = getTargetKeys(stack);
		if (targetKeys.isEmpty()) {
			targetKeys = List.of(structureKey);
		}
		search(serverLevel, player, targetKeys, targetKeys.get(0), false, pos, stack, prevPos, true);
	}

	private void search(ServerLevel level, Player player, List<ResourceLocation> structureKeys, ResourceLocation displayKey, boolean isGroup, BlockPos pos, ItemStack stack, List<BlockPos> prevPos, boolean ignoreNearStart) {
		// The keys arrive over the network, so they may be stale (the client keeps the structure list
		// from the last world it synced with), duplicated, or simply made up. Resolve them against this
		// world and drop anything that does not belong, rather than handing nulls to world generation.
		List<Structure> structures = new ArrayList<Structure>();
		List<ResourceLocation> validKeys = new ArrayList<ResourceLocation>();
		Set<ResourceLocation> seenKeys = new HashSet<ResourceLocation>();
		for (ResourceLocation key : structureKeys) {
			if (key == null || !seenKeys.add(key)) {
				continue;
			}

			Structure structure = StructureUtils.getStructureForKey(level, key);
			if (structure == null) {
				ExplorersCompass.LOGGER.warn("Ignoring search for " + key + ": no such structure in this world");
			} else if (StructureUtils.structureIsBlacklisted(level, structure)) {
				ExplorersCompass.LOGGER.warn("Ignoring search for " + key + ": structure is blacklisted");
			} else {
				structures.add(structure);
				validKeys.add(key);
			}
		}

		setSearching(stack, displayKey, player);
		setTargetCount(stack, isGroup ? 1 : structures.size());
		setTargetKeys(stack, isGroup ? List.<ResourceLocation>of() : validKeys);
		setSearchRadius(stack, 0, player);

		final SearchWorkerManager workerManager = getWorkerManager(player);
		workerManager.stop();
		if (structures.isEmpty()) {
			setNotFound(stack, 0, 0);
			return;
		}

		workerManager.createWorkers(level, player, stack, structures, pos, prevPos, isGroup, ignoreNearStart);
		boolean started = workerManager.start();
		if (!started) {
			setNotFound(stack, 0, 0);
		}
	}

	/** The worker manager running the given player's searches. */
	private SearchWorkerManager getWorkerManager(Player player) {
		return workerManagers.computeIfAbsent(player.getUUID(), (uuid) -> new SearchWorkerManager());
	}

	/**
	 * Whether the given player may start a search now, and records the attempt when they may.
	 * Search packets cost a client nothing to send, so without this a modified client could restart
	 * expensive searches as fast as it can spam them.
	 */
	private boolean tryAcquireSearchSlot(Player player) {
		final int cooldown = ConfigHandler.GENERAL.searchRequestCooldownMillis.get();
		if (cooldown <= 0) {
			return true;
		}

		final long now = System.currentTimeMillis();
		final Long lastStart = lastSearchStartTimes.get(player.getUUID());
		if (lastStart != null && now - lastStart < cooldown) {
			return false;
		}
		lastSearchStartTimes.put(player.getUUID(), now);
		return true;
	}

	public void succeed(Player player, ItemStack stack, ResourceLocation structureKey, boolean isGroup, int x, int z, int y, ResourceLocation dimensionKey, List<BlockPos> prevPos, int samples, boolean displayCoordinates) {
		setFound(stack, structureKey, x, z, y, dimensionKey, samples);
		setIsGroup(stack, isGroup);
		setPrevPos(stack, prevPos);
		setDisplayCoordinates(stack, displayCoordinates);
		getWorkerManager(player).clear();

		final String structureName = StructureUtils.getBasicStructureName(structureKey);
		final String coordinates = y != UNKNOWN_Y ? x + ", " + y + ", " + z : x + ", " + z;
		notifySearchResult(player, Component.translatable("string.explorerscompass.found").append(Component.literal(displayCoordinates ? ": " + structureName + " (" + coordinates + ")" : ": " + structureName)));
	}

	public void fail(Player player, ItemStack stack, int radius, int samples) {
		final SearchWorkerManager workerManager = getWorkerManager(player);
		workerManager.pop();
		boolean started = workerManager.start();
		if (!started) {
			setNotFound(stack, radius, samples);
			notifySearchResult(player, Component.translatable("string.explorerscompass.notFound").append(Component.literal(": " + StructureUtils.getBasicStructureName(getStructureKey(stack)))));
		}
	}

	/**
	 * A one-off message about the outcome of a search, so that a search finishing is noticed even
	 * when the compass has been put away and the HUD is not showing.
	 */
	private void notifySearchResult(Player player, Component message) {
		if (player instanceof ServerPlayer && !((ServerPlayer) player).hasDisconnected()) {
			player.displayClientMessage(message, true);
		}
	}

	public boolean isActive(ItemStack stack) {
		if (ItemUtils.verifyNBT(stack)) {
			return getState(stack) != CompassState.INACTIVE;
		}

		return false;
	}

	public void setSearching(ItemStack stack, ResourceLocation structureKey, Player player) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putString("StructureKey", structureKey.toString());
			stack.getTag().putInt("State", CompassState.SEARCHING.getID());
			CustomModelDataConfig.apply(stack, structureKey);
		}
	}

	public void setFound(ItemStack stack, ResourceLocation structureKey, int x, int z, int y, ResourceLocation dimensionKey, int samples) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putInt("State", CompassState.FOUND.getID());
			stack.getTag().putString("StructureKey", structureKey.toString());
			stack.getTag().putInt("FoundX", x);
			stack.getTag().putInt("FoundZ", z);
			if (y != UNKNOWN_Y) {
				stack.getTag().putInt("FoundY", y);
			} else {
				stack.getTag().remove("FoundY");
			}
			if (dimensionKey != null) {
				stack.getTag().putString("FoundDimension", dimensionKey.toString());
			}
			stack.getTag().putInt("Samples", samples);
			CustomModelDataConfig.apply(stack, structureKey);
		}
	}

	public void setNotFound(ItemStack stack, int searchRadius, int samples) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putInt("State", CompassState.NOT_FOUND.getID());
			stack.getTag().putInt("SearchRadius", searchRadius);
			stack.getTag().putInt("Samples", samples);
		}
	}

	public void setInactive(ItemStack stack, Player player) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putInt("State", CompassState.INACTIVE.getID());
		}
	}

	public void setState(ItemStack stack, BlockPos pos, CompassState state, Player player) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putInt("State", state.getID());
			if (state == CompassState.INACTIVE) {
				CustomModelDataConfig.remove(stack);
			}
		}
	}

	public void setIsGroup(ItemStack stack, boolean isGroup) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putBoolean("IsGroup", isGroup);
		}
	}

	public boolean getIsGroup(ItemStack stack) {
		return ItemUtils.verifyNBT(stack) && stack.getTag().getBoolean("IsGroup");
	}

	/** How many structures the current search is looking for at once. */
	public int getTargetCount(ItemStack stack) {
		if (ItemUtils.verifyNBT(stack) && stack.getTag().contains("TargetCount")) {
			return Math.max(1, stack.getTag().getInt("TargetCount"));
		}

		return 1;
	}

	private void setTargetCount(ItemStack stack, int targetCount) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putInt("TargetCount", targetCount);
		}
	}

	/**
	 * The structures a multi-structure search was asked for, so that searching for a further
	 * instance keeps considering the whole selection. Empty for single and group searches.
	 */
	public List<ResourceLocation> getTargetKeys(ItemStack stack) {
		final List<ResourceLocation> targetKeys = new ArrayList<ResourceLocation>();
		if (ItemUtils.verifyNBT(stack) && stack.getTag().contains("TargetKeys", Tag.TAG_LIST)) {
			for (Tag tag : stack.getTag().getList("TargetKeys", Tag.TAG_STRING)) {
				ResourceLocation key = ResourceLocation.tryParse(tag.getAsString());
				if (key != null) {
					targetKeys.add(key);
				}
			}
		}
		return targetKeys;
	}

	private void setTargetKeys(ItemStack stack, List<ResourceLocation> targetKeys) {
		if (ItemUtils.verifyNBT(stack)) {
			if (targetKeys.size() <= 1) {
				stack.getTag().remove("TargetKeys");
				return;
			}

			final ListTag listTag = new ListTag();
			for (ResourceLocation key : targetKeys) {
				listTag.add(StringTag.valueOf(key.toString()));
			}
			stack.getTag().put("TargetKeys", listTag);
		}
	}

	/** The locations this compass has already located, which further searches pass over. */
	public List<BlockPos> getPrevPos(ItemStack stack) {
		final List<BlockPos> prevPos = new ArrayList<BlockPos>();
		if (ItemUtils.verifyNBT(stack) && stack.getTag().contains("PrevPos", Tag.TAG_LIST)) {
			for (Tag tag : stack.getTag().getList("PrevPos", Tag.TAG_COMPOUND)) {
				CompoundTag posTag = (CompoundTag) tag;
				prevPos.add(new BlockPos(posTag.getInt("X"), posTag.getInt("Y"), posTag.getInt("Z")));
			}
		}
		return prevPos;
	}

	public void setPrevPos(ItemStack stack, List<BlockPos> prevPos) {
		if (ItemUtils.verifyNBT(stack)) {
			final ListTag listTag = new ListTag();
			for (BlockPos pos : prevPos) {
				CompoundTag posTag = new CompoundTag();
				posTag.putInt("X", pos.getX());
				posTag.putInt("Y", pos.getY());
				posTag.putInt("Z", pos.getZ());
				listTag.add(posTag);
			}
			stack.getTag().put("PrevPos", listTag);
		}
	}

	public void clearPrevPos(ItemStack stack) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().remove("PrevPos");
		}
	}

	public void setFoundStructureX(ItemStack stack, int x, Player player) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putInt("FoundX", x);
		}
	}

	public void setFoundStructureZ(ItemStack stack, int z, Player player) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putInt("FoundZ", z);
		}
	}

	public void setStructureKey(ItemStack stack, ResourceLocation structureKey, Player player) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putString("StructureKey", structureKey.toString());
		}
	}

	public void setSearchRadius(ItemStack stack, int searchRadius, Player player) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putInt("SearchRadius", searchRadius);
		}
	}

	public void setSamples(ItemStack stack, int samples, Player player) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putInt("Samples", samples);
		}
	}
	
	public void setDisplayCoordinates(ItemStack stack, boolean displayPosition) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putBoolean("DisplayCoordinates", displayPosition);
		}
	}

	public CompassState getState(ItemStack stack) {
		if (ItemUtils.verifyNBT(stack)) {
			return CompassState.fromID(stack.getTag().getInt("State"));
		}

		return null;
	}

	public int getFoundStructureX(ItemStack stack) {
		if (ItemUtils.verifyNBT(stack)) {
			return stack.getTag().getInt("FoundX");
		}

		return 0;
	}

	public int getFoundStructureZ(ItemStack stack) {
		if (ItemUtils.verifyNBT(stack)) {
			return stack.getTag().getInt("FoundZ");
		}

		return 0;
	}

	/** The height of the located structure, or {@link #UNKNOWN_Y} when the search could not tell it. */
	public int getFoundStructureY(ItemStack stack) {
		if (ItemUtils.verifyNBT(stack) && stack.getTag().contains("FoundY")) {
			return stack.getTag().getInt("FoundY");
		}

		return UNKNOWN_Y;
	}

	/** The dimension the located structure is in, or null when an older compass never recorded it. */
	public ResourceLocation getFoundDimension(ItemStack stack) {
		if (ItemUtils.verifyNBT(stack) && stack.getTag().contains("FoundDimension", Tag.TAG_STRING)) {
			return ResourceLocation.tryParse(stack.getTag().getString("FoundDimension"));
		}

		return null;
	}

	public ResourceLocation getStructureKey(ItemStack stack) {
		if (ItemUtils.verifyNBT(stack)) {
			return new ResourceLocation(stack.getTag().getString("StructureKey"));
		}

		return new ResourceLocation("");
	}

	public int getSearchRadius(ItemStack stack) {
		if (ItemUtils.verifyNBT(stack)) {
			return stack.getTag().getInt("SearchRadius");
		}

		return -1;
	}

	public int getSamples(ItemStack stack) {
		if (ItemUtils.verifyNBT(stack)) {
			return stack.getTag().getInt("Samples");
		}

		return -1;
	}

	public boolean shouldDisplayCoordinates(ItemStack stack) {
		if (ItemUtils.verifyNBT(stack) && stack.getTag().contains("DisplayCoordinates")) {
			return stack.getTag().getBoolean("DisplayCoordinates");
		}

		return true;
	}

}
