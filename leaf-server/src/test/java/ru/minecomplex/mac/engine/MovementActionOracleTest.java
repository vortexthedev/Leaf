package ru.minecomplex.mac.engine;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.List;

import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.inventory.item.ItemBehavior;

import ru.minecomplex.mac.fixture.FixtureItems;

import ru.minecomplex.mac.fixture.WireItem;
import ru.minecomplex.mac.listener.ClientPlayerActions;
import ru.minecomplex.mac.player.state.ClientPlayerData;
import net.minecraft.world.level.GameType;
import ru.minecomplex.mac.player.PlayerModel;
import ru.minecomplex.mac.fixture.inbound.*;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.engine.movementtick.LocalPlayerOracleCodec;
import ru.minecomplex.mac.engine.movementtick.MovementTickerPlayer;
import ru.minecomplex.mac.engine.movementtick.LocalPlayerPhysicsOracleTest;
import ru.minecomplex.mac.engine.movementtick.LocalTickOracle;
import ru.minecomplex.mac.engine.movementtick.MovementOracleTest;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;

/** Whole movement chains around actual use/release/attack entry points. Aim, target ticks and entity collisions are
 * fixture boundaries; item use state, attack slowdown and subsequent motion are computed by the production model.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class MovementActionOracleTest {
    @org.junit.jupiter.api.BeforeAll static void initializeNativePackets() { ru.minecomplex.mac.packet.NativeRuntime.ensure(); }

    @Test
    void useReleaseSlotChangeAndAttackRemainBitExactDuringMovement() throws Exception {
        var raw = getClass().getResourceAsStream("/vanilla/777/movement-actions.bin.gz");
        assertNotNull(raw, "26.3");
        var items = ItemBehavior.get();
        int using = 0, attacks = 0, ticks = 0;
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d41414d, in.readInt()); assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt()); org.junit.jupiter.api.Assertions.assertEquals("26.3", in.readUTF(), "Fixture version"); assertEquals(64, in.readInt());
            for (int scenario = 0; scenario < 64; scenario++) {
                String item = in.readUTF(); boolean off = in.readBoolean(); String type = in.readUTF();
                int mode = in.readInt(), floor = in.readInt();
                var initial = LocalTickOracle.State.read(in); var base = initial.base();
                PlayerState player = base.player().toState(); var local = new LocalPlayerState();
                local.input = base.input(); local.vector = base.vector(); local.send = base.sendState();
                local.send.wasShiftKeyDown = initial.wasShift();
                var data = new ClientPlayerData(); data.clientLoaded=true; data.localMode = GameType.SURVIVAL; data.food = 14;
                data.inventory.replace(off ? 40 : 0, FixtureItems.stack(WireItem.of(item,1)));
                data.inventory.replace(9, FixtureItems.stack(WireItem.of("minecraft:arrow",1)));
                var hypothesis = new PlayerModel().start(player, local, data, -1);
                var blocks = new ArrayList<LocalTickOracle.Block>();
                for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) blocks.add(new LocalTickOracle.Block(x,63,z,floor));
                var physics = new MovementTickerPlayer(LocalPlayerPhysicsOracleTest.environment(BlockRegistry.get(),blocks), player,local,data);
                var target = new ClientPlayerActions.Target() {
                    public String block(int x,int y,int z) { return null; }
                    public String entityType(int id) { return id == 42 ? type : null; }
                };
                assertEquals(32, in.readInt());
                for (int tick = 0; tick < 32; tick++) {
                    int op = in.readUnsignedByte(), mask = in.readUnsignedByte(); float yaw = in.readFloat();
                    String context = "26.3" + " " + item + " off=" + off + " target=" + type + " mode=" + mode + " tick=" + tick;
                    InboundEvent action = switch (op) {
                        case 0 -> null;
                        case 1 -> new UseItem(off, tick, yaw, 0F);
                        case 2 -> PlayerAction.of(PlayerAction.Action.RELEASE_USE_ITEM);
                        case 3 -> new Attack(42);
                        case 4 -> new HeldSlot(1);
                        case 5 -> new HeldSlot(0);
                        default -> throw new AssertionError("Unknown MAAM action");
                    };
                    if (action != null) assertTrue(hypothesis.act(Wire.of(action,Wire.FACTS),target).isEmpty(), context);
                    if (op == 3) attacks++;
                    hypothesis.livingTickStart();
                    player.yRot = yaw; player.xRot = 0F;
                    var actual = physics.tick(PlayerInput.fromMask(mask));
                    hypothesis.playerTickEnd(); hypothesis.tickEnded();
                    var expected = LocalTickOracle.State.read(in);
                    boolean expectedUsing = in.readBoolean(); int attackTicks = in.readInt();
                    var packets = new ArrayList<InboundEvent>(); int count = in.readInt();
                    for (int i = 0; i < count; i++) packets.add(LocalPlayerOracleCodec.packet(in));
                    assertTrue(actual.isEmpty(), context + actual);
                    assertEquals(List.of(), expected.base().player().differences(MovementOracleTest.Recorded.of(player)), context);
                    assertEquals(packets, Wire.in(physics.packets()), context + " packets");
                    assertEquals(expectedUsing, data.usingItem, context + " use state");
                    if (expectedUsing) using++;
                    for (String field : new String[]{"attackTicksMin","attackTicksMax"}) {
                        var value = ClientPlayerData.class.getDeclaredField(field); value.setAccessible(true);
                        assertEquals(attackTicks,value.getInt(data), context + " " + field);
                    }
                    ticks++;
                }
            }
            assertEquals(-1,in.read());
        }
        assertEquals(2048,ticks); assertEquals(64,attacks); assertTrue(using >= 512, "Actual uses must start");
    }
}
