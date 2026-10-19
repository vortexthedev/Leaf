package org.dreeam.leaf.orebfuscator.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.dreeam.leaf.orebfuscator.LeafOrebfuscator;
import org.dreeam.leaf.orebfuscator.config.OrebfuscatorConfig;
import org.dreeam.leaf.orebfuscator.obfuscation.ObfuscationResult;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Obfuscated chunks in memory (Caffeine) and optionally on disk (region files of the server, one
 * storage per dimension). Evicted entries are written to disk, like the plugin did.
 */
@NullMarked
public final class ObfuscationCache {

    public record Key(ResourceKey<Level> dimension, long chunkPos) {
    }

    // section data is LZ4 compressed to keep the memory usage low
    private record Entry(byte[] hash, byte[] compressedData, long[] proximityBlocks, boolean[] lavaObfuscated, long[] blockEntities) {

        static Entry create(final byte[] hash, final ObfuscationResult result) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream(result.data().length / 2);
            try (LZ4BlockOutputStream output = new LZ4BlockOutputStream(bytes)) {
                output.write(result.data());
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            return new Entry(hash, bytes.toByteArray(), result.proximityBlocks(), result.lavaObfuscated(), result.blockEntities().toLongArray());
        }

        ObfuscationResult toResult() {
            final byte[] data;
            try (LZ4BlockInputStream input = LZ4BlockInputStream.newBuilder().build(new ByteArrayInputStream(this.compressedData))) {
                data = input.readAllBytes();
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            return new ObfuscationResult(data, new LongOpenHashSet(this.blockEntities), this.proximityBlocks, this.lavaObfuscated);
        }

        CompoundTag toTag() {
            final CompoundTag tag = new CompoundTag();
            tag.putByteArray("hash", this.hash);
            tag.putByteArray("data", this.compressedData);
            tag.putLongArray("proximity", this.proximityBlocks);
            final byte[] lava = new byte[this.lavaObfuscated.length];
            for (int i = 0; i < lava.length; i++) {
                lava[i] = (byte) (this.lavaObfuscated[i] ? 1 : 0);
            }
            tag.putByteArray("lava", lava);
            tag.putLongArray("blockEntities", this.blockEntities);
            return tag;
        }

        static @Nullable Entry fromTag(final CompoundTag tag) {
            final byte[] hash = tag.getByteArray("hash").orElse(null);
            final byte[] data = tag.getByteArray("data").orElse(null);
            final long[] proximity = tag.getLongArray("proximity").orElse(null);
            final byte[] lava = tag.getByteArray("lava").orElse(null);
            final long[] blockEntities = tag.getLongArray("blockEntities").orElse(null);
            if (hash == null || data == null || proximity == null || lava == null || blockEntities == null || lava.length != proximity.length) {
                return null;
            }

            final boolean[] lavaObfuscated = new boolean[lava.length];
            for (int i = 0; i < lava.length; i++) {
                lavaObfuscated[i] = lava[i] != 0;
            }
            return new Entry(hash, data, proximity, lavaObfuscated, blockEntities);
        }
    }

    private final OrebfuscatorConfig config;
    private final Cache<Key, Entry> memory;
    private final boolean diskEnabled;
    private final Map<ResourceKey<Level>, RegionFileStorage> storages = new ConcurrentHashMap<>();
    // entries that are being written, so a read right after the eviction still finds them
    private final Map<Key, Entry> pendingWrites = new ConcurrentHashMap<>();

    public ObfuscationCache(final OrebfuscatorConfig config, final Executor executor) {
        this.config = config;
        this.diskEnabled = config.diskCacheEnabled();

        if (this.diskEnabled) {
            // the files are deleted before any storage opens them
            this.deleteOldFiles();
        }

        this.memory = Caffeine.newBuilder()
            .maximumSize(config.cacheMaximumSize())
            .expireAfterAccess(Duration.ofMillis(config.cacheExpireAfterAccess()))
            .executor(executor)
            .removalListener((Key key, Entry entry, RemovalCause cause) -> {
                if (this.diskEnabled && key != null && entry != null && cause.wasEvicted()) {
                    this.writeAsync(key, entry);
                }
            })
            .build();
    }

