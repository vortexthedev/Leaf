package ru.minecomplex.mac.replica;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import ru.minecomplex.mac.listener.MultiBlockChangeBitRepackHandler;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import ru.minecomplex.mac.world.ChunkPacketReader;
import ru.minecomplex.mac.entity.EntityDataItems;
import ru.minecomplex.mac.player.connection.PlayerConnectionContext;
import ru.minecomplex.mac.packet.Packets;
import ru.minecomplex.mac.player.transaction.ChangeLog;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.world.BlockEntityData;
import ru.minecomplex.mac.world.BlockEvent;
import ru.minecomplex.mac.world.BlockInput;
import ru.minecomplex.mac.world.BlockPosition;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.BlockTags;
import ru.minecomplex.mac.world.BlockType;
import ru.minecomplex.mac.world.ChunkColumn;
import ru.minecomplex.mac.world.PalettedSection;
import ru.minecomplex.mac.world.ResolvedBlockTags;

/**
 * The client's world across the synchronization window (Grim's {@code CompensatedWorld}). World changes observed on the
 * wire are pending until a later marker is acknowledged; then they are applied, in wire order, to the acknowledged world.
 * For a tick in an uncertain window, {@link #viewAt} materialises the world after any admissible prefix of the pending
 * changes (a copy-on-write fork, so views and promotion run the same code).
 *
 * <p>World changes ({@link #isWorldChange}) are the login, respawn, chunk cache, chunk, light, biome, block, block event,
 * block entity, tag and border packets, entity relationship and entity state packets, the level time, rain and the
 * connection's teams and player infos. They are kept as native 26.3 packets; persistent hypotheses retain
 * local piston/shulker ticks and passenger state independently of marker promotion.
 */
public final class WorldModel {
    private final BlockRegistry registry;
    private final PlayerConnectionContext facts;
    private final int globalBits;
    private final ChangeLog<Object> pending = new ChangeLog<>();
    private final Map<ClientboundLevelChunkWithLightPacket, ChunkColumn> decoded = new IdentityHashMap<>();
    // Registry snapshots follow the lifetime of native packets retained by pending hypotheses.
    private final Map<ClientboundLevelChunkWithLightPacket, RegistryAccess> chunkRegistries = new java.util.WeakHashMap<>();

    public void rememberChunkRegistries(ClientboundLevelChunkWithLightPacket packet, RegistryAccess registries) {
        chunkRegistries.put(packet, java.util.Objects.requireNonNull(registries));
    }

    private final java.util.ArrayList<Hypothesis> hypotheses = new java.util.ArrayList<>();
    private CompensatedWorld acknowledged;
    private long settledThrough = -1;
    /** The block tags of the configuration phase ({@code update_tags} before the login), {@code null}: none seen. */
    private BlockTags loginTags;

    public WorldModel(BlockRegistry registry) {
        this(registry, new PlayerConnectionContext(null, null));
    }

    public WorldModel(BlockRegistry registry, PlayerConnectionContext facts) {
        this.registry = registry;
        this.facts = facts;
        this.globalBits = 32 - Integer.numberOfLeadingZeros(registry.stateCount() - 1);
    }

