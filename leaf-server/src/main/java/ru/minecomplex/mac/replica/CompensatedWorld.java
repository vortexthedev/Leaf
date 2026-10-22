package ru.minecomplex.mac.replica;

import ru.minecomplex.mac.util.Tri;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;
import ru.minecomplex.mac.engine.Unmodeled;
import java.util.*;
import ru.minecomplex.mac.engine.blockeffects.GeyserLaunch;
import ru.minecomplex.mac.mechanics.collision.BlockCollisions;
import ru.minecomplex.mac.world.BinaryNbt;
import ru.minecomplex.mac.world.BlockChange;
import ru.minecomplex.mac.world.BlockDirection;
import ru.minecomplex.mac.world.BlockEntityData;
import ru.minecomplex.mac.world.BlockEvent;
import ru.minecomplex.mac.world.BlockInput;
import ru.minecomplex.mac.world.BlockPosition;
import ru.minecomplex.mac.world.BlockPredictions;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.BlockSource;
import ru.minecomplex.mac.world.BlockTag;
import ru.minecomplex.mac.world.BlockTags;
import ru.minecomplex.mac.world.BlockType;
import ru.minecomplex.mac.world.ChunkColumn;
import ru.minecomplex.mac.world.ClientWorldBorder;
import ru.minecomplex.mac.world.FluidProperties;
import ru.minecomplex.mac.world.PalettedSection;
import ru.minecomplex.mac.world.piston.MovingPiston;
import ru.minecomplex.mac.world.piston.PistonNbt;
import ru.minecomplex.mac.world.piston.PistonProperties;
import ru.minecomplex.mac.world.piston.PistonPush;
import ru.minecomplex.mac.world.piston.PistonWorld;
import ru.minecomplex.mac.world.shulker.ShulkerAnimation;
import ru.minecomplex.mac.world.shulker.ShulkerPush;

/**
 * The blocks one client level could see, reproducing {@code ClientChunkCache}: chunks live in a ring of
 * {@code (2r + 1)²} slots ({@code r = max(2, viewDistance) + 3}) indexed by {@code floorMod} of their coordinates; a
 * chunk is visible only while it is within {@code r} of the storage center <em>and</em> its slot still holds it.
 * Moving the center hides chunks without dropping them; chunks sent outside the range are ignored; a chunk evicts
 * whatever occupied its slot. Verified identical in 1.21.4 and 26.3.
 *
 * <p>Not thread-safe. A {@link #fork()} shares columns copy-on-write; it is valid only until this world is mutated
 * again (asserted).
 */
public final class CompensatedWorld implements BlockSource {

    /**
     * Block reads in chunks the client does not have.
     */
    public static final int UNLOADED = -1;

    public static final int AIR = 0;

    private final Object token = new Object();

    private final String dimension;

    private final int minY;

    private final int height;

    private final int minSection;

    private int serverRadius;

    private int chunkRadius;

    private int viewRange;

    private int centerX;

    private int centerZ;

    private ChunkColumn[] ring;

    private int generation;

    private final CompensatedWorld base;

    private final int baseGeneration;

    private boolean dynamicApplicationUnresolved;

    private ru.minecomplex.mac.replica.CompensatedEntities entities;

    /**
     * The level's game time as the client counts it; {@code null} until the server's {@code set_time}.
     */
    private Long gameTime;

    private final BlockRegistry registry;

    private final LinkedHashMap<BlockPosition, PistonEntry> pistons = new LinkedHashMap<>();

    private final LinkedHashMap<BlockPosition, ShulkerEntry> shulkers = new LinkedHashMap<>();

    private final LinkedHashMap<BlockPosition, Object> tickerOrder = new LinkedHashMap<>();

    private final EnumSet<Unmodeled> dynamicGaps = EnumSet.noneOf(Unmodeled.class);

    private ClientWorldBorder pistonBorder;

    private boolean pistonClockResolved;



    /**
     * {@code Level.rainLevel} (and {@code oRainLevel}, which {@code setRainLevel} sets alike): 0 in a new level.
     */
    private float rainLevel;

    private ru.minecomplex.mac.util.Tri canHaveWeather = ru.minecomplex.mac.util.Tri.EITHER;

    /**
     * The block tags of the connection's last {@code update_tags} the client handled ({@code null}: those of the login).
     */
    private BlockTags tags;

    /**
     * {@code ClientLevel.lightUpdateQueue} entries (a chunk's light data, a dropped chunk's, a {@code light_update}) that
     * may not have run: {chunk key, client ticks until it surely ran}.
     */
    private final ArrayList<long[]> lightQueue = new ArrayList<>();

    /**
     * Columns ({@link #columnKey}) whose blocks changed: client ticks until the light engine surely ran after it.
     */
    private final HashMap<Long, Integer> relitColumns = new HashMap<>();

    private BlockPredictions predictions = new BlockPredictions();

    private record PistonEntry(MovingPiston entity, Object chunkGeneration) {
    }

    private record ShulkerEntry(ShulkerAnimation lid, Object chunkGeneration) {
    }

    /**
     * Potent sulfur block entities (26.2+) by position: the chunk generation that created them.
     */
    private final LinkedHashMap<BlockPosition, Object> geysers = new LinkedHashMap<>();

    public CompensatedWorld(String dimension, int minY, int height, int serverRadius) {
        this(dimension, minY, height, serverRadius, null);
    }

    public CompensatedWorld(String dimension, int minY, int height, int serverRadius, BlockRegistry registry) {
        this.registry = registry;
        this.pistonBorder = registry == null ? null : new ClientWorldBorder();
        this.dimension = dimension;
        this.minY = minY;
        this.height = height;
        this.minSection = minY >> 4;
        this.serverRadius = serverRadius;
        this.chunkRadius = storageRange(serverRadius);
        this.viewRange = chunkRadius * 2 + 1;
        this.ring = new ChunkColumn[viewRange * viewRange];
        this.base = null;
        this.baseGeneration = 0;
    }

    private CompensatedWorld(CompensatedWorld source) {
        entities = source.entities == null ? null : source.entities.copy();
        tickingChunks.addAll(source.tickingChunks);
        gameTime = source.gameTime;
        this.registry = source.registry;
        this.pistonBorder = source.pistonBorder == null ? null : source.pistonBorder.copy();
        pistons.putAll(source.pistons);
        dynamicGaps.addAll(source.dynamicGaps);
        shulkers.putAll(source.shulkers);
        tickerOrder.putAll(source.tickerOrder);
        geysers.putAll(source.geysers);
        pistonClockResolved = source.pistonClockResolved;
        rainLevel = source.rainLevel;
        canHaveWeather = source.canHaveWeather;
        tags = source.tags;
        for (long[] entry : source.lightQueue) lightQueue.add(entry.clone());
        relitColumns.putAll(source.relitColumns);
        predictions = source.predictions.copy();
        this.dimension = source.dimension;
        this.minY = source.minY;
        this.height = source.height;
        this.minSection = source.minSection;
        this.serverRadius = source.serverRadius;
        this.chunkRadius = source.chunkRadius;
        this.viewRange = source.viewRange;
        this.centerX = source.centerX;
        this.centerZ = source.centerZ;
        this.ring = source.ring.clone();
        this.base = source;
        this.baseGeneration = source.generation;
        this.dynamicApplicationUnresolved = source.dynamicApplicationUnresolved;
    }

    /**
     * {@code ClientChunkCache.calculateStorageRange}.
     */
    static int storageRange(int viewRange) {
        return Math.max(2, viewRange) + 3;
    }

    /**
     * A world that starts equal to this one and can be changed without affecting it.
     */
    public CompensatedWorld fork() {
        assert valid();
        return new CompensatedWorld(this);
    }