    /**
     * @return the cached result if its hash matches, null otherwise
     */
    public CompletableFuture<@Nullable ObfuscationResult> get(final Key key, final byte[] hash) {
        final Entry memoryEntry = this.memory.getIfPresent(key);
        if (memoryEntry != null) {
            if (Arrays.equals(memoryEntry.hash(), hash)) {
                return CompletableFuture.completedFuture(memoryEntry.toResult());
            }
            this.memory.invalidate(key);
        }

        if (!this.diskEnabled) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            Entry entry = this.pendingWrites.get(key);
            if (entry == null) {
                entry = this.read(key);
            }
            if (entry == null || !Arrays.equals(entry.hash(), hash)) {
                return null;
            }

            this.memory.put(key, entry);
            return entry.toResult();
        }, Util.ioPool());
    }

    public void put(final Key key, final byte[] hash, final ObfuscationResult result) {
        try {
            this.memory.put(key, Entry.create(hash, result));
        } catch (final RuntimeException e) {
            LeafOrebfuscator.LOGGER.error("Failed to cache obfuscated chunk {}", key, e);
        }
    }

    public void invalidate(final Key key) {
        this.memory.invalidate(key);
    }

    private void writeAsync(final Key key, final Entry entry) {
        this.pendingWrites.put(key, entry);
        Util.ioPool().execute(() -> {
            try {
                this.write(key, entry);
            } finally {
                this.pendingWrites.remove(key, entry);
            }
        });
    }

    private @Nullable Entry read(final Key key) {
        try {
            final CompoundTag tag = this.storage(key.dimension()).read(ChunkPos.unpack(key.chunkPos()));
            return tag != null ? Entry.fromTag(tag) : null;
        } catch (final IOException e) {
            LeafOrebfuscator.LOGGER.error("Failed to read cached chunk {}", key, e);
            return null;
        }
    }

    private void write(final Key key, final Entry entry) {
        try {
            this.storage(key.dimension()).write(ChunkPos.unpack(key.chunkPos()), entry.toTag());
        } catch (final IOException e) {
            LeafOrebfuscator.LOGGER.error("Failed to write cached chunk {}", key, e);
        }
    }

    private RegionFileStorage storage(final ResourceKey<Level> dimension) {
        return this.storages.computeIfAbsent(dimension, key -> {
            final Path directory = this.config.diskCacheDirectory()
                .resolve(key.identifier().getNamespace())
                .resolve(key.identifier().getPath());
            final boolean sync = MinecraftServer.getServer() instanceof DedicatedServer server && server.forceSynchronousWrites();
            return new RegionFileStorage(new RegionStorageInfo("orebfuscator", key, "orebfuscator_cache"), directory, sync);
        });
    }

    private void deleteOldFiles() {
        final Path directory = this.config.diskCacheDirectory();
        if (Files.notExists(directory)) {
            return;
        }

        final long deleteAfter = this.config.diskCacheDeleteAfterAccess();
        final long now = System.currentTimeMillis();
        final int[] deleted = {0};
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path path, final BasicFileAttributes attributes) throws IOException {
                    if (now - attributes.lastAccessTime().toMillis() > deleteAfter) {
                        Files.delete(path);
                        deleted[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(final Path dir, final @Nullable IOException exc) throws IOException {
                    if (!directory.equals(dir)) {
                        try {
                            Files.delete(dir);
                        } catch (final NoSuchFileException | DirectoryNotEmptyException ignored) {
                            // only empty directories are removed
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException e) {
            LeafOrebfuscator.LOGGER.error("Failed to clean up the Orebfuscator cache directory", e);
        }

        if (deleted[0] > 0) {
            LeafOrebfuscator.LOGGER.info("Deleted {} old Orebfuscator cache file(s)", deleted[0]);
        }
    }

    /**
     * Writes all memory entries to disk and closes the region files.
     */
    public void close() {
        if (!this.diskEnabled) {
            this.memory.invalidateAll();
            return;
        }

        final Map<Key, Entry> entries = Map.copyOf(this.memory.asMap());
        // invalidations are not written by the removal listener
        this.memory.invalidateAll();
        for (final Map.Entry<Key, Entry> entry : entries.entrySet()) {
            this.write(entry.getKey(), entry.getValue());
        }

        // let evicted entries that are still being written finish before the files are closed
        final long waitUntil = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!this.pendingWrites.isEmpty() && System.nanoTime() < waitUntil) {
            Thread.onSpinWait();
        }

        for (final RegionFileStorage storage : this.storages.values()) {
            try {
                storage.close();
            } catch (final IOException e) {
                LeafOrebfuscator.LOGGER.error("Failed to close Orebfuscator cache files", e);
            }
        }
        this.storages.clear();
    }
}
