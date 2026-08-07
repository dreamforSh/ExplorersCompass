package com.chaosthedude.explorerscompass.client;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.network.StructurePreviewRequestPacket;
import com.chaosthedude.explorerscompass.preview.StructurePreview;

import net.minecraft.Util;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * The previews this client has been sent, and what it is still waiting for.
 *
 * <p>A preview is asked for when a structure is first looked at and kept afterwards, so that
 * looking at the same one again costs nothing and the server is not asked to build it twice. Only
 * one request is outstanding at a time: the server answers them in order, and a client that asked
 * for several at once would be rate limited into losing all but the first anyway.
 */
@OnlyIn(Dist.CLIENT)
public class StructurePreviewCache {

	/** Where a preview stands: what to show for it, rather than what it is. */
	public enum State {
		/** Nothing has been asked for yet. */
		NONE,
		/** Asked for, and the answer has not arrived. */
		PENDING,
		/** Arrived, and there to be drawn. */
		READY,
		/** The server has nothing to show for it. */
		UNAVAILABLE
	}

	/**
	 * How many previews are held. Enough to flick back and forth between a handful of structures
	 * without asking again, and small enough that nothing is kept for long after a screen closes.
	 */
	private static final int MAX_CACHED = 8;

	/** How long to wait for an answer before asking again, and how many times to bother. */
	private static final long RETRY_AFTER_MILLIS = 1500L;
	private static final int MAX_ATTEMPTS = 3;

	private static final Map<ResourceLocation, StructurePreview> previews = new LinkedHashMap<ResourceLocation, StructurePreview>(16, 0.75F, true) {

		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<ResourceLocation, StructurePreview> eldest) {
			return size() > MAX_CACHED;
		}

	};

	/** Structures the server said it has nothing to show for, so that they are not asked for again. */
	private static final Set<ResourceLocation> unavailable = new HashSet<ResourceLocation>();

	private static ResourceLocation pendingKey;
	private static long lastRequestedAt;
	private static int attempts;

	private StructurePreviewCache() {
	}

	/**
	 * Asks for a preview of the given structure, unless it is already held or already being waited
	 * for. Safe to call on every frame: it is what re-sends a request the server dropped because it
	 * arrived while the last one was still being answered.
	 */
	public static void request(ResourceLocation structureKey) {
		if (structureKey == null || previews.containsKey(structureKey) || unavailable.contains(structureKey)) {
			return;
		}

		if (structureKey.equals(pendingKey)) {
			if (attempts >= MAX_ATTEMPTS || Util.getMillis() - lastRequestedAt < RETRY_AFTER_MILLIS) {
				return;
			}
		} else {
			// Whatever was being waited for is no longer what is being looked at
			pendingKey = structureKey;
			attempts = 0;
		}

		attempts++;
		lastRequestedAt = Util.getMillis();
		ExplorersCompass.network.sendToServer(new StructurePreviewRequestPacket(structureKey));
	}

	/** Takes in what the server answered. A null preview means it has nothing to show. */
	public static void receive(ResourceLocation structureKey, StructurePreview preview) {
		if (structureKey.equals(pendingKey)) {
			pendingKey = null;
			attempts = 0;
		}

		if (preview == null) {
			unavailable.add(structureKey);
		} else {
			previews.put(structureKey, preview);
		}
	}

	public static StructurePreview get(ResourceLocation structureKey) {
		return previews.get(structureKey);
	}

	/** Where the preview of the given structure stands, which is what its screen draws. */
	public static State stateOf(ResourceLocation structureKey) {
		if (structureKey == null) {
			return State.NONE;
		}
		if (previews.containsKey(structureKey)) {
			return State.READY;
		}
		if (unavailable.contains(structureKey)) {
			return State.UNAVAILABLE;
		}
		if (structureKey.equals(pendingKey)) {
			// An answer that never came, after the last attempt, is as good as no answer at all
			final boolean givenUp = attempts >= MAX_ATTEMPTS && Util.getMillis() - lastRequestedAt >= RETRY_AFTER_MILLIS;
			return givenUp ? State.UNAVAILABLE : State.PENDING;
		}
		return State.NONE;
	}

	/**
	 * Forgets everything. Called when the client leaves a server: what a structure looks like follows
	 * from that server's data packs, and the next one it joins may load different ones.
	 */
	public static void clear() {
		previews.clear();
		unavailable.clear();
		pendingKey = null;
		attempts = 0;
	}

}
