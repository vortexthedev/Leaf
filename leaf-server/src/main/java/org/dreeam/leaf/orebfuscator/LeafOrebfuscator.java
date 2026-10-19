package org.dreeam.leaf.orebfuscator;

import com.google.common.hash.Hashing;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.shorts.Short2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.dreeam.leaf.orebfuscator.cache.ObfuscationCache;
import org.dreeam.leaf.orebfuscator.config.OrebfuscatorConfig;
import org.dreeam.leaf.orebfuscator.config.ProximityConfig;
import org.dreeam.leaf.orebfuscator.config.WorldConfigBundle;
import org.dreeam.leaf.orebfuscator.obfuscation.ChunkObfuscator;
import org.dreeam.leaf.orebfuscator.obfuscation.ObfuscationResult;
import org.dreeam.leaf.orebfuscator.player.OrebfuscatorPlayer;
import org.dreeam.leaf.orebfuscator.proximity.ProximitySystem;
import org.dreeam.leaf.orebfuscator.util.OrebfuscatorExecutor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Built-in port of Orebfuscator (https://github.com/Imprex-Development/Orebfuscator, GPL-3.0),
 * based on the local fork of version 5.0.0-b0 and rewritten on top of the Minecraft classes.
 * <p>
 * The plugin cancelled the encoded chunk packet with PacketEvents, obfuscated a raw copy and
 * resent it. Here the chunk packet is obfuscated before it is encoded: it is marked as not ready,
 * which holds back every following packet of that connection (see Connection#processQueue) until
 * the obfuscation is done, so the packet order is kept like before.
 */
@NullMarked
public final class LeafOrebfuscator {

    public static final Logger LOGGER = LogManager.getLogger("Orebfuscator");

    static final String PERMISSION_ADMIN = "orebfuscator.admin";
    static final String PERMISSION_BYPASS = "orebfuscator.bypass";

    private static volatile @Nullable LeafOrebfuscator instance;
    private static volatile boolean verbose;

    private final MinecraftServer server;
    private final OrebfuscatorConfig config;
    private final OrebfuscatorExecutor executor;
    private final @Nullable ObfuscationCache cache;
    private final Map<ServerPlayer, OrebfuscatorPlayer> players = new ConcurrentHashMap<>();
    private final Set<ClientboundLevelChunkWithLightPacket> pendingPackets = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private final Deobfuscator deobfuscator;
    private volatile boolean running = true;

    private LeafOrebfuscator(final MinecraftServer server, final OrebfuscatorConfig config) {
        this.server = server;
        this.config = config;
        this.executor = new OrebfuscatorExecutor(config.threads());
        this.cache = config.cacheEnabled() ? new ObfuscationCache(config, this.executor) : null;
        this.deobfuscator = new Deobfuscator(config, this.cache);
    }

    /* Lifecycle */

    public static void start(final MinecraftServer server) {
        if (!org.dreeam.leaf.config.modules.misc.Orebfuscator.enabled) {
            return;
        }

        if (Bukkit.getPluginManager().getPlugin("Orebfuscator") != null) {
            LOGGER.error("The Orebfuscator plugin is installed, the built-in Orebfuscator stays disabled. Remove the plugin to use the built-in one.");
            return;
        }

        final OrebfuscatorConfig config;
        try {
            config = new OrebfuscatorConfig(org.dreeam.leaf.config.modules.misc.Orebfuscator.settings, server.server.getWorldContainer().toPath());
        } catch (final RuntimeException e) {
            LOGGER.error("Invalid Orebfuscator config, the built-in Orebfuscator stays disabled and chunks are sent without obfuscation: {}", e.getMessage());
            return;
        }

        verbose = config.verbose();
        if (config.report() != null) {
            LOGGER.warn(config.report());
        }

        final LeafOrebfuscator orebfuscator = new LeafOrebfuscator(server, config);
        try {
            orebfuscator.enable();
        } catch (final RuntimeException e) {
            LOGGER.error("An error occurred while enabling the built-in Orebfuscator, chunks are sent without obfuscation", e);
            orebfuscator.disable();
            return;
        }

        instance = orebfuscator;
        LOGGER.info("Built-in Orebfuscator enabled");
    }

    private void enable() {
        if (this.config.proximityEnabled()) {
            new ProximitySystem(this, this.config, this.executor).start();
        }

        final PluginManager pluginManager = Bukkit.getPluginManager();
        addPermission(pluginManager, new Permission(PERMISSION_ADMIN, "Shows Orebfuscator config issues on join", PermissionDefault.OP));
        addPermission(pluginManager, new Permission(PERMISSION_BYPASS, "Bypass the obfuscation", PermissionDefault.FALSE));
        addPermission(pluginManager, new Permission("orebfuscator.*", "Access to all of Orebfuscator's features", PermissionDefault.FALSE,
            Map.of(PERMISSION_ADMIN, true, PERMISSION_BYPASS, true)));
    }

    private static void addPermission(final PluginManager pluginManager, final Permission permission) {
        if (pluginManager.getPermission(permission.getName()) == null) {
            pluginManager.addPermission(permission);
        }
    }

    public static void shutdown() {
        final LeafOrebfuscator orebfuscator = instance;
        if (orebfuscator != null) {
            instance = null;
            orebfuscator.disable();
        }
    }

    private void disable() {
        this.running = false;
        this.executor.shutdown();

        if (this.cache != null) {
            this.cache.close();
        }

        // pending obfuscations won't finish anymore, send what is left so the connections don't get stuck
        final List<ClientboundLevelChunkWithLightPacket> pending;
        synchronized (this.pendingPackets) {
            pending = new ArrayList<>(this.pendingPackets);
            this.pendingPackets.clear();
        }
        for (final ClientboundLevelChunkWithLightPacket packet : pending) {
            packet.setReady(true);
        }

        this.players.clear();
    }

    public static boolean isActive() {
        return instance != null;
    }

    /* Server hooks */

    /**
     * Called for every chunk packet before it is sent to the player.
     */
    public static void onSendChunk(final ServerPlayer player, final ClientboundLevelChunkWithLightPacket packet) {
        final LeafOrebfuscator orebfuscator = instance;
        if (orebfuscator != null) {
            orebfuscator.obfuscate(player, packet);
        }
    }

    /**
     * Called for every final block change. Obfuscated blocks next to a block that is no longer
     * occluding can be seen now, so their real state is sent. This covers everything the plugin
     * listened to (break, burn, explosions, pistons, entities, hoes) and changes by other plugins.
     */
    public static void onBlockChange(final Level level, final BlockPos pos, final BlockState oldState, final BlockState newState) {
        final LeafOrebfuscator orebfuscator = instance;
        if (orebfuscator != null && oldState != newState && oldState.isSolidRender() && !newState.isSolidRender()
            && level instanceof ServerLevel serverLevel) {
            orebfuscator.deobfuscator.deobfuscate(serverLevel, pos);
        }
    }

    /**
     * Called when a player starts to break a block, replaces the BlockDamageEvent listener.
     */
    public static void onStartDestroyBlock(final ServerLevel level, final BlockPos pos) {
        final LeafOrebfuscator orebfuscator = instance;
        if (orebfuscator != null && orebfuscator.config.updateOnBlockDamage()) {
            orebfuscator.deobfuscator.deobfuscate(level, pos);
        }
    }

    /**
     * Called after the PlayerJoinEvent.
     */
    public static void onPlayerJoin(final ServerPlayer player) {
        final LeafOrebfuscator orebfuscator = instance;
        if (orebfuscator == null) {
            return;
        }

        final org.bukkit.entity.Player bukkitPlayer = player.getBukkitEntity();
        if (orebfuscator.config.bypassNotification() && bukkitPlayer.hasPermission(PERMISSION_BYPASS)) {
            bukkitPlayer.sendMessage("[§bOrebfuscator§f]§7 You bypass Orebfuscator because you have the 'orebfuscator.bypass' permission.");
        }

        final String report = orebfuscator.config.report();
        if (report != null && (bukkitPlayer.isOp() || bukkitPlayer.hasPermission(PERMISSION_ADMIN))) {
            bukkitPlayer.sendMessage("[§bOrebfuscator§f]§c " + report);
        }
    }

    /**
     * Called after the PlayerQuitEvent.
     */
    public static void onPlayerQuit(final ServerPlayer player) {
        final LeafOrebfuscator orebfuscator = instance;
        if (orebfuscator != null) {
            orebfuscator.players.remove(player);
        }
    }

    /**
     * Called when a level is removed from the server.
     */
    public static void onLevelUnload(final ServerLevel level) {
        final LeafOrebfuscator orebfuscator = instance;
        if (orebfuscator != null) {
            orebfuscator.config.removeWorld(level);
        }
    }

    /**
     * Called when the player is told to forget a chunk, the proximity blocks of it are dropped.
     */
    public static void onForgetChunk(final ServerPlayer serverPlayer, final int chunkX, final int chunkZ) {
        final LeafOrebfuscator orebfuscator = instance;
        if (orebfuscator == null || !orebfuscator.config.proximityEnabled()) {
            return;
        }

        final OrebfuscatorPlayer player = orebfuscator.players.get(serverPlayer);
        if (player == null || bypasses(serverPlayer)) {
            return;
        }

        final ServerLevel level = player.level();
        final ProximityConfig proximityConfig = orebfuscator.config.world(level).proximity();
        if (proximityConfig != null && proximityConfig.isEnabled()) {
            player.removeChunk(level, chunkX, chunkZ);
        }
    }

    private void obfuscate(final ServerPlayer serverPlayer, final ClientboundLevelChunkWithLightPacket packet) {
        // don't add players that already left, the quit event won't remove them again
        if (serverPlayer.hasDisconnected() || bypasses(serverPlayer)) {
            return;
        }

        final OrebfuscatorPlayer player = this.players.computeIfAbsent(serverPlayer, key -> new OrebfuscatorPlayer(this.config, key));
        final ServerLevel level = player.level();
        final WorldConfigBundle bundle = this.config.world(level);
        if (!bundle.needsObfuscation()) {
            return;
        }

        final int chunkX = packet.x();
        final int chunkZ = packet.z();
        final byte[] data = packet.chunkData().buffer();

        // neighbors are looked up here on the main thread, missing ones are loaded later
        final @Nullable LevelChunk[] neighbors = new LevelChunk[ChunkObfuscator.NEIGHBOR_OFFSETS.length];
        for (int i = 0; i < neighbors.length; i++) {
            final int[] offset = ChunkObfuscator.NEIGHBOR_OFFSETS[i];
            neighbors[i] = level.getChunkSource().getChunkNow(chunkX + offset[0], chunkZ + offset[1]);
        }

        packet.setReady(false);
        this.pendingPackets.add(packet);

        try {
            final ObfuscationCache.Key cacheKey = new ObfuscationCache.Key(level.dimension(), ChunkPos.pack(chunkX, chunkZ));
            final byte[] hash = this.cache != null
                ? Hashing.murmur3_128().newHasher().putBytes(this.config.systemHash()).putBytes(data).hash().asBytes()
                : null;

            final CompletableFuture<@Nullable ObfuscationResult> cached = this.cache != null && hash != null
                ? this.cache.get(cacheKey, hash)
                : CompletableFuture.completedFuture(null);

            CompletableFuture<ObfuscationResult> future = cached.thenComposeAsync(result -> {
                if (result != null) {
                    return CompletableFuture.completedFuture(result);
                }
                return this.loadMissingNeighbors(level, chunkX, chunkZ, neighbors).thenApplyAsync(loaded -> {
                    final ObfuscationResult obfuscated = ChunkObfuscator.obfuscate(level, bundle, chunkX, chunkZ, loaded, data);
                    if (this.cache != null && hash != null) {
                        this.cache.put(cacheKey, hash, obfuscated);
                    }
                    return obfuscated;
                }, this.executor);
            }, this.executor);

            if (this.config.hasObfuscationTimeout()) {
                future = future.orTimeout(this.config.obfuscationTimeout(), TimeUnit.MILLISECONDS);
            }

            future.whenComplete((result, throwable) -> {
                try {
                    if (throwable == null) {
                        this.apply(packet, result);
                        player.addChunk(level, chunkX, chunkZ, result.proximityBlocks(), result.lavaObfuscated());
                    } else {
                        // the original data is sent, like the plugin did
                        logFailure(level, chunkX, chunkZ, throwable);
                    }
                } finally {
                    this.release(packet);
                }
            });
        } catch (final RuntimeException e) {
            LOGGER.error("An error occurred while requesting chunk obfuscation", e);
            this.release(packet);
        }
    }

    // same as the plugin: missing neighbors are loaded (and generated) asynchronously
    private CompletableFuture<@Nullable LevelChunk[]> loadMissingNeighbors(final ServerLevel level, final int chunkX, final int chunkZ, final @Nullable LevelChunk[] neighbors) {
        List<CompletableFuture<?>> futures = null;
        for (int i = 0; i < neighbors.length; i++) {
            if (neighbors[i] != null) {
                continue;
            }

            final int index = i;
            final int x = chunkX + ChunkObfuscator.NEIGHBOR_OFFSETS[i][0];
            final int z = chunkZ + ChunkObfuscator.NEIGHBOR_OFFSETS[i][1];
            if (futures == null) {
                futures = new ArrayList<>(neighbors.length);
            }
            futures.add(level.getWorld().getChunkAtAsync(x, z).handle((chunk, throwable) -> {
                if (throwable != null) {
                    LOGGER.error("Can't get neighboring chunk ({}, {})", x, z, throwable);
                } else {
                    neighbors[index] = level.getChunkSource().getChunkNow(x, z);
                }
                return null;
            }));
        }

        if (futures == null) {
            return CompletableFuture.completedFuture(neighbors);
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(unused -> neighbors);
    }

    private void apply(final ClientboundLevelChunkWithLightPacket packet, final ObfuscationResult result) {
        packet.chunkData().setBuffer(result.data());

        final LongSet blockEntities = result.blockEntities();
        if (!blockEntities.isEmpty()) {
            packet.chunkData().removeBlockEntitiesIf(packet.x(), packet.z(), (x, y, z) -> blockEntities.contains(BlockPos.asLong(x, y, z)));
        }
    }

    private void release(final ClientboundLevelChunkWithLightPacket packet) {
        if (this.pendingPackets.remove(packet)) {
            packet.setReady(true);
        }
    }

    private static void logFailure(final ServerLevel level, final int chunkX, final int chunkZ, Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            throwable = throwable.getCause();
        }

        final String world = level.getWorld().getName();
        if (throwable instanceof TimeoutException) {
            LOGGER.warn("Obfuscation for chunk[world={}, x={}, z={}] timed out", world, chunkX, chunkZ);
        } else {
            LOGGER.error("An error occurred while obfuscating chunk[world={}, x={}, z={}]", world, chunkX, chunkZ, throwable);
        }
    }

    /* Shared helpers */

    public List<OrebfuscatorPlayer> players() {
        return List.copyOf(this.players.values());
    }

    public static boolean bypasses(final ServerPlayer player) {
        return player.getBukkitEntity().hasPermission(PERMISSION_BYPASS);
    }

    public static void debug(final String message, final Object... arguments) {
        if (verbose) {
            LOGGER.info("[Debug] {}", message.formatted(arguments));
        }
    }

    /**
     * Runs the task on the main thread while the built-in Orebfuscator is running.
     */
    public static void runOnMain(final Runnable task) {
        final LeafOrebfuscator orebfuscator = instance;
        if (orebfuscator != null) {
            orebfuscator.server.execute(() -> {
                if (orebfuscator.running) {
                    task.run();
                }
            });
        }
    }

    /**
     * Sends the real blocks at the given positions ({@link BlockPos#asLong}) to the player.
     */
    public static void sendBlockUpdates(final ServerPlayer player, final LongList positions) {
        final ServerLevel level = player.level();
        final ServerChunkCache chunkSource = level.getChunkSource();

        final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        final Map<SectionPos, Short2ObjectMap<BlockState>> sectionPackets = new HashMap<>();
        final List<Packet<ClientGamePacketListener>> blockEntityPackets = new ArrayList<>();

        for (int i = 0; i < positions.size(); i++) {
            position.set(positions.getLong(i));
            if (!chunkSource.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)) {
                continue;
            }

            final BlockState state = level.getBlockState(position);
            sectionPackets.computeIfAbsent(SectionPos.of(position), key -> new Short2ObjectLinkedOpenHashMap<>())
                .put(SectionPos.sectionRelativePos(position), state);

            if (state.hasBlockEntity()) {
                final BlockEntity blockEntity = level.getBlockEntity(position);
                if (blockEntity != null) {
                    final Packet<ClientGamePacketListener> packet = blockEntity.getUpdatePacket();
                    if (packet != null) {
                        blockEntityPackets.add(packet);
                    }
                }
            }
        }

        for (final Map.Entry<SectionPos, Short2ObjectMap<BlockState>> entry : sectionPackets.entrySet()) {
            final Short2ObjectMap<BlockState> states = entry.getValue();
            if (states.size() == 1) {
                final Short2ObjectMap.Entry<BlockState> block = states.short2ObjectEntrySet().iterator().next();
                player.connection.send(new ClientboundBlockUpdatePacket(entry.getKey().relativeToBlockPos(block.getShortKey()), block.getValue()));
            } else {
                player.connection.send(new ClientboundSectionBlocksUpdatePacket(entry.getKey(), states));
            }
        }

        for (final Packet<ClientGamePacketListener> packet : blockEntityPackets) {
            player.connection.send(packet);
        }
    }
}
