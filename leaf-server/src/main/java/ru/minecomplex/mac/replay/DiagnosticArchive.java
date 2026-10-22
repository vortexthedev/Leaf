package ru.minecomplex.mac.replay;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import ru.minecomplex.mac.world.BlockTags;
import ru.minecomplex.mac.world.ResolvedBlockTags;

/** One portable incident bundle; its MACR is an ordinary full-session prefix in the existing format. */
public record DiagnosticArchive(Recording recording, BlockTags tags, BiomeTrace biomes, String report) {
    public static DiagnosticArchive read(Path path) throws IOException {
        try (var zip = new ZipFile(path.toFile())) {
            for (String name : new String[]{"recording.macr", "recording.tags", "biomes.tsv", "diagnostic.txt"})
                if (zip.getEntry(name) == null) throw new IOException("Diagnostic archive is missing " + name);
            Recording recording;
            try (var in = zip.getInputStream(zip.getEntry("recording.macr"))) { recording = ReplayFormat.read(in); }
            BlockTags tags;
            try (var in = new InputStreamReader(zip.getInputStream(zip.getEntry("recording.tags")), StandardCharsets.UTF_8)) {
                tags = ResolvedBlockTags.ofKeys(TagsFile.read(in));
            }
            BiomeTrace biomes;
            try (var in = new InputStreamReader(zip.getInputStream(zip.getEntry("biomes.tsv")), StandardCharsets.UTF_8)) {
                biomes = BiomeTrace.read(in);
            }
            String report;
            try (var in = zip.getInputStream(zip.getEntry("diagnostic.txt"))) {
                report = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            return new DiagnosticArchive(recording, tags, biomes, report);
        }
    }
}
