package com.chaosthedude.explorerscompass.util;

import java.util.Objects;
import java.util.Optional;

import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * A location the compass has located, kept so that the player can point the compass back at it
 * after searching for something else.
 */
public class BookmarkEntry {

	public static final Codec<BookmarkEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			SearchTarget.CODEC.optionalFieldOf("search_target", SearchTarget.STRUCTURE).forGetter(BookmarkEntry::getSearchTarget),
			ResourceLocation.CODEC.fieldOf("target_key").forGetter(BookmarkEntry::getTargetKey),
			Codec.INT.fieldOf("x").forGetter(BookmarkEntry::getX),
			Codec.INT.optionalFieldOf("y", ExplorersCompassItem.UNKNOWN_Y).forGetter(BookmarkEntry::getY),
			Codec.INT.fieldOf("z").forGetter(BookmarkEntry::getZ),
			ResourceLocation.CODEC.optionalFieldOf("dimension").forGetter(entry -> Optional.ofNullable(entry.getDimensionKey()))
	).apply(instance, (searchTarget, targetKey, x, y, z, dimension) -> new BookmarkEntry(searchTarget, targetKey, x, y, z, dimension.orElse(null))));

	public static final StreamCodec<ByteBuf, BookmarkEntry> STREAM_CODEC = StreamCodec.composite(
			SearchTarget.STREAM_CODEC, BookmarkEntry::getSearchTarget,
			ResourceLocation.STREAM_CODEC, BookmarkEntry::getTargetKey,
			ByteBufCodecs.INT, BookmarkEntry::getX,
			ByteBufCodecs.INT, BookmarkEntry::getY,
			ByteBufCodecs.INT, BookmarkEntry::getZ,
			ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC), entry -> Optional.ofNullable(entry.getDimensionKey()),
			(searchTarget, targetKey, x, y, z, dimension) -> new BookmarkEntry(searchTarget, targetKey, x, y, z, dimension.orElse(null)));

	private final SearchTarget searchTarget;
	private final ResourceLocation targetKey;
	private final int x;
	private final int y;
	private final int z;
	private final ResourceLocation dimensionKey;

	public BookmarkEntry(SearchTarget searchTarget, ResourceLocation targetKey, int x, int y, int z, ResourceLocation dimensionKey) {
		this.searchTarget = searchTarget;
		this.targetKey = targetKey;
		this.x = x;
		this.y = y;
		this.z = z;
		this.dimensionKey = dimensionKey;
	}

	/**
	 * Reads an entry the way a compass from before 1.21 wrote it, or returns null when the tag does
	 * not hold a usable one. Only reached while migrating such a compass; everything since goes
	 * through {@link #CODEC}.
	 */
	public static BookmarkEntry fromNBT(CompoundTag tag) {
		final ResourceLocation targetKey = ResourceLocation.tryParse(tag.getString("StructureKey"));
		if (targetKey == null) {
			return null;
		}

		// An entry from before biomes could be searched for records no target, and back then every
		// location a compass remembered was a structure's
		final SearchTarget searchTarget = SearchTarget.fromID(tag.getInt("SearchTarget"));
		final ResourceLocation dimensionKey = ResourceLocation.tryParse(tag.getString("Dimension"));
		final int y = tag.contains("Y") ? tag.getInt("Y") : ExplorersCompassItem.UNKNOWN_Y;
		return new BookmarkEntry(searchTarget, targetKey, tag.getInt("X"), y, tag.getInt("Z"), dimensionKey);
	}

	/** Whether this location is a structure's or a biome's. */
	public SearchTarget getSearchTarget() {
		return searchTarget;
	}

	public ResourceLocation getTargetKey() {
		return targetKey;
	}

	public int getX() {
		return x;
	}

	/** The structure's height, or {@link ExplorersCompassItem#UNKNOWN_Y} when it was never recorded. */
	public int getY() {
		return y;
	}

	public int getZ() {
		return z;
	}

	/** The dimension this location is in, or null for an entry from before it was recorded. */
	public ResourceLocation getDimensionKey() {
		return dimensionKey;
	}

	/** Whether this entry points at the same place as the given one, ignoring the height. */
	public boolean isSamePlace(BookmarkEntry other) {
		return x == other.x && z == other.z && Objects.equals(dimensionKey, other.dimensionKey);
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof BookmarkEntry)) {
			return false;
		}
		final BookmarkEntry entry = (BookmarkEntry) other;
		return x == entry.x && y == entry.y && z == entry.z && searchTarget == entry.searchTarget && targetKey.equals(entry.targetKey) && Objects.equals(dimensionKey, entry.dimensionKey);
	}

	@Override
	public int hashCode() {
		return Objects.hash(searchTarget, targetKey, x, y, z, dimensionKey);
	}

}
