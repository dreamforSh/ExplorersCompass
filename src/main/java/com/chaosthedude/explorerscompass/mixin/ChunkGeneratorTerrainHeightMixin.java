package com.chaosthedude.explorerscompass.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.chaosthedude.explorerscompass.worker.TerrainHeightCache;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Lets a search work out how high the ground reaches at a column once, rather than once for every
 * structure it asks about that location.
 *
 * <p><b>What is changed.</b> {@code getFirstOccupiedHeight} answers out of what the search looking at
 * this location has already worked out, when it has.
 *
 * <p><b>Why a mixin.</b> This is where every structure asks how high the ground is — the helpers
 * they are all built on ({@code onTopOfChunkCenter}, {@code getLowestY} and the box variants of it)
 * all come through here. A search cannot get between them and it: what it hands a structure is a
 * generation context, and the generator in one is the level's own. Opening something up would not
 * help, because there is nothing to open — the repetition is inside code that has to be run as it is.
 *
 * <p><b>Why this injector.</b> The answer has to be given instead of the method running, so the
 * smallest thing that does it is a cancellable inject at the head, paired with one at the return to
 * take note of what running it produced. Nothing chainable can express "do not run this at all".
 *
 * <p><b>Compatibility.</b> This sits on a method the whole game uses while generating chunks, so it
 * is written to cost as close to nothing as possible when it is not wanted: a search that is not
 * running is a single field read, and a thread that is not one of a search's own finds nothing held
 * for it. What it answers with is the same value the method would have returned — the height of a
 * column follows from the seed and the column alone — so anything else injecting here sees what it
 * would have seen. See {@link TerrainHeightCache} for how it is kept to one location at a time.
 */
@Mixin(ChunkGenerator.class)
abstract class ChunkGeneratorTerrainHeightMixin {

	// There is one getFirstOccupiedHeight, so the name alone names it; require = 1 is what says so
	@Inject(method = "getFirstOccupiedHeight", at = @At("HEAD"), cancellable = true, require = 1)
	private void explorerscompass$answerFromTheLocationBeingSearched(int blockX, int blockZ, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState, CallbackInfoReturnable<Integer> callback) {
		final int height = TerrainHeightCache.get(blockX, blockZ, type);
		if (height != TerrainHeightCache.UNKNOWN) {
			callback.setReturnValue(Integer.valueOf(height));
		}
	}

	@Inject(method = "getFirstOccupiedHeight", at = @At("RETURN"), require = 1)
	private void explorerscompass$rememberForTheLocationBeingSearched(int blockX, int blockZ, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState, CallbackInfoReturnable<Integer> callback) {
		TerrainHeightCache.put(blockX, blockZ, type, callback.getReturnValueI());
	}

}
