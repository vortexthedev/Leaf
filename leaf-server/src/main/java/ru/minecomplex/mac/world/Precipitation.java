package ru.minecomplex.mac.world;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;



import ru.minecomplex.mac.listener.MultiBlockChangeBitRepackHandler;


import ru.minecomplex.mac.util.math.VanillaMath;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.replica.CompensatedWorld;

/**
 * Whether the biome the client reads at a block has rain: {@code level.getBiome(pos).value().getPrecipitationAt(pos,
 * seaLevel) == RAIN}, the last condition of {@code Level.precipitationAt} (the others, {@code isRaining},
 * {@code canSeeSky} and the {@code MOTION_BLOCKING} heightmap, are {@link CompensatedWorld#rainColumn}).
 *
 * <p>{@code BiomeManager.getBiome} picks one of the eight noise cells around {@code pos - 2} by a hash of the zoom seed;
 * the model does not choose: a block whose eight candidates agree is decided. The client's noise biomes are the
 * server's (chunk and {@code chunks_biomes} data), so the candidates come from the server's own chunks
 * ({@link Source}); a candidate in a column the client has not loaded ({@code getUncachedNoiseBiome}: plains) or the
 * server has not loaded is not known.
 *
 * <p>{@code Biome.getPrecipitationAt}: no precipitation → {@code NONE}; {@code getTemperature(pos, seaLevel) < 0.15F} →
 * {@code SNOW}; else {@code RAIN}. The temperature above {@code seaLevel + 17} subtracts
 * {@code (v + y - (seaLevel + 17)) * 0.05F / 40.0F} where {@code v} is eight times the temperature noise at
 * {@code (x / 8.0F, z / 8.0F)}; a {@code FROZEN} modifier first replaces the base temperature by {@code 0.2F} or keeps
 * it by two other noises. Releases compute the noise differently (26.3: {@code SimplexNoise.get} as a float, the
 * frozen noise a {@code NoiseStack}; before: {@code PerlinSimplexNoise} of one octave, {@code (float) (value * 8.0)}),
 * so the model does not evaluate it: it evaluates the float expression at both ends of the noise's range, which
 * bounds the result (every float operation in it is monotonic), and both modifier outcomes.
 *
 * <p>The noise's range: both are 2-D simplex noise {@code 70 * (n0 + n1 + n2)}, each corner
 * {@code t^4 * dot(g, d)} with {@code t = 0.5 - |d|^2 > 0} and a gradient of length at most {@code sqrt(2)}, so a corner
 * is at most {@code max (0.5 - r^2)^4 * sqrt(2) * r} (at {@code r^2 = 1/18}: 0.0130052) and the noise at most
 * {@code 70 * 3 * 0.0130052 = 2.7311}; {@code v} lies within {@link #NOISE_BOUND}.
 */
public final class Precipitation {
    private Precipitation() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /** {@code |8 * noise|} is below {@code 8 * 2.7311 = 21.85}. */
    static final float NOISE_BOUND = 22.0F;

    /** The parts of a biome its precipitation depends on ({@code Biome.ClimateSettings}). */
    public static record Facts(boolean hasPrecipitation, float temperature, boolean frozen) {
    }

    /** The server's noise biomes, as its chunks hold them; called on the connection's thread. */
    public static interface Source {
        /** Answers nothing: no biome is known. */
        Source UNKNOWN = new Source() {
            @Override public Facts noiseBiome(String dimension, int quartX, int quartY, int quartZ) { return null; }
            @Override public Integer seaLevel(String dimension) { return null; }
        };

        /** {@code chunk.getNoiseBiome(quartX, quartY, quartZ)} of the server's loaded chunk; {@code null}: not known. */
        Facts noiseBiome(String dimension, int quartX, int quartY, int quartZ);

        /** {@code Level.getSeaLevel()} the client received for the dimension; {@code null}: not known. */
        Integer seaLevel(String dimension);
    }

