package com.chaosthedude.explorerscompass.worker;

import java.util.List;

import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

public class RandomSpreadSearchWorker extends StructureSearchWorker<RandomSpreadStructurePlacement> {

	private int spacing;
	private int length;
	private int startSectionPosX;
	private int startSectionPosZ;
	private int x;
	private int z;

	public RandomSpreadSearchWorker(ServerLevel level, Player player, ItemStack stack, BlockPos startPos, RandomSpreadStructurePlacement placement, List<Structure> structureSet, List<BlockPos> prevPos, boolean isGroup, boolean ignoreNearStart, String managerId) {
		super(level, player, stack, startPos, placement, structureSet, prevPos, isGroup, ignoreNearStart, managerId);

		spacing = placement.spacing();
		startSectionPosX = SectionPos.blockToSectionCoord(startPos.getX());
		startSectionPosZ = SectionPos.blockToSectionCoord(startPos.getZ());
		x = 0;
		z = 0;
		length = 0;
	}

	@Override
	protected boolean doSample() {
		if (hasWork()) {
			int sampleX = startSectionPosX + (spacing * x);
			int sampleZ = startSectionPosZ + (spacing * z);

			ChunkPos chunkPos = placement.getPotentialStructureChunk(seed, sampleX, sampleZ);
			// The corners of a ring reach past its edges, so part of the outer rings lies beyond the
			// configured radius
			if (isWithinMaxRadius(chunkPos)) {
				currentPos = chunkPos.getMiddleBlockPosition(0);

				Pair<BlockPos, Structure> pair = getStructureGeneratingAt(chunkPos);
				samples++;
				if (pair != null) {
					succeed(pair.getFirst(), pair.getSecond());
				}
			}

			advanceToNextRingCell();
		}

		if (hasWork()) {
			return true;
		}

		if (!finished) {
			fail();
		}

		return false;
	}

	/**
	 * Moves to the next cell on the ring being walked, growing to the next ring once it is done.
	 *
	 * <p>Only cells on the ring itself are visited. The cells it encloses were covered by the
	 * smaller rings before it, and stepping over them instead of walking them matters: a ring of the
	 * given length has about 8 * length cells on it, but encloses roughly 4 * length * length of
	 * them.
	 *
	 * <p>Both coordinates have to be reset to -length using the length of the ring they are about to
	 * walk, otherwise x never reaches -length and the entire western column of every ring is
	 * skipped.
	 */
	private void advanceToNextRingCell() {
		if (x == -length || x == length) {
			// Every cell of the westmost and eastmost rows is on the ring
			z++;
		} else {
			// Of the rows in between, only the two ends are
			z = z < length ? length : length + 1;
		}

		if (z > length) {
			x++;
			z = -length;
			if (x > length) {
				length++;
				x = -length;
				z = -length;
			}
		}
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
		// A cell of this ring is at least length - 1 cells away from the one the search started in, so
		// everything closer than that has been covered by the rings already walked. Capping this at the
		// configured radius keeps the reported value from overshooting it, and still ends the search on
		// the ring that reaches it.
		final int covered = Math.max(0, SectionPos.sectionToBlockCoord((length - 1) * spacing));
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
