package ru.minecomplex.mac.replay;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import ru.minecomplex.mac.world.BlockTag;

/**
 * The block tags a recorded session ran with (the server's, {@code ServerBlockTags}), stored next to the recording: one
 * line per tag, its key and then its members' block keys, separated by spaces. Tags missing from a file have no members.
 */
public final class TagsFile {
    private TagsFile() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }


    public static void write(Map<BlockTag, List<String>> keys, Writer out) throws IOException {
        for (BlockTag tag : BlockTag.values()) {
            out.write(tag.key());
            for (String member : keys.getOrDefault(tag, List.of())) {
                out.write(' ');
                out.write(member);
            }
            out.write('\n');
        }
        out.flush();
    }

    public static Map<BlockTag, List<String>> read(Reader in) throws IOException {
        Map<BlockTag, List<String>> keys = new EnumMap<>(BlockTag.class);
        BufferedReader lines = new BufferedReader(in);
        for (String line = lines.readLine(); line != null; line = lines.readLine()) {
            String[] parts = line.trim().split(" +");
            if (parts.length == 0 || parts[0].isEmpty()) continue;
            for (BlockTag tag : BlockTag.values()) {
                if (tag.key().equals(parts[0])) keys.put(tag, new ArrayList<>(Arrays.asList(parts).subList(1, parts.length)));
            }
        }
        return keys;
    }
}
