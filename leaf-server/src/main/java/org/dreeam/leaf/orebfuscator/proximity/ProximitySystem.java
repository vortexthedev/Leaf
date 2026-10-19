package org.dreeam.leaf.orebfuscator.proximity;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.dreeam.leaf.orebfuscator.BlockStateInfo;
import org.dreeam.leaf.orebfuscator.LeafOrebfuscator;
import org.dreeam.leaf.orebfuscator.config.OrebfuscatorConfig;
import org.dreeam.leaf.orebfuscator.config.ProximityConfig;
import org.dreeam.leaf.orebfuscator.player.OrebfuscatorPlayer;
import org.dreeam.leaf.orebfuscator.player.ProximityChunk;
import org.dreeam.leaf.orebfuscator.util.OrebfuscatorExecutor;
import org.joml.FrustumIntersection;
import org.joml.Quaternionf;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reveals proximity hidden blocks once a player is close enough (and can see them). Players are
 * checked in buckets on the Orebfuscator workers, the block updates are sent on the main thread.
 */
@NullMarked
public final class ProximitySystem implements Runnable {

    private final LeafOrebfuscator orebfuscator;
    private final OrebfuscatorConfig config;
    private final OrebfuscatorExecutor executor;

    private final int workerCount;
    private final int defaultBucketSize;
    private final long checkInterval;

    public ProximitySystem(final LeafOrebfuscator orebfuscator, final OrebfuscatorConfig config, final OrebfuscatorExecutor executor) {
        this.orebfuscator = orebfuscator;
        this.config = config;
        this.executor = executor;

        this.workerCount = config.threads();
        this.defaultBucketSize = config.proximityDefaultBucketSize();
        this.checkInterval = TimeUnit.MILLISECONDS.toNanos(config.proximityThreadCheckInterval());
    }

    public void start() {
        this.executor.schedule(this, this.checkInterval, TimeUnit.NANOSECONDS);
    }

    @Override
    public void run() {
        final long processStart = System.nanoTime();
        this.process().whenComplete((unused, throwable) -> {
            try {
                if (throwable != null) {
                    LeafOrebfuscator.LOGGER.error("An error occurred while running proximity worker", throwable);
                }

                if (this.executor.isShutdown()) {
                    return;
                }

                final long waitMillis = TimeUnit.NANOSECONDS.toMillis(Math.max(0, this.checkInterval - (System.nanoTime() - processStart)));
                // always schedule to break reentrant execution chains
                this.executor.schedule(this, waitMillis, TimeUnit.MILLISECONDS);
            } catch (final Exception e) {
                LeafOrebfuscator.LOGGER.error("An error occurred while scheduling proximity worker", e);
                this.executor.schedule(this, 1L, TimeUnit.SECONDS);
            }
        });
    }

