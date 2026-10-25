package com.chaosthedude.explorerscompass.preview;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
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
 * <p>A structure that generates underground is answered differently: everything inside its bounds
 * that has not been taken down reads as stone, since that is what surrounds it in the world. The
 * older underground pieces lean on this. A stronghold only carves its rooms out of blocks that are
 * already there, and a mineshaft only furnishes a corridor it can tell is below ground, so handing
 * either of them open air answers with a stronghold that is all doors and no walls and a mineshaft
 * with none of its chests.
 *
 * <p>Within a piece the last block to reach a position is the one that stands there, as it is in
 * the world. Pieces lay themselves out in layers: the desert temple lays a solid foundation and then
 * carves its treasure room out of it and puts its chests in, and the ocean monument floods its whole
 * box with water and then builds its walls through it. Each later write has to win over the earlier
 * one, or the room is never carved, the chests never stand anywhere and the monument is nothing but
 * water. Across pieces it is the other way round: a block placed where an earlier piece already put
 * one is dropped, so that a later piece cannot build over an earlier one, unless what stands there is
 * only air or water, which is nothing to win with. A piece another mod's compatibility code says builds
 * in layers is let build over earlier pieces as it would in the world: see
 * {@link PreviewCompat#registerLayeredPieces}.
 *
 * <p>The one thing that does reach the real level is {@link #getLevel}, which has to answer with a
 * server level because its type says so. Structure pieces are handed this interface and build
 * through it; a piece that went around it to write through the level it names would be writing into
 * an ungenerated chunk during ordinary world generation too, so nothing does.
 */
class RecordingLevel implements WorldGenLevel, PreviewLevel {

	/** What stands in for the ground the structure is built into, below where it begins. */
	private static final BlockState GROUND = Blocks.STONE.defaultBlockState();

	private final ServerLevel level;
	/** Where blocks are taken down, packed position to block state id. */
	private final Long2IntOpenHashMap recorded = new Long2IntOpenHashMap();
	/**
	 * The block entities that went with those blocks, so that a piece which then writes a loot table
	 * onto a chest has somewhere to write it. Absent for anything that is not an entity block.
	 */
	private final Long2ObjectOpenHashMap<BlockEntity> blockEntities = new Long2ObjectOpenHashMap<BlockEntity>();
	/**
	 * The containers that are entities rather than blocks — the chest minecarts a mineshaft parks
	 * in its corridors — which a piece adds to the world instead of placing. Nothing else that a
	 * piece spawns is kept.
	 */
	private final List<ContainerEntity> containerEntities = new ArrayList<ContainerEntity>();
	/**
	 * Where pieces fill their foundations down into the ground from, packed position to the block
	 * state id they fill with, whether or not the ground left room to fill anything.
	 */
	private final Long2IntOpenHashMap foundations = new Long2IntOpenHashMap();
	/** Which piece took each position down, packed position to the number {@link #beginPiece} gave it. */
	private final Long2IntOpenHashMap owners = new Long2IntOpenHashMap();
	/** The piece whose blocks are being taken down now; 0 before any piece has begun. */
	private int currentPiece;
	/** Whether the piece being taken down now builds over what earlier pieces laid. */
	private boolean layered;
	/**
	 * Blocks outside this are dropped, so that a piece reaching out of the structure cannot grow it
	 * without end. It runs some way below the structure's own bounds, since the bounds world generation
	 * gives a structure are worked out before its pieces build, and some pieces then sink themselves
	 * into the ground they find and build down from there: the desert temple's treasure room and the
	 * jungle temple's chests both stand below the bounds the temple was given.
	 */
	private final BoundingBox limit;
	/** Where the structure begins; everything under it reads as ground rather than as open air. */
	private final int groundY;
	/** Where the structure ends at the top, which is the surface for one that is buried. */
	private final int topY;
	/** Whether the structure generates underground, so that everything inside its bounds reads as ground too. */
	private final boolean buried;
	private final int maxBlocks;
	private final RandomSource random;
	private final BiomeManager biomeManager;
	private ProtoChunk scratchChunk;
	private boolean full;

	RecordingLevel(ServerLevel level, BoundingBox limit, int maxBlocks, long seed) {
		this(level, limit, maxBlocks, seed, false);
	}

	RecordingLevel(ServerLevel level, BoundingBox limit, int maxBlocks, long seed, boolean buried) {
		this(level, limit, limit, maxBlocks, seed, buried);
	}

	/**
	 * @param structureBounds the bounds world generation gave the structure, which say where the
	 *                        ground is: under them for a structure that stands on it, and all through
	 *                        them for one that is buried
	 * @param limit           the space blocks are taken down in, which may reach past those bounds
	 */
	RecordingLevel(ServerLevel level, BoundingBox structureBounds, BoundingBox limit, int maxBlocks, long seed, boolean buried) {
		this.level = level;
		this.limit = limit;
		this.maxBlocks = maxBlocks;
		this.buried = buried;
		groundY = structureBounds.minY();
		topY = structureBounds.maxY();
		random = RandomSource.create(seed);
		// Routed back through this level, so that asking which biome is somewhere is answered from the
		// generator's noise rather than from a chunk that would have to be generated to answer
		biomeManager = level.getBiomeManager().withDifferentSource(this);
		recorded.defaultReturnValue(-1);
		owners.defaultReturnValue(-1);
		foundations.defaultReturnValue(-1);
	}

	/**
	 * Starts taking down the blocks of the next piece. A piece builds over what it laid itself, and
	 * not over what an earlier piece laid.
	 */
	void beginPiece() {
		beginPiece(false);
	}

	/**
	 * Starts taking down the blocks of the next piece, which builds over what earlier pieces laid as
	 * well as over what it laid itself when it builds in layers.
	 */
	void beginPiece(boolean layered) {
		currentPiece++;
		this.layered = layered;
	}

	/** Takes a block down. Answers whether there is still room for more. */
	boolean record(BlockPos pos, BlockState state) {
		return record(pos, state, null);
	}

	/**
	 * Takes a block down, and the nbt a template wrote for it, so that a chest's loot table is not
	 * thrown away with the rest of what the template knew about the block.
	 */
	boolean record(BlockPos pos, BlockState state, CompoundTag nbt) {
		if (full || !limit.isInside(pos)) {
			return !full;
		}
		final long key = pos.asLong();
		final int existing = recorded.get(key);
		if (existing >= 0) {
			final int stateId = Block.getId(state);
			// A piece's own later write stands, as it would in the world, and so does that of a piece
			// building in layers; another piece's only where it lands on air or water. Replacing grows
			// nothing, so the budget is not asked.
			final boolean replace = layered || owners.get(key) == currentPiece
					? stateId != existing
					: !isFiller(state) && isFiller(Block.stateById(existing));
			if (replace) {
				recorded.put(key, stateId);
				owners.put(key, currentPiece);
				// Whatever stood there before goes with its block entity, so that a chest carved away
				// leaves no loot behind and a chest put down in its place gets one of its own
				blockEntities.remove(key);
				rememberBlockEntity(pos.immutable(), state, nbt);
			}
			return true;
		}
		if (recorded.size() >= maxBlocks) {
			full = true;
			return false;
		}
		// The position is copied: pieces reuse a mutable one
		final BlockPos stored = pos.immutable();
		recorded.put(key, Block.getId(state));
		owners.put(key, currentPiece);
		rememberBlockEntity(stored, state, nbt);
		return true;
	}

	/**
	 * Whether a block only fills space rather than building anything: air, and a fluid with nothing
	 * else in it. A piece floods or carves its box before it builds in it, and another piece's walls
	 * standing in that space are not to be lost to it.
	 */
	private static boolean isFiller(BlockState state) {
		return state.isAir() || state.getBlock() instanceof LiquidBlock;
	}

	/**
	 * Builds the block entity a chest (or anything like it) carries, so that a later write of a loot
	 * table has something to land on. A template's nbt is loaded into it where there is some; a piece
	 * that places a chest and then names a loot table writes through {@link #getBlockEntity} instead.
	 */
	private void rememberBlockEntity(BlockPos pos, BlockState state, CompoundTag nbt) {
		if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
			return;
		}
		try {
			final BlockEntity entity = entityBlock.newBlockEntity(pos, state);
			if (entity == null) {
				return;
			}
			if (nbt != null) {
				entity.loadWithComponents(nbt.copy(), level.registryAccess());
			}
			blockEntities.put(pos.asLong(), entity);
		} catch (Throwable t) {
			// One block entity that cannot be built costs its loot table, not the preview
		}
	}

	/** Everything taken down so far, packed position to block state id. Shared; do not modify. */
	Long2IntOpenHashMap getRecorded() {
		return recorded;
	}

	@Override
	public void recordFoundation(BlockPos pos, BlockState state) {
		if (limit.isInside(pos)) {
			foundations.put(pos.asLong(), Block.getId(state));
		}
	}

	/** Where foundations are filled down from, packed position to block state id. Shared; do not modify. */
	Long2IntOpenHashMap getFoundations() {
		return foundations;
	}

	/** The block entities taken down with the blocks. Shared; do not modify. */
	Long2ObjectOpenHashMap<BlockEntity> getBlockEntities() {
		return blockEntities;
	}

	/** The containers that were added as entities rather than placed as blocks. Shared; do not modify. */
	List<ContainerEntity> getContainerEntities() {
		return containerEntities;
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

	/**
	 * Keeps the containers a piece adds to the world as entities, and drops everything else it
	 * spawns. Answers that the entity was added, which is what a piece expects of a world; nothing
	 * kept here is ever ticked or placed anywhere.
	 */
	@Override
	public boolean addFreshEntity(Entity entity) {
		if (entity instanceof ContainerEntity container && limit.isInside(entity.blockPosition()) && containerEntities.size() < maxBlocks) {
			containerEntities.add(container);
			return true;
		}
		return false;
	}

	// Reads are answered out of what was taken down, out of air above the structure, and out of
	// ground below it, so that a piece meant to be dug into the ground still has something to dig into

	@Override
	public BlockState getBlockState(BlockPos pos) {
		final int stateId = recorded.get(pos.asLong());
		if (stateId >= 0) {
			return Block.stateById(stateId);
		}
		return isGroundAt(pos.getY()) ? GROUND : Blocks.AIR.defaultBlockState();
	}

	/**
	 * Whether a position nothing has been taken down at reads as ground: everything under the
	 * structure does, and everything inside it too when the structure generates underground.
	 */
	private boolean isGroundAt(int y) {
		return y < groundY || (buried && y <= topY);
	}

	/**
	 * Where the surface stands. Level with the bottom of a structure that stands on the ground, which
	 * is the ground it was placed against, and level with the top of one that is buried, so that a
	 * piece asking whether it is below ground is answered that it is.
	 */
	private int surfaceY() {
		return buried ? topY : groundY;
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return getBlockState(pos).getFluidState();
	}

	@Override
	public BlockEntity getBlockEntity(BlockPos pos) {
		return blockEntities.get(pos.asLong());
	}

	@Override
	public <T extends BlockEntity> Optional<T> getBlockEntity(BlockPos pos, BlockEntityType<T> type) {
		final BlockEntity entity = getBlockEntity(pos);
		return entity != null && entity.getType() == type ? Optional.of((T) entity) : Optional.empty();
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
		// Flat ground everywhere, at whichever height the structure meets the surface
		return surfaceY();
	}

	@Override
	public BlockPos getHeightmapPos(Heightmap.Types type, BlockPos pos) {
		return new BlockPos(pos.getX(), surfaceY(), pos.getZ());
	}

	/**
	 * A chunk that exists only to be written off. Placing a fence or a torch has the piece mark its
	 * position on the chunk for a later pass, and there has to be something there to mark. What is
	 * marked on this one is never read.
	 */
	@Override
	public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean requireChunk) {
		if (scratchChunk == null) {
			final Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
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

	/**
	 * The experiments the world was made with, which decide what a piece is allowed to place. Answered
	 * from the level being previewed, so that a structure builds here exactly as it would there.
	 */
	@Override
	public FeatureFlagSet enabledFeatures() {
		return level.enabledFeatures();
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

	/** Game events are named through the registry now rather than passed as the event itself. */
	@Override
	public void gameEvent(Holder<GameEvent> event, Vec3 position, GameEvent.Context context) {
	}

}
