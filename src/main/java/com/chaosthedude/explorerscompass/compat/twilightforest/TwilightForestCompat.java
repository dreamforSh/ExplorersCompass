package com.chaosthedude.explorerscompass.compat.twilightforest;

import com.chaosthedude.explorerscompass.preview.PreviewCompat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.StructurePiece;

/**
 * Compatibility with The Twilight Forest, set up only when it is loaded.
 *
 * <p>Its structures are a main piece decorated by many small ones laid out over it in turn: the
 * labyrinth's maze is one piece, and each dead end, fountain and vault in it another that puts
 * itself into a corridor the maze already carved. Read the way the game's own structures are, each
 * of those small pieces finds the maze already standing where it builds and is kept from building
 * over it, lays nothing of its own, and is drawn as the box it occupies — which is what turned the
 * labyrinth into a heap of stone boxes. So its pieces are read as building in layers, the way world
 * generation places them. The same goes for its marker pieces, which exist only to shape the terrain
 * around a structure and lay nothing at all.
 *
 * <p>Its pieces are told apart by the namespace their piece type is registered under rather than by
 * any class of its own, so nothing here has to be loaded against its classes. What does have to be
 * — a check of its own a piece makes, which a preview has to answer differently — is done by the
 * mixins in {@code explorerscompass.twilightforest.mixins.json}, which are applied only for versions
 * of it they were written against: see {@link TwilightForestMixinPlugin}.
 */
public final class TwilightForestCompat {

	public static final String MOD_ID = "twilightforest";

	private TwilightForestCompat() {
	}

	public static void init() {
		PreviewCompat.registerLayeredPieces(TwilightForestCompat::isTwilightForestPiece);
	}

	private static boolean isTwilightForestPiece(StructurePiece piece) {
		final ResourceLocation type = BuiltInRegistries.STRUCTURE_PIECE.getKey(piece.getType());
		return type != null && MOD_ID.equals(type.getNamespace());
	}

}
