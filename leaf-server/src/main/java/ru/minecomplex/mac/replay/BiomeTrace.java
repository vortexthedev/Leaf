package ru.minecomplex.mac.replay;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import ru.minecomplex.mac.world.Precipitation;

/** Queried server facts, keyed by client frame. Unknown answers stay unknown and later changes do not rewrite history. */
public final class BiomeTrace implements Precipitation.Source {
    private final Precipitation.Source delegate;
    private final Consumer<String> record;
    private final Map<String, java.util.ArrayDeque<String>> answers = new HashMap<>();
    private long frame;

    public BiomeTrace(Precipitation.Source delegate, Consumer<String> record) {
        this.delegate = delegate;
        this.record = record;
    }

    public void nextFrame(long frame) { this.frame = frame; }

    private String key(String kind, String dimension, int x, int y, int z) {
        return frame + "\t" + kind + "\t" + Base64.getEncoder().encodeToString(dimension.getBytes(StandardCharsets.UTF_8))
                + "\t" + x + "\t" + y + "\t" + z;
    }

    @Override public Precipitation.Facts noiseBiome(String dimension, int x, int y, int z) {
        String key = key("biome", dimension, x, y, z);
        if (delegate != null) {
            var value = delegate.noiseBiome(dimension, x, y, z);
            record.accept(key + "\t" + (value == null ? "?" : value.hasPrecipitation() + ","
                    + Float.toHexString(value.temperature()) + "," + value.frozen()) + "\n");
            return value;
        }
        String value = answer(key);
        if (value.equals("?")) return null;
        String[] fields = value.split(",");
        return new Precipitation.Facts(Boolean.parseBoolean(fields[0]), Float.parseFloat(fields[1]), Boolean.parseBoolean(fields[2]));
    }

    @Override public Integer seaLevel(String dimension) {
        String key = key("sea", dimension, 0, 0, 0);
        if (delegate != null) {
            Integer value = delegate.seaLevel(dimension);
            record.accept(key + "\t" + (value == null ? "?" : value) + "\n");
            return value;
        }
        String value = answer(key);
        return value.equals("?") ? null : Integer.valueOf(value);
    }

    private String answer(String key) {
        var values = answers.get(key);
        return values == null || values.isEmpty() ? "?" : values.removeFirst();
    }

    public static BiomeTrace read(Reader input) throws IOException {
        var trace = new BiomeTrace(null, null);
        var reader = new BufferedReader(input);
        String line;
        while ((line = reader.readLine()) != null) {
            int split = line.lastIndexOf('\t');
            if (split < 0) throw new IOException("Malformed biome observation");
            trace.answers.computeIfAbsent(line.substring(0, split), ignored -> new java.util.ArrayDeque<>())
                    .addLast(line.substring(split + 1));
        }
        return trace;
    }
}
