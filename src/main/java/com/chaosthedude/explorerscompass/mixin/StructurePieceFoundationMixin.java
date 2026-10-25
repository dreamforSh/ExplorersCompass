package com.chaosthedude.explorerscompass.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.chaosthedude.explorerscompass.preview.PreviewLevel;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;

/**
 * Lets a structure preview hear what a piece fills its foundation down into the ground with.
 *
 * <p><b>What is changed.</b> {@code fillColumnDown} tells a {@link PreviewLevel} it is handed
 * where the column starts and what it is filled with, before filling it exactly as it otherwise
 * would. Handed any other world, it does nothing more than it did.
 *
 * <p><b>Why a mixin.</b> The fill asks the world whether there is room before placing anything, and
 * a preview stands a structure on ground that is already there, so the fill answers no and places
 * nothing. What it would have placed is an argument to the method and nowhere else: neither the
 * world it is handed nor anything the piece keeps hears of it.
 *
 * <p><b>Why this injector.</b> The fill is to run unchanged, and only its arguments are wanted, so
 * the smallest thing that does it is an inject at the head that cancels nothing.
 *
 * <p><b>Compatibility.</b> This sits on a method world generation runs while placing structures, so
 * it costs one type check of the world it is handed whenever it is not a preview's.
 */
@Mixin(StructurePiece.class)
abstract class StructurePieceFoundationMixin {

	@Shadow
	protected abstract BlockPos.MutableBlockPos getWorldPos(int x, int y, int z);

	@Inject(method = "fillColumnDown", at = @At("HEAD"), require = 1)
	private void explorerscompass$recordFoundation(WorldGenLevel level, BlockState state, int x, int y, int z, BoundingBox box, CallbackInfo callback) {
		if (level instanceof PreviewLevel previewLevel) {
			final BlockPos pos = getWorldPos(x, y, z);
			// The fill only starts inside the space it is handed, so the column is only noted there
			if (box.isInside(pos)) {
				previewLevel.recordFoundation(pos, state);
			}
		}
	}

}
