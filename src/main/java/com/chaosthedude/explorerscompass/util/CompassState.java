package com.chaosthedude.explorerscompass.util;

import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public enum CompassState {

	INACTIVE(0), SEARCHING(1), FOUND(2), NOT_FOUND(3);

	/**
	 * Serialized by id rather than by name, so that a compass written by an older version of the mod
	 * still reads. An id no state carries is read as {@link #INACTIVE} instead of as nothing, since a
	 * component that fails to decode takes the whole stack down with it.
	 */
	public static final Codec<CompassState> CODEC = Codec.INT.xmap(CompassState::fromIDOrInactive, CompassState::getID);

	public static final StreamCodec<ByteBuf, CompassState> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(CompassState::fromIDOrInactive, CompassState::getID);

	private int id;

	CompassState(int id) {
		this.id = id;
	}

	public int getID() {
		return id;
	}

	public static CompassState fromID(int id) {
		for (CompassState state : values()) {
			if (state.getID() == id) {
				return state;
			}
		}

		return null;
	}

	/** As {@link #fromID}, but never null, for the places that cannot deal with an unknown id. */
	public static CompassState fromIDOrInactive(int id) {
		final CompassState state = fromID(id);
		return state != null ? state : INACTIVE;
	}

}