    /** Whether the client's handler of {@code packet} changes its level, its entities or the connection's level state. */
    public static boolean isWorldChange(Object packet) {
        return switch (packet) {
            case ClientboundLoginPacket ignored -> true;
            case ClientboundRespawnPacket ignored -> true;
            case ClientboundSetChunkCacheCenterPacket ignored -> true;
            case ClientboundSetChunkCacheRadiusPacket ignored -> true;
            case ClientboundLevelChunkWithLightPacket ignored -> true;
            case ClientboundForgetLevelChunkPacket ignored -> true;
            // the client's light queue and biomes (the rain at a position), the connection's block tags
            case ClientboundUpdateTagsPacket ignored -> true;
            case ClientboundLightUpdatePacket ignored -> true;
            case ClientboundChunksBiomesPacket ignored -> true;
            case ClientboundBlockUpdatePacket ignored -> true;
            case MultiBlockChangeBitRepackHandler.Update ignored -> true;
            case ClientboundBlockChangedAckPacket ignored -> true;
            case ClientboundBlockEventPacket ignored -> true;
            case ClientboundBlockEntityDataPacket ignored -> true;
            case ClientboundInitializeBorderPacket ignored -> true;
            case ClientboundSetBorderCenterPacket ignored -> true;
            case ClientboundSetBorderSizePacket ignored -> true;
            case ClientboundSetBorderLerpSizePacket ignored -> true;
            case ClientboundAddEntityPacket ignored -> true;
            case ClientboundRemoveEntitiesPacket ignored -> true;
            case ClientboundSetPassengersPacket ignored -> true;
            // other entities' transforms (boats, happy ghasts, pushables): EntityMotions
            case ClientboundTeleportEntityPacket ignored -> true;
            case ClientboundEntityPositionSyncPacket ignored -> true;
            // other entities' synced data, attributes and effects (the own player's belong to PlayerModel; the entities
            // ignore them), equipment and the level time
            case ClientboundSetEntityDataPacket data -> !EntityDataItems.of(data).isEmpty();
            case ClientboundUpdateAttributesPacket ignored -> true;
            case ClientboundUpdateMobEffectPacket ignored -> true;
            case ClientboundRemoveMobEffectPacket ignored -> true;
            case ClientboundSetEquipmentPacket equipment -> CompensatedEntities.modelledEquipment(equipment);
            // other entities' delta movement (where a mount starts from when the player drives it)
            case ClientboundSetEntityMotionPacket ignored -> true;
            case ClientboundSetTimePacket ignored -> true;
            // the client's rain level (a riptide release reads Level.isRaining)
            case ClientboundGameEventPacket game -> CompensatedWorld.rainEvent(game);
            // the connection's scoreboard teams and player profiles (who pushes whom)
            case ClientboundSetPlayerTeamPacket ignored -> true;
            case ClientboundPlayerInfoUpdatePacket info -> Packets.playerInfoModelled(info);
            case ClientboundPlayerInfoRemovePacket ignored -> true;
            default -> Packets.entityMove(packet);
        };
    }

    /** A world change left at wire position {@code seq}. */
    public void sent(long seq, Object change) {
        pending.append(seq, change);
        for (Hypothesis hypothesis : hypotheses) hypothesis.changes.append(seq, change);
    }

    /** Applies every change the client certainly processed. Windows must not go backwards. */
    public void settle(SyncWindow window) {
        if (window.certainThrough() < settledThrough) {
            throw new IllegalStateException("Window " + window + " is older than the settled state (" + settledThrough + ")");
        }
        settledThrough = window.certainThrough();
        while (!pending.isEmpty() && pending.first().seq() <= settledThrough) {
            ChangeLog.Entry<Object> entry = pending.first();
            acknowledged = apply(acknowledged, entry.change(), true);
            if (entry.change() instanceof ClientboundLevelChunkWithLightPacket chunk) {
                decoded.remove(chunk);
            }
            pending.truncateThrough(entry.seq());
        }
    }

    /** The world after every certainly processed change; {@code null} before the client joined. */
    public CompensatedWorld acknowledged(SyncWindow window) {
        settle(window);
        return acknowledged;
    }

    /**
     * Ends of the admissible prefixes of pending world changes: {@code certainThrough} (none applied) followed by
     * the wire position of each pending change inside the window, in order.
     */
    public long[] prefixEnds(SyncWindow window) {
        settle(window);
        List<ChangeLog.Entry<Object>> inWindow = pending.between(window.certainThrough(), window.possibleBefore());
        long[] ends = new long[inWindow.size() + 1];
        ends[0] = window.certainThrough();
        for (int i = 0; i < inWindow.size(); i++) {
            ends[i + 1] = inWindow.get(i).seq();
        }
        return ends;
    }