    /**
     * An independent hypothesis snapshot, valid after the marker baseline changes.
     */
    public CompensatedWorld detachedCopy() {
        assert valid();
        CompensatedWorld copy = new CompensatedWorld(dimension, minY, height, serverRadius, registry);
        copy.entities = entities == null ? null : entities.copy();
        copy.tickingChunks.addAll(tickingChunks);
        copy.gameTime = gameTime;
        copy.centerX = centerX;
        copy.centerZ = centerZ;
        for (int i = 0; i < ring.length; i++) if (ring[i] != null)
            copy.ring[i] = ring[i].detached();
        copy.dynamicApplicationUnresolved = dynamicApplicationUnresolved;
        copy.dynamicGaps.addAll(dynamicGaps);
        copy.pistons.putAll(pistons);
        copy.shulkers.putAll(shulkers);
        copy.tickerOrder.putAll(tickerOrder);
        copy.geysers.putAll(geysers);
        copy.pistonClockResolved = pistonClockResolved;
        copy.rainLevel = rainLevel;
        copy.canHaveWeather = canHaveWeather;
        copy.tags = tags;
        for (long[] entry : lightQueue) copy.lightQueue.add(entry.clone());
        copy.relitColumns.putAll(relitColumns);
        copy.pistonBorder = pistonBorder == null ? null : pistonBorder.copy();
        copy.predictions = predictions.copy();
        return copy;
    }

    public ru.minecomplex.mac.replica.CompensatedEntities entities() {
        assert valid();
        return entities;
    }

    void initializeEntities(int localPlayerId) {
        mutate();
        entities = new ru.minecomplex.mac.replica.CompensatedEntities(localPlayerId);
    }

    /**
     * Chunks whose entities tick ({@code TransientEntitySectionManager.tickingChunks}): added by {@code onChunkLoaded},
     * removed by {@code unload} when a chunk is dropped or its storage slot is reused; a view-radius change discards
     * chunks without unloading them.
     */
    private final java.util.HashSet<Long> tickingChunks = new java.util.HashSet<>();

    public boolean tickingChunk(long key) {
        assert valid();
        return tickingChunks.contains(key);
    }

    private void startTicking(int chunkX, int chunkZ) {
        if (tickingChunks.add(ru.minecomplex.mac.entity.EntityMotions.chunkKeyOf(chunkX, chunkZ)) && entities != null)
            entities.chunkTicking(chunkX, chunkZ, true);
    }

    private void stopTicking(int chunkX, int chunkZ) {
        if (tickingChunks.remove(ru.minecomplex.mac.entity.EntityMotions.chunkKeyOf(chunkX, chunkZ)) && entities != null)
            entities.chunkTicking(chunkX, chunkZ, false);
    }

    /**
     * Entity ticks of {@code ClientLevel.tickEntities} on one side of the local player.
     */
    public void tickEntities(boolean beforePlayer, ru.minecomplex.mac.entity.EntityMotions.BodyQueries queries) {
        tickEntities(beforePlayer, queries, null, java.util.EnumSet.noneOf(ru.minecomplex.mac.engine.Unmodeled.class));
    }

    /**
     * {@link #tickEntities(boolean, ru.minecomplex.mac.entity.EntityMotions.BodyQueries)} pushing the local player.
     */
    public void tickEntities(boolean beforePlayer, ru.minecomplex.mac.entity.EntityMotions.BodyQueries queries, ru.minecomplex.mac.entity.EntityMotions.LocalPlayerTarget player, java.util.Set<ru.minecomplex.mac.engine.Unmodeled> pushGaps) {
        tickEntities(beforePlayer, queries, player, pushGaps, this::fluidIn);
    }

    /**
     * {@link #tickEntities(boolean, ru.minecomplex.mac.entity.EntityMotions.BodyQueries, ru.minecomplex.mac.entity.EntityMotions.LocalPlayerTarget, java.util.Set)}
     * with the boxes where a fluid may push a remote entity ({@link #fluidIn}: any fluid).
     */
    public void tickEntities(boolean beforePlayer, ru.minecomplex.mac.entity.EntityMotions.BodyQueries queries, ru.minecomplex.mac.entity.EntityMotions.LocalPlayerTarget player, java.util.Set<ru.minecomplex.mac.engine.Unmodeled> pushGaps, java.util.function.Predicate<ru.minecomplex.mac.collision.Aabb> fluidPush) {
        tickEntities(beforePlayer, queries, player, pushGaps, fluidPush, null);
    }

    /**
     * ... knowing which positions are in a climbable block ({@code null}: not known).
     */
    public void tickEntities(boolean beforePlayer, ru.minecomplex.mac.entity.EntityMotions.BodyQueries queries, ru.minecomplex.mac.entity.EntityMotions.LocalPlayerTarget player, java.util.Set<ru.minecomplex.mac.engine.Unmodeled> pushGaps, java.util.function.Predicate<ru.minecomplex.mac.collision.Aabb> fluidPush, java.util.function.Function<ru.minecomplex.mac.util.math.Vec3d, ru.minecomplex.mac.util.Tri> climbable) {
        if (entities == null || entities.motions() == null)
            return;
        mutate();
        if (beforePlayer)
            entities.motions().beginTick();
        entities.motions().tick(beforePlayer, this::tickingChunk, queries, player, pushGaps, this::sleepHeight, fluidPush, climbable);
    }

    /**
     * The ride's transform of the local player's owned root vehicle.
     */
    public void rideTransform(int id, ru.minecomplex.mac.util.math.Vec3d position, float yaw, float pitch) {
        rideTransform(id, position, yaw, pitch, null);
    }

    /**
     * {@link #rideTransform(int, ru.minecomplex.mac.util.math.Vec3d, float, float)} with the vehicle's delta movement after the ride.
     */
    public void rideTransform(int id, ru.minecomplex.mac.util.math.Vec3d position, float yaw, float pitch, ru.minecomplex.mac.util.math.Vec3d velocity) {
        rideTransform(id, position, yaw, pitch, velocity, null, true);
    }

    /**
     * {@link #rideTransform(int, ru.minecomplex.mac.util.math.Vec3d, float, float, ru.minecomplex.mac.util.math.Vec3d)} with the interpolation the ride followed.
     */
    public void rideTransform(int id, ru.minecomplex.mac.util.math.Vec3d position, float yaw, float pitch, ru.minecomplex.mac.util.math.Vec3d velocity, ru.minecomplex.mac.entity.interpolation.EntityInterpolation interpolation, boolean exact) {
        rideTransform(id, position, yaw, pitch, velocity, velocity != null, interpolation, exact);
    }

    /**
     * Preserve the simulated movement estimate separately from its certainty.
     */
    public void rideTransform(int id, ru.minecomplex.mac.util.math.Vec3d position, float yaw, float pitch, ru.minecomplex.mac.util.math.Vec3d velocity, boolean velocityKnown, ru.minecomplex.mac.entity.interpolation.EntityInterpolation interpolation, boolean exact) {
        if (entities == null || entities.motions() == null)
            return;
        mutate();
        entities.motions().ride(id, position, yaw, pitch, velocity, velocityKnown, interpolation, exact, this::tickingChunk);
    }

