package com.chaosthedude.explorerscompass.preview;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The world a structure is built into for a preview rather than for real, which code that runs
 * while a structure builds can tell apart from the world by asking whether it is one of these.
 *
 * <p>It also wants to know what a structure piece fills its foundation down into the ground with,
 * including where the ground is already there and nothing ends up being filled at all. A piece that
 * does this is saying what stands under it in the world, whatever the ground there turns out to be:
 * the desert temple fills sandstone down under every column of itself. A preview is built against
 * ground that is already there, so the fill never places anything, and this is the only way left to
 * hear what it would have been. Told by the piece itself, through
 * {@code StructurePieceFoundationMixin}.
 */
public interface PreviewLevel {

	/** Notes that a piece fills its foundation down from the given position with the given block. */
	void recordFoundation(BlockPos pos, BlockState state);

}
