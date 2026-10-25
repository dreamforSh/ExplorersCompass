package com.chaosthedude.explorerscompass.preview;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import net.minecraft.world.level.levelgen.structure.StructurePiece;

/**
 * What another mod's compatibility code has to say about how the pieces of that mod's structures
 * are read for a preview, where they are not built the way the game's own are. Registered by that
 * code when, and only when, the mod is loaded, so that nothing here knows of any mod in particular.
 */
public final class PreviewCompat {

	/** Which pieces build in layers: see {@link #registerLayeredPieces}. */
	private static final List<Predicate<StructurePiece>> layeredPieces = new CopyOnWriteArrayList<Predicate<StructurePiece>>();

	private PreviewCompat() {
	}

	/**
	 * Has the pieces the given test picks out read as building in layers. Such a piece lays itself out
	 * over whatever the pieces before it laid, the way world generation places one piece after another,
	 * rather than leaving their blocks alone; and one that adds nothing of its own is left out rather
	 * than drawn as the box it occupies, since it has either laid itself over what was already there or
	 * decided there was nothing to lay, and an outline would be wrong either way.
	 *
	 * <p>Meant for a mod whose structures are a main piece decorated by many small ones: a maze whose
	 * dead ends each put a fountain or a chest into a corridor the maze already carved.
	 */
	public static void registerLayeredPieces(Predicate<StructurePiece> pieces) {
		layeredPieces.add(pieces);
	}

	/** Whether the given piece builds in layers: see {@link #registerLayeredPieces}. */
	static boolean isLayered(StructurePiece piece) {
		for (Predicate<StructurePiece> pieces : layeredPieces) {
			if (pieces.test(piece)) {
				return true;
			}
		}
		return false;
	}

}