    /**
     * {@code Entity.isInRain} apart from {@code isRaining}: {@code isRainingAt(blockPosition())} or at
     * {@code BlockPos.containing(x, maxY, z)} (the top of the box), with the changes still in flight ({@code pending}).
     */
    public static Tri inRain(CompensatedWorld level, java.util.List<? extends Object> pending, BlockTags tags, Source source,
                             double x, double y, double z, double maxY) {
        int blockX = VanillaMath.floor(x), blockZ = VanillaMath.floor(z);
        Tri feet = rainingAt(level, pending, tags, source, blockX, VanillaMath.floor(y), blockZ);
        if (feet == Tri.TRUE) return Tri.TRUE;
        Tri head = rainingAt(level, pending, tags, source, blockX, VanillaMath.floor(maxY), blockZ);
        if (head == Tri.TRUE) return Tri.TRUE;
        return feet == Tri.FALSE && head == Tri.FALSE ? Tri.FALSE : Tri.EITHER;
    }

    /**
     * {@code Level.isRainingAt} at a block apart from {@code isRaining}: the block column ({@link CompensatedWorld#rainColumn})
     * and the biome ({@link #rain(Source, CompensatedWorld, int, int, int)}), each {@code EITHER} while a change still in
     * flight may have changed it for the client.
     */
    static Tri rainingAt(CompensatedWorld level, java.util.List<? extends Object> pending, BlockTags tags, Source source,
                         int x, int y, int z) {
        int chunkX = x >> 4, chunkZ = z >> 4;
        // the chunks of BiomeManager.getBiome's candidate cells
        int biomeX0 = ((x - 2) >> 2) >> 2, biomeX1 = (((x - 2) >> 2) + 1) >> 2;
        int biomeZ0 = ((z - 2) >> 2) >> 2, biomeZ1 = (((z - 2) >> 2) + 1) >> 2;
        boolean columnInFlight = false, lightInFlight = false, biomesInFlight = false;
        int centerX = level.centerX(), centerZ = level.centerZ(), radius = level.serverRadius();
        for (Object change : pending) {
            switch (change) {
                case ClientboundLoginPacket ignored -> { return Tri.EITHER; }
                case ClientboundRespawnPacket ignored -> { return Tri.EITHER; }
                case ClientboundSetChunkCacheCenterPacket center -> {
                    centerX = center.getX();
                    centerZ = center.getZ();
                }
                case ClientboundSetChunkCacheRadiusPacket distance -> radius = distance.getRadius();
                case ClientboundBlockUpdatePacket update -> columnInFlight |= update.getPos().getX() == x && update.getPos().getZ() == z;
                case MultiBlockChangeBitRepackHandler.Update update -> {
                    if (update.sectionX() != chunkX || update.sectionZ() != chunkZ) break;
                    for (int i = 0; i < update.count(); i++) {
                        columnInFlight |= update.blockX(i) == x && update.blockZ(i) == z;
                    }
                }
                case ClientboundLevelChunkWithLightPacket chunk -> {
                    int cx = chunk.x(), cz = chunk.z();
                    columnInFlight |= cx == chunkX && cz == chunkZ;
                    biomesInFlight |= candidate(cx, cz, biomeX0, biomeX1, biomeZ0, biomeZ1);
                }
                case ClientboundForgetLevelChunkPacket forget -> {
                    columnInFlight |= forget.pos().x() == chunkX && forget.pos().z() == chunkZ;
                    biomesInFlight |= candidate(forget.pos().x(), forget.pos().z(), biomeX0, biomeX1, biomeZ0, biomeZ1);
                }
                case ClientboundLightUpdatePacket light -> lightInFlight |= light.x() == chunkX && light.z() == chunkZ;
                case ClientboundChunksBiomesPacket changed -> {
                    for (var chunk : changed.chunkBiomeData()) {
                        biomesInFlight |= candidate(chunk.pos().x(), chunk.pos().z(), biomeX0, biomeX1, biomeZ0, biomeZ1);
                    }
                }
                default -> { }
            }
            // A center move only hides chunks; a radius change can discard them. A dependency must remain visible
            // at every prefix, including an intermediate move away followed by a return to the original center.
            columnInFlight |= !CompensatedWorld.viewContains(centerX, centerZ, radius, chunkX, chunkZ);
            for (int cx = biomeX0; cx <= biomeX1; cx++) for (int cz = biomeZ0; cz <= biomeZ1; cz++) {
                biomesInFlight |= !CompensatedWorld.viewContains(centerX, centerZ, radius, cx, cz);
            }
        }
        Tri column = columnInFlight ? Tri.EITHER : level.rainColumn(x, y, z, tags);
        if (column == Tri.FALSE) return Tri.FALSE;
        // Light packets do not change MOTION_BLOCKING. A resolved roof rejects precipitation even if canSeeSky
        // is unknown; only a positive column result depends on the in-flight light data.
        if (lightInFlight) column = Tri.EITHER;
        Tri biome = biomesInFlight ? Tri.EITHER : rain(source, level, x, y, z);
        if (biome == Tri.FALSE) return Tri.FALSE;
        return column == Tri.TRUE && biome == Tri.TRUE ? Tri.TRUE : Tri.EITHER;
    }

