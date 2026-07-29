package com.chaosthedude.explorerscompass.items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.client.CompassTooltip;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.config.CustomModelDataConfig;
import com.chaosthedude.explorerscompass.gui.GuiWrapper;
import com.chaosthedude.explorerscompass.network.ShareLocationPacket;
import com.chaosthedude.explorerscompass.network.SyncPacket;
import com.chaosthedude.explorerscompass.util.BiomeUtils;
import com.chaosthedude.explorerscompass.util.BookmarkEntry;
import com.chaosthedude.explorerscompass.util.CompassData;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import com.chaosthedude.explorerscompass.util.PlayerUtils;
import com.chaosthedude.explorerscompass.util.SearchTarget;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.chaosthedude.explorerscompass.worker.SearchContext;
import com.chaosthedude.explorerscompass.worker.SearchService;
import com.chaosthedude.explorerscompass.worker.SearchWorkerManager;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;

public class ExplorersCompassItem extends Item {

	public static final String NAME = "explorerscompass";

	/** Marks a structure height the search could not determine. */
	public static final int UNKNOWN_Y = Integer.MIN_VALUE;

	/**
	 * How many of a multi-selection's keys are kept on the compass. They are only there so that
	 * searching for a further instance keeps considering the whole selection, and everything written
	 * to a stack rides along on every sync of it — of which a running search causes one each time it
	 * reports how far it has looked. A selection past this costs more to carry around than searching
	 * for the ones beyond it is worth; the search itself is never cut down, only what is remembered
	 * for the next one.
	 */
	private static final int MAX_PERSISTED_TARGET_KEYS = 256;

	/**
	 * When each player last shared a location, so that sharing cannot be used to flood chat. Only
	 * ever touched on the server thread.
	 */
	private final Map<UUID, Long> lastShareTimes = new HashMap<UUID, Long>();

	public ExplorersCompassItem() {
		// Which creative tab this shows up in is no longer a property of the item; it is decided by
		// whoever fills the tab, in ExplorersCompassRegistry
		super(new Properties().stacksTo(1));
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
					PacketDistributor.sendToPlayer(serverPlayer, packet);
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

	/**
	 * What the compass says about itself while the pointer rests on it. Everything it names has to be
	 * named in the player's own language, and the translations only resolve on the client, so the
	 * lines are put together over there. A dedicated server never builds a tooltip at all, and with
	 * that branch never taken the class is never loaded, so nothing is added rather than something
	 * half translated being.
	 */
	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		if (FMLEnvironment.dist == Dist.CLIENT) {
			CompassTooltip.appendHoverText(this, stack, tooltip, flag);
		}
	}

