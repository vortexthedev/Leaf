package ru.minecomplex.mac.player;

import org.junit.jupiter.api.Test;

import net.minecraft.world.level.GameType;

import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataInputStream;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.fixture.outbound.*;
import ru.minecomplex.mac.listener.ClientPlayerHandlers;
import ru.minecomplex.mac.player.state.ClientPlayerData;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;

/** MAPI v1 compares real handler/map/cache identities and signed-zero-sensitive callback motion. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class PlayerInfoIdentityOracleTest {

    @Test
    void followsRealPlayerInfoIdentity() throws Exception {
        int protocol = Integer.parseInt("26.3".substring(0, 3));
        String suffix = "26.3".substring(4);
        String path = "/vanilla/" + protocol + "/player-info-identity" + suffix + ".bin.gz";
        var resource = getClass().getResourceAsStream(path);
        assertNotNull(resource, path);
        var handlers = new ClientPlayerHandlers();
        PlayerState player = new PlayerState();
        LocalPlayerState local = new LocalPlayerState();
        ClientPlayerData data = new ClientPlayerData();
        data.localMode = GameType.SURVIVAL;
        try (var in = new DataInputStream(new GZIPInputStream(resource))) {
            assertEquals(0x4d415049, in.readInt()); assertEquals(1, in.readInt());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt()); org.junit.jupiter.api.Assertions.assertEquals("26.3", in.readUTF(), "Fixture version");
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int op = in.readUnsignedByte(), id = in.readInt(); double vy = in.readDouble();
                String context = "26.3" + " operation " + i + " kind " + op;
                player.deltaMovement = new Vec3d(0.25, vy, -0.125);
                switch (op) {
                    case 0 -> { data.playerInfoMode(); player.spectator = data.peekPlayerInfoMode() == GameType.SPECTATOR; }
                    case 1, 2 -> handlers.apply(player, local, data, Wire.of(new PlayerInfoGameMode(op == 1, true, id)));
                    case 3 -> handlers.apply(player, local, data, Wire.of(new PlayerInfoRemove()));
                    case 4 -> {
                        var result = handlers.apply(player, local, data,
                                Wire.of(new Respawn("minecraft:overworld", Respawn.KEEP_ENTITY_DATA, -64, 384)));
                        player = result.player(); local = result.local();
                    }
                    default -> fail("Unknown operation " + op);
                }
                assertEquals(in.readBoolean(), data.infoPresent, context + " map present");
                int mapMode = in.readInt();
                if (data.infoPresent) assertEquals(mapMode, data.infoMode.ordinal(), context + " map mode");
                assertEquals(in.readBoolean(), data.infoCached, context + " cached");
                assertEquals(in.readBoolean(), data.infoCached && data.infoCacheAttached, context + " attached");
                int mode = in.readInt();
                assertEquals(mode == 3, player.spectator, context + " spectator");
                assertEquals(mode, data.peekPlayerInfoMode() == null ? -1 : data.peekPlayerInfoMode().ordinal(), context + " effective mode");
                assertEquals(Double.doubleToLongBits(in.readDouble()), Double.doubleToLongBits(player.deltaMovement.x()), context + " vx");
                assertEquals(Double.doubleToLongBits(in.readDouble()), Double.doubleToLongBits(player.deltaMovement.y()), context + " vy");
                assertEquals(Double.doubleToLongBits(in.readDouble()), Double.doubleToLongBits(player.deltaMovement.z()), context + " vz");
            }
            assertEquals(-1, in.read());
        }
    }
}
