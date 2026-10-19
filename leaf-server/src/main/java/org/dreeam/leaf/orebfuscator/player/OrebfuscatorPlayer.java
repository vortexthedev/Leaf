package org.dreeam.leaf.orebfuscator.player;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import org.dreeam.leaf.orebfuscator.config.OrebfuscatorConfig;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orebfuscator state of a player: the proximity hidden blocks of the chunks sent to them.
 */
@NullMarked
public final class OrebfuscatorPlayer {

    /**
     * Position and rotation of the player, the rotation is the same as Player#getLocation.
     */
    public record Pose(@Nullable ServerLevel level, double x, double y, double z, float rotX, float rotY) {

        static final Pose ZERO = new Pose(null, 0, 0, 0, 0, 0);

        public int blockX() {
            return Mth.floor(this.x);
        }

        public int blockY() {
            return Mth.floor(this.y);
        }

        public int blockZ() {
            return Mth.floor(this.z);
        }

        double distanceSquared(final Pose other) {
            final double dx = this.x - other.x;
            final double dy = this.y - other.y;
            final double dz = this.z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private final OrebfuscatorConfig config;
    private final ServerPlayer player;

    private final AtomicReference<@Nullable ServerLevel> level = new AtomicReference<>();
    private final Map<Long, ProximityChunk> chunks = new ConcurrentHashMap<>();

    private final AtomicBoolean pendingUpdate = new AtomicBoolean(false);
    private volatile long latestUpdateTimestamp = System.currentTimeMillis();
    private volatile Pose location = Pose.ZERO;

    public OrebfuscatorPlayer(final OrebfuscatorConfig config, final ServerPlayer player) {
        this.config = config;
        this.player = player;
    }

    public ServerPlayer player() {
        return this.player;
    }

    public Pose pose() {
        return new Pose(this.player.level(), this.player.getX(), this.player.getY(), this.player.getZ(),
            this.player.getXRot(), this.player.getBukkitYaw());
    }

    public Pose eyePose() {
        return new Pose(this.player.level(), this.player.getX(), this.player.getEyeY(), this.player.getZ(),
            this.player.getXRot(), this.player.getBukkitYaw());
    }

    /**
     * Returns the current level of the player and forgets the sent chunks if it changed.
     */
    public ServerLevel level() {
        final ServerLevel level = this.player.level();
        if (this.level.getAndSet(level) != level) {
            this.chunks.clear();
        }
        return level;
    }

    /**
     * True if the last proximity update is longer ago than the configured player check interval
     * (default 5s) or if the player moved or turned since then.
     */
    public boolean needsProximityUpdate(final boolean rotation) {
        if (!this.player.isAlive()) {
            return false;
        }

        final long timestamp = System.currentTimeMillis();
        if (this.pendingUpdate.compareAndSet(true, false)) {
            this.location = this.pose();
            this.latestUpdateTimestamp = timestamp;
            return true;
        }

        if (this.config.hasProximityPlayerCheckInterval()
            && timestamp - this.latestUpdateTimestamp > this.config.proximityPlayerCheckInterval()) {
            this.latestUpdateTimestamp = timestamp;
            return true;
        }

        final Pose location = this.pose();
        if (isLocationSimilar(rotation, this.location, location)) {
            return false;
        }

        this.location = location;
        this.latestUpdateTimestamp = timestamp;
        return true;
    }

    // same world, moved less than 0.5 blocks and (if checked) turned less than 5deg yaw and 2.5deg pitch
    private static boolean isLocationSimilar(final boolean rotation, final Pose a, final Pose b) {
        if (a.level() != b.level()) {
            return false;
        }
        if (a.distanceSquared(b) > 0.25) {
            return false;
        }
        return !rotation || (Math.abs(a.rotY() - b.rotY()) <= 5 && Math.abs(a.rotX() - b.rotX()) <= 2.5);
    }

    public void addChunk(final ServerLevel level, final int chunkX, final int chunkZ, final long[] proximityBlocks, final boolean[] lavaObfuscated) {
        if (this.level.getAcquire() != level) {
            return;
        }

        this.chunks.put(ChunkPos.pack(chunkX, chunkZ), new ProximityChunk(proximityBlocks.clone(), lavaObfuscated.clone()));

        // check right away if the chunk is next to the player
        final Pose location = this.location;
        final int dChunkX = chunkX - (location.blockX() >> 4);
        final int dChunkZ = chunkZ - (location.blockZ() >> 4);
        if (dChunkX >= -1 && dChunkX <= 1 && dChunkZ >= -1 && dChunkZ <= 1) {
            this.pendingUpdate.set(true);
        }
    }

    public @Nullable ProximityChunk getChunk(final ServerLevel level, final int chunkX, final int chunkZ) {
        return this.level.getAcquire() == level ? this.chunks.get(ChunkPos.pack(chunkX, chunkZ)) : null;
    }

    public void removeChunk(final ServerLevel level, final int chunkX, final int chunkZ) {
        if (this.level.getAcquire() == level) {
            this.chunks.remove(ChunkPos.pack(chunkX, chunkZ));
        }
    }
}