    /**
     * Whether a box may touch a fluid: a block it reaches holds one, or a column it reaches is not loaded (for a remote
     * entity's fluid pushing, which is not modelled).
     */
    public boolean fluidIn(ru.minecomplex.mac.collision.Aabb box) {
        assert valid();
        if (registry == null)
            return true;
        int minX = (int) Math.floor(box.minX()), maxX = (int) Math.floor(box.maxX());
        int minY = (int) Math.floor(box.minY()), maxY = (int) Math.floor(box.maxY());
        int minZ = (int) Math.floor(box.minZ()), maxZ = (int) Math.floor(box.maxZ());
        for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) {
            int state = getBlock(x, y, z);
            if (state == UNLOADED || !registry.fluid(state).equals("minecraft:empty"))
                return true;
        }
        return false;
    }

    /**
     * The game time the entities of the current client tick see, {@code null} before the server set it.
     */
    public Long gameTime() {
        assert valid();
        return gameTime;
    }

    /**
     * {@code set_time}.
     */
    void gameTime(long value) {
        mutate();
        gameTime = value;
    }

    /**
     * {@code ClientLevel.tickTime} at the end of a client tick (a level frozen by the tick rate manager is not modelled).
     */
    public void advanceGameTime() {
        if (gameTime != null) {
            mutate();
            gameTime = gameTime + 1L;
        }
    }

    void respawnLocalEntity() {
        if (entities != null) {
            mutate();
            entities.respawnLocalPlayer();
        }
    }

    /**
     * {@code AbstractBedBlock.getSleepHeight} of the block at a position (26.3): the maximum height of its outline shape —
     * a bed's {@code column(16, 3, 9)} 0.5625, a straw bed's head 0.3125 and foot 0.25; {@code null} for no bed,
     * {@code NaN} for an unloaded block or another bed class.
     */
    public Double sleepHeight(int x, int y, int z) {
        int state = getBlock(x, y, z);
        if (state == UNLOADED)
            return Double.NaN;
        var type = registry.block(state);
        if (type.isInstance("BedBlock"))
            return 0.5625;
        if (type.isInstance("StrawBedBlock"))
            return registry.properties(state).contains("part=head") ? 0.3125 : 0.25;
        return type.isInstance("AbstractBedBlock") ? Double.NaN : null;
    }

    /**
     * Entity handlers invalidate ephemeral prefix views just like block handlers.
     */
    void entityInput(Object packet) {
        if (entities == null)
            return;
        mutate();
        switch(packet) {
            case ClientboundAddEntityPacket spawn ->
                entities.spawn(spawn, this::tickingChunk);
            case ClientboundTeleportEntityPacket teleport ->
                entities.motion(teleport, this::tickingChunk);
            case ClientboundEntityPositionSyncPacket sync ->
                entities.motion(sync, this::tickingChunk);
            case ClientboundRemoveEntitiesPacket remove ->
                entities.remove(remove);
            case ClientboundSetPassengersPacket passengers ->
                entities.apply(passengers);
            case ClientboundSetEntityDataPacket data ->
                entities.syncedData(data, this::sleepHeight, this::tickingChunk);
            case ClientboundUpdateAttributesPacket attributes ->
                entities.attributes(attributes);
            case ClientboundUpdateMobEffectPacket effect ->
                entities.effect(effect);
            case ClientboundRemoveMobEffectPacket effect ->
                entities.effect(effect);
            case ClientboundSetEquipmentPacket equipment ->
                entities.equipment(equipment);
            case ClientboundSetEntityMotionPacket velocity ->
                entities.velocity(velocity);
            case ClientboundSetPlayerTeamPacket team ->
                entities.team(team);
            case ClientboundPlayerInfoUpdatePacket info ->
                entities.playerInfo(info);
            case ClientboundPlayerInfoRemovePacket info ->
                entities.playerInfo(info);
            default ->
                {
                    if (!ru.minecomplex.mac.packet.Packets.entityMove(packet))
                        throw new IllegalArgumentException("Not an entity relationship event: " + packet);
                    entities.motion(packet, this::tickingChunk);
                }
        }
    }

    private boolean valid() {
        if (base != null && base.generation != baseGeneration) {
            throw new IllegalStateException("Fork used after its base world changed");
        }
        return true;
    }

    public String dimension() {
        return dimension;
    }

    public int minY() {
        return minY;
    }

    public int height() {
        return height;
    }

    public int serverRadius() {
        return serverRadius;
    }

    public int centerX() {
        return centerX;
    }

    public int centerZ() {
        return centerZ;
    }

    private boolean inRange(int chunkX, int chunkZ) {
        return viewContains(centerX, centerZ, serverRadius, chunkX, chunkZ);
    }

    /**
     * ClientChunkCache.Storage.inRange for a possible center/radius packet prefix.
     */
    public static boolean viewContains(int centerX, int centerZ, int serverRadius, int chunkX, int chunkZ) {
        int radius = storageRange(serverRadius);
        return Math.abs(chunkX - centerX) <= radius && Math.abs(chunkZ - centerZ) <= radius;
    }

    private int index(int chunkX, int chunkZ) {
        return Math.floorMod(chunkZ, viewRange) * viewRange + Math.floorMod(chunkX, viewRange);
    }

    /**
     * The column the client would return from {@code getChunk}, or {@code null}.
     */
    public ChunkColumn column(int chunkX, int chunkZ) {
        assert valid();
        if (!inRange(chunkX, chunkZ)) {
            return null;
        }
        ChunkColumn column = ring[index(chunkX, chunkZ)];
        return column != null && column.x() == chunkX && column.z() == chunkZ ? column : null;
    }

    public boolean isLoaded(int chunkX, int chunkZ) {
        return column(chunkX, chunkZ) != null;
    }

    /**
     * Block state at a position, {@link #AIR} outside the build height, {@link #UNLOADED} in missing chunks.
     */
    @Override
    public int getBlock(int x, int y, int z) {
        ChunkColumn column = column(x >> 4, z >> 4);
        if (column == null) {
            return UNLOADED;
        }
        PalettedSection section = column.section((y >> 4) - minSection);
        return section == null ? AIR : section.get(x & 15, y & 15, z & 15);
    }

    /**
     * {@code replaceWithPacketData}; ignored outside the storage range.
     */
    public void load(ChunkColumn column) {
        if (!inRange(column.x(), column.z())) {
            return;
        }
        mutate();
        ChunkColumn old = ring[index(column.x(), column.z())];
        if (old != null)
            retirePistons(old.chunkGeneration());
        // Storage.replace unloads a different chunk held by the slot; onChunkLoaded follows
        if (old != null && (old.x() != column.x() || old.z() != column.z()))
            stopTicking(old.x(), old.z());
        ring[index(column.x(), column.z())] = column;
        startTicking(column.x(), column.z());
    }

    /**
     * {@code ClientChunkCache.drop}.
     */
    public void forget(int chunkX, int chunkZ) {
        if (column(chunkX, chunkZ) != null) {
            mutate();
            retirePistons(column(chunkX, chunkZ).chunkGeneration());
            ring[index(chunkX, chunkZ)] = null;
            stopTicking(chunkX, chunkZ);
        }
    }

    public void setCenter(int chunkX, int chunkZ) {
        mutate();
        centerX = chunkX;
        centerZ = chunkZ;
    }

    /**
     * {@code updateViewRadius}: a new storage keeping only chunks inside the new range.
     */
    public void setRadius(int radius) {
        mutate();
        serverRadius = radius;
        int newChunkRadius = storageRange(radius);
        if (newChunkRadius == chunkRadius) {
            return;
        }
        ChunkColumn[] old = ring;
        chunkRadius = newChunkRadius;
        viewRange = newChunkRadius * 2 + 1;
        ring = new ChunkColumn[viewRange * viewRange];
        for (ChunkColumn column : old) {
            if (column != null && inRange(column.x(), column.z())) {
                ring[index(column.x(), column.z())] = column;
            } else if (column != null)
                retirePistons(column.chunkGeneration());
        }
    }

    /**
     * {@code ClientLevel.setBlock}; no effect in missing chunks or outside the build height.
     */
    public void setBlock(int x, int y, int z, int state) {
        setBlock(new BlockPosition(x, y, z), state, 19);
    }

    private void setBlock(BlockPosition pos, int state, int flags) {
        int x = pos.x(), y = pos.y(), z = pos.z();
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        ChunkColumn column = column(chunkX, chunkZ);
        if (column == null) {
            return;
        }
        int sectionIndex = (y >> 4) - minSection;
        if (sectionIndex < 0 || sectionIndex >= column.sectionCount()) {
            return;
        }
        int previous = getBlock(x, y, z);
        if (previous == state)
            return;
        mutate();
        if (!column.ownedBy(token)) {
            column = column.forkFor(token);
            ring[index(chunkX, chunkZ)] = column;
        }
        column.setBlock(sectionIndex, x & 15, y & 15, z & 15, state);
        // LevelChunk.setBlockState → lightEngine.checkBlock, run in the next frame
        relitColumns.put(columnKey(x, z), FRAME_TICKS);
        // ClientLevel.setBlock while predicting: the server's state before the change is kept
        if (predictions.predicting)
            predictions.retain(pos, previous);
        if (!column.dynamicInputs().isEmpty())
            column.dynamicInput(new BlockChange(x, y, z, state));
        if (registry != null) {
            if (registry.block(previous) != registry.block(state)) {
                pistons.remove(pos);
                retireShulkerTiming(shulkers.get(pos));
                shulkers.remove(pos);
                tickerOrder.remove(pos);
                geysers.remove(pos);
            }
            if (registry.block(state).isInstance("ShulkerBoxBlock"))
                shulker(pos, true);
            // LevelChunk.setBlockState: a new block with a block entity creates it and adds its ticker
            if (registry.block(previous) != registry.block(state))
                geyser(pos);
            if ((flags & 16) == 0) {
                int neighbourFlags = flags & -34;
                neighbourShapes(previous, pos, neighbourFlags, true);
                neighbourShapes(state, pos, neighbourFlags, false);
                neighbourShapes(state, pos, neighbourFlags, true);
            }
        }
    }

    private void retirePistons(Object chunkGeneration) {
        pistons.entrySet().removeIf(e -> {
            if (e.getValue().chunkGeneration != chunkGeneration)
                return false;
            tickerOrder.remove(e.getKey());
            return true;
        });
        shulkers.entrySet().removeIf(e -> {
            if (e.getValue().chunkGeneration != chunkGeneration)
                return false;
            retireShulkerTiming(e.getValue());
            tickerOrder.remove(e.getKey());
            return true;
        });
        geysers.entrySet().removeIf(e -> {
            if (e.getValue() != chunkGeneration)
                return false;
            tickerOrder.remove(e.getKey());
            return true;
        });
    }

    private void retireShulkerTiming(ShulkerEntry entry) {
        if (!pistonClockResolved && entry != null && !entry.lid.isClosed())
            dynamicGaps.add(Unmodeled.SHULKER_TICK_TIMING);
    }

    private ChunkColumn heldColumn(BlockPosition pos) {
        ChunkColumn column = ring[index(pos.x() >> 4, pos.z() >> 4)];
        return column != null && column.x() == pos.x() >> 4 && column.z() == pos.z() >> 4 ? column : null;
    }

    private int block(BlockPosition pos) {
        int state = getBlock(pos.x(), pos.y(), pos.z());
        return state == UNLOADED ? AIR : state;
    }

    private int heldBlock(BlockPosition pos) {
        var held = heldColumn(pos);
        if (held == null)
            return AIR;
        var section = held.section((pos.y() >> 4) - minSection);
        return section == null ? AIR : section.get(pos.x() & 15, pos.y() & 15, pos.z() & 15);
    }

    private boolean tickerInsideBorder(BlockPosition pos) {
        var inside = pistonBorder.within(pos.x(), pos.z(), 0.0);
        if (inside == ru.minecomplex.mac.util.Tri.EITHER)
            dynamicGaps.add(Unmodeled.WORLD_BORDER_CLOCK);
        return pistonBorder.snapshot().within(pos.x(), pos.z(), 0.0);
    }

    private BlockDirection direction(int state) {
        return BlockDirection.valueOf(PistonProperties.get().value(state, "facing").toUpperCase(Locale.ROOT));
    }

    private boolean fittingBase(int head, int base) {
        var facts = PistonProperties.get();
        String expected = "sticky".equals(facts.value(head, "type")) ? "minecraft:sticky_piston" : "minecraft:piston";
        return registry.block(base).name().equals(expected) && "true".equals(facts.value(base, "extended")) && Objects.equals(facts.value(head, "facing"), facts.value(base, "facing"));
    }

    private int updateShape(int state, BlockPosition pos, BlockDirection neighbour) {
        var facts = PistonProperties.get();
        if (registry.block(state).isInstance("DoorBlock")) {
            boolean lower = "lower".equals(facts.value(state, "half"));
            if (neighbour.axis == ru.minecomplex.mac.collision.Axis.Y && lower == (neighbour == BlockDirection.UP)) {
                int other = block(pos.relative(neighbour));
                return registry.block(other).isInstance("DoorBlock") && !Objects.equals(facts.value(other, "half"), facts.value(state, "half")) ? facts.with(other, "half", facts.value(state, "half")) : state;
            }
            // A missing partner or lost lower support returns AIR, which Block.updateOrDestroy ignores on the client.
        } else if (registry.block(state).isInstance("TrapDoorBlock")) {
            // updateShape only schedules a water tick and delegates to Block's unchanged-state callback.
        } else if (registry.block(state).isInstance("PistonHeadBlock")) {
            var facing = direction(state);
            if (neighbour == facing.opposite()) {
                int base = block(pos.relative(facing.opposite()));
                if (!fittingBase(state, base) && !(registry.block(base).isInstance("MovingPistonBlock") && facing == direction(base)))
                    return 0;
            }
        } else if (!facts.baseNeighbourMethod(state, 1))
            dynamicGaps.add(Unmodeled.PISTON_NEIGHBOURS);
        return state;
    }

    private static final BlockDirection[] SHAPE_ORDER = { BlockDirection.WEST, BlockDirection.EAST, BlockDirection.NORTH, BlockDirection.SOUTH, BlockDirection.DOWN, BlockDirection.UP };

    private void neighbourShapes(int state, BlockPosition pos, int flags, boolean indirect) {
        var facts = PistonProperties.get();
        if (indirect) {
            if (!facts.baseNeighbourMethod(state, 2))
                dynamicGaps.add(Unmodeled.PISTON_NEIGHBOURS);
            return;
        }
        for (var direction : SHAPE_ORDER) {
            BlockPosition target = pos.relative(direction);
            int before = block(target), after = updateShape(before, target, direction.opposite());
            if (after != before)
                setBlock(target, after, flags);
        }
    }

    public MovingPiston piston(BlockPosition pos) {
        assert valid();
        if (!blockEntityPositionValid(pos) || column(pos.x() >> 4, pos.z() >> 4) == null)
            return null;
        PistonEntry entry = pistons.get(pos);
        return entry == null ? null : entry.entity;
    }

    private void piston(BlockPosition pos, MovingPiston value) {
        mutate();
        if (value == null) {
            pistons.remove(pos);
            tickerOrder.remove(pos);
            return;
        }
        ChunkColumn column = heldColumn(pos);
        if (pos.y() < minY || pos.y() >= minY + height)
            return;
        if (!pistons.containsKey(pos) && (registry == null || !registry.block(block(pos)).isInstance("MovingPistonBlock")))
            return;
        if (column != null) {
            pistons.put(pos, new PistonEntry(value, column.chunkGeneration()));
            tickerOrder.computeIfAbsent(pos, ignored -> new Object());
        }
    }

    private PistonWorld engine(BlockPosition ticking, java.util.function.BiConsumer<BlockPosition, MovingPiston> displacement) {
        return new PistonWorld(new PistonWorld.World() {

            public int block(BlockPosition pos) {
                return CompensatedWorld.this.block(pos);
            }

            public int minY() {
                return minY;
            }

            public int maxY() {
                return minY + height - 1;
            }

            public boolean withinBorder(BlockPosition pos) {
                return tickerInsideBorder(pos);
            }

            public MovingPiston piston(BlockPosition pos) {
                if (pos.equals(ticking)) {
                    var entry = pistons.get(pos);
                    return entry == null ? null : entry.entity;
                }
                return CompensatedWorld.this.piston(pos);
            }

            public void piston(BlockPosition pos, MovingPiston value) {
                CompensatedWorld.this.piston(pos, value);
            }

            public void setBlock(BlockPosition pos, int state, int flags) {
                CompensatedWorld.this.setBlock(pos, state, flags);
            }

            public int updateFromNeighbours(int state, BlockPosition pos) {
                for (var direction : SHAPE_ORDER) state = updateShape(state, pos, direction);
                return state;
            }

            public void neighbourShapes(int state, BlockPosition pos, int flags, boolean indirect) {
                CompensatedWorld.this.neighbourShapes(state, pos, flags, indirect);
            }

            // Level implementations are no-ops on ClientLevel; these are server notification paths.
            public void neighbours(BlockPosition pos, int state, BlockDirection direction) {
            }

            public void displacement(BlockPosition pos, MovingPiston piston, float next) {
                if (displacement == null)
                    dynamicGaps.add(Unmodeled.PISTON_ENTITIES);
                else
                    displacement.accept(pos, piston);
            }
        }, registry);
    }

    public boolean pistonEvent(BlockEvent event) {
        if (registry == null)
            throw new IllegalStateException("Piston replay needs a client registry");
        return engine(null, null).event(event);
    }

    /**
     * The {@code game_event}s that set the client's rain level: start (1), stop (2) and rain level change (7).
     */
    public static boolean rainEvent(ClientboundGameEventPacket event) {
        // native Reason constants are declared in vanilla id order
        var type = event.getEvent();
        return type == ClientboundGameEventPacket.START_RAINING || type == ClientboundGameEventPacket.STOP_RAINING || type == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE;
    }

    /**
     * {@code handleGameEvent}: {@code START_RAINING} sets the rain level 0, {@code STOP_RAINING} 1,
     * {@code RAIN_LEVEL_CHANGE} the event's value ({@code Level.setRainLevel} clamps to [0, 1]).
     */
    public void rain(ClientboundGameEventPacket event) {
        mutate();
        float level = event.getEvent() == ClientboundGameEventPacket.START_RAINING ? 0.0F : event.getEvent() == ClientboundGameEventPacket.STOP_RAINING ? 1.0F : event.getParam();
        rainLevel = Math.clamp(level, 0.0F, 1.0F);
    }

    /**
     * Immutable dimension facts from login/respawn, independently of the server's current weather.
     */
    public void canHaveWeather(ru.minecomplex.mac.util.Tri value) {
        mutate();
        canHaveWeather = value;
    }

    /**
     * {@code Level.isRaining}: weather gate and a float rain level promoted to double before comparison.
     */
    public ru.minecomplex.mac.util.Tri raining() {
        assert valid();
        if (!((double) rainLevel > 0.2))
            return ru.minecomplex.mac.util.Tri.FALSE;
        if ("minecraft:the_end".equals(dimension)) {
            if (registry == null)
                return canHaveWeather == ru.minecomplex.mac.util.Tri.FALSE ? ru.minecomplex.mac.util.Tri.FALSE : ru.minecomplex.mac.util.Tri.EITHER;
            return ru.minecomplex.mac.util.Tri.FALSE;
        }
        return canHaveWeather;
    }

    /**
     * Whether the received rain level or dimension proves that rain is impossible.
     */
    public boolean surelyDry() {
        return raining() == ru.minecomplex.mac.util.Tri.FALSE;
    }

    /**
     * {@code Minecraft.runTick} runs at most 10 client ticks ({@code Math.min(10, ticksToDo)}) before the frame, whose
     * {@code ClientLevel.update} (26.1+; before: {@code LevelRenderer.renderLevel}) polls the light queue and runs the
     * light engine ({@code runLightUpdates}, all pending block checks): a change applied before a tick is lit after
     * at most 10 ticks.
     */
    public static final int FRAME_TICKS = 10;

    /**
     * {@code BlockPos.asLong}-like key of a block column.
     */
    private static long columnKey(int x, int z) {
        return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32;
    }

    /**
     * {@code ChunkPos.asLong}.
     */
    private static long chunkKey(int chunkX, int chunkZ) {
        return (long) chunkX & 0xFFFFFFFFL | ((long) chunkZ & 0xFFFFFFFFL) << 32;
    }

    /**
     * {@code ClientLevel.queueLightUpdate} for a column (a chunk's light data, a dropped chunk, a {@code light_update}).
     * Each frame {@code pollLightUpdates} runs {@code max(10, size / 10)} entries, all of them from 1000: an entry
     * behind {@code p - 1} entries that may still wait runs within {@code ceil(p / 10)} frames, one frame from 1000.
     */
    public void queueLight(int chunkX, int chunkZ) {
        mutate();
        int position = lightQueue.size() + 1;
        int frames = position >= 1000 ? 1 : (position + 9) / 10;
        lightQueue.add(new long[] { chunkKey(chunkX, chunkZ), (long) frames * FRAME_TICKS });
    }

    /**
     * One client tick for the light: entries and checks that surely ran by its end are dropped.
     */
    public void tickLight() {
        if (lightQueue.isEmpty() && relitColumns.isEmpty())
            return;
        mutate();
        lightQueue.removeIf(entry -> --entry[1] <= 0);
        relitColumns.replaceAll((key, ticks) -> ticks - 1);
        relitColumns.values().removeIf(ticks -> ticks <= 0);
    }

    /**
     * Whether the client's light of the column surely matches its blocks: no queued light data, no unlit change.
     */
    private boolean lightSettled(int x, int z) {
        if (relitColumns.containsKey(columnKey(x, z)))
            return false;
        long chunk = chunkKey(x >> 4, z >> 4);
        for (long[] entry : lightQueue) if (entry[0] == chunk)
            return false;
        return true;
    }

    /**
     * The block column's part of {@code Level.precipitationAt(pos)}: {@code canSeeSky(pos)} (sky light 15) and the
     * {@code MOTION_BLOCKING} heightmap not above {@code pos} ({@code getHeightmapPos(...).getY() > pos.getY()} → none).
     *
     * <p>The heightmap is the highest motion-blocking block plus one, never below {@code minY}: the chunk packet's
     * (the server's, computed from the same blocks) kept by {@code Heightmap.update} on every {@code setBlockState};
     * a block at or above {@code pos} that blocks motion ({@code blocksMotion()}, from 26.3 the
     * {@code #blocks_motion_in_heightmap} tag) or holds a fluid stops the rain. Sky light 15 is a sky source
     * ({@code ChunkSkyLightSources}: no occluded edge between the top and {@code pos}); air never occludes, so a column
     * of air from {@code pos} up is lit by the sky once the client's light caught up with its blocks. Other blocks
     * above are not modelled (light dampening, occlusion faces).
     */
    public ru.minecomplex.mac.util.Tri rainColumn(int x, int y, int z, BlockTags tags) {
        assert valid();
        if (registry == null || column(x >> 4, z >> 4) == null)
            return ru.minecomplex.mac.util.Tri.EITHER;
        if (y < minY)
            return ru.minecomplex.mac.util.Tri.FALSE;
        boolean open = lightSettled(x, z);
        for (int by = y, top = minY + height; by < top; by++) {
            int state = getBlock(x, by, z);
            boolean blocks = !"minecraft:empty".equals(registry.fluid(state)) || tags.contains(BlockTag.BLOCKS_MOTION_IN_HEIGHTMAP, registry.block(state));
            if (blocks)
                return ru.minecomplex.mac.util.Tri.FALSE;
            if (!registry.isAir(state))
                open = false;
        }
        return open ? ru.minecomplex.mac.util.Tri.TRUE : ru.minecomplex.mac.util.Tri.EITHER;
    }

    /**
     * {@code WorldBorder.isWithinBounds(x, z)} in every phase the client's border may be in.
     */
    public ru.minecomplex.mac.util.Tri withinBorder(double x, double z) {
        assert valid();
        return pistonBorder == null ? ru.minecomplex.mac.util.Tri.TRUE : pistonBorder.within(x, z, 0.0);
    }

    /**
     * The block tags of the last {@code update_tags} this level's client handled ({@code null}: none since the login).
     */
    public BlockTags tags() {
        assert valid();
        return tags;
    }

    /**
     * {@code handleUpdateTags}: the connection's block tags (they outlive the level: a new level keeps them).
     */
    public void tags(BlockTags tags) {
        mutate();
        this.tags = tags;
    }

    /**
     * Any of the border packets ({@link ru.minecomplex.mac.packet.Packets#border}).
     */
    public void border(Object packet) {
        switch(packet) {
            case ClientboundInitializeBorderPacket p ->
                border(p);
            case ClientboundSetBorderCenterPacket p ->
                border(p);
            case ClientboundSetBorderSizePacket p ->
                border(p);
            case ClientboundSetBorderLerpSizePacket p ->
                border(p);
            default ->
                throw new IllegalArgumentException("Not a border packet: " + packet);
        }
    }

    /**
     * {@code handleInitializeBorder}.
     */
    private void border(ClientboundInitializeBorderPacket packet) {
        mutate();
        pistonBorder.setCenter(packet.getNewCenterX(), packet.getNewCenterZ());
        if (packet.getLerpTime() > 0)
            borderLerp(packet.getOldSize(), packet.getNewSize(), packet.getLerpTime());
        else
            pistonBorder.setSize(packet.getNewSize());
        pistonBorder.setAbsoluteMaxSize(packet.getNewAbsoluteMaxSize());
    }

    /**
     * {@code handleSetBorderCenter}.
     */
    private void border(ClientboundSetBorderCenterPacket packet) {
        mutate();
        pistonBorder.setCenter(packet.getNewCenterX(), packet.getNewCenterZ());
    }

    /**
     * {@code handleSetBorderSize}.
     */
    private void border(ClientboundSetBorderSizePacket packet) {
        mutate();
        pistonBorder.setSize(packet.getSize());
    }

    /**
     * {@code handleSetBorderLerpSize}.
     */
    private void border(ClientboundSetBorderLerpSizePacket packet) {
        mutate();
        borderLerp(packet.getOldSize(), packet.getNewSize(), packet.getLerpTime());
    }

    private void borderLerp(double oldSize, double newSize, long duration) {
        {
            // a world whose client ticks are not followed from its start does not know the border ticks since
            if (oldSize != newSize && !pistonClockResolved)
                dynamicGaps.add(Unmodeled.WORLD_BORDER_CLOCK);
            pistonBorder.lerp(oldSize, newSize, duration);
        }
    }

    /** ClientLevel.tick advances the world border after entities and block entities. */
    public void tickBorder() {
        mutate();
        pistonBorder.tick();
    }

    /**
     * The client handled the current border lerp up to {@code ticks} client ticks before this world applied it.
     */
    public void borderAhead(int ticks) {
        mutate();
        pistonBorder.ahead(ticks);
    }

    /**
     * {@code collectColliders}' world border for an entity at (x, z) moving in {@code query}
     * ({@code isInsideCloseToBorder ? getCollisionShape() : none}); where the client's possible border phases disagree,
     * {@code gaps} gets {@link Unmodeled#WORLD_BORDER_CLOCK} and this model's estimate is returned.
     */
    public ru.minecomplex.mac.collision.VoxelShape borderShape(double x, double z, ru.minecomplex.mac.collision.Aabb query, java.util.Set<Unmodeled> gaps) {
        assert valid();
        var collider = pistonBorder.collision(x, z, query);
        if (!collider.known())
            gaps.add(Unmodeled.WORLD_BORDER_CLOCK);
        return collider.shape();
    }

    public void pistonData(BlockEntityData packet) {
        var pos = new BlockPosition(packet.x(), packet.y(), packet.z());
        var entity = piston(pos);
        if (entity == null || !packet.type().equals("minecraft:piston"))
            return;
        byte[] nbt = packet.nbt();
        // Standalone packets use TRUSTED_COMPOUND_TAG, not the chunk entry's optional compound codec.
        if (nbt.length == 0 || nbt[0] != 10) {
            dynamicGaps.add(Unmodeled.PISTON_BLOCK_ENTITY);
            return;
        }
        try {
            piston(pos, PistonNbt.load(entity, nbt, registry));
        } catch (IllegalArgumentException | java.io.UncheckedIOException invalid) {
            dynamicGaps.add(Unmodeled.PISTON_BLOCK_ENTITY);
        }
    }

    /**
     * Runs only a chosen client block-entity phase. The callback must resolve all queried entity effects.
     */
    public void tickPistons(long gameTime, java.util.function.BiConsumer<BlockPosition, MovingPiston> displacement) {
        assert valid();
        for (var entry : new ArrayList<>(pistons.entrySet())) {
            BlockPosition pos = entry.getKey();
            PistonEntry value = entry.getValue();
            ChunkColumn held = heldColumn(pos);
            if (held == null || held.chunkGeneration() != value.chunkGeneration || pistons.get(pos) != value || !tickerInsideBorder(pos))
                continue;
            if (value.entity.direction() == null || !Float.isFinite(value.entity.progress())) {
                dynamicGaps.add(Unmodeled.PISTON_BLOCK_ENTITY);
                continue;
            }
            engine(pos, displacement).tick(pos, gameTime);
        }
    }

    /**
     * Runs entity displacement against the caller's complete entity-query hypothesis.
     */
    public void tickPistons(long gameTime, PistonPush push, PistonPush.Query entities) {
        Objects.requireNonNull(push);
        Objects.requireNonNull(entities);
        tickPistons(gameTime, (pos, piston) -> push.push(pos, piston, piston.progress() + 0.5F, entities));
    }

    public Set<Unmodeled> dynamicGaps() {
        assert valid();
        var gaps = EnumSet.copyOf(dynamicGaps);
        if (entities != null)
            gaps.addAll(entities.unmodeled());
        if (!pistonClockResolved && !pistons.isEmpty())
            gaps.add(Unmodeled.PISTON_TICK_TIMING);
        if (!pistonClockResolved && shulkers.values().stream().anyMatch(e -> !e.lid.isClosed()))
            gaps.add(Unmodeled.SHULKER_TICK_TIMING);
        if (!predictions.uncertain.isEmpty())
            gaps.add(Unmodeled.BLOCK_PREDICTION);
        return Collections.unmodifiableSet(gaps);
    }

    public void resolveInitialPistonClock() {
        if (pistons.isEmpty() && shulkers.values().stream().allMatch(e -> e.lid.isClosed()))
            pistonClockResolved = true;
    }

    /**
     * {@code setServerVerifiedBlockState(pos, state, 19)} ({@code block_update}, each entry of {@code section_blocks_update}):
     * a position the client predicts only keeps the server's state until the acknowledgement.
     */
    public void setServerVerifiedBlock(int x, int y, int z, int state) {
        BlockPosition pos = new BlockPosition(x, y, z);
        mutate();
        if (!predictions.updateKnown(pos, state))
            setBlock(pos, state, 19);
    }

    /**
     * {@code BlockStatePredictionHandler.startPredicting} for the client action whose packet carries {@code sequence}.
     *
     * @param playerPos {@code player.position()} when the prediction begins
     */
    public void startPrediction(int sequence, ru.minecomplex.mac.util.math.Vec3d playerPos) {
        mutate();
        predictions.currentSequence = sequence;
        predictions.predicting = true;
        predictions.playerPos = playerPos;
    }

    /**
     * The end of {@code startPrediction}'s try block.
     */
    public void endPrediction() {
        mutate();
        predictions.predicting = false;
    }

    /**
     * The current prediction's local effect is not resolved: the client's blocks are uncertain until its acknowledgement.
     */
    public void predictionUncertain() {
        mutate();
        predictions.uncertain.add(predictions.currentSequence);
    }

    /**
     * Consuming useWithoutItem: trapdoor flags 2, door/gate flags 10. Containers change no client block state.
     */
    public void useOpenable(int x, int y, int z, float yaw) {
        int before = getBlock(x, y, z);
        if (before == UNLOADED)
            return;
        var type = registry.block(before);
        boolean trapdoor = type.isInstance("TrapDoorBlock");
        if (!trapdoor && !type.isInstance("DoorBlock") && !type.isInstance("FenceGateBlock"))
            return;
        var facts = PistonProperties.get();
        boolean wasOpen = "true".equals(facts.value(before, "open"));
        int after = facts.with(before, "open", Boolean.toString(!wasOpen));
        if (type.isInstance("FenceGateBlock") && !wasOpen) {
            // Direction.fromYRot: floor(double yaw / 90 + .5), SOUTH/WEST/NORTH/EAST.
            BlockDirection facing = new BlockDirection[] { BlockDirection.SOUTH, BlockDirection.WEST, BlockDirection.NORTH, BlockDirection.EAST }[(int) Math.floor((double) yaw / 90.0 + 0.5) & 3];
            if (facts.value(before, "facing").equals(facing.opposite().name().toLowerCase(Locale.ROOT)))
                after = facts.with(after, "facing", facing.name().toLowerCase(Locale.ROOT));
        }
        boolean unresolved = dynamicGaps.contains(Unmodeled.PISTON_NEIGHBOURS);
        setBlock(new BlockPosition(x, y, z), after, trapdoor ? 2 : 10);
        if (!unresolved && dynamicGaps.remove(Unmodeled.PISTON_NEIGHBOURS))
            predictionUncertain();
    }

    /**
     * {@code MultiPlayerGameMode.destroyBlock}'s change inside a prediction: {@code setBlock(pos, fluid.createLegacyBlock(), 11)}
     * (air, or the liquid a waterlogged block holds), with neighbour shape updates. A neighbour callback the model does not
     * port leaves the client's blocks uncertain only until this prediction's acknowledgement (the client then syncs every
     * block it predicted to the server's state).
     *
     * @return whether the block changed
     */
    public boolean destroyBlock(int x, int y, int z) {
        BlockPosition pos = new BlockPosition(x, y, z);
        int before = getBlock(x, y, z);
        if (before == UNLOADED)
            return false;
        boolean unresolved = dynamicGaps.contains(Unmodeled.PISTON_NEIGHBOURS);
        setBlock(pos, legacyFluidBlock(before), 11);
        if (!unresolved && dynamicGaps.remove(Unmodeled.PISTON_NEIGHBOURS))
            predictions.uncertain.add(predictions.currentSequence);
        return getBlock(x, y, z) != before;
    }

    /**
     * {@code getFluidState().createLegacyBlock()}: air without a fluid, else the liquid block of its legacy level.
     */
    private int legacyFluidBlock(int state) {
        String fluid = registry.fluid(state);
        if (fluid.equals("minecraft:empty"))
            return AIR;
        boolean water = fluid.equals("minecraft:water") || fluid.equals("minecraft:flowing_water");
        if (!water && !fluid.equals("minecraft:lava") && !fluid.equals("minecraft:flowing_lava")) {
            throw new IllegalStateException("Unknown fluid " + fluid);
        }
        // FlowingFluid.getLegacyLevel
        int level = registry.has(state, BlockRegistry.FLAG_FLUID_SOURCE) ? 0 : 8 - Math.min(registry.fluidAmount(state), 8) + (registry.has(state, BlockRegistry.FLAG_FLUID_FALLING) ? 8 : 0);
        var properties = PistonProperties.get();
        return properties.with(properties.defaultState(water ? "minecraft:water" : "minecraft:lava"), "level", Integer.toString(level));
    }

    /**
     * {@code handleBlockChangedAck}: {@code endPredictionsUpTo(sequence)}. Each ended prediction whose kept state differs
     * from the block is set back ({@code syncBlockState}, flags 19) and listed with the player's position at the
     * prediction ({@link #takeRestored}), unless a teleport handled since covers it (26.1+).
     */
    public void blockChangedAck(int sequence) {
        mutate();
        boolean guard = registry != null;
        var ended = new ArrayList<Map.Entry<BlockPosition, BlockPredictions.Verified>>();
        for (var entry : predictions.states.entrySet()) if (entry.getValue().sequence <= sequence)
            ended.add(entry);
        for (var entry : ended) {
            predictions.states.remove(entry.getKey());
            BlockPosition pos = entry.getKey();
            BlockPredictions.Verified verified = entry.getValue();
            if (block(pos) == verified.state)
                continue;
            setBlock(pos, verified.state, 19);
            if (!guard || predictions.lastTeleportSequence < sequence) {
                predictions.restored.add(new BlockPredictions.Restored(pos, verified.state, verified.playerPos));
            }
        }
        predictions.uncertain.headSet(sequence, true).clear();
    }

    /**
     * {@code BlockStatePredictionHandler.onTeleport} (26.1+, {@code handleMovePlayer}).
     */
    public void predictionTeleport() {
        mutate();
        predictions.lastTeleportSequence = predictions.currentSequence;
    }

    /**
     * The blocks acknowledgements set back since the last call, with the player's position at their prediction: a player
     * colliding with one is put back there ({@code absSnapTo}). Vanilla ends predictions in hash order; with two of them
     * moving the player the order is not modelled (the caller reports it).
     */
    public List<BlockPredictions.Restored> takeRestored() {
        if (predictions.restored.isEmpty())
            return List.of();
        mutate();
        var taken = List.copyOf(predictions.restored);
        predictions.restored.clear();
        return taken;
    }

    /**
     * Whether the client predicts the block at the position (its server state is kept until an acknowledgement).
     */
    public boolean predicted(int x, int y, int z) {
        assert valid();
        return predictions.states.containsKey(new BlockPosition(x, y, z));
    }

    /**
     * The shulker lids this world holds within {@code area} (block positions), for diagnostics; nothing is created.
     */
    public Map<BlockPosition, ShulkerAnimation> shulkersIn(ru.minecomplex.mac.collision.Aabb area) {
        assert valid();
        var found = new LinkedHashMap<BlockPosition, ShulkerAnimation>();
        shulkers.forEach((pos, entry) -> {
            if (pos.x() + 1 > area.minX() && pos.x() < area.maxX() && pos.y() + 1 > area.minY() && pos.y() < area.maxY() && pos.z() + 1 > area.minZ() && pos.z() < area.maxZ()) {
                found.put(pos, entry.lid);
            }
        });
        return found;
    }

    /**
     * Level.getBlockEntity uses IMMEDIATE; shape and suffocation lookups can lazily create a ticker.
     */
    public ShulkerAnimation shulker(BlockPosition pos, boolean create) {
        assert valid();
        if (!blockEntityPositionValid(pos))
            return null;
        ChunkColumn column = column(pos.x() >> 4, pos.z() >> 4);
        if (column == null || pos.y() < minY || pos.y() >= minY + height)
            return null;
        var entry = shulkers.get(pos);
        if (entry != null)
            return entry.lid;
        if (!create || registry == null || !registry.block(block(pos)).isInstance("ShulkerBoxBlock"))
            return null;
        mutate();
        var lid = ShulkerAnimation.closed();
        shulkers.put(pos, new ShulkerEntry(lid, column.chunkGeneration()));
        tickerOrder.computeIfAbsent(pos, ignored -> new Object());
        return lid;
    }

    /**
     * Level.getBlockEntity; the generated lifetime oracle pins ChunkPos/ChunkPyramid's margin on all releases.
     */
    private boolean blockEntityPositionValid(BlockPosition pos) {
        return pos.y() >= minY && pos.y() < minY + height && (registry == null || Math.max(Math.abs(pos.x() >> 4), Math.abs(pos.z() >> 4)) <= 2097061);
    }

    public boolean shulkerEvent(BlockEvent event) {
        var pos = new BlockPosition(event.x(), event.y(), event.z());
        var lid = shulker(pos, true);
        if (lid == null)
            return false;
        mutate();
        shulkers.put(pos, new ShulkerEntry(lid.event(event.action(), event.parameter()), shulkers.get(pos).chunkGeneration));
        return event.action() == 1;
    }

    /**
     * A potent sulfur block entity at {@code pos} (26.2+): created with its chunk's block entities
     * ({@code replaceWithPacketData}, in packet order) or by a new block ({@code setBlockState}), its ticker appended.
     */
    public void geyser(BlockPosition pos) {
        assert valid();
        if (registry == null || geysers.containsKey(pos))
            return;
        ChunkColumn column = column(pos.x() >> 4, pos.z() >> 4);
        if (column == null || pos.y() < minY || pos.y() >= minY + height)
            return;
        if (!registry.block(block(pos)).isInstance("PotentSulfurBlock"))
            return;
        mutate();
        geysers.put(pos, column.chunkGeneration());
        tickerOrder.computeIfAbsent(pos, ignored -> new Object());
    }

    /**
     * {@code PotentSulfurBlock.getTicker} on the client: an {@code erupting} or {@code continuous} block runs
     * {@code LAUNCH_ENTITY_TICKER} (after the plume ticker, which only spawns particles and sounds), in a ticking chunk
     * ({@code LevelChunk.isTicking}: inside the border) while the block is still potent sulfur.
     */
    private void tickGeyser(BlockPosition pos, GeyserLaunch.Query entities) {
        var held = heldColumn(pos);
        if (held == null || held.chunkGeneration() != geysers.get(pos) || !tickerInsideBorder(pos))
            return;
        int state = heldBlock(pos);
        if (!registry.block(state).isInstance("PotentSulfurBlock"))
            return;
        String properties = registry.properties(state);
        if (!properties.contains("potent_sulfur_state=erupting") && !properties.contains("potent_sulfur_state=continuous"))
            return;
        // findNoxiousGasSourceBlock: the first block above the water column (up to 4 blocks of source water)
        Integer source = null;
        for (int y = pos.y() + 1; y <= pos.y() + 5; y++) {
            var at = new BlockPosition(pos.x(), y, pos.z());
            int above = block(at);
            boolean waterLogged = "minecraft:water".equals(registry.fluid(above));
            // CollisionContext.positionContext(origin.getY())
            Boolean passable = geyserPassable(above, at, pos.y());
            if (passable == null) {
                dynamicGaps.add(Unmodeled.GEYSER);
                return;
            }
            if (!waterLogged || !registry.block(above).name().equals("minecraft:water") && !passable) {
                if (registry.isAir(above) || passable)
                    source = y;
                break;
            }
        }
        if (source == null)
            return;
        int waterBlocks = source - pos.y() - 1;
        // getUnobstructedBlockCount from the block above, up to 6 blocks per water block
        int forceHeight = 6 * waterBlocks;
        for (int i = 0; i < 6 * waterBlocks; i++) {
            var at = new BlockPosition(pos.x(), pos.y() + 1 + i, pos.z());
            // CollisionContext.positionContext(pos.below().getY()) of the block above the geyser: the geyser's y
            Boolean passable = geyserPassable(block(at), at, pos.y());
            if (passable == null) {
                dynamicGaps.add(Unmodeled.GEYSER);
                return;
            }
            if (!passable) {
                forceHeight = i;
                break;
            }
        }
        if (entities == null) {
            dynamicGaps.add(Unmodeled.GEYSER);
            return;
        }
        GeyserLaunch.launch(GeyserLaunch.column(pos, forceHeight), waterBlocks, entities);
    }

    /**
     * {@code isGeyserPassableBlock}: air, the water block, or an empty collision shape for
     * {@code CollisionContext.positionContext(contextY)} ({@code PositionCollisionContext}: not descending, no entity,
     * {@code isAbove(shape, pos) = y > pos.y + shape.maxY - 1.0E-5F}): a liquid and powder snow have none (they collide
     * only for an entity), scaffolding its stable shape above it and the unstable bottom above the block below it, a
     * moving piston its block entity's shape. {@code null}: a context-dependent block not ported.
     */
    private Boolean geyserPassable(int state, BlockPosition pos, double contextY) {
        BlockType block = registry.block(state);
        if (registry.isAir(state) || block.name().equals("minecraft:water"))
            return true;
        if (block.isInstance("MovingPistonBlock")) {
            return BlockCollisions.emptyContext(registry, this, dynamicGaps::add).shape(state, pos.x(), pos.y(), pos.z()).isEmpty();
        }
        if (registry.has(state, BlockRegistry.FLAG_CONTEXT_DEPENDENT)) {
            if (block.isInstance("LiquidBlock") || block.isInstance("PowderSnowBlock"))
                return true;
            if (block.isInstance("ScaffoldingBlock")) {
                // ScaffoldingBlock.getCollisionShape: Shapes.block() (max 1), SHAPE_BELOW_BLOCK (max 0)
                if (contextY > pos.y() + 1.0 - 1.0E-5F)
                    return false;
                String properties = registry.properties(state);
                boolean unstableBottom = !properties.contains("distance=0") && properties.contains("bottom=true") && contextY > pos.y() + 0.0 - 1.0E-5F;
                return !unstableBottom;
            }
            return null;
        }
        return BlockCollisions.staticShape(registry, state, pos.x(), pos.y(), pos.z()).isEmpty();
    }

    private void tickShulker(BlockPosition pos, ShulkerPush.Query entities) {
        var entry = shulkers.get(pos);
        if (entry == null)
            return;
        var held = heldColumn(pos);
        if (held == null || held.chunkGeneration() != entry.chunkGeneration || !tickerInsideBorder(pos))
            return;
        int state = heldBlock(pos);
        if (!registry.block(state).isInstance("ShulkerBoxBlock"))
            return;
        var before = entry.lid;
        var after = before.tick();
        mutate();
        shulkers.put(pos, new ShulkerEntry(after, entry.chunkGeneration));
        if (before.status() == ShulkerAnimation.Status.OPENING && (before.progress() == 0.0F || after.status() == ShulkerAnimation.Status.OPENED) || before.status() == ShulkerAnimation.Status.CLOSING && (before.progress() == 1.0F || after.status() == ShulkerAnimation.Status.CLOSED)) {
            var previous = EnumSet.copyOf(dynamicGaps);
            neighbourShapes(state, pos, 3, false);
            if (!previous.contains(Unmodeled.PISTON_NEIGHBOURS) && dynamicGaps.remove(Unmodeled.PISTON_NEIGHBOURS))
                dynamicGaps.add(Unmodeled.SHULKER_NEIGHBOURS);
        }
        if (before.status() == ShulkerAnimation.Status.OPENING) {
            if (entities == null)
                dynamicGaps.add(Unmodeled.SHULKER_ENTITIES);
            else
                ShulkerPush.push(pos, direction(state), after, entities);
        }
    }

    /**
     * Chosen client block-entity phase; mixed piston/shulker actors retain their insertion order.
     */
    public void tickBlockEntities(long gameTime, PistonPush push, PistonPush.Query pistonsQuery, ShulkerPush.Query shulkersQuery) {
        tickBlockEntities(gameTime, push, pistonsQuery, shulkersQuery, null);
    }

    /**
     * {@link #tickBlockEntities(long, PistonPush, PistonPush.Query, ShulkerPush.Query)} with the geysers' entities.
     */
    public void tickBlockEntities(long gameTime, PistonPush push, PistonPush.Query pistonsQuery, ShulkerPush.Query shulkersQuery, GeyserLaunch.Query geysersQuery) {
        assert valid();
        for (var ticker : new ArrayList<>(tickerOrder.entrySet())) {
            BlockPosition pos = ticker.getKey();
            if (tickerOrder.get(pos) != ticker.getValue())
                continue;
            if (geysers.containsKey(pos))
                tickGeyser(pos, geysersQuery);
            else if (shulkers.containsKey(pos))
                tickShulker(pos, shulkersQuery);
            else if (pistons.containsKey(pos)) {
                var entry = pistons.get(pos);
                var held = heldColumn(pos);
                if (held == null || held.chunkGeneration() != entry.chunkGeneration || !tickerInsideBorder(pos))
                    continue;
                if (entry.entity.direction() == null || !Float.isFinite(entry.entity.progress())) {
                    dynamicGaps.add(Unmodeled.PISTON_BLOCK_ENTITY);
                    continue;
                }
                engine(pos, push == null || pistonsQuery == null ? null : (at, piston) -> push.push(at, piston, piston.progress() + 0.5F, pistonsQuery)).tick(pos, gameTime);
            }
        }
    }

    /**
     * Records a client handler input in the same world/chunk hypothesis as block changes.
     */
    void dynamicInput(int x, int y, int z, BlockInput input) {
        dynamicInput(x, y, z, input, true);
    }

    void dynamicInput(int x, int y, int z, BlockInput input, boolean unresolved) {
        ChunkColumn column = column(x >> 4, z >> 4);
        if (column == null || y < minY || y >= minY + height)
            return;
        mutate();
        if (!column.ownedBy(token)) {
            column = column.forkFor(token);
            ring[index(x >> 4, z >> 4)] = column;
        }
        column.dynamicInput(input);
        // A piston event can affect neighbours and entities outside this column. Unload must not clear this gap.
        dynamicApplicationUnresolved |= unresolved;
    }

    /**
     * Raw handler inputs still need local replay, animation ticks, neighbour updates and entity pushes.
     */
    public boolean dynamicApplicationUnresolved() {
        assert valid();
        return dynamicApplicationUnresolved;
    }

    /**
     * Makes a column this world loads its own (it must not be referenced by any other world any more).
     */
    void claim(ChunkColumn column) {
        column.claim(token);
    }

    private void mutate() {
        assert valid();
        generation++;
    }
}
