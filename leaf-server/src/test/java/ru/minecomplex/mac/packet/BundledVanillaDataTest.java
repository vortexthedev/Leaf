package ru.minecomplex.mac.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.entity.EntityTypes;
import ru.minecomplex.mac.inventory.item.ItemBehavior;

/** A distributable jar needs complete generated registries, not merely compilable classes. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class BundledVanillaDataTest {

    @Test
    void generatedRegistriesAreCompleteAndReadable() throws Exception {
        NativeRuntime.ensure();
        String[] families = {"entities", "item-behavior"};
        int[] magic = {0x4d414554, 0x4d414942};
        int[] schemas = {6, 1};
        for (int i = 0; i < families.length; i++) {
            String path = "/mac/" + families[i] + "/" + ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL + ".bin.gz";
            try (var raw = getClass().getResourceAsStream(path)) {
                assertNotNull(raw, path);
                try (var in = new DataInputStream(new GZIPInputStream(raw))) {
                    assertEquals(magic[i], in.readInt(), path);
                    assertEquals(schemas[i], in.readUnsignedShort(), path);
                    assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt(), path);
                    // Read through the gzip trailer, checking length and CRC as well as the header.
                    assertTrue(in.readAllBytes().length > 0, path);
                }
            }
        }
        assertNotNull(EntityTypes.get().type("minecraft:pig"));
        assertTrue(ItemBehavior.get().arrow("minecraft:arrow"));
    }
}
