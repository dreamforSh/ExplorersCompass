package com.chaosthedude.explorerscompass.gui;

import com.chaosthedude.explorerscompass.preview.StructurePreview;

import it.unimi.dsi.fastutil.ints.Int2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * A preview, read the way the block renderer reads a chunk of the world.
 *
 * <p>The renderer that draws the blocks of a chunk wants to be handed the world around each block:
 * it asks what stands on every side of one to leave out the faces nothing could see, reads the
 * corners around each face to darken them where they are tucked in, and asks the biome what colour
 * the grass and the leaves are. This answers all of those out of the preview instead, so that the
 * model of a structure comes out shaded and coloured exactly as the structure would in the world.
 *
 * <p>Light is answered as full daylight everywhere: a preview is drawn in an inventory's light, and
 * the shading the renderer works out from what surrounds a block is what gives it depth. The
 * biome the colours are taken from is fixed when the getter is made, so that nothing here has to
 * reach back into the world from the thread the model is built on.
 *
 * <p>Cells above the cut, where the structure has been opened up to look inside, read as air, so
 * that the faces the cut exposes are drawn and everything above it is left out.
 */
@OnlyIn(Dist.CLIENT)
final class PreviewBlockGetter implements BlockAndTintGetter {

	/** What water shows as where no biome is to hand: the colour the plains give it. */
	private static final int DEFAULT_WATER_COLOR = 0x3F76E4;

	private final StructurePreview preview;
	private final int layerLimit;
	/** Every cell that holds something, packed cell to block state id: the shell, the walled-in middle and the chests. */
	private final Int2IntOpenHashMap states;
	/**
	 * The cells among those whose block hides the whole of any face laid against it. Worked out once
	 * here rather than on every one of the many millions of times the shading asks, since each asking
	 * would otherwise be a registry lookup on top of the hash lookup.
	 */
	private final IntOpenHashSet occluding;
	private final Biome biome;

	PreviewBlockGetter(StructurePreview preview, int layerLimit, Biome biome) {
		this.preview = preview;
		this.layerLimit = layerLimit;
		this.biome = biome;
		final int filled = preview.getCellCount() + preview.getInteriorCellCount() + preview.getComponentCount();
		states = new Int2IntOpenHashMap(filled);
		states.defaultReturnValue(-1);
		occluding = new IntOpenHashSet(filled);
		final Int2BooleanOpenHashMap solidByState = new Int2BooleanOpenHashMap();

		for (int cell = 0; cell < preview.getCellCount(); cell++) {
			put(preview.getCellPosition(cell), preview.getPaletteStateId(preview.getCellPaletteIndex(cell)), solidByState);
		}
		for (int run = 0; run < preview.getInteriorRunCount(); run++) {
			final int start = preview.getInteriorRunStart(run);
			final int stateId = preview.getPaletteStateId(preview.getInteriorRunPaletteIndex(run));
			for (int offset = 0; offset < preview.getInteriorRunLength(run); offset++) {
				// A run is consecutive packed positions, which along a row is consecutive cells
				put(start + offset, stateId, solidByState);
			}
		}
		for (int component = 0; component < preview.getComponentCount(); component++) {
			put(preview.getComponentPosition(component), preview.getComponentStateId(component), solidByState);
		}
	}

	private void put(int packed, int stateId, Int2BooleanOpenHashMap solidByState) {
		states.put(packed, stateId);
		if (!solidByState.containsKey(stateId)) {
			// Answered off the state's own cache, which every state has once the registries are frozen
			solidByState.put(stateId, Block.stateById(stateId).isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
		}
		if (solidByState.get(stateId)) {
			occluding.add(packed);
		}
	}

	/** How many layers are drawn, counted from the ground up. */
	int getLayerLimit() {
		return layerLimit;
	}

	/** Whether the given cell is inside the grid and below the cut. */
	boolean isShown(int x, int y, int z) {
		return x >= 0 && y >= 0 && z >= 0 && x < preview.getGridX() && y < layerLimit && y < preview.getGridY() && z < preview.getGridZ();
	}

	/** The state id at the given cell, or -1 where there is nothing, which covers everything above the cut. */
	int getStateId(int x, int y, int z) {
		if (!isShown(x, y, z)) {
			return -1;
		}
		return states.get(StructurePreview.pack(x, y, z));
	}

	/** Whether the given cell holds a block that hides the whole of any face laid against it. */
	boolean isOccluding(int x, int y, int z) {
		return isShown(x, y, z) && occluding.contains(StructurePreview.pack(x, y, z));
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		final int stateId = getStateId(pos.getX(), pos.getY(), pos.getZ());
		return stateId < 0 ? Blocks.AIR.defaultBlockState() : Block.stateById(stateId);
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return getBlockState(pos).getFluidState();
	}

	@Override
	public BlockEntity getBlockEntity(BlockPos pos) {
		return null;
	}

	/**
	 * How much of its brightness a face keeps for the way it points, which is what the world does to
	 * every block in it: tops lit in full, undersides at half, and the sides in between. It is what
	 * lets a plain cube read as a cube.
	 */
	@Override
	public float getShade(Direction direction, boolean shade) {
		if (!shade) {
			return 1.0F;
		}
		switch (direction) {
			case DOWN:
				return 0.5F;
			case UP:
				return 1.0F;
			case NORTH:
			case SOUTH:
				return 0.8F;
			case WEST:
			case EAST:
				return 0.6F;
			default:
				return 1.0F;
		}
	}

	/** Never consulted: both readings of light are answered below without one. */
	@Override
	public LevelLightEngine getLightEngine() {
		return null;
	}

	@Override
	public int getBrightness(LightLayer lightType, BlockPos pos) {
		return getMaxLightLevel();
	}

	@Override
	public int getRawBrightness(BlockPos pos, int amount) {
		return getMaxLightLevel();
	}

	@Override
	public boolean canSeeSky(BlockPos pos) {
		return true;
	}

	/**
	 * The colour the biome gives the grass, the leaves and the water. Resolved against one biome for
	 * the whole preview rather than averaged over neighbours the way the world does: a structure is
	 * being shown as itself, not as it would weather into one patch of ground.
	 */
	@Override
	public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
		if (biome != null) {
			return colorResolver.getColor(biome, pos.getX(), pos.getZ());
		}
		if (colorResolver == BiomeColors.GRASS_COLOR_RESOLVER) {
			// The middle of the grass colour map, which is what later versions call its default
			return GrassColor.get(0.5D, 1.0D);
		}
		if (colorResolver == BiomeColors.FOLIAGE_COLOR_RESOLVER) {
			return FoliageColor.getDefaultColor();
		}
		if (colorResolver == BiomeColors.WATER_COLOR_RESOLVER) {
			return DEFAULT_WATER_COLOR;
		}
		return -1;
	}

	@Override
	public int getMinBuildHeight() {
		return 0;
	}

	@Override
	public int getHeight() {
		return StructurePreview.MAX_GRID;
	}

}
