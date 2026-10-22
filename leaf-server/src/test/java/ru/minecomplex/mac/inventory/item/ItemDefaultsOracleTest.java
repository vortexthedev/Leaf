package ru.minecomplex.mac.inventory.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.fixture.ItemSampleCodec;
import ru.minecomplex.mac.fixture.FixtureItems;
import ru.minecomplex.mac.packet.NativeRuntime;
import net.minecraft.core.component.DataComponents;

/** Independent defaults generated from real vanilla JARs, compared with the PE registry used in production. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class ItemDefaultsOracleTest {

    @Test
    void everyVanillaPrototypeMatchesNativePackets() throws Exception {
        NativeRuntime.ensure();
        try (var in = new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream(
                "/vanilla/items/" + ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL + ".bin.gz")))) {
            assertEquals(0x4d414954, in.readInt()); assertEquals(4, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt()); in.readUTF();
            int count = in.readInt();
            assertEquals(count, ItemBehavior.get().size());
            for (int i = 0; i < count; i++) {
                String key = in.readUTF(); int use = in.readUnsignedByte(), flags = in.readUnsignedByte();
                int tool = in.readUnsignedByte(); String override = in.readUTF();
                var expected = ItemSampleCodec.read(in, in.readInt());
                var stack = Stacks.of(key, 1);
                assertEquals(expected, FixtureItems.components(stack), key);
                var actualTool = stack.getOrDefault(DataComponents.TOOL, null);
                assertEquals(tool, actualTool == null ? 0 : actualTool.canDestroyBlocksInCreative() ? 1 : 2,
                        key + " tool");
                var behavior = ItemBehavior.get();
                assertEquals(UseKind.fromDataId(use), behavior.use(key));
                assertEquals((flags & 1) != 0, behavior.arrow(key));
                assertEquals((flags & 2) != 0, behavior.startsUsingOnBlock(key));
                assertEquals((flags & 4) != 0, behavior.blockItemUseOn(key));
                assertEquals(override, behavior.destroyRuleOverride(key));
            }
            assertEquals(-1, in.read());
        }
    }
}
