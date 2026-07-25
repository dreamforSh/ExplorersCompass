package com.chaosthedude.explorerscompass.util;

import java.util.Objects;

import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * A location the compass has located, kept so that the player can point the compass back at it
 * after searching for something else.
 */
public class BookmarkEntry {

	private final ResourceLocation structureKey;
	private final int x;
	private final int y;
	private final int z;
	private final ResourceLocation dimensionKey;

	public BookmarkEntry(ResourceLocation structureKey, int x, int y, int z, ResourceLocation dimensionKey) {
		this.structureKey = structureKey;
		this.x = x;
		this.y = y;
		this.z = z;
		this.dimensionKey = dimensionKey;
	}

	/** Reads an entry, or returns null when the tag does not hold a usable one. */
	public static BookmarkEntry fromNBT(CompoundTag tag) {
		final ResourceLocation structureKey = ResourceLocation.tryParse(tag.getString("StructureKey"));
		if (structureKey == null) {
			return null;
		}

		final ResourceLocation dimensionKey = ResourceLocation.tryParse(tag.getString("Dimension"));
		final int y = tag.contains("Y") ? tag.getInt("Y") : ExplorersCompassItem.UNKNOWN_Y;
		return new BookmarkEntry(structureKey, tag.getInt("X"), y, tag.getInt("Z"), dimensionKey);
	}

	public CompoundTag toNBT() {
		final CompoundTag tag = new CompoundTag();
		tag.putString("StructureKey", structureKey.toString());
		tag.putInt("X", x);
		tag.putInt("Z", z);
		if (y != ExplorersCompassItem.UNKNOWN_Y) {
			tag.putInt("Y", y);
		}
		if (dimensionKey != null) {
			tag.putString("Dimension", dimensionKey.toString());
		}
		return tag;
	}

	public ResourceLocation getStructureKey() {
		return structureKey;
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
		return x == entry.x && y == entry.y && z == entry.z && structureKey.equals(entry.structureKey) && Objects.equals(dimensionKey, entry.dimensionKey);
	}

	@Override
	public int hashCode() {
		return Objects.hash(structureKey, x, y, z, dimensionKey);
	}

}