    /**
     * The world after the pending changes up to and including wire position {@code appliedThrough}. Returns the
     * acknowledged world itself when no pending change is included; callers must not mutate the result.
     */
    public CompensatedWorld viewAt(SyncWindow window, long appliedThrough) {
        settle(window);
        if (appliedThrough >= window.possibleBefore()) {
            throw new IllegalArgumentException("Prefix end " + appliedThrough + " outside " + window);
        }
        List<ChangeLog.Entry<Object>> changes = pending.between(window.certainThrough(), appliedThrough + 1);
        if (changes.isEmpty()) {
            return acknowledged;
        }
        CompensatedWorld view = acknowledged == null ? null : acknowledged.fork();
        for (ChangeLog.Entry<Object> entry : changes) {
            view = apply(view, entry.change(), false);
        }
        return view;
    }

    private CompensatedWorld apply(CompensatedWorld world, Object change, boolean promote) {
        return apply(world, change, promote, false);
    }
    private CompensatedWorld apply(CompensatedWorld world, Object change, boolean promote, boolean independent) {
        switch (change) {
            case ClientboundUpdateTagsPacket update -> {
                // the configuration phase's tags come before any level
                if (world == null) {
                    loginTags = ResolvedBlockTags.apply(loginTags, update);
                    return null;
                }
                world.tags(ResolvedBlockTags.apply(world.tags(), update));
                return world;
            }
            case ClientboundLoginPacket join -> {
                var dimension = join.commonPlayerSpawnInfo().dimensionType().value();
                var fresh = new CompensatedWorld(join.commonPlayerSpawnInfo().dimension().identifier().toString(), dimension.minY(), dimension.height(), join.chunkRadius(), registry);
                fresh.canHaveWeather(Packets.canHaveWeather(join, dimension));
                fresh.tags(world != null && world.tags() != null ? world.tags() : loginTags);
                fresh.initializeEntities(join.playerId());
                fresh.entities().localName(Packets.localName(join, facts));
                if (independent) fresh.resolveInitialPistonClock();
                return fresh;
            }
            case ClientboundRespawnPacket respawn -> {
                String name = respawn.commonPlayerSpawnInfo().dimension().identifier().toString();
                if (world != null && !name.equals(world.dimension())) {
                    var dimension = respawn.commonPlayerSpawnInfo().dimensionType().value();
                    var fresh = new CompensatedWorld(name, dimension.minY(), dimension.height(), world.serverRadius(), registry);
                    fresh.canHaveWeather(Packets.canHaveWeather(respawn, dimension));
                    fresh.tags(world.tags());
                    if (world.entities() != null) {
                        fresh.initializeEntities(world.entities().localPlayerId());
                        fresh.entities().inheritConnectionState(world.entities());
                    }
                    if (independent) fresh.resolveInitialPistonClock();
                    return fresh;
                }
                if (world != null) world.respawnLocalEntity();
                return world;
            }
            default -> {
            }
        }
        if (world == null) {
            return null;
        }
        switch (change) {
            case ClientboundSetTimePacket time -> world.gameTime(time.gameTime());
            case ClientboundGameEventPacket event -> world.rain(event);
            case ClientboundInitializeBorderPacket border -> world.border(border);
            case ClientboundSetBorderCenterPacket border -> world.border(border);
            case ClientboundSetBorderSizePacket border -> world.border(border);
            case ClientboundSetBorderLerpSizePacket border -> world.border(border);
            case ClientboundSetChunkCacheCenterPacket center -> world.setCenter(center.getX(), center.getZ());
            case ClientboundSetChunkCacheRadiusPacket radius -> world.setRadius(radius.getRadius());
            case ClientboundLevelChunkWithLightPacket chunk -> {
                ChunkColumn column = independent ? decode(chunk) : decoded.computeIfAbsent(chunk, this::decode);
                if (promote) {
                    world.claim(column);
                }
                world.load(column);
                var data = chunk;
                // handleLevelChunkWithLight queues the light data (also for a chunk out of range)
                world.queueLight(data.x(), data.z());
                for (var entity : ChunkPacketReader.blockEntities(data)) {
                    if ((entity.x() >> 4) != data.x() || (entity.z() >> 4) != data.z()) {
                        throw new IllegalArgumentException("Block entity outside its packet column");
                    }
                    dynamicInput(world, entity.x(), entity.y(), entity.z(), entity, true);
                    // Level.getBlockEntity creates a shulker box's block entity (and its ticker) in packet order
                    world.shulker(new BlockPosition(entity.x(), entity.y(), entity.z()), true);
                    // the potent sulfur block entities' tickers (geysers), in packet order
                    world.geyser(new BlockPosition(entity.x(), entity.y(), entity.z()));
                }
            }
            case ClientboundForgetLevelChunkPacket forget -> {
                world.forget(forget.pos().x(), forget.pos().z());
                // queueLightRemoval
                world.queueLight(forget.pos().x(), forget.pos().z());
            }
            case ClientboundLightUpdatePacket light -> world.queueLight(light.x(), light.z());
            // the biomes the model reads are the server's (Precipitation); a change matters while in flight
            case ClientboundChunksBiomesPacket ignored -> { }
            case ClientboundBlockUpdatePacket update -> {
                var pos = update.getPos();
                world.setServerVerifiedBlock(pos.getX(), pos.getY(), pos.getZ(), net.minecraft.world.level.block.Block.getId(update.getBlockState()));
            }
            case ClientboundBlockChangedAckPacket ack -> world.blockChangedAck(ack.sequence());
            case ClientboundBlockEventPacket action -> {
                var event = BlockEvent.of(action);
                dynamicInput(world, event.x(), event.y(), event.z(), event);
            }
            case ClientboundBlockEntityDataPacket data -> {
                var entity = BlockEntityData.of(data);
                dynamicInput(world, entity.x(), entity.y(), entity.z(), entity);
            }
            case MultiBlockChangeBitRepackHandler.Update update -> {
                for (int i = 0; i < update.count(); i++) {
                    world.setServerVerifiedBlock(update.blockX(i), update.blockY(i), update.blockZ(i), update.stateId(i));
                }
            }
            default -> {
                if (!isWorldChange(change)) throw new IllegalArgumentException("Not a world change: " + change);
                world.entityInput(change);
            }
        }
        return world;
    }

