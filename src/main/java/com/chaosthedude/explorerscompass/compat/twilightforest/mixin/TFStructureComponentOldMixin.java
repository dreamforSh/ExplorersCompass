package com.chaosthedude.explorerscompass.compat.twilightforest.mixin;

import java.util.function.Predicate;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.chaosthedude.explorerscompass.preview.PreviewLevel;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;

/**
 * Lets the pieces of The Twilight Forest that only build in a biome of their own build in a preview.
 *
 * <p><b>What is changed.</b> {@code isBoundingBoxOutsideBiomes}, which a piece asks before building
 * whether every column around it lies in the biomes it wants, answers that none lies outside them
 * when the world it is asked of is a structure preview's. Asked of any other world, it answers
 * exactly as it did.
 *
 * <p><b>Why.</b> A preview assembles a structure near the world origin rather than where it really
 * generates, so the biomes there are whatever the origin holds: the final castle's dungeon rooms ask
 * for the plateau the castle stands on and the troll caves' gardens for the highlands, find neither,
 * and build nothing, leaving their castle and their caves with rooms missing. Which biome a piece
 * stands in is a question about one instance of a structure; a preview is of the structure.
 *
 * <p><b>Why this injector.</b> The answer has to be given instead of the method running, so the
 * smallest thing that does it is a cancellable inject at the head.
 *
 * <p><b>Compatibility.</b> Named by string, so that nothing here is loaded against a class of The
 * Twilight Forest's, and applied only for versions of it it was written against: see
 * {@code TwilightForestMixinPlugin}.
 */
@Mixin(targets = "twilightforest.world.components.structures.TFStructureComponentOld")
abstract class TFStructureComponentOldMixin {

	@Inject(method = "isBoundingBoxOutsideBiomes", at = @At("HEAD"), cancellable = true, require = 1)
	private void explorerscompass$acceptAnyBiomeInAPreview(WorldGenLevel world, Predicate<Biome> predicate, BlockPos structurePos, CallbackInfoReturnable<Boolean> callback) {
		if (world instanceof PreviewLevel) {
			callback.setReturnValue(Boolean.FALSE);
		}
	}

}
