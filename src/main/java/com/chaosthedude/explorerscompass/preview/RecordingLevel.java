package com.chaosthedude.explorerscompass.preview;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;

/**
 * A world that only remembers what is put into it.
 *
 * <p>Most of the older structures — strongholds, mineshafts, monuments, nether fortresses, the
 * desert and jungle temples — have no template on disk. They are written as code that lays itself
 * out block by block while a chunk is being generated, so the only way to find out what one looks
 * like is to let it build itself and watch where the blocks go. That is what this is for: a piece is
 * handed this instead of the world, and every block it places is taken down rather than placed.
 *
 * <p><b>Nothing here reads or writes the world.</b> Reads are answered out of what has been taken
 * down so far, out of air above the structure and stone below it. That matters for two reasons
 * beyond safety: asking the real world would load and generate chunks on the server thread, and a
 * structure built against whatever happens to stand at the world origin is a picture of that spot
 * rather than of the structure.
 *
 * <p>The one thing that does reach the real level is {@link #getLevel}, which has to answer with a
 * server level because its type says so. Structure pieces are handed this interface and build
 * through it; a piece that went around it to write through the level it names would be writing into
 * an ungenerated chunk during ordinary world generation too, so nothing does.
 */
class RecordingLevel implements WorldGenLevel {

	/** What stands in for the ground the structure is built into, below where it begins. */
	private static final BlockState GROUND = Blocks.STONE.defaultBlockState();

	private final ServerLevel level;
	/** Where blocks are taken down, packed position to block state id. */
	private final Long2IntOpenHashMap recorded = new Long2IntOpenHashMap();
	/** Blocks outside this are dropped, so that a piece reaching out of the structure cannot grow it. */
	private final BoundingBox limit;
	/** Where the structure begins; everything under it reads as ground rather than as open air. */
	private final int groundY;
	private final int maxBlocks;
	private final RandomSource random;
	private final BiomeManager biomeManager;
	private ProtoChunk scratchChunk;
	private boolean full;

	RecordingLevel(ServerLevel level, BoundingBox limit, int maxBlocks, long seed) {
		this.level = level;
		this.limit = limit;
		this.maxBlocks = maxBlocks;
		groundY = limit.minY();
		random = RandomSource.create(seed);
		// Routed back through this level, so that asking which biome is somewhere is answered from the
		// generator's noise rather than from a chunk that would have to be generated to answer
		biomeManager = level.getBiomeManager().withDifferentSource(this);
		recorded.defaultReturnValue(-1);
	}

	/** Takes a block down. Answers whether there is still room for more. */
	boolean record(BlockPos pos, BlockState state) {
		if (full || !limit.isInside(pos)) {
			return !full;
		}
		if (recorded.size() >= maxBlocks) {
			full = true;
			return false;
		}
		// The first block to reach a position wins, the way an earlier piece is not overwritten by a
		// later one standing in the same space
		recorded.putIfAbsent(pos.asLong(), Block.getId(state));
		return true;
	}

	/** Everything taken down so far, packed position to block state id. Shared; do not modify. */
	Long2IntOpenHashMap getRecorded() {
		return recorded;
	}

	int getRecordedCount() {
		return recorded.size();
	}

	/** Whether the ceiling on how much of one structure is read has been reached. */
	boolean isFull() {
		return full;
	}

	// The whole point: writes are taken down and go no further

