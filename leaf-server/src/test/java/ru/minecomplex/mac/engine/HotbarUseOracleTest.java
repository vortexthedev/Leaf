package ru.minecomplex.mac.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.Wire;
import ru.minecomplex.mac.fixture.FixtureItems;
import ru.minecomplex.mac.fixture.inbound.*;
import ru.minecomplex.mac.fixture.outbound.*;
import ru.minecomplex.mac.frame.ClientTickFrame;
import ru.minecomplex.mac.inventory.item.ItemBehavior;
import ru.minecomplex.mac.player.PlayerModel;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.replica.WorldModel;
import ru.minecomplex.mac.engine.movementtick.LocalPlayerOracleCodec;

import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.util.math.Vec3d;

/** Real hotbar keybinds can stop use a movement tick before their carried-slot packet. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class HotbarUseOracleTest {

    @Test
    void keySelectionBeforeItsPacketKeepsMovementExact() throws Exception {
        var registry = BlockRegistry.get();
        String local = System.getProperty("mac.hotbar.input");
        var raw = local == null ? getClass().getResourceAsStream("/vanilla/777/hotbar-use.bin.gz")
                : java.nio.file.Files.newInputStream(java.nio.file.Path.of(local));
        assertNotNull(raw, "26.3");
        int hiddenStops = 0, uses = 0;
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d415348, in.readInt()); assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            org.junit.jupiter.api.Assertions.assertEquals("26.3", in.readUTF(), "Fixture version"); assertEquals(60, in.readInt());
            for (int scenario = 0; scenario < 60; scenario++) {
                String item = in.readUTF(); boolean off = in.readBoolean(), air = in.readBoolean();
                int choice = in.readInt(), floor = in.readInt();
                var world = new WorldModel(registry); var player = new PlayerModel();
                var probe = new MovementCheckRunner(world, player, new SyncWindow(-1, 0));
                var join = new JoinGame(1, "minecraft:overworld", 0, 256, 8);
                world.sent(0, Wire.of(join)); player.sent(0, Wire.of(join)); long seq = 1;
                for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                    int[] ones = new int[16]; java.util.Arrays.fill(ones, 1);
                    world.sent(seq++, Wire.of(new ChunkData(x, z, new int[16], ones, new int[]{0,0,0,floor,0,0,0,0,0,0,0,0,0,0,0,0},
                            new long[0], ChunkBlockEntities.encode(List.of()))));
                }
                for (int slot = 0; slot < 41; slot++) {
                    var stack = ItemUseCatalogOracleTest.stack(in, ItemBehavior.get());
                    player.sent(seq++, Wire.of(PlayerInventorySlot.of(slot, FixtureItems.of(stack))));
                }
                player.sent(seq++, Wire.of(new PlayerHealth(20F, 14, 5F)));
                player.sent(seq++, Wire.of(new PlayerPosition(1, .5, air ? 95.0 : 64.0, .5, 0,0,0,37F,0F,0)));
                assertEquals(24, in.readInt());
                boolean wasUsing = false;
                for (int tick = 0; tick < 24; tick++) {
                    int mask = in.readUnsignedByte(), count = in.readInt();
                    var packets = new ArrayList<InboundEvent>(); if (tick == 0) packets.add(PlayerLoaded.INSTANCE);
                    Movement movement = null; boolean announced = false;
                    for (int p = 0; p < count; p++) {
                        var packet = LocalPlayerOracleCodec.packet(in); packets.add(packet);
                        if (packet instanceof Movement move) movement = move;
                        if (packet instanceof HeldSlot) announced = true;
                    }
                    var expected = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
                    var velocity = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
                    boolean using = in.readBoolean();
                    String context = "26.3" + " " + item + " off=" + off + " air=" + air + " choice=" + choice + " tick=" + tick
                            + " using=" + using + " packets=" + packets;
                    var frame = new ClientTickFrame(tick, tick, tick, new SyncWindow(seq-1, seq), Wire.ofAll(packets),
                            (new ru.minecomplex.mac.fixture.inbound.PlayerInput(mask)).flags(), true, Wire.of(movement), Set.of());
                    var report = probe.frame(frame);
                    assertEquals(MovementCheckRunner.Status.COMPARED, report.status(), context);
                    assertEquals(expected, report.predicted(), context + " " + report + " velocity=" + velocity);
                    assertEquals(0.0, report.offset(), context);
                    assertTrue(Set.of(Unmodeled.ITEM_USE_TIMING).containsAll(report.gaps()), context + report.gaps());
                    String diagnostic = probe.diagnosticState();
                    var carried = java.util.regex.Pattern.compile("(?m)^player position=.*? velocity=Vec3d\\[x=([^,]+), y=([^,]+), z=([^\\]]+)\\]")
                            .matcher(diagnostic);
                    assertTrue(carried.find(), context + " missing velocity diagnostic");
                    assertEquals(velocity, new Vec3d(Double.parseDouble(carried.group(1)), Double.parseDouble(carried.group(2)),
                            Double.parseDouble(carried.group(3))), context + " carried velocity");
                    assertTrue(diagnostic.contains(" using=" + using + " useRemaining="), context + " use state");
                    if (using) uses++;
                    if (wasUsing && !using && tick == 8 && !announced) hiddenStops++;
                    if (tick == 9) assertTrue(announced, context + " hidden selection must be announced next tick");
                    wasUsing = using;
                }
            }
            assertEquals(-1, in.read());
        }
        assertTrue(uses > 0); assertEquals(20, hiddenStops, "26.3 different-item main-hand switches");
    }
}
