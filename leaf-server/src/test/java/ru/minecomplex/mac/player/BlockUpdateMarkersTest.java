package ru.minecomplex.mac.player;

import org.junit.jupiter.api.Test;

import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;

import static org.junit.jupiter.api.Assertions.*;

import ru.minecomplex.mac.frame.BetweenTicks;
import ru.minecomplex.mac.Wire;
import ru.minecomplex.mac.frame.ClientTickFrame;
import ru.minecomplex.mac.frame.FrameListener;
import ru.minecomplex.mac.player.transaction.MarkerPolicy;
import net.minecraft.nbt.CompoundTag;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.ServerboundPongPacket;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class BlockUpdateMarkersTest {

    private static MacPlayer session() {
        return new MacPlayer(new FrameListener() {
            @Override public void betweenTicks(BetweenTicks event) { }
            @Override public void frame(ClientTickFrame frame) { }
        });
    }

    private static Object block(int kind) {
        return switch (kind % 3) {
            case 0 -> new ClientboundBlockChangedAckPacket(kind);
            case 1 -> new ClientboundBlockEventPacket(new BlockPos(0, 64, 0), net.minecraft.world.level.block.Blocks.AIR, 0, 0);
            default -> new ClientboundBlockEntityDataPacket(new BlockPos(0, 64, 0), net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.withDefaultNamespace("chest")), new CompoundTag());
        };
    }

    @Test
    void burstSharesOneRequestWithoutDroppingAnyWorldChange() {
        var session = session();
        int requests = 0;
        for (int i = 0; i < 3_000; i++) {
            if (session.outbound(block(i), i)) requests++;
        }
        assertEquals(1, requests);
        assertEquals(3_000, session.world().pendingCount());
        assertEquals(3_000, session.currentWindow().possibleBefore());
    }

    @Test
    void sustainedUpdatesRespectTheCooldownEvenBeforeTheQueuedMarkerRuns() {
        var session = session();
        int requests = 0;
        for (int i = 0; i < 10_000; i++) {
            if (session.outbound(block(i), i * 1_000L)) requests++;
        }
        assertEquals(5, requests); // 10 ms of changes, one request per 2 ms
        assertEquals(10_000, session.world().pendingCount());
    }

    @Test
    void everyObservedOwnMarkerStartsTheCooldownButForeignOrCancelledMarkersDoNot() {
        var session = session();
        int marker = session.allocateMarker(0);
        session.outbound(new ClientboundPingPacket(marker), 0);
        long interval = MarkerPolicy.BLOCK_UPDATE_INTERVAL_NANOS;
        assertFalse(session.outbound(block(0), interval - 1));
        // This ping belongs to another plugin and cannot prove our block window.
        session.outbound(new ClientboundPingPacket(marker + 1234), interval - 1);
        session.allocateMarker(interval - 1); // cancelled before observation
        assertTrue(session.outbound(block(1), interval));
        assertEquals(1, session.sync().outstandingCount());
    }

    @Test
    void criticalPacketsBypassTheBlockCooldown() {
        var session = session();
        assertTrue(session.outbound(block(0), 0));
        assertTrue(session.outbound(Wire.of(new ru.minecomplex.mac.fixture.outbound.Abilities(false,false,false,false,.05F,.1F)), 1));
        assertTrue(session.outbound(Wire.of(new ru.minecomplex.mac.fixture.outbound.Abilities(false,true,true,false,.05F,.1F)), 2));
        assertFalse(session.outbound(block(1), 3));
    }

    @Test
    void tickMarkerSettlesTheSuppressedTailOnlyAfterItsPong() {
        var session = session();
        assertTrue(session.outbound(block(0), 0));
        assertFalse(session.outbound(block(1), 1));
        int marker = session.allocateMarker(2);
        session.outbound(new ClientboundPingPacket(marker), 3); // periodic tick marker, after the last update
        assertEquals(2, session.world().pendingCount());
        assertEquals(-1, session.currentWindow().certainThrough());
        assertEquals(MacPlayer.InboundDecision.CANCEL, session.inbound(new ServerboundPongPacket(marker), 4));
        assertEquals(2, session.currentWindow().certainThrough());
        session.world().settle(session.currentWindow());
        assertEquals(0, session.world().pendingCount());
    }
}