    private void dynamicInput(CompensatedWorld world, int x, int y, int z, BlockInput input) {
        dynamicInput(world, x, y, z, input, false);
    }
    private void dynamicInput(CompensatedWorld world, int x, int y, int z, BlockInput input, boolean chunkEntry) {
        int state = world.getBlock(x, y, z);
        if (!registry.isValidState(state)) return;
        BlockType block = registry.block(state);
        if (block.isInstance("ShulkerBoxBlock") || block.isInstance("PistonBaseBlock") || block.isInstance("MovingPistonBlock")) {
            world.dynamicInput(x, y, z, input, false);
            if (block.isInstance("ShulkerBoxBlock")) {
                if (input instanceof BlockEvent event) world.shulkerEvent(event);
                // handleBlockEntityData: getBlockEntity creates it; shulker NBT holds no lid fields
                else if (!chunkEntry && input instanceof BlockEntityData) world.shulker(new BlockPosition(x, y, z), true);
            }
            if (input instanceof BlockEvent event && block.isInstance("PistonBaseBlock")) world.pistonEvent(event);
            else if (input instanceof BlockEntityData entity && block.isInstance("MovingPistonBlock")) world.pistonData(entity);
        }
    }

    /** The column a {@code level_chunk_with_light} loads: block palettes and packed storage as decoded with the client's version. */
    private ChunkColumn decode(ClientboundLevelChunkWithLightPacket chunk) {
        RegistryAccess registries = chunkRegistries.get(chunk);
        if (registries == null) registries = ru.minecomplex.mac.packet.NativeRuntime.registries();
        return new ChunkColumn(chunk.x(), chunk.z(),
                ChunkPacketReader.sections(chunk, registries).toArray(PalettedSection[]::new));
    }