	/**
	 * Starts a fresh search for the nearest of the given structures or biomes, forgetting the
	 * locations any earlier search had collected.
	 */
	public void searchForTargets(Level level, Player player, SearchTarget searchTarget, List<ResourceLocation> keys, BlockPos pos, ItemStack stack) {
		if (!(level instanceof ServerLevel) || keys.isEmpty() || SearchService.isOnCooldown(player)) {
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
		if (!(level instanceof ServerLevel) || SearchService.isOnCooldown(player)) {
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
		if (!(level instanceof ServerLevel) || ConfigHandler.GENERAL.maxNextSearches.get() <= 0 || SearchService.isOnCooldown(player)) {
			return;
		}

		ServerLevel serverLevel = (ServerLevel) level;
		SearchTarget searchTarget = getSearchTarget(stack);
		ResourceLocation targetKey = getTargetKey(stack);
		if (targetKey == null || getState(stack) != CompassState.FOUND) {
			return;
		}

		List<BlockPos> prevPos = new ArrayList<BlockPos>(getPrevPos(stack));
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
			SearchService.stopSearch(player);
		}
		setState(stack, null, CompassState.INACTIVE, player);
		clearPrevPos(stack);
	}

	private void search(ServerLevel level, Player player, SearchTarget searchTarget, List<ResourceLocation> keys, ResourceLocation displayKey, boolean isGroup, BlockPos pos, ItemStack stack, List<BlockPos> prevPos, boolean ignoreNearStart) {
		// Everything a request has to be for it to search at all has been checked by now, so this is
		// where the cooldown starts: resolving the keys below is itself worth rate limiting
		SearchService.recordSearchStart(player);

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

		final SearchWorkerManager workerManager = SearchService.getWorkerManager(player);
		workerManager.stop();
		if (validKeys.isEmpty()) {
			// Reported the same way a search that ran and turned up nothing is, so that the compass, the
			// remaining workers and the player are all left in the state every other ending leaves them
			fail(player, stack, 0, 0);
			return;
		}

		final SearchContext context = new SearchContext(level, player, stack, pos, prevPos, isGroup, ignoreNearStart);
		if (searchTarget == SearchTarget.BIOME) {
			workerManager.createBiomeWorker(context, biomes);
		} else {
			workerManager.createStructureWorkers(context, structures);
		}
		if (!workerManager.start()) {
			fail(player, stack, 0, 0);
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

	/** Stops every search on this server, and forgets who they belonged to. */
	public void forgetAllPlayers() {
		SearchService.forgetAllPlayers();
		lastShareTimes.clear();
	}

	/**
	 * Drops everything remembered about a player who has left the server, so that none of it is kept
	 * for the rest of the server's life.
	 */
	public void forgetPlayer(UUID playerId) {
		SearchService.forgetPlayer(playerId);
		lastShareTimes.remove(playerId);
	}

	public void succeed(Player player, ItemStack stack, ResourceLocation targetKey, boolean isGroup, int x, int z, int y, ResourceLocation dimensionKey, List<BlockPos> prevPos, int samples, boolean displayCoordinates) {
		final SearchTarget searchTarget = getSearchTarget(stack);
		// One write rather than three: the record is immutable and replaced whole, so setting these
		// one at a time would copy it once per field
		ItemUtils.updateData(stack, data -> data
				.found(targetKey, x, z, y, dimensionKey, samples)
				.withIsGroup(isGroup)
				.withDisplayCoordinates(displayCoordinates));
		CustomModelDataConfig.apply(stack, targetKey);
		setPrevPos(stack, prevPos);
		addBookmark(stack, new BookmarkEntry(searchTarget, targetKey, x, y, z, dimensionKey));
		SearchService.getWorkerManager(player).clear();

		final String name = searchTarget.getBasicName(targetKey);
		final String coordinates = StructureUtils.formatCoordinates(x, y, z);
		notifySearchResult(player, Component.translatable("string.explorerscompass.found").append(Component.literal(displayCoordinates ? ": " + name + " (" + coordinates + ")" : ": " + name)));
	}

	/** Reports that the search is over and located nothing. */
	public void fail(Player player, ItemStack stack, int radius, int samples) {
		setNotFound(stack, radius, samples);
		SearchService.getWorkerManager(player).clear();
		notifySearchResult(player, Component.translatable("string.explorerscompass.notFound").append(Component.literal(": " + getSearchTarget(stack).getBasicName(getTargetKey(stack)))));
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
		return ItemUtils.isCompass(stack) && getState(stack) != CompassState.INACTIVE;
	}

	public void setSearching(ItemStack stack, SearchTarget searchTarget, ResourceLocation targetKey, Player player) {
		ItemUtils.updateData(stack, data -> data.searching(searchTarget, targetKey));
		CustomModelDataConfig.apply(stack, targetKey);
	}

	public void setFound(ItemStack stack, ResourceLocation targetKey, int x, int z, int y, ResourceLocation dimensionKey, int samples) {
		ItemUtils.updateData(stack, data -> data.found(targetKey, x, z, y, dimensionKey, samples));
		CustomModelDataConfig.apply(stack, targetKey);
	}

	public void setNotFound(ItemStack stack, int searchRadius, int samples) {
		ItemUtils.updateData(stack, data -> data.notFound(searchRadius, samples));
	}

	public void setInactive(ItemStack stack, Player player) {
		ItemUtils.updateData(stack, data -> data.withState(CompassState.INACTIVE));
	}

	public void setState(ItemStack stack, BlockPos pos, CompassState state, Player player) {
		ItemUtils.updateData(stack, data -> data.withState(state));
		if (state == CompassState.INACTIVE) {
			CustomModelDataConfig.remove(stack);
		}
	}

	/** What the compass is currently looking for, or last looked for. */
	public SearchTarget getSearchTarget(ItemStack stack) {
		return ItemUtils.getData(stack).searchTarget();
	}

	public void setSearchTarget(ItemStack stack, SearchTarget searchTarget) {
		ItemUtils.updateData(stack, data -> data.withSearchTarget(searchTarget));
	}

	public void setIsGroup(ItemStack stack, boolean isGroup) {
		ItemUtils.updateData(stack, data -> data.withIsGroup(isGroup));
	}

	public boolean getIsGroup(ItemStack stack) {
		return ItemUtils.getData(stack).isGroup();
	}

	/** How many structures the current search is looking for at once. */
	public int getTargetCount(ItemStack stack) {
		return Math.max(1, ItemUtils.getData(stack).targetCount());
	}

	private void setTargetCount(ItemStack stack, int targetCount) {
		ItemUtils.updateData(stack, data -> data.withTargetCount(targetCount));
	}

	/**
	 * The structures a multi-structure search was asked for, so that searching for a further
	 * instance keeps considering the whole selection. Empty for single and group searches.
	 */
	public List<ResourceLocation> getTargetKeys(ItemStack stack) {
		return ItemUtils.getTargetKeys(stack);
	}

	private void setTargetKeys(ItemStack stack, List<ResourceLocation> targetKeys) {
		if (targetKeys.size() <= 1) {
			ItemUtils.setTargetKeys(stack, List.of());
			return;
		}

		if (targetKeys.size() > MAX_PERSISTED_TARGET_KEYS) {
			ExplorersCompass.LOGGER.warn("Remembering only the first " + MAX_PERSISTED_TARGET_KEYS + " of " + targetKeys.size() + " selected targets; searching for a further instance will consider those alone");
			targetKeys = targetKeys.subList(0, MAX_PERSISTED_TARGET_KEYS);
		}
		ItemUtils.setTargetKeys(stack, targetKeys);
	}

	/** The locations this compass has located and remembered, oldest first. */
	public List<BookmarkEntry> getBookmarks(ItemStack stack) {
		return ItemUtils.getBookmarks(stack);
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

		final List<BookmarkEntry> bookmarks = new ArrayList<BookmarkEntry>(getBookmarks(stack));
		for (BookmarkEntry existing : bookmarks) {
			if (existing.isSamePlace(entry)) {
				return;
			}
		}

		bookmarks.add(entry);
		while (bookmarks.size() > maxBookmarks) {
			bookmarks.remove(0);
		}
		ItemUtils.setBookmarks(stack, bookmarks);
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
		ItemUtils.updateData(stack, data -> data
				.withSearchTarget(entry.getSearchTarget())
				.found(entry.getTargetKey(), entry.getX(), entry.getZ(), entry.getY(), entry.getDimensionKey(), 0)
				.withIsGroup(false)
				.withTargetCount(1));
		CustomModelDataConfig.apply(stack, entry.getTargetKey());
		ItemUtils.setTargetKeys(stack, List.of());
		// Only the horizontal coordinates of these are ever compared
		setPrevPos(stack, List.of(new BlockPos(entry.getX(), 0, entry.getZ())));
	}

	public void removeBookmark(ItemStack stack, int index) {
		final List<BookmarkEntry> bookmarks = new ArrayList<BookmarkEntry>(getBookmarks(stack));
		if (index < 0 || index >= bookmarks.size()) {
			return;
		}

		bookmarks.remove(index);
		ItemUtils.setBookmarks(stack, bookmarks);
	}

	public void clearBookmarks(ItemStack stack) {
		ItemUtils.setBookmarks(stack, List.of());
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
			final CompassData data = ItemUtils.getData(stack);
			searchTarget = data.searchTarget();
			targetKey = data.targetKeyOrNull();
			x = data.foundX();
			y = data.foundY();
			z = data.foundZ();
			dimensionKey = data.foundDimensionOrNull();
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

		// A compass aimed at nothing has nothing to announce, which the tag this replaces expressed as
		// an empty key rather than as an absent one
		if (targetKey == null || !tryAcquireShareSlot(player)) {
			return;
		}

		// A compass from before the dimension was recorded reports where its holder is instead
		final ResourceLocation dimension = dimensionKey != null ? dimensionKey : player.serverLevel().dimension().location();
		player.getServer().getPlayerList().broadcastSystemMessage(sharedLocationMessage(player, searchTarget, targetKey, x, y, z, dimension), false);
	}

	/**
	 * The chat message a shared location produces. The coordinates can be clicked to copy them,
	 * which needs no permission, unlike suggesting a teleport command.
	 */
	private static Component sharedLocationMessage(ServerPlayer player, SearchTarget searchTarget, ResourceLocation targetKey, int x, int y, int z, ResourceLocation dimensionKey) {
		final String coordinates = StructureUtils.formatCoordinates(x, y, z);
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

	/**
	 * The locations this compass has already located, which further searches pass over.
	 *
	 * <p>The list is immutable and is replaced rather than edited whenever it changes, so its
	 * identity tracks its contents: a reader that kept the last one it saw can tell whether anything
	 * has changed by comparing references, which is what the HUD does rather than walking the list
	 * every frame.
	 */
	public List<BlockPos> getPrevPos(ItemStack stack) {
		return ItemUtils.getPrevPos(stack);
	}

	/** Number of remembered locations, without copying the list. */
	public int getPrevPosCount(ItemStack stack) {
		return ItemUtils.getPrevPos(stack).size();
	}

	/**
	 * How many locations this compass has collected. The tooltip that wants this number is built
	 * again for every frame the pointer rests on the stack, so it reads the size rather than the
	 * entries.
	 */
	public int getBookmarkCount(ItemStack stack) {
		return ItemUtils.getBookmarks(stack).size();
	}

	public void setPrevPos(ItemStack stack, List<BlockPos> prevPos) {
		ItemUtils.setPrevPos(stack, prevPos);
	}

	public void clearPrevPos(ItemStack stack) {
		ItemUtils.setPrevPos(stack, List.of());
	}

	public void setFoundStructureX(ItemStack stack, int x, Player player) {
		ItemUtils.updateData(stack, data -> data.found(data.targetKeyOrNull(), x, data.foundZ(), data.foundY(), data.foundDimensionOrNull(), data.samples()));
	}

	public void setFoundStructureZ(ItemStack stack, int z, Player player) {
		ItemUtils.updateData(stack, data -> data.found(data.targetKeyOrNull(), data.foundX(), z, data.foundY(), data.foundDimensionOrNull(), data.samples()));
	}

	public void setTargetKey(ItemStack stack, ResourceLocation targetKey, Player player) {
		ItemUtils.updateData(stack, data -> data.withTargetKey(targetKey));
	}

	public void setSearchRadius(ItemStack stack, int searchRadius, Player player) {
		ItemUtils.updateData(stack, data -> data.withSearchRadius(searchRadius));
	}

	public void setSamples(ItemStack stack, int samples, Player player) {
		ItemUtils.updateData(stack, data -> data.withSamples(samples));
	}

	public void setDisplayCoordinates(ItemStack stack, boolean displayPosition) {
		ItemUtils.updateData(stack, data -> data.withDisplayCoordinates(displayPosition));
	}

	/** What the compass is doing, or null for a stack that is not a compass at all. */
	public CompassState getState(ItemStack stack) {
		return ItemUtils.isCompass(stack) ? ItemUtils.getData(stack).state() : null;
	}

	public int getFoundStructureX(ItemStack stack) {
		return ItemUtils.getData(stack).foundX();
	}

	public int getFoundStructureZ(ItemStack stack) {
		return ItemUtils.getData(stack).foundZ();
	}

	/** The height of the located structure, or {@link #UNKNOWN_Y} when the search could not tell it. */
	public int getFoundStructureY(ItemStack stack) {
		return ItemUtils.getData(stack).foundY();
	}

	/** The dimension the located structure is in, or null when an older compass never recorded it. */
	public ResourceLocation getFoundDimension(ItemStack stack) {
		return ItemUtils.getData(stack).foundDimensionOrNull();
	}

	/**
	 * The key of whatever the compass is aimed at: a structure, a biome, or the group either of them
	 * is being searched within. Which of those it is follows from {@link #getSearchTarget} and
	 * {@link #getIsGroup}. Null when the compass is aimed at nothing, which the tag this replaces
	 * expressed as a key with an empty path.
	 */
	public ResourceLocation getTargetKey(ItemStack stack) {
		return ItemUtils.getData(stack).targetKeyOrNull();
	}

	public int getSearchRadius(ItemStack stack) {
		return ItemUtils.getData(stack).searchRadius();
	}

	public int getSamples(ItemStack stack) {
		return ItemUtils.getData(stack).samples();
	}

	public boolean shouldDisplayCoordinates(ItemStack stack) {
		return ItemUtils.getData(stack).displayCoordinates();
	}

}
