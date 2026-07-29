package com.chaosthedude.explorerscompass.util;

import java.util.Optional;

import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * Everything the compass knows about what it is doing, as one value.
 *
 * <p>This used to live in a {@code CompoundTag} on the stack, with every field read and written
 * one key at a time. An item stack no longer carries arbitrary NBT, so it is a data component
 * instead: one immutable record, replaced wholesale whenever anything about it changes. The
 * {@code withX} methods below are what makes that bearable, since a search reports progress often
 * enough that building the whole record by hand each time would drown out what is being changed.
 *
 * @param state              what the compass is doing
 * @param searchTarget       whether it is aimed at a structure or a biome
 * @param targetKey          what it is aimed at, absent when it is aimed at nothing
 * @param isGroup            whether {@code targetKey} names a group rather than a single target
 * @param targetCount        how many targets the running search is considering at once
 * @param foundX             where the located target is
 * @param foundY             the located target's height, or {@link ExplorersCompassItem#UNKNOWN_Y}
 * @param foundZ             where the located target is
 * @param foundDimension     the dimension it was located in, absent on a compass from before that
 *                           was recorded
 * @param searchRadius       how far the search has looked so far
 * @param samples            how many locations it has looked at
 * @param displayCoordinates whether the server allows the coordinates to be shown
 */