	@Override
	public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
		record(pos, state);
		return true;
	}

	@Override
	public boolean removeBlock(BlockPos pos, boolean isMoving) {
		record(pos, Blocks.AIR.defaultBlockState());
		return true;
	}

	@Override
	public boolean destroyBlock(BlockPos pos, boolean dropBlock, Entity entity, int recursionLeft) {
		record(pos, Blocks.AIR.defaultBlockState());
		return true;
	}

	// Reads are answered out of what was taken down, out of air above the structure, and out of
	// ground below it, so that a piece meant to be dug into the ground still has something to dig into

	@Override
	public BlockState getBlockState(BlockPos pos) {
		final int stateId = recorded.get(pos.asLong());
		if (stateId >= 0) {
			return Block.stateById(stateId);
		}
		return pos.getY() < groundY ? GROUND : Blocks.AIR.defaultBlockState();
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return getBlockState(pos).getFluidState();
	}

	@Override
	public BlockEntity getBlockEntity(BlockPos pos) {
		return null;
	}

	@Override
	public <T extends BlockEntity> Optional<T> getBlockEntity(BlockPos pos, BlockEntityType<T> type) {
		return Optional.empty();
	}

	@Override
	public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) {
		return predicate.test(getBlockState(pos));
	}

	@Override
	public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) {
		return predicate.test(getFluidState(pos));
	}

	@Override
	public int getHeight(Heightmap.Types type, int x, int z) {
		// Flat ground at the height the structure begins at, which is the ground it was placed against
		return groundY;
	}

	@Override
	public BlockPos getHeightmapPos(Heightmap.Types type, BlockPos pos) {
		return new BlockPos(pos.getX(), groundY, pos.getZ());
	}

	/**
	 * A chunk that exists only to be written off. Placing a fence or a torch has the piece mark its
	 * position on the chunk for a later pass, and there has to be something there to mark. What is
	 * marked on this one is never read.
	 */
	@Override
	public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean requireChunk) {
		if (scratchChunk == null) {
			final Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
			scratchChunk = new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY, level, biomes, null);
		}
		return scratchChunk;
	}

	@Override
	public boolean hasChunk(int x, int z) {
		return true;
	}

	@Override
	public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ) {
		return getUncachedNoiseBiome(quartX, quartY, quartZ);
	}

	@Override
	public Holder<Biome> getUncachedNoiseBiome(int quartX, int quartY, int quartZ) {
		// Straight from the generator's noise, which reads no part of the world
		return level.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(quartX, quartY, quartZ, level.getChunkSource().randomState().sampler());
	}

	@Override
	public BiomeManager getBiomeManager() {
		return biomeManager;
	}

	@Override
	public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
		// Built rather than asked for: the real answer reads how long a chunk has been inhabited
		return new DifficultyInstance(level.getDifficulty(), 0L, 0L, 0.0F);
	}

	// Everything that follows is either the level's own answer, where giving it reads nothing, or the
	// emptiest answer the caller will accept

	@Override
	public ServerLevel getLevel() {
		return level;
	}

	@Override
	public MinecraftServer getServer() {
		return level.getServer();
	}

	@Override
	public RegistryAccess registryAccess() {
		return level.registryAccess();
	}

	@Override
	public ChunkSource getChunkSource() {
		return level.getChunkSource();
	}

	@Override
	public LevelData getLevelData() {
		return level.getLevelData();
	}

	@Override
	public DimensionType dimensionType() {
		return level.dimensionType();
	}

	@Override
	public WorldBorder getWorldBorder() {
		return level.getWorldBorder();
	}

	@Override
	public LevelLightEngine getLightEngine() {
		return level.getLightEngine();
	}

	@Override
	public long getSeed() {
		return level.getSeed();
	}

	@Override
	public int getSeaLevel() {
		return level.getSeaLevel();
	}

	@Override
	public int getMinBuildHeight() {
		return level.getMinBuildHeight();
	}

	@Override
	public int getHeight() {
		return level.getHeight();
	}

	@Override
	public RandomSource getRandom() {
		return random;
	}

	@Override
	public boolean isClientSide() {
		return false;
	}

	@Override
	public int getSkyDarken() {
		return 0;
	}

	@Override
	public long dayTime() {
		return 0L;
	}

	@Override
	public long nextSubTickCount() {
		return 0L;
	}

	@Override
	public LevelTickAccess<Block> getBlockTicks() {
		return BlackholeTickAccess.emptyLevelList();
	}

	@Override
	public LevelTickAccess<Fluid> getFluidTicks() {
		return BlackholeTickAccess.emptyLevelList();
	}

	@Override
	public List<? extends Player> players() {
		return List.of();
	}

	@Override
	public List<Entity> getEntities(Entity entity, AABB area, Predicate<? super Entity> predicate) {
		return List.of();
	}

	@Override
	public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> typeTest, AABB area, Predicate<? super T> predicate) {
		return List.of();
	}

	@Override
	public List<VoxelShape> getEntityCollisions(Entity entity, AABB area) {
		return List.of();
	}

	@Override
	public net.minecraft.world.level.BlockGetter getChunkForCollisions(int chunkX, int chunkZ) {
		return null;
	}

	@Override
	public float getShade(Direction direction, boolean shade) {
		return 1.0F;
	}

	@Override
	public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
		return 0;
	}

	@Override
	public void playSound(Player player, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch) {
	}

	@Override
	public void addParticle(ParticleOptions particle, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed) {
	}

	@Override
	public void levelEvent(Player player, int type, BlockPos pos, int data) {
	}

	@Override
	public void gameEvent(GameEvent event, Vec3 position, GameEvent.Context context) {
	}

}
