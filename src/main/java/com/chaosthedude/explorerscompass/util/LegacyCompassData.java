package com.chaosthedude.explorerscompass.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * Reads what a compass written before 1.21 carried.
 *
 * <p>An item stack no longer holds arbitrary NBT. Upgrading a world runs Minecraft's own data fixer
 * over every stack, which sweeps whatever it cannot account for into the custom data component, so
 * everything this mod used to write lands there under the keys below - not lost, only unreadable
 * until it is read across onto the components that replaced it.
 *
 * <p>This is deliberately free of {@link net.minecraft.world.item.ItemStack}: the keys and their
 * types are the part that has to keep matching what a released version wrote, and keeping them
 * behind a plain tag makes that checkable without a game to hold a stack.
 */
class LegacyCompassData {

	static final String STATE = "State";
	static final String TARGET_KEY = "StructureKey";
	static final String SEARCH_TARGET = "SearchTarget";
	static final String IS_GROUP = "IsGroup";
	static final String TARGET_COUNT = "TargetCount";
	static final String FOUND_X = "FoundX";
	static final String FOUND_Y = "FoundY";
	static final String FOUND_Z = "FoundZ";
	static final String FOUND_DIMENSION = "FoundDimension";
	static final String SEARCH_RADIUS = "SearchRadius";
	static final String SAMPLES = "Samples";
	static final String DISPLAY_COORDINATES = "DisplayCoordinates";
	static final String TARGET_KEYS = "TargetKeys";
	static final String PREV_POS = "PrevPos";
	static final String BOOKMARKS = "Bookmarks";

	private static final String[] ALL_KEYS = {STATE, TARGET_KEY, SEARCH_TARGET, IS_GROUP, TARGET_COUNT, FOUND_X,
			FOUND_Y, FOUND_Z, FOUND_DIMENSION, SEARCH_RADIUS, SAMPLES, DISPLAY_COORDINATES, TARGET_KEYS, PREV_POS,
			BOOKMARKS};

	private LegacyCompassData() {
	}

	/**
	 * Whether this tag is a compass's rather than somebody else's. Custom data on a compass may just
	 * as well have come from a loot table, a datapack or another mod, and reading that as a search
	 * would invent a state the compass was never in.
	 */
	static boolean isLegacy(CompoundTag tag) {
		return tag.contains(STATE) || tag.contains(TARGET_KEY);
	}

	static CompassData readData(CompoundTag tag) {
		return new CompassData(
				CompassState.fromIDOrInactive(tag.getInt(STATE)),
				SearchTarget.fromID(tag.getInt(SEARCH_TARGET)),
				Optional.ofNullable(ResourceLocation.tryParse(tag.getString(TARGET_KEY))),
				tag.getBoolean(IS_GROUP),
				Math.max(1, tag.getInt(TARGET_COUNT)),
				tag.getInt(FOUND_X),
				tag.contains(FOUND_Y) ? tag.getInt(FOUND_Y) : ExplorersCompassItem.UNKNOWN_Y,
				tag.getInt(FOUND_Z),
				Optional.ofNullable(ResourceLocation.tryParse(tag.getString(FOUND_DIMENSION))),
				tag.getInt(SEARCH_RADIUS),
				tag.getInt(SAMPLES),
				!tag.contains(DISPLAY_COORDINATES) || tag.getBoolean(DISPLAY_COORDINATES));
	}

	static List<ResourceLocation> readTargetKeys(CompoundTag tag) {
		final List<ResourceLocation> targetKeys = new ArrayList<ResourceLocation>();
		for (Tag entry : tag.getList(TARGET_KEYS, Tag.TAG_STRING)) {
			final ResourceLocation key = ResourceLocation.tryParse(entry.getAsString());
			if (key != null) {
				targetKeys.add(key);
			}
		}
		return targetKeys;
	}

	static List<BlockPos> readPrevPos(CompoundTag tag) {
		final List<BlockPos> prevPos = new ArrayList<BlockPos>();
		for (Tag entry : tag.getList(PREV_POS, Tag.TAG_COMPOUND)) {
			final CompoundTag posTag = (CompoundTag) entry;
			prevPos.add(new BlockPos(posTag.getInt("X"), posTag.getInt("Y"), posTag.getInt("Z")));
		}
		return prevPos;
	}

	static List<BookmarkEntry> readBookmarks(CompoundTag tag) {
		final List<BookmarkEntry> bookmarks = new ArrayList<BookmarkEntry>();
		for (Tag entry : tag.getList(BOOKMARKS, Tag.TAG_COMPOUND)) {
			final BookmarkEntry bookmark = BookmarkEntry.fromNBT((CompoundTag) entry);
			if (bookmark != null) {
				bookmarks.add(bookmark);
			}
		}
		return bookmarks;
	}

	/**
	 * Takes the keys back out, rather than leaving a second copy that nothing reads any more. Anything
	 * else in the tag is left alone: it may well belong to somebody else.
	 */
	static void removeKeys(CompoundTag tag) {
		for (String key : ALL_KEYS) {
			tag.remove(key);
		}
	}

}
