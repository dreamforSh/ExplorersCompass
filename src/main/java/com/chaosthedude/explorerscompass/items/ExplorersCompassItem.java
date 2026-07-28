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
import com.chaosthedude.explorerscompass.network.ShareLocationPacket;
import com.chaosthedude.explorerscompass.network.SyncPacket;
import com.chaosthedude.explorerscompass.util.BiomeUtils;
import com.chaosthedude.explorerscompass.util.BookmarkEntry;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import com.chaosthedude.explorerscompass.util.PlayerUtils;
import com.chaosthedude.explorerscompass.util.SearchTarget;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.chaosthedude.explorerscompass.worker.SearchWorkerManager;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
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
import net.minecraft.world.level.biome.Biome;
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

	// When each player last shared a location, so that sharing cannot be used to flood chat. Only
	// ever touched on the server thread.
	private final Map<UUID, Long> lastShareTimes = new HashMap<UUID, Long>();

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
			cancelSearch(level, player, player.getItemInHand(hand));
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
		final SearchTarget searchTarget = getSearchTarget(stack);
		final String name = searchTarget.getBasicName(getTargetKey(stack));
		if (state == CompassState.SEARCHING) {
			// While a group is being searched, the stored key is the group's, which may have a
			// configured display name; a search for several at once shows how many more it considers
			String targetName = getIsGroup(stack) ? ExplorersCompass.groupNames.getOrDefault(getTargetKey(stack), searchTarget.getBasicGroupName(getTargetKey(stack))) : name;
			final int targetCount = getTargetCount(stack);
			if (targetCount > 1) {
				targetName += " (+" + (targetCount - 1) + ")";
			}
			tooltip.add(Component.translatable("string.explorerscompass.searching").append(Component.literal(": " + targetName)).withStyle(ChatFormatting.GRAY));
		} else if (state == CompassState.FOUND) {
			tooltip.add(Component.translatable("string.explorerscompass.found").append(Component.literal(": " + name)).withStyle(ChatFormatting.GRAY));
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
			tooltip.add(Component.translatable("string.explorerscompass.notFound").append(Component.literal(": " + name)).withStyle(ChatFormatting.GRAY));
		}
	}

	/**
	 * Starts a fresh search for the nearest of the given structures or biomes, forgetting the
	 * locations any earlier search had collected.
	 */
	public void searchForTargets(Level level, Player player, SearchTarget searchTarget, List<ResourceLocation> keys, BlockPos pos, ItemStack stack) {
		if (!(level instanceof ServerLevel) || keys.isEmpty() || !tryAcquireSearchSlot(player)) {
			return;
		}

		setIsGroup(stack, false);
		clearPrevPos(stack);
		search((ServerLevel) level, player, searchTarget, keys, keys.get(0), false, pos, stack, new ArrayList<BlockPos>(), false);
	}

	/**
	 * Starts a fresh search for the nearest member of the given group, forgetting the locations any
	 * earlier search had collected.
	 */
	public void searchForGroup(Level level, Player player, SearchTarget searchTarget, ResourceLocation groupKey, BlockPos pos, ItemStack stack) {
		if (!(level instanceof ServerLevel) || !tryAcquireSearchSlot(player)) {
			return;
		}

		setIsGroup(stack, true);
		clearPrevPos(stack);
		search((ServerLevel) level, player, searchTarget, getKeysForGroup((ServerLevel) level, searchTarget, groupKey), groupKey, true, pos, stack, new ArrayList<BlockPos>(), false);
	}

	/**
	 * Searches for another instance of whatever the compass has already located, skipping the ones
	 * it has collected so far. Once the configured number of instances has been collected the list
	 * is dropped and the search starts over from the closest one again.
	 */
	public void searchForNext(Level level, Player player, BlockPos pos, ItemStack stack) {
		if (!(level instanceof ServerLevel) || ConfigHandler.GENERAL.maxNextSearches.get() <= 0 || !tryAcquireSearchSlot(player)) {
			return;
		}

		ServerLevel serverLevel = (ServerLevel) level;
		SearchTarget searchTarget = getSearchTarget(stack);
		ResourceLocation targetKey = getTargetKey(stack);
		if (targetKey == null || getState(stack) != CompassState.FOUND) {
			return;
		}

		List<BlockPos> prevPos = getPrevPos(stack);
		if (prevPos.size() >= ConfigHandler.GENERAL.maxNextSearches.get()) {
			prevPos.clear();
		}

		// The compass stores the key of what was found, so a group search has to be widened back out
		// to the group it belongs to
		if (getIsGroup(stack)) {
			ResourceLocation groupKey = getGroupKey(serverLevel, searchTarget, targetKey);
			if (groupKey != null) {
				search(serverLevel, player, searchTarget, getKeysForGroup(serverLevel, searchTarget, groupKey), groupKey, true, pos, stack, prevPos, true);
				return;
			}
			setIsGroup(stack, false);
		}

		// A selection of several is remembered, so that searching for the next instance keeps
		// considering the whole selection rather than only the one that happened to be found
		List<ResourceLocation> targetKeys = getTargetKeys(stack);
		if (targetKeys.isEmpty()) {
			targetKeys = List.of(targetKey);
		}
		search(serverLevel, player, searchTarget, targetKeys, targetKeys.get(0), false, pos, stack, prevPos, true);
	}

	/**
	 * Stops whatever the compass is doing and takes the structure it was aimed at back off it,
	 * forgetting the locations it had collected as well.
	 *
	 * <p>Both sides run this on their own copy of the stack: the server is where the search actually
	 * stops, and the client doing the same to the copy it holds is what clears the HUD at the moment
	 * it is asked to, rather than once the server's copy of the stack has made it back.
	 */
	public void cancelSearch(Level level, Player player, ItemStack stack) {
		// Only the server runs searches; the client-side use of this item has no workers
		if (!level.isClientSide()) {
			final SearchWorkerManager workerManager = getWorkerManager(player);
			workerManager.stop();
			workerManager.clear();
		}
		setState(stack, null, CompassState.INACTIVE, player);
		clearPrevPos(stack);
	}

	private void search(ServerLevel level, Player player, SearchTarget searchTarget, List<ResourceLocation> keys, ResourceLocation displayKey, boolean isGroup, BlockPos pos, ItemStack stack, List<BlockPos> prevPos, boolean ignoreNearStart) {
		// The keys arrive over the network, so they may be stale (the client keeps the list from the
		// last world it synced with), duplicated, or simply made up. Resolve them against this world
		// and drop anything that does not belong, rather than handing nulls to world generation.
		List<Structure> structures = new ArrayList<Structure>();
		List<Holder<Biome>> biomes = new ArrayList<Holder<Biome>>();
		List<ResourceLocation> validKeys = new ArrayList<ResourceLocation>();
		Set<ResourceLocation> seenKeys = new HashSet<ResourceLocation>();
		for (ResourceLocation key : keys) {
			if (key == null || !seenKeys.add(key)) {
				continue;
			}

			if (searchTarget == SearchTarget.BIOME) {
				Holder<Biome> biome = BiomeUtils.getHolderForKey(level, key);
				if (biome == null) {
					ExplorersCompass.LOGGER.warn("Ignoring search for " + key + ": no such biome in this world");
				} else if (BiomeUtils.biomeIsBlacklisted(key)) {
					ExplorersCompass.LOGGER.warn("Ignoring search for " + key + ": biome is blacklisted");
				} else {
					biomes.add(biome);
					validKeys.add(key);
				}
			} else {
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
		}

		setSearching(stack, searchTarget, displayKey, player);
		setTargetCount(stack, isGroup ? 1 : validKeys.size());
		setTargetKeys(stack, isGroup ? List.<ResourceLocation>of() : validKeys);
		setSearchRadius(stack, 0, player);

		final SearchWorkerManager workerManager = getWorkerManager(player);
		workerManager.stop();
		if (validKeys.isEmpty()) {
			setNotFound(stack, 0, 0);
			return;
		}

		if (searchTarget == SearchTarget.BIOME) {
			workerManager.createBiomeWorker(level, player, stack, biomes, pos, prevPos, isGroup, ignoreNearStart);
		} else {
			workerManager.createStructureWorkers(level, player, stack, structures, pos, prevPos, isGroup, ignoreNearStart);
		}
		boolean started = workerManager.start();
		if (!started) {
			setNotFound(stack, 0, 0);
		}
	}

	/** The keys of everything belonging to the given group of the given kind. */
	private static List<ResourceLocation> getKeysForGroup(ServerLevel level, SearchTarget searchTarget, ResourceLocation groupKey) {
		return searchTarget == SearchTarget.BIOME ? BiomeUtils.getBiomeKeysForGroupKey(level, groupKey) : StructureUtils.getStructureKeysForTypeKey(level, groupKey);
	}

	/** The group the given key belongs to, or null when this world holds no such key. */
	private static ResourceLocation getGroupKey(ServerLevel level, SearchTarget searchTarget, ResourceLocation key) {
		return searchTarget == SearchTarget.BIOME ? BiomeUtils.getBiomeKeysToGroupKeys(level).get(key) : StructureUtils.getStructureKeysToTypeKeys(level).get(key);
	}

	/** The worker manager running the given player's searches. */
	private SearchWorkerManager getWorkerManager(Player player) {
		return workerManagers.computeIfAbsent(player.getUUID(), (uuid) -> new SearchWorkerManager());
	}

	/**
	 * Drops everything remembered about a player who has left the server, so that none of it is kept
	 * for the rest of the server's life.
	 */
	public void forgetPlayer(UUID playerId) {
		final SearchWorkerManager workerManager = workerManagers.remove(playerId);
		if (workerManager != null) {
			// Stop before dropping the manager: its workers are registered with the world worker
			// manager, and would otherwise keep sampling for a player who is no longer here, with
			// nothing left that could stop them
			workerManager.stop();
			workerManager.clear();
		}
		lastSearchStartTimes.remove(playerId);
		lastShareTimes.remove(playerId);
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

	public void succeed(Player player, ItemStack stack, ResourceLocation targetKey, boolean isGroup, int x, int z, int y, ResourceLocation dimensionKey, List<BlockPos> prevPos, int samples, boolean displayCoordinates) {
		final SearchTarget searchTarget = getSearchTarget(stack);
		setFound(stack, targetKey, x, z, y, dimensionKey, samples);
		setIsGroup(stack, isGroup);
		setPrevPos(stack, prevPos);
		setDisplayCoordinates(stack, displayCoordinates);
		addBookmark(stack, new BookmarkEntry(searchTarget, targetKey, x, y, z, dimensionKey));
		getWorkerManager(player).clear();

		final String name = searchTarget.getBasicName(targetKey);
		final String coordinates = y != UNKNOWN_Y ? x + ", " + y + ", " + z : x + ", " + z;
		notifySearchResult(player, Component.translatable("string.explorerscompass.found").append(Component.literal(displayCoordinates ? ": " + name + " (" + coordinates + ")" : ": " + name)));
	}

	public void fail(Player player, ItemStack stack, int radius, int samples) {
		final SearchWorkerManager workerManager = getWorkerManager(player);
		workerManager.pop();
		boolean started = workerManager.start();
		if (!started) {
			setNotFound(stack, radius, samples);
			notifySearchResult(player, Component.translatable("string.explorerscompass.notFound").append(Component.literal(": " + getSearchTarget(stack).getBasicName(getTargetKey(stack)))));
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

	public void setSearching(ItemStack stack, SearchTarget searchTarget, ResourceLocation targetKey, Player player) {
		if (ItemUtils.verifyNBT(stack)) {
			setSearchTarget(stack, searchTarget);
			stack.getTag().putString("StructureKey", targetKey.toString());
			stack.getTag().putInt("State", CompassState.SEARCHING.getID());
			CustomModelDataConfig.apply(stack, targetKey);
		}
	}

	public void setFound(ItemStack stack, ResourceLocation targetKey, int x, int z, int y, ResourceLocation dimensionKey, int samples) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putInt("State", CompassState.FOUND.getID());
			stack.getTag().putString("StructureKey", targetKey.toString());
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
			CustomModelDataConfig.apply(stack, targetKey);
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

	/** What the compass is currently looking for, or last looked for. */
	public SearchTarget getSearchTarget(ItemStack stack) {
		if (ItemUtils.verifyNBT(stack) && stack.getTag().contains("SearchTarget")) {
			return SearchTarget.fromID(stack.getTag().getInt("SearchTarget"));
		}

		// A compass from before biomes could be searched for records nothing here, and everything one
		// could point at back then was a structure
		return SearchTarget.STRUCTURE;
	}

	public void setSearchTarget(ItemStack stack, SearchTarget searchTarget) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putInt("SearchTarget", searchTarget.getID());
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

	/** The locations this compass has located and remembered, oldest first. */
	public List<BookmarkEntry> getBookmarks(ItemStack stack) {
		final List<BookmarkEntry> bookmarks = new ArrayList<BookmarkEntry>();
		if (ItemUtils.verifyNBT(stack) && stack.getTag().contains("Bookmarks", Tag.TAG_LIST)) {
			for (Tag tag : stack.getTag().getList("Bookmarks", Tag.TAG_COMPOUND)) {
				final BookmarkEntry entry = BookmarkEntry.fromNBT((CompoundTag) tag);
				if (entry != null) {
					bookmarks.add(entry);
				}
			}
		}
		return bookmarks;
	}

	private void setBookmarks(ItemStack stack, List<BookmarkEntry> bookmarks) {
		if (ItemUtils.verifyNBT(stack)) {
			if (bookmarks.isEmpty()) {
				stack.getTag().remove("Bookmarks");
				return;
			}

			final ListTag listTag = new ListTag();
			for (BookmarkEntry entry : bookmarks) {
				listTag.add(entry.toNBT());
			}
			stack.getTag().put("Bookmarks", listTag);
		}
	}

	/**
	 * Remembers a location the compass has located, dropping the oldest one when the list is full.
	 * A location that is already remembered is not added again, so that finding the same place twice
	 * does not fill the list with copies of it.
	 */
	private void addBookmark(ItemStack stack, BookmarkEntry entry) {
		final int maxBookmarks = ConfigHandler.GENERAL.maxBookmarks.get();
		if (maxBookmarks <= 0) {
			return;
		}

		final List<BookmarkEntry> bookmarks = getBookmarks(stack);
		for (BookmarkEntry existing : bookmarks) {
			if (existing.isSamePlace(entry)) {
				return;
			}
		}

		bookmarks.add(entry);
		while (bookmarks.size() > maxBookmarks) {
			bookmarks.remove(0);
		}
		setBookmarks(stack, bookmarks);
	}

	/**
	 * Points the compass back at a remembered location. The location itself is put back on the list
	 * of places already located, so that searching for a further instance looks past it rather than
	 * answering with the one being pointed at.
	 */
	public void selectBookmark(ItemStack stack, int index) {
		final List<BookmarkEntry> bookmarks = getBookmarks(stack);
		if (index < 0 || index >= bookmarks.size()) {
			return;
		}

		final BookmarkEntry entry = bookmarks.get(index);
		setSearchTarget(stack, entry.getSearchTarget());
		setFound(stack, entry.getTargetKey(), entry.getX(), entry.getZ(), entry.getY(), entry.getDimensionKey(), 0);
		setIsGroup(stack, false);
		setTargetCount(stack, 1);
		setTargetKeys(stack, List.<ResourceLocation>of());
		// Only the horizontal coordinates of these are ever compared
		setPrevPos(stack, List.of(new BlockPos(entry.getX(), 0, entry.getZ())));
	}

	public void removeBookmark(ItemStack stack, int index) {
		final List<BookmarkEntry> bookmarks = getBookmarks(stack);
		if (index < 0 || index >= bookmarks.size()) {
			return;
		}

		bookmarks.remove(index);
		setBookmarks(stack, bookmarks);
	}

	public void clearBookmarks(ItemStack stack) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().remove("Bookmarks");
		}
	}

	/**
	 * Announces a located structure to everyone on the server, either one of the remembered
	 * locations or the one the compass is pointing at.
	 */
	public void shareLocation(ServerPlayer player, ItemStack stack, int bookmarkIndex) {
		if (!ConfigHandler.GENERAL.allowSharing.get()) {
			return;
		}

		final SearchTarget searchTarget;
		final ResourceLocation targetKey;
		final int x;
		final int y;
		final int z;
		final ResourceLocation dimensionKey;
		if (bookmarkIndex == ShareLocationPacket.CURRENT_TARGET) {
			if (getState(stack) != CompassState.FOUND) {
				return;
			}
			searchTarget = getSearchTarget(stack);
			targetKey = getTargetKey(stack);
			x = getFoundStructureX(stack);
			y = getFoundStructureY(stack);
			z = getFoundStructureZ(stack);
			dimensionKey = getFoundDimension(stack);
		} else {
			final List<BookmarkEntry> bookmarks = getBookmarks(stack);
			if (bookmarkIndex < 0 || bookmarkIndex >= bookmarks.size()) {
				return;
			}
			final BookmarkEntry entry = bookmarks.get(bookmarkIndex);
			searchTarget = entry.getSearchTarget();
			targetKey = entry.getTargetKey();
			x = entry.getX();
			y = entry.getY();
			z = entry.getZ();
			dimensionKey = entry.getDimensionKey();
		}

		if (!tryAcquireShareSlot(player)) {
			return;
		}

		// A compass from before the dimension was recorded reports where its holder is instead
		final ResourceLocation dimension = dimensionKey != null ? dimensionKey : player.getLevel().dimension().location();
		player.getServer().getPlayerList().broadcastSystemMessage(sharedLocationMessage(player, searchTarget, targetKey, x, y, z, dimension), false);
	}

	/**
	 * The chat message a shared location produces. The coordinates can be clicked to copy them,
	 * which needs no permission, unlike suggesting a teleport command.
	 */
	private static Component sharedLocationMessage(ServerPlayer player, SearchTarget searchTarget, ResourceLocation targetKey, int x, int y, int z, ResourceLocation dimensionKey) {
		final String coordinates = y != UNKNOWN_Y ? x + ", " + y + ", " + z : x + ", " + z;
		final Component coordinatesComponent = Component.literal(coordinates).withStyle((style) -> style
				.withColor(ChatFormatting.GREEN)
				.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, coordinates))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("string.explorerscompass.copyCoordinates"))));
		// Names are resolved here rather than sent as translation keys, since most structures have no
		// translation and every client would then see the raw key
		return Component.translatable("string.explorerscompass.sharedLocation",
				player.getDisplayName(),
				Component.literal(searchTarget.getBasicName(targetKey)),
				coordinatesComponent,
				Component.literal(StructureUtils.getBasicStructureName(dimensionKey)));
	}

	private boolean tryAcquireShareSlot(ServerPlayer player) {
		final int cooldown = ConfigHandler.GENERAL.shareCooldownMillis.get();
		if (cooldown <= 0) {
			return true;
		}

		final long now = System.currentTimeMillis();
		final Long lastShare = lastShareTimes.get(player.getUUID());
		if (lastShare != null && now - lastShare < cooldown) {
			return false;
		}
		lastShareTimes.put(player.getUUID(), now);
		return true;
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

	/**
	 * The list those locations are held in, or null while there are none. It is replaced whenever
	 * they change, so its identity is what tells a reader that parsing them again is worth it.
	 */
	public Tag getPrevPosTag(ItemStack stack) {
		return ItemUtils.verifyNBT(stack) ? stack.getTag().get("PrevPos") : null;
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

	public void setTargetKey(ItemStack stack, ResourceLocation targetKey, Player player) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putString("StructureKey", targetKey.toString());
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

	/**
	 * The key of whatever the compass is aimed at: a structure, a biome, or the group either of them
	 * is being searched within. Which of those it is follows from {@link #getSearchTarget} and
	 * {@link #getIsGroup}. The tag is named for structures because that is all a compass could point
	 * at when it was first written, and renaming it would leave every existing compass blank.
	 */
	public ResourceLocation getTargetKey(ItemStack stack) {
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
