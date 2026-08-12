package com.chaosthedude.explorerscompass.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.chaosthedude.explorerscompass.client.StructurePreviewCache;
import com.chaosthedude.explorerscompass.preview.StructurePreview;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * What a structure looks like, or that there is nothing to show for it. Every request is answered
 * with one of these, so that a screen waiting on a preview always learns where it stands.
 *
 * <p>A preview of a structure shown one cell to one block is far larger than a custom payload may
 * be — the limit is a megabyte, and the shell of a village or a stronghold at that detail is more
 * than that — so a preview is written out once and sent as a run of packets carrying a piece of it
 * each. The packets of a channel arrive in the order they were sent, so the receiving side has only
 * to append each piece to the last and read the whole once the final one says so.
 */
public class StructurePreviewPacket {

	/** How much of a preview one packet carries, well under what a custom payload may hold. */
	private static final int MAX_CHUNK_BYTES = 32768;

	/** Far more than any preview built here comes to; more than this is a malformed packet. */
	private static final int MAX_TOTAL_BYTES = 8 << 20;

	private ResourceLocation structureKey;
	/** How many bytes the whole preview comes to, or 0 when there is nothing to show for it. */
	private int totalBytes;
	private byte[] chunk;
	/** Whether this is the last of the run, which is what says the preview can be read. */
	private boolean last;

	private StructurePreviewPacket(ResourceLocation structureKey, int totalBytes, byte[] chunk, boolean last) {
		this.structureKey = structureKey;
		this.totalBytes = totalBytes;
		this.chunk = chunk;
		this.last = last;
	}

	/**
	 * The packets that carry the given preview, or the single packet that says there is nothing to
	 * show. Always at least one packet, so that nothing asked for goes unanswered.
	 */
	public static List<StructurePreviewPacket> createFor(ResourceLocation structureKey, StructurePreview preview) {
		if (preview == null) {
			return List.of(new StructurePreviewPacket(structureKey, 0, new byte[0], true));
		}

		final FriendlyByteBuf written = new FriendlyByteBuf(Unpooled.buffer());
		preview.write(written);
		final byte[] payload = new byte[written.readableBytes()];
		written.readBytes(payload);

		final List<StructurePreviewPacket> packets = new ArrayList<StructurePreviewPacket>();
		int offset = 0;
		while (offset < payload.length) {
			final int length = Math.min(MAX_CHUNK_BYTES, payload.length - offset);
			final byte[] chunk = new byte[length];
			System.arraycopy(payload, offset, chunk, 0, length);
			offset += length;
			packets.add(new StructurePreviewPacket(structureKey, payload.length, chunk, offset >= payload.length));
		}
		// A preview that wrote nothing at all would otherwise never be answered for
		if (packets.isEmpty()) {
			packets.add(new StructurePreviewPacket(structureKey, 0, new byte[0], true));
		}
		return packets;
	}

	public StructurePreviewPacket(FriendlyByteBuf buf) {
		structureKey = buf.readResourceLocation();
		totalBytes = buf.readVarInt();
		if (totalBytes < 0 || totalBytes > MAX_TOTAL_BYTES) {
			throw new DecoderException("Structure preview claims to be " + totalBytes + " bytes");
		}
		last = buf.readBoolean();
		chunk = buf.readByteArray(MAX_CHUNK_BYTES);
	}

	public void toBytes(FriendlyByteBuf buf) {
		buf.writeResourceLocation(structureKey);
		buf.writeVarInt(totalBytes);
		buf.writeBoolean(last);
		buf.writeByteArray(chunk);
	}

	public void handle(Supplier<NetworkEvent.Context> ctx) {
		// Reached through the dist executor rather than called outright, so that loading this class on
		// a dedicated server does not drag the client-only cache in behind it
		ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> StructurePreviewCache.receiveChunk(structureKey, totalBytes, chunk, last)));
		ctx.get().setPacketHandled(true);
	}

}