    private CompletableFuture<Void> process() {
        try {
            final List<OrebfuscatorPlayer> players = this.orebfuscator.players();
            if (players.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }

            // derive the bucket size from the player count
            final int playerCount = players.size();
            final int maxBucketSize = Math.max(this.defaultBucketSize, (int) Math.ceil((float) playerCount / this.workerCount));
            final int bucketCount = (int) Math.ceil((float) playerCount / maxBucketSize);
            final int bucketSize = (int) Math.ceil((float) playerCount / (float) bucketCount);

            final CompletableFuture<?>[] futures = new CompletableFuture[bucketCount];
            final Iterator<OrebfuscatorPlayer> iterator = players.iterator();
            for (int index = 0; index < bucketCount; index++) {
                final List<OrebfuscatorPlayer> bucket = new ArrayList<>(bucketSize);
                for (int size = 0; size < bucketSize && iterator.hasNext(); size++) {
                    bucket.add(iterator.next());
                }
                futures[index] = CompletableFuture.runAsync(() -> this.process(bucket), this.executor);
            }

            return CompletableFuture.allOf(futures);
        } catch (final Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void process(final List<OrebfuscatorPlayer> players) {
        for (final OrebfuscatorPlayer player : players) {
            try {
                this.process(player);
            } catch (final Exception e) {
                LeafOrebfuscator.LOGGER.error("An error occurred while processing proximity of {}", player.player().getScoreboardName(), e);
            }
        }
    }

    private void process(final OrebfuscatorPlayer player) {
        if (LeafOrebfuscator.bypasses(player.player())
            || (player.player().isSpectator() && this.config.ignoreSpectator())) {
            return;
        }

        final ServerLevel level = player.level();
        final ProximityConfig proximityConfig = this.config.world(level).proximity();
        if (proximityConfig == null || !proximityConfig.isEnabled()) {
            return;
        }

        // frustum culling and ray casting both need rotation changes
        final boolean needsRotation = proximityConfig.frustumCullingEnabled() || proximityConfig.rayCastCheckEnabled();
        if (!player.needsProximityUpdate(needsRotation)) {
            return;
        }

        final int distance = proximityConfig.distance();
        final int distanceSquared = distance * distance;
        final OrebfuscatorPlayer.Pose eye = player.eyePose();
        final OrebfuscatorPlayer.Pose location = player.pose();

        final FrustumIntersection frustum = proximityConfig.frustumCullingEnabled()
            ? new FrustumIntersection(proximityConfig.frustumCullingProjectionMatrix()
                .rotate(new Quaternionf()
                    .rotateX((float) Math.toRadians(eye.rotX()))
                    .rotateY((float) Math.toRadians(eye.rotY() + 180)))
                .translate((float) -eye.x(), (float) -eye.y(), (float) -eye.z()), false)
            : null;

        final ChunkCache chunks = new ChunkCache(level);
        final boolean isInLava = BlockStateInfo.isLava(chunks.getBlockState(location.blockX(), eye.blockY(), location.blockZ()));
        final double lavaDistance = player.player().hasEffect(MobEffects.FIRE_RESISTANCE) ? 7 : 2;
        final double lavaDistanceSquared = lavaDistance * lavaDistance;
        final boolean onlyCheckCenter = proximityConfig.rayCastCheckOnlyCheckCenter();

        final int minChunkX = (location.blockX() - distance) >> 4;
        final int maxChunkX = (location.blockX() + distance) >> 4;
        final int minChunkZ = (location.blockZ() - distance) >> 4;
        final int maxChunkZ = (location.blockZ() + distance) >> 4;

        final LongArrayList updateBlocks = new LongArrayList();
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                final ProximityChunk chunk = player.getChunk(level, chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }

                final boolean[] removed = new boolean[chunk.size()];
                boolean anyRemoved = false;
                for (int i = 0; i < chunk.size(); i++) {
                    final long position = chunk.position(i);
                    final int x = BlockPos.getX(position);
                    final int y = BlockPos.getY(position);
                    final int z = BlockPos.getZ(position);
                    final boolean lavaObfuscated = chunk.lavaObfuscated(i);

                    // lava hidden blocks are only revealed inside lava
                    if (lavaObfuscated && !isInLava) {
                        continue;
                    }

                    final double blockDistanceSquared;
                    final double compareDistanceSquared;
                    if (lavaObfuscated) {
                        blockDistanceSquared = distanceSquared(x, y, z, eye.x(), eye.y(), eye.z());
                        compareDistanceSquared = lavaDistanceSquared;
                    } else {
                        blockDistanceSquared = distanceSquared(x, y, z, location.x(), location.y(), location.z());
                        compareDistanceSquared = distanceSquared;
                    }
                    if (blockDistanceSquared > compareDistanceSquared) {
                        continue;
                    }

                    if (frustum != null && blockDistanceSquared > proximityConfig.frustumCullingMinDistanceSquared()) {
                        final int result = frustum.intersectAab(x, y, z, x + 1, y + 1, z + 1);
                        if (result != FrustumIntersection.INSIDE && result != FrustumIntersection.INTERSECT) {
                            continue;
                        }
                    }

                    if (proximityConfig.rayCastCheckEnabled() && !isVisible(chunks, eye, x, y, z, onlyCheckCenter)) {
                        continue;
                    }

                    removed[i] = true;
                    anyRemoved = true;
                    updateBlocks.add(position);
                }

                if (anyRemoved) {
                    chunk.removeAll(removed);
                }
                if (chunk.isEmpty()) {
                    player.removeChunk(level, chunkX, chunkZ);
                }
            }
        }

        if (!updateBlocks.isEmpty()) {
            LeafOrebfuscator.runOnMain(() -> {
                if (player.player().isAlive() && player.player().level() == level) {
                    LeafOrebfuscator.sendBlockUpdates(player.player(), updateBlocks);
                }
            });
        }
    }

