package com.chaosthedude.explorerscompass.worker;

import java.util.List;

import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

public class RandomSpreadSearchWorker extends StructureSearchWorker<RandomSpreadStructurePlacement> {

	private final RingWalker ring = new RingWalker();
	private final int spacing;
	private final int startSectionPosX;
	private final int startSectionPosZ;

	public RandomSpreadSearchWorker(SearchContext context, RandomSpreadStructurePlacement placement, List<Structure> structureSet) {
		super(context, placement, structureSet);

		spacing = placement.spacing();
		startSectionPosX = SectionPos.blockToSectionCoord(startPos.getX());
		startSectionPosZ = SectionPos.blockToSectionCoord(startPos.getZ());
	}

	@Override
	protected boolean doSample() {
		final int sampleX = startSectionPosX + (spacing * ring.getX());
		final int sampleZ = startSectionPosZ + (spacing * ring.getZ());

		final ChunkPos chunkPos = placement.getPotentialStructureChunk(seed, sampleX, sampleZ);
		// The corners of a ring reach past its edges, so part of the outer rings lies beyond the
		// configured radius, and beyond anything already located
		if (isWorthSampling(chunkPos)) {
			currentPos = chunkPos.getMiddleBlockPosition(0);

			final Pair<BlockPos, Structure> pair = getStructureGeneratingAt(chunkPos);
			samples++;
			if (pair != null) {
				found(pair.getFirst(), pair.getSecond());
			}
		}

		ring.advance();
		return hasWork();
	}

	/**
	 * The radius this worker has finished searching, which is what bounds the search and what the
	 * compass reports.
	 *
	 * <p>Deliberately not the distance to the location sampled last. The cells of a ring sit between
	 * length and length times the square root of two steps away from the one the search started in,
	 * and they are walked row by row, so the first cell of a ring is one of its corners. Bounding
	 * the search by the last location sampled therefore ends it as soon as a corner passes the
	 * configured radius, leaving everything between that ring and the configured radius unsearched.
	 */
	@Override
	protected int getRadius() {
		// Capping this at the configured radius keeps the reported value from overshooting it, and
		// still ends the search on the ring that reaches it
		final int covered = SectionPos.sectionToBlockCoord(ring.getCoveredLength() * spacing);
		return Math.min(covered, maxRadius);
	}

	@Override
	protected String getName() {
		return "RandomSpreadSearchWorker";
	}

	@Override
	public boolean shouldLogRadius() {
		return true;
	}

}
