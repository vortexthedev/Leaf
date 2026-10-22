package ru.minecomplex.mac.replay;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagNetworkSerialization.NetworkPayload;
import ru.minecomplex.mac.packet.NativePacketCodec;
import ru.minecomplex.mac.packet.NativeRuntime;
import ru.minecomplex.mac.packet.PacketDecoder;
import ru.minecomplex.mac.packet.PacketInput;
import ru.minecomplex.mac.player.connection.PlayerConnectionContext;

/** A connection's native 26.3 registries and codecs, shared by live capture and headless replay. */
public final class ReplayConnection {
    private RegistryAccess.Frozen registries;
    private NativePacketCodec codec;
    private final Map<ResourceKey<? extends Registry<?>>, RegistryDataLoader.NetworkedRegistryData> entries = new LinkedHashMap<>();

    private final Map<ResourceKey<? extends Registry<?>>,NetworkPayload> tags = new LinkedHashMap<>();

    public ReplayConnection(PlayerConnectionContext facts) {
        registries = NativeRuntime.registries();
        codec = new NativePacketCodec(registries);
    }

    public RegistryAccess.Frozen registries() { return registries; }
    public NativePacketCodec codec() { return codec; }
    public PacketInput receive(Recording.Packet packet) { return new PacketInput(packet, PacketDecoder.decode(codec.decode(packet), registries), registries); }
    public PacketInput send(Recording.Packet packet) { return new PacketInput(packet, PacketDecoder.decode(codec.decode(packet), registries), registries); }

    public ClientboundUpdateTagsPacket configurationTags(Recording.Packet packet) {
        var decoded = codec.decode(packet);
        if (decoded instanceof ClientboundUpdateTagsPacket update) { tags.putAll(update.tags()); return update; }
        return null;
    }

    public void registry(Recording.Packet packet) {
        var decoded = codec.decode(packet);
        if (decoded instanceof ClientboundRegistryDataPacket data) {
            entries.put(data.registry(), new RegistryDataLoader.NetworkedRegistryData(data.entries(), NetworkPayload.EMPTY));
        } else if (decoded instanceof ClientboundFinishConfigurationPacket && !entries.isEmpty()) {
            tags.forEach((key,value) -> { if (net.minecraft.core.RegistrySynchronization.isNetworkable(key)) entries.compute(key,(k,old)->new RegistryDataLoader.NetworkedRegistryData(old == null ? java.util.List.of() : old.elements(),value)); });
            var builtins = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            var received = ru.minecomplex.mac.packet.NativeRegistryLoader.load(entries, builtins, tags);
            registries = NativeRuntime.combine(builtins, received);
            BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(pending -> {
                if (net.minecraft.core.RegistrySynchronization.isNetworkable(pending.key())) pending.apply();
            });
            codec = new NativePacketCodec(registries);
            entries.clear();
            tags.clear();
        }
    }
}
