package ru.minecomplex.mac.bukkit.net;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import ru.minecomplex.mac.world.Precipitation;

/**
 * The server's noise biomes, which the client received with its chunks: {@code chunk.getNoiseBiome(quartX, quartY,
 * quartZ)} of a chunk the server holds loaded ({@code getChunkIfLoaded}, Paper; nothing is loaded here) and the
 * climate of its biome ({@code hasPrecipitation}, {@code getBaseTemperature}, the temperature modifier). Read through the
 * server's own classes (Mojang names at runtime) on the connection's thread, as the previous anticheat read
 * {@code isRainingAt}; a biome's facts are read once. A server whose classes do not answer leaves every biome unknown.
 */
public final class ServerBiomes implements Precipitation.Source {
    private final Logger log;
    private final Map<Object, Precipitation.Facts> facts = new ConcurrentHashMap<>();
    private volatile boolean failed;
    /** Set last: the other methods are resolved once it is. */
    private volatile Method handle;
    private Method chunkIfLoaded, noiseBiome, value, seaLevel, hasPrecipitation, baseTemperature, modifier;
    private Field climate;

    public ServerBiomes(Logger log) {
        this.log = log;
    }

    @Override
    public Precipitation.Facts noiseBiome(String dimension, int quartX, int quartY, int quartZ) {
        if (failed) return null;
        try {
            Object level = level(dimension);
            if (level == null) return null;
            Object chunk = chunkIfLoaded.invoke(level, quartX >> 2, quartZ >> 2);
            if (chunk == null) return null;
            Object holder = noiseBiome.invoke(chunk, quartX, quartY, quartZ);
            Object biome = value.invoke(holder);
            Precipitation.Facts known = facts.get(biome);
            if (known != null) return known;
            Object settings = climate.get(biome);
            known = new Precipitation.Facts((boolean) hasPrecipitation.invoke(biome), (float) baseTemperature.invoke(biome),
                    switch (((Enum<?>) modifier.invoke(settings)).name()) {
                        case "NONE" -> false;
                        case "FROZEN" -> true;
                        default -> throw new IllegalStateException("Unknown temperature modifier");
                    });
            facts.put(biome, known);
            return known;
        } catch (Throwable unknown) {
            fail(unknown);
            return null;
        }
    }

    @Override
    public Integer seaLevel(String dimension) {
        if (failed) return null;
        try {
            Object level = level(dimension);
            return level == null ? null : (Integer) seaLevel.invoke(level);
        } catch (Throwable unknown) {
            fail(unknown);
            return null;
        }
    }

    /** The server level of a dimension key ({@code null}: the server has none). */
    private Object level(String dimension) throws ReflectiveOperationException {
        NamespacedKey key = NamespacedKey.fromString(dimension);
        World world = key == null ? null : Bukkit.getWorld(key);
        if (world == null) return null;
        if (handle == null) resolve(world);
        return handle.invoke(world);
    }

    private synchronized void resolve(World world) throws ReflectiveOperationException {
        if (handle != null) return;
        Method getHandle = world.getClass().getMethod("getHandle");
        Class<?> level = getHandle.getReturnType();
        Class<?> biome = Class.forName("net.minecraft.world.level.biome.Biome");
        chunkIfLoaded = method(level, "getChunkIfLoaded", int.class, int.class);
        noiseBiome = method(Class.forName("net.minecraft.world.level.chunk.ChunkAccess"), "getNoiseBiome", int.class, int.class, int.class);
        value = method(Class.forName("net.minecraft.core.Holder"), "value");
        seaLevel = method(level, "getSeaLevel");
        hasPrecipitation = method(biome, "hasPrecipitation");
        baseTemperature = method(biome, "getBaseTemperature");
        climate = biome.getDeclaredField("climateSettings");
        climate.setAccessible(true);
        modifier = method(climate.getType(), "temperatureModifier");
        handle = getHandle;
    }

    private void fail(Throwable unknown) {
        if (failed) return;
        failed = true;
        log.warning("The server's biomes could not be read (" + unknown + "): the rain at a position stays uncertain");
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, parameters);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException next) {
                // declared further up
            }
        }
        Method m = type.getMethod(name, parameters);
        m.setAccessible(true);
        return m;
    }
}