    // distance to the block corner, same as the plugin
    private static double distanceSquared(final int x, final int y, final int z, final double ox, final double oy, final double oz) {
        final double dx = x - ox;
        final double dy = y - oy;
        final double dz = z - oz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean isVisible(final ChunkCache chunks, final OrebfuscatorPlayer.Pose origin, final int x, final int y, final int z, final boolean onlyCheckCenter) {
        if (onlyCheckCenter) {
            return canRayPass(chunks, origin, x, y, z, 0.5, 0.5, 0.5);
        }

        // 6 face centers, then 8 corners
        return canRayPass(chunks, origin, x, y, z, 0.0, 0.5, 0.5)
            || canRayPass(chunks, origin, x, y, z, 0.5, 0.0, 0.5)
            || canRayPass(chunks, origin, x, y, z, 0.5, 0.5, 0.0)
            || canRayPass(chunks, origin, x, y, z, 0.5, 1.0, 0.5)
            || canRayPass(chunks, origin, x, y, z, 0.5, 0.5, 1.0)
            || canRayPass(chunks, origin, x, y, z, 1.0, 0.5, 0.5)
            || canRayPass(chunks, origin, x, y, z, 0.0, 0.0, 0.0)
            || canRayPass(chunks, origin, x, y, z, 1.0, 0.0, 0.0)
            || canRayPass(chunks, origin, x, y, z, 0.0, 1.0, 0.0)
            || canRayPass(chunks, origin, x, y, z, 1.0, 1.0, 0.0)
            || canRayPass(chunks, origin, x, y, z, 0.0, 0.0, 1.0)
            || canRayPass(chunks, origin, x, y, z, 1.0, 0.0, 1.0)
            || canRayPass(chunks, origin, x, y, z, 0.0, 1.0, 1.0)
            || canRayPass(chunks, origin, x, y, z, 1.0, 1.0, 1.0);
    }

    // steps along the dominant axis from the eye towards the target, blocked by the first occluding block
    private static boolean canRayPass(final ChunkCache chunks, final OrebfuscatorPlayer.Pose origin, final int targetX, final int targetY, final int targetZ,
                                      final double offsetX, final double offsetY, final double offsetZ) {
        double dx = origin.x() - (targetX + offsetX);
        double dy = origin.y() - (targetY + offsetY);
        double dz = origin.z() - (targetZ + offsetZ);

        final double maxAbs = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        if (maxAbs < 1) {
            return true;
        }

        dx /= maxAbs;
        dy /= maxAbs;
        dz /= maxAbs;

        double cx = origin.x();
        double cy = origin.y();
        double cz = origin.z();
        for (int steps = (int) Math.ceil(maxAbs); steps > 0; steps--) {
            cx -= dx;
            cy -= dy;
            cz -= dz;

            final int x = Mth.floor(cx);
            final int y = Mth.floor(cy);
            final int z = Mth.floor(cz);
            if (x == targetX && y == targetY && z == targetZ) {
                return true;
            }
            if (BlockStateInfo.isOccluding(chunks.getBlockState(x, y, z))) {
                return false;
            }
        }

        return true;
    }

    /**
     * Loaded chunks looked up once per player check.
     */
    private static final class ChunkCache {

        private final ServerLevel level;
        private final Long2ObjectOpenHashMap<@Nullable LevelChunk> chunks = new Long2ObjectOpenHashMap<>();

        ChunkCache(final ServerLevel level) {
            this.level = level;
        }

        int getBlockState(final int x, final int y, final int z) {
            if (y < this.level.getMinY() || y > this.level.getMaxY()) {
                return BlockStateInfo.AIR;
            }

            final long key = ChunkPos.pack(x >> 4, z >> 4);
            LevelChunk chunk = this.chunks.get(key);
            if (chunk == null && !this.chunks.containsKey(key)) {
                // lock free full chunk lookup, safe off the main thread
                chunk = this.level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                this.chunks.put(key, chunk);
            }
            return BlockStateInfo.getId(chunk, x, y, z);
        }
    }
}
