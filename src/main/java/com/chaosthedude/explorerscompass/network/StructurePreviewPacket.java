package com.chaosthedude.explorerscompass.network;

import java.util.ArrayList;
import java.util.List;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.client.StructurePreviewCache;
import com.chaosthedude.explorerscompass.preview.StructurePreview;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * What a structure looks like, or that there is nothing to show for it. Every request is answered
 * with one of these, so that a screen waiting on a preview always learns where it stands.
 *
 * <p>A preview of a structure shown one cell to one block is far larger than a custom payload may
 * be — the limit is a megabyte, and the shell of a village or a stronghold at that detail is more
 * than that — so a preview is written out once and sent as a run of packets carrying a piece of it
 * each. The packets of a channel arrive in the order they were sent, so the receiving side has only
 * to append each piece to the last and read the whole once the final one says so.
 *
 * @param totalBytes how many bytes the whole preview comes to, or 0 when there is nothing to show
 * @param last       whether this is the last of the run, which is what says the preview can be read
 */
public record StructurePreviewPacket(ResourceLocation structureKey, int totalBytes, byte[] chunk, boolean last) implements CustomPacketPayload {

	/** How much of a preview one packet carries, well under what a custom payload may hold. */
	private static final int MAX_CHUNK_BYTES = 32768;

	/** Far more than any preview built here comes to; more than this is a malformed packet. */
	private static final int MAX_TOTAL_BYTES = 8 << 20;

	public static final CustomPacketPayload.Type<StructurePreviewPacket> TYPE = new CustomPacketPayload.Type<StructurePreviewPacket>(ResourceLocation.fromNamespaceAndPath(ExplorersCompass.MODID, "structure_preview"));

	/**
	 * Written out by hand rather than composed, so that what arrives is bounded before any of it is
	 * held: a declared size larger than any preview ever built is a modified sender rather than a
	 * large structure.
	 */
	public static final StreamCodec<RegistryFriendlyByteBuf, StructurePreviewPacket> STREAM_CODEC = StreamCodec.of(
			(buf, packet) -> {
				ResourceLocation.STREAM_CODEC.encode(buf, packet.structureKey());
				buf.writeVarInt(packet.totalBytes());
				buf.writeBoolean(packet.last());
				buf.writeByteArray(packet.chunk());
			},
			buf -> {
				final ResourceLocation structureKey = ResourceLocation.STREAM_CODEC.decode(buf);
				final int totalBytes = buf.readVarInt();
				if (totalBytes < 0 || totalBytes > MAX_TOTAL_BYTES) {
					throw new DecoderException("Structure preview claims to be " + totalBytes + " bytes");
				}
				final boolean last = buf.readBoolean();
				return new StructurePreviewPacket(structureKey, totalBytes, buf.readByteArray(MAX_CHUNK_BYTES), last);
			});

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	/**
	 * The packets that carry the given preview, or the single packet that says there is nothing to
	 * show. Always at least one packet, so that nothing asked for goes unanswered.
	 */
	public static List<StructurePreviewPacket> createFor(ResourceLocation structureKey, StructurePreview preview) {
		if (preview == null) {
			return List.of(new StructurePreviewPacket(structureKey, 0, new byte[0], true));
		}

		// A preview writes nothing that has to be looked up in a registry — block states go by id — so a
		// plain buffer is all it is written into, and the packets carry the bytes that came out
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

	/**
	 * Only ever reached on a client: this payload is registered in the client direction alone, so the
	 * cache it hands the piece to is never looked up on a dedicated server.
	 */
	public static void handle(StructurePreviewPacket packet, IPayloadContext ctx) {
		StructurePreviewCache.receiveChunk(packet.structureKey(), packet.totalBytes(), packet.chunk(), packet.last());
	}

}