    private static boolean candidate(int chunkX, int chunkZ, int x0, int x1, int z0, int z1) {
        return (chunkX == x0 || chunkX == x1) && (chunkZ == z0 || chunkZ == z1);
    }

    /**
     * Whether the biome {@code level.getBiome(x, y, z)} of the client's level has rain at the block ({@code EITHER}: the
     * candidates disagree or are not known).
     */
    public static Tri rain(Source source, CompensatedWorld level, int x, int y, int z) {
        Integer seaLevel = source.seaLevel(level.dimension());
        if (seaLevel == null) return Tri.EITHER;
        // BiomeManager.getBiome: the cells (parent + 0/1) around the position less 2
        int parentX = (x - 2) >> 2, parentY = (y - 2) >> 2, parentZ = (z - 2) >> 2;
        boolean rain = false, dry = false;
        for (int i = 0; i < 8; i++) {
            int quartX = parentX + (i >> 2 & 1), quartY = parentY + (i >> 1 & 1), quartZ = parentZ + (i & 1);
            if (!level.isLoaded(quartX >> 2, quartZ >> 2)) return Tri.EITHER;
            Facts facts = source.noiseBiome(level.dimension(), quartX, quartY, quartZ);
            if (facts == null) return Tri.EITHER;
            Tri candidate = rain(facts, y, seaLevel);
            if (candidate == Tri.EITHER) return Tri.EITHER;
            if (candidate == Tri.TRUE) rain = true; else dry = true;
        }
        return rain && dry ? Tri.EITHER : Tri.of(rain);
    }

    /** {@code getPrecipitationAt(pos, seaLevel) == RAIN} for a biome with these facts at height {@code y}. */
    static Tri rain(Facts facts, int y, int seaLevel) {
        if (!facts.hasPrecipitation()) return Tri.FALSE;
        Tri warm = warm(facts.temperature(), y, seaLevel);
        // TemperatureModifier.FROZEN: 0.2F where its noises say so, else the base temperature
        if (facts.frozen() && warm(0.2F, y, seaLevel) != warm) return Tri.EITHER;
        return warm;
    }

    /** {@code warmEnoughToRain}: {@code getTemperature >= 0.15F}, at both ends of the height noise. */
    private static Tri warm(float temperature, int y, int seaLevel) {
        int snowLevel = seaLevel + 17;
        if (y <= snowLevel) return Tri.of(temperature >= 0.15F);
        boolean coldest = adjusted(temperature, NOISE_BOUND, y, snowLevel) >= 0.15F;
        boolean warmest = adjusted(temperature, -NOISE_BOUND, y, snowLevel) >= 0.15F;
        return coldest == warmest ? Tri.of(coldest) : Tri.EITHER;
    }

    /** {@code getHeightAdjustedTemperature} above the snow level for the noise term {@code v}. */
    private static float adjusted(float temperature, float v, int y, int snowLevel) {
        return temperature - (v + y - snowLevel) * 0.05F / 40.0F;
    }
}