public record CompassData(
		CompassState state,
		SearchTarget searchTarget,
		Optional<ResourceLocation> targetKey,
		boolean isGroup,
		int targetCount,
		int foundX,
		int foundY,
		int foundZ,
		Optional<ResourceLocation> foundDimension,
		int searchRadius,
		int samples,
		boolean displayCoordinates) {

	/** What a compass that has never been used reads as. */
	public static final CompassData EMPTY = new CompassData(
			CompassState.INACTIVE, SearchTarget.STRUCTURE, Optional.empty(), false, 1,
			0, ExplorersCompassItem.UNKNOWN_Y, 0, Optional.empty(), 0, 0, true);

	/**
	 * Every field carries the value {@link #EMPTY} holds as its default, so a compass that has only
	 * been aimed at something writes a handful of keys rather than all twelve.
	 */
	public static final Codec<CompassData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			CompassState.CODEC.optionalFieldOf("state", CompassState.INACTIVE).forGetter(CompassData::state),
			SearchTarget.CODEC.optionalFieldOf("search_target", SearchTarget.STRUCTURE).forGetter(CompassData::searchTarget),
			ResourceLocation.CODEC.optionalFieldOf("target_key").forGetter(CompassData::targetKey),
			Codec.BOOL.optionalFieldOf("is_group", false).forGetter(CompassData::isGroup),
			Codec.INT.optionalFieldOf("target_count", 1).forGetter(CompassData::targetCount),
			Codec.INT.optionalFieldOf("found_x", 0).forGetter(CompassData::foundX),
			Codec.INT.optionalFieldOf("found_y", ExplorersCompassItem.UNKNOWN_Y).forGetter(CompassData::foundY),
			Codec.INT.optionalFieldOf("found_z", 0).forGetter(CompassData::foundZ),
			ResourceLocation.CODEC.optionalFieldOf("found_dimension").forGetter(CompassData::foundDimension),
			Codec.INT.optionalFieldOf("search_radius", 0).forGetter(CompassData::searchRadius),
			Codec.INT.optionalFieldOf("samples", 0).forGetter(CompassData::samples),
			Codec.BOOL.optionalFieldOf("display_coordinates", true).forGetter(CompassData::displayCoordinates)
	).apply(instance, CompassData::new));

	/**
	 * Written out by hand rather than composed, since {@code StreamCodec.composite} only reaches
	 * eight fields and this record has twelve.
	 */
	public static final StreamCodec<ByteBuf, CompassData> STREAM_CODEC = StreamCodec.of(
			(buf, data) -> {
				CompassState.STREAM_CODEC.encode(buf, data.state());
				SearchTarget.STREAM_CODEC.encode(buf, data.searchTarget());
				ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC).encode(buf, data.targetKey());
				ByteBufCodecs.BOOL.encode(buf, data.isGroup());
				ByteBufCodecs.VAR_INT.encode(buf, data.targetCount());
				ByteBufCodecs.INT.encode(buf, data.foundX());
				ByteBufCodecs.INT.encode(buf, data.foundY());
				ByteBufCodecs.INT.encode(buf, data.foundZ());
				ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC).encode(buf, data.foundDimension());
				ByteBufCodecs.VAR_INT.encode(buf, data.searchRadius());
				ByteBufCodecs.VAR_INT.encode(buf, data.samples());
				ByteBufCodecs.BOOL.encode(buf, data.displayCoordinates());
			},
			buf -> new CompassData(
					CompassState.STREAM_CODEC.decode(buf),
					SearchTarget.STREAM_CODEC.decode(buf),
					ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC).decode(buf),
					ByteBufCodecs.BOOL.decode(buf),
					ByteBufCodecs.VAR_INT.decode(buf),
					ByteBufCodecs.INT.decode(buf),
					ByteBufCodecs.INT.decode(buf),
					ByteBufCodecs.INT.decode(buf),
					ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC).decode(buf),
					ByteBufCodecs.VAR_INT.decode(buf),
					ByteBufCodecs.VAR_INT.decode(buf),
					ByteBufCodecs.BOOL.decode(buf)));

	public CompassData withState(CompassState state) {
		return new CompassData(state, searchTarget, targetKey, isGroup, targetCount, foundX, foundY, foundZ, foundDimension, searchRadius, samples, displayCoordinates);
	}

	public CompassData withSearchTarget(SearchTarget searchTarget) {
		return new CompassData(state, searchTarget, targetKey, isGroup, targetCount, foundX, foundY, foundZ, foundDimension, searchRadius, samples, displayCoordinates);
	}

	public CompassData withTargetKey(ResourceLocation targetKey) {
		return new CompassData(state, searchTarget, Optional.ofNullable(targetKey), isGroup, targetCount, foundX, foundY, foundZ, foundDimension, searchRadius, samples, displayCoordinates);
	}

	public CompassData withIsGroup(boolean isGroup) {
		return new CompassData(state, searchTarget, targetKey, isGroup, targetCount, foundX, foundY, foundZ, foundDimension, searchRadius, samples, displayCoordinates);
	}

	public CompassData withTargetCount(int targetCount) {
		return new CompassData(state, searchTarget, targetKey, isGroup, targetCount, foundX, foundY, foundZ, foundDimension, searchRadius, samples, displayCoordinates);
	}

	public CompassData withSearchRadius(int searchRadius) {
		return new CompassData(state, searchTarget, targetKey, isGroup, targetCount, foundX, foundY, foundZ, foundDimension, searchRadius, samples, displayCoordinates);
	}

	public CompassData withSamples(int samples) {
		return new CompassData(state, searchTarget, targetKey, isGroup, targetCount, foundX, foundY, foundZ, foundDimension, searchRadius, samples, displayCoordinates);
	}

	public CompassData withDisplayCoordinates(boolean displayCoordinates) {
		return new CompassData(state, searchTarget, targetKey, isGroup, targetCount, foundX, foundY, foundZ, foundDimension, searchRadius, samples, displayCoordinates);
	}

	/** Aims the compass at something and marks it as looking for it. */
	public CompassData searching(SearchTarget searchTarget, ResourceLocation targetKey) {
		return new CompassData(CompassState.SEARCHING, searchTarget, Optional.ofNullable(targetKey), isGroup, targetCount, foundX, foundY, foundZ, foundDimension, searchRadius, samples, displayCoordinates);
	}

	/** Records a located target, which is the one change that touches most of the record at once. */
	public CompassData found(ResourceLocation targetKey, int x, int z, int y, ResourceLocation dimensionKey, int samples) {
		return new CompassData(CompassState.FOUND, searchTarget, Optional.ofNullable(targetKey), isGroup, targetCount, x, y, z,
				// A search that could not tell the dimension leaves whatever was already recorded, the
				// way the tag it replaces was only overwritten when there was something to write
				dimensionKey != null ? Optional.of(dimensionKey) : foundDimension,
				searchRadius, samples, displayCoordinates);
	}

	public CompassData notFound(int searchRadius, int samples) {
		return new CompassData(CompassState.NOT_FOUND, searchTarget, targetKey, isGroup, targetCount, foundX, foundY, foundZ, foundDimension, searchRadius, samples, displayCoordinates);
	}

	/** The key the compass is aimed at, or null when it is aimed at nothing. */
	public ResourceLocation targetKeyOrNull() {
		return targetKey.orElse(null);
	}

	/** The dimension the located target is in, or null when none was ever recorded. */
	public ResourceLocation foundDimensionOrNull() {
		return foundDimension.orElse(null);
	}

}
