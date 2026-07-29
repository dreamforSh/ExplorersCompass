package com.chaosthedude.explorerscompass.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.util.BookmarkEntry;
import com.chaosthedude.explorerscompass.util.CompassData;
import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * What the compass carries on the stack. Everything here used to be one {@code CompoundTag}; an
 * item stack no longer holds arbitrary NBT, so it is split into components instead.
 *
 * <p>They are split rather than kept as one record because the two screens that watch for changes
 * ({@code ClientEventHandler} and {@code BookmarksScreen}) only care about one list each, and
 * comparing that list alone is what keeps them from re-parsing every frame.
 *
 * <p>Every one of them declares both a codec and a stream codec. Leaving the stream codec off would
 * still save and load correctly while leaving the HUD, the tooltip and the compass needle blank,
 * since all three read this on the client.
 */
public class ModDataComponents {

	/**
	 * Bounds on what arrives from the network. These are already what the item enforces when writing,
	 * so a longer list is a modified server rather than a large selection.
	 *
	 * <p>These bounds apply to encoding as well, so anything that decides how long one of these lists
	 * may grow has to be bounded by them: a stack that exceeds one cannot be synced at all. That is
	 * why the two configured limits are declared against these rather than a number of their own.
	 */
	public static final int MAX_STREAMED_TARGET_KEYS = 512;
	public static final int MAX_STREAMED_POSITIONS = 1024;
	public static final int MAX_STREAMED_BOOKMARKS = 1024;

	public static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, ExplorersCompass.MODID);

	/** What the compass is doing, and what it last located. */
	public static final DeferredHolder<DataComponentType<?>, DataComponentType<CompassData>> COMPASS_DATA = COMPONENTS.registerComponentType("compass_state",
			builder -> builder
					.persistent(CompassData.CODEC)
					.networkSynchronized(CompassData.STREAM_CODEC));

	/**
	 * The targets a multi-target search was asked for, so that searching for a further instance keeps
	 * considering the whole selection. Absent for single and group searches.
	 */
	public static final DeferredHolder<DataComponentType<?>, DataComponentType<List<ResourceLocation>>> TARGET_KEYS = COMPONENTS.registerComponentType("target_keys",
			builder -> builder
					.persistent(ResourceLocation.CODEC.listOf())
					.networkSynchronized(immutableList(ResourceLocation.STREAM_CODEC, MAX_STREAMED_TARGET_KEYS)));

	/** The locations this compass has already located, which further searches pass over. */
	public static final DeferredHolder<DataComponentType<?>, DataComponentType<List<BlockPos>>> PREV_POSITIONS = COMPONENTS.registerComponentType("prev_positions",
			builder -> builder
					.persistent(BlockPos.CODEC.listOf())
					.networkSynchronized(immutableList(BlockPos.STREAM_CODEC, MAX_STREAMED_POSITIONS)));

	/** The locations this compass has collected, oldest first, so they can be pointed at again. */
	public static final DeferredHolder<DataComponentType<?>, DataComponentType<List<BookmarkEntry>>> BOOKMARKS = COMPONENTS.registerComponentType("bookmarks",
			builder -> builder
					.persistent(Codec.list(BookmarkEntry.CODEC))
					.networkSynchronized(immutableList(BookmarkEntry.STREAM_CODEC, MAX_STREAMED_BOOKMARKS)));

	/**
	 * A bounded list codec whose decoded value is immutable, so that a component read off the network
	 * is the same kind of value as one written here - the contents of a component may not be changed
	 * in place, and an {@code ArrayList} invites exactly that.
	 */
	private static <B extends ByteBuf, V> StreamCodec<B, List<V>> immutableList(StreamCodec<? super B, V> elementCodec, int maxSize) {
		return ByteBufCodecs.<B, V, List<V>>collection(ArrayList::new, elementCodec, maxSize).map(List::copyOf, Function.identity());
	}

	private ModDataComponents() {
	}

}
