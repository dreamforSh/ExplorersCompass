package com.chaosthedude.explorerscompass.worker;

import java.util.List;

import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

public class GenericSearchWorker extends StructureSearchWorker<StructurePlacement> {

	private final int startChunkX;
	private final int startChunkZ;

	// Where the spiral has reached, and how far along the side of it the next step is. Unlike the
	// placements that only put a structure on a grid of their own, any chunk here can hold one, so
	// this walks every chunk rather than only the ones on each ring.
	private int chunkX;
	private int chunkZ;
	private int length;
	private double nextLength;
	private Direction direction;

	public GenericSearchWorker(SearchContext context, StructurePlacement placement, List<Structure> structureSet) {
		super(context, placement, structureSet);

		startChunkX = SectionPos.blockToSectionCoord(startPos.getX());
		startChunkZ = SectionPos.blockToSectionCoord(startPos.getZ());
		chunkX = startChunkX;
		chunkZ = startChunkZ;
		nextLength = 1;
		length = 0;
		direction = Direction.UP;
	}

	@Override
	protected boolean doSample() {
		if (direction == Direction.NORTH) {
			chunkZ--;
		} else if (direction == Direction.EAST) {
			chunkX++;
		} else if (direction == Direction.SOUTH) {
			chunkZ++;
		} else if (direction == Direction.WEST) {
			chunkX--;
		}

		final ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
		// The corners of the spiral reach past its edges, so part of the outer rings lies beyond the
		// configured radius, and beyond anything already located
		if (isWorthSampling(chunkPos)) {
			currentPos = chunkPos.getMiddleBlockPosition(0);

			final Pair<BlockPos, Structure> pair = getStructureGeneratingAt(chunkPos);
			samples++;
			if (pair != null) {
				found(pair.getFirst(), pair.getSecond());
			}
		}

		length++;
		if (length >= (int) nextLength) {
			if (direction != Direction.UP) {
				nextLength += 0.5;
				direction = direction.getClockWise();
			} else {
				direction = Direction.NORTH;
			}
			length = 0;
		}

		return hasWork();
	}

	/**
	 * The radius this worker has finished searching, which is what bounds the search and what the
	 * compass reports. The spiral walks a full ring of chunks before starting the next, and a chunk
	 * on the ring it is on is at least one ring closer than the ring itself, so everything nearer
	 * than that has already been sampled. See the note on the same method in
	 * {@link RandomSpreadSearchWorker} for why the location sampled last cannot be used instead.
	 */
	@Override
	protected int getRadius() {
		final int ring = Math.max(Math.abs(chunkX - startChunkX), Math.abs(chunkZ - startChunkZ));
		final int covered = Math.max(0, SectionPos.sectionToBlockCoord(ring - 1));
		return Math.min(covered, maxRadius);
	}

	@Override
	protected String getName() {
		return "GenericSearchWorker";
	}

	@Override
	public boolean shouldLogRadius() {
		return true;
	}

}