    public BlockRegistry registry() {
        return registry;
    }

    /** Pending world changes (diagnostics). */
    public int pendingCount() {
        return pending.size();
    }

    /** Persistent local client state. Marker promotion cannot reset its piston ticks or mutate its chunks. */
    public Hypothesis hypothesis(SyncWindow window, long appliedThrough) {
        settle(window);
        if (hypotheses.size() >= 64) throw new IllegalStateException("Too many live world hypotheses; retire unused forks");
        var hypothesis = new Hypothesis(acknowledged == null ? null : acknowledged.detachedCopy(), settledThrough);
        if (hypothesis.world != null) hypothesis.world.resolveInitialPistonClock();
        for (var entry : pending.between(settledThrough, Long.MAX_VALUE)) hypothesis.changes.append(entry.seq(), entry.change());
        hypotheses.add(hypothesis);
        try { hypothesis.apply(window, appliedThrough); }
        catch (RuntimeException failure) { hypothesis.close(); throw failure; }
        return hypothesis;
    }

    public final class Hypothesis implements AutoCloseable {
        private CompensatedWorld world;
        private final ChangeLog<Object> changes = new ChangeLog<>();
        private long appliedThrough;
        private boolean closed;
        private Hypothesis(CompensatedWorld world, long appliedThrough) { this.world = world; this.appliedThrough = appliedThrough; }
        public CompensatedWorld world() { if (closed) throw new IllegalStateException("Closed hypothesis"); return world; }
        public long appliedThrough() { return appliedThrough; }
        public void apply(SyncWindow window, long through) {
            if (closed || through < appliedThrough || through < window.certainThrough() || through >= window.possibleBefore())
                throw new IllegalArgumentException("Invalid hypothesis prefix " + through + " in " + window);
            for (var entry : changes.between(appliedThrough, through + 1)) world = WorldModel.this.apply(world, entry.change(), false, true);
            changes.truncateThrough(through); appliedThrough = through;
        }
        /** The changes sent before wire position {@code before} that this hypothesis has not applied, in wire order. */
        public List<Object> pendingBefore(long before) {
            var pending = new java.util.ArrayList<Object>();
            for (var entry : changes.between(appliedThrough, before)) pending.add(entry.change());
            return pending;
        }
        /** The wire positions of {@link #pendingBefore}. */
        public long[] pendingSeqs(long before) {
            return changes.between(appliedThrough, before).stream().mapToLong(ChangeLog.Entry::seq).toArray();
        }
        /**
         * One step of an application interleaved with another model's changes: the pending changes through {@code through}.
         * The interleaving caller ends with {@link #apply}, which checks the window.
         */
        public void applyStep(long through) {
            if (closed || through < appliedThrough) throw new IllegalArgumentException("Invalid hypothesis step " + through);
            for (var entry : changes.between(appliedThrough, through + 1)) world = WorldModel.this.apply(world, entry.change(), false, true);
            changes.truncateThrough(through); appliedThrough = through;
        }
        public Hypothesis fork() {
            if (closed) throw new IllegalStateException("Closed hypothesis");
            if (hypotheses.size() >= 64) throw new IllegalStateException("Too many live world hypotheses");
            var fork = new Hypothesis(world == null ? null : world.detachedCopy(), appliedThrough);
            for (var entry : changes.between(appliedThrough, Long.MAX_VALUE)) fork.changes.append(entry.seq(), entry.change());
            hypotheses.add(fork); return fork;
        }
        @Override public void close() { hypotheses.remove(this); closed = true; }
    }
}
