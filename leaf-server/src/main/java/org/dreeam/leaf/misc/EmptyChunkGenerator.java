package org.dreeam.leaf.misc;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Random;

public class EmptyChunkGenerator extends ChunkGenerator {

    public static final EmptyChunkGenerator INSTANCE = new EmptyChunkGenerator();
    private static final Biome BIOME = Biome.PLAINS;

    @Override
    public ChunkGenerator.ChunkData generateChunkData(@NotNull World world, @NotNull Random random, int ChunkX, int ChunkZ, ChunkGenerator.BiomeGrid biome) {
        ChunkGenerator.ChunkData chunkData = createChunkData(world);

        for(int x = 0; x < 16; ++x) {
            for(int z = 0; z < 16; ++z) {
                biome.setBiome(x, z, BIOME);
            }
        }

        return chunkData;
    }

    public Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        return new Location(world, 0.0, 65.0, 0.0);
    }

    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        return List.of();
    }

    @Override
    public boolean canSpawn(World world, int x, int z) {
        return true;
    }

    public byte[] generate(World world, Random random, int chunkX, int chunkZ) {
        return new byte['耀'];
    }
}
