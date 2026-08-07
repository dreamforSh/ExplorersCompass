package com.chaosthedude.explorerscompass.network;

import java.util.function.Supplier;

import com.chaosthedude.explorerscompass.client.StructurePreviewCache;
import com.chaosthedude.explorerscompass.preview.StructurePreview;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * What a structure looks like, or that there is nothing to show for it. Every request is answered
 * with one of these, so that a screen waiting on a preview always learns where it stands.
 */
public class StructurePreviewPacket {

	private ResourceLocation structureKey;
	/** What the structure looks like, or null when the server has nothing to show for it. */
	private StructurePreview preview;

	public StructurePreviewPacket(ResourceLocation structureKey, StructurePreview preview) {
		this.structureKey = structureKey;
		this.preview = preview;
	}

	public StructurePreviewPacket(FriendlyByteBuf buf) {
		structureKey = buf.readResourceLocation();
		preview = buf.readBoolean() ? StructurePreview.read(buf) : null;
	}

	public void toBytes(FriendlyByteBuf buf) {
		buf.writeResourceLocation(structureKey);
		buf.writeBoolean(preview != null);
		if (preview != null) {
			preview.write(buf);
		}
	}

	public void handle(Supplier<NetworkEvent.Context> ctx) {
		// Reached through the dist executor rather than called outright, so that loading this class on
		// a dedicated server does not drag the client-only cache in behind it
		ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> StructurePreviewCache.receive(structureKey, preview)));
		ctx.get().setPacketHandled(true);
	}

}
