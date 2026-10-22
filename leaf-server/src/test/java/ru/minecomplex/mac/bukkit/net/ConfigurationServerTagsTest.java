package ru.minecomplex.mac.bukkit.net;

import org.junit.jupiter.api.Test;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import static org.junit.jupiter.api.Assertions.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import ru.minecomplex.mac.frame.BetweenTicks;
import ru.minecomplex.mac.frame.ClientTickFrame;
import ru.minecomplex.mac.frame.FrameListener;
import ru.minecomplex.mac.packet.NativeRuntime;
import ru.minecomplex.mac.replay.Recording;
import ru.minecomplex.mac.replay.RecordingBuffer;
import ru.minecomplex.mac.player.MacPlayer;
import org.dreeam.leaf.network.PacketUser;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;

class ConfigurationServerTagsTest {
    @org.junit.jupiter.api.BeforeAll static void initialize() { NativeTestRuntime.initialize(); }

    private static Recording.Packet recorded(ClientboundUpdateTagsPacket tags, long nanos) {
        return new ru.minecomplex.mac.packet.NativePacketCodec(NativeRuntime.registries()).encode(nanos,Recording.Stage.CONFIGURATION,tags);
    }

    @Test
    void configurationKeepsSplitRegistryUpdatesAndTheirRecordedOrder() {
        NativeRuntime.ensure();
        var channel = new EmbeddedChannel();
        // No play sessions or writer tasks are started: this test exercises only the configuration buffer.
        var sessions = new Sessions(Logger.getAnonymousLogger(), null, null, null, null, null);
        var connection = new Connection(PacketFlow.SERVERBOUND);
        connection.channel = channel;
        connection.protocolVersion = 777;
        var user = new PacketUser(connection);
        var session = new MacPlayer(new FrameListener() {
            @Override public void betweenTicks(BetweenTicks event) { }
            @Override public void frame(ClientTickFrame frame) { }
        });
        var recording = new RecordingBuffer(0);
        session.record(recording);
        try {
            var block = new ClientboundUpdateTagsPacket(Map.of(net.minecraft.core.registries.Registries.BLOCK,new net.minecraft.tags.TagNetworkSerialization.NetworkPayload(Map.of(Identifier.withDefaultNamespace("climbable"),new it.unimi.dsi.fastutil.ints.IntArrayList(new int[]{1})))));
            var item = new ClientboundUpdateTagsPacket(Map.of(net.minecraft.core.registries.Registries.ITEM,net.minecraft.tags.TagNetworkSerialization.NetworkPayload.EMPTY));
            var fluid = new ClientboundUpdateTagsPacket(Map.of(net.minecraft.core.registries.Registries.FLUID,net.minecraft.tags.TagNetworkSerialization.NetworkPayload.EMPTY));
            var blocksPacket = recorded(block, 1);
            var itemsPacket = recorded(item, 2);
            var fluidsPacket = recorded(fluid, 3);
            sessions.configuration(user, blocksPacket, block);
            sessions.configuration(user, itemsPacket, item);
            sessions.configuration(user, fluidsPacket, fluid);
            sessions.takeConfiguration(user, session);
            assertEquals(3, session.world().pendingCount());
            assertEquals(List.of(blocksPacket, itemsPacket, fluidsPacket), recording.snapshot("tags").entries());
        } finally { channel.finishAndReleaseAll(); }
    }
}
