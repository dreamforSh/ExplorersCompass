package com.chaosthedude.explorerscompass.worker;

import java.util.List;

import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

public class GenericSearchWorker extends StructureSearchWorker<StructurePlacement> {

	public int chunkX;
	public int chunkZ;
	public int length;
	public double nextLength;
	public Direction direction;

	private int startChunkX;
	private int startChunkZ;

	public GenericSearchWorker(ServerLevel level, Player player, ItemStack stack, BlockPos startPos, StructurePlacement placement, List<Structure> structureSet, List<BlockPos> prevPos, boolean isGroup, boolean ignoreNearStart, SearchWorkerManager manager) {
		super(level, player, stack, startPos, placement, structureSet, prevPos, isGroup, ignoreNearStart, manager);
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
		if (hasWork()) {
			if (direction == Direction.NORTH) {
				chunkZ--;
			} else if (direction == Direction.EAST) {
				chunkX++;
			} else if (direction == Direction.SOUTH) {
				chunkZ++;
			} else if (direction == Direction.WEST) {
				chunkX--;
			}

			ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
			// The corners of the spiral reach past its edges, so part of the outer rings lies beyond
			// the configured radius
			if (isWithinMaxRadius(chunkPos)) {
				currentPos = chunkPos.getMiddleBlockPosition(0);

				Pair<BlockPos, Structure> pair = getStructureGeneratingAt(chunkPos);
				samples++;
				if (pair != null) {
					succeed(pair.getFirst(), pair.getSecond());
				}
			}

			length++;
			if (length >= (int)nextLength) {
				if (direction != Direction.UP) {
					nextLength += 0.5;
					direction = direction.getClockWise();
				} else {
					direction = Direction.NORTH;
				}
				length = 0;
			}
		}

		if (hasWork()) {
			return true;
		}

		if (!finished) {
			endOfWork();
		}

		// Handing back can widen what this worker may search, in which case it carries straight on
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
