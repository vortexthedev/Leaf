package ru.minecomplex.mac.fixture;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.configuration.*;
import net.minecraft.resources.*;
import net.minecraft.world.level.dimension.DimensionType;
import ru.minecomplex.mac.packet.*;
import ru.minecomplex.mac.listener.MultiBlockChangeBitRepackHandler;
import ru.minecomplex.mac.player.MacPlayer;
import ru.minecomplex.mac.player.connection.PlayerConnectionContext;
import ru.minecomplex.mac.replay.*;

/** Native packets round-trip through exactly the codec and registry stream used by a recorded session. */
public final class FixturePackets {
    private static final Map<MacPlayer, Connection> CONNECTIONS = new WeakHashMap<>();
    private FixturePackets() { }
    public static Recording.Packet packet(long nanos, Recording.Stage stage, Object packet) {
        return new NativePacketCodec(NativeRuntime.registries()).encode(nanos, stage, nativePacket(packet));
    }
    private static Packet<?> nativePacket(Object packet) {
        return switch (packet) {
            case Packet<?> p -> p;
            case UnhandledPacket p -> p.clientbound()
                    ? new net.minecraft.network.protocol.common.ClientboundKeepAlivePacket(0)
                    : new net.minecraft.network.protocol.common.ServerboundKeepAlivePacket(0);
            case MultiBlockChangeBitRepackHandler.Update p -> NativeFixtureFactory.section(p);
            default -> throw new IllegalArgumentException("Not a native wire fixture: " + packet);
        };
    }
    public static final class Connection {
        private final ReplayConnection replay;
        private RegistryAccess.Frozen registries = NativeRuntime.registries();
        private final List<DimensionType> dimensions = new ArrayList<>();
        public Connection(PlayerConnectionContext facts) { replay = new ReplayConnection(facts); }
        public ReplayConnection replay() { return replay; }
        public List<Recording.Packet> clientbound(long nanos, Object value) {
            var packets = new ArrayList<Recording.Packet>();
            var packet = nativePacket(value);
            if (packet instanceof ClientboundLoginPacket login) {
                login.setCommonPlayerSpawnInfo(register(nanos,login.commonPlayerSpawnInfo(),packets));
            } else if (packet instanceof ClientboundRespawnPacket respawn) {
                respawn.setCommonPlayerSpawnInfo(register(nanos,respawn.commonPlayerSpawnInfo(),packets));
            }
            packets.add(new NativePacketCodec(registries).encode(nanos,Recording.Stage.CLIENTBOUND,packet));
            return packets;
        }
        private CommonPlayerSpawnInfo register(long nanos, CommonPlayerSpawnInfo spawn, List<Recording.Packet> packets) {
            if (spawn.dimensionType().unwrapKey().isPresent()) return spawn;
            int existing=dimensions.indexOf(spawn.dimensionType().value());
            if(existing>=0) {
                var holder=registries.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(
                        ResourceKey.create(Registries.DIMENSION_TYPE,Identifier.fromNamespaceAndPath("mac","fixture_"+existing)));
                return new CommonPlayerSpawnInfo(holder,spawn.dimension(),spawn.seed(),spawn.gameType(),spawn.previousGameType(),spawn.isDebug(),spawn.isFlat(),spawn.lastDeathLocation(),spawn.portalCooldown(),spawn.seaLevel());
            }
            dimensions.add(spawn.dimensionType().value());
            var registry = new MappedRegistry<DimensionType>(Registries.DIMENSION_TYPE,com.mojang.serialization.Lifecycle.stable());
            var original = NativeRuntime.registries().lookupOrThrow(Registries.DIMENSION_TYPE);
            for (var holder : original.listElements().toList()) registry.register(holder.key(),holder.value(),RegistrationInfo.BUILT_IN);
            Holder<DimensionType> selected = null;
            for (int i=0;i<dimensions.size();i++) selected=registry.register(ResourceKey.create(Registries.DIMENSION_TYPE,Identifier.fromNamespaceAndPath("mac","fixture_"+i)),dimensions.get(i),RegistrationInfo.BUILT_IN);
            registry.freeze();
            var all = new LinkedHashMap<ResourceKey<?>,Registry<?>>();
            NativeRuntime.registries().registries().forEach(e->all.put(e.key(),e.value()));
            all.put(Registries.DIMENSION_TYPE,registry);
            registries = new RegistryAccess.ImmutableRegistryAccess(List.copyOf(all.values())).freeze();
            var codec = new NativePacketCodec(registries);
            RegistrySynchronization.packRegistries(RegistryOps.create(NbtOps.INSTANCE,registries),registries,Set.of(),(key,entries)->
                    packets.add(codec.encode(nanos,Recording.Stage.REGISTRY,new ClientboundRegistryDataPacket(key,entries))));
            var tags=new LinkedHashMap<ResourceKey<? extends Registry<?>>,net.minecraft.tags.TagNetworkSerialization.NetworkPayload>();
            registries.registries().filter(e->RegistrySynchronization.isNetworkable(e.key())).forEach(e->{
                var values=new LinkedHashMap<Identifier,it.unimi.dsi.fastutil.ints.IntList>();
                e.value().getTags().forEach(tag->{
                    var ids=new it.unimi.dsi.fastutil.ints.IntArrayList();
                    tag.forEach(holder->ids.add(((Registry)e.value()).getId(holder.value())));
                    values.put(tag.key().location(),ids);
                });
                tags.put(e.key(),new net.minecraft.tags.TagNetworkSerialization.NetworkPayload(values));
            });
            packets.add(codec.encode(nanos,Recording.Stage.CONFIGURATION,new net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket(tags)));
            packets.add(codec.encode(nanos,Recording.Stage.REGISTRY,ClientboundFinishConfigurationPacket.INSTANCE));
            for(var recorded:packets) {if(recorded.stage()==Recording.Stage.CONFIGURATION) replay.configurationTags(recorded);else replay.registry(recorded); }
            return new CommonPlayerSpawnInfo(selected,spawn.dimension(),spawn.seed(),spawn.gameType(),spawn.previousGameType(),spawn.isDebug(),spawn.isFlat(),spawn.lastDeathLocation(),spawn.portalCooldown(),spawn.seaLevel());
        }
    }
    public static MacPlayer.InboundDecision receive(MacPlayer session,Object packet,long nanos) {
        var connection=connection(session);
        var recorded=connection.replay().codec().encode(nanos,Recording.Stage.SERVERBOUND,nativePacket(packet));
        return session.receive(connection.replay().receive(recorded),nanos);
    }
    public static boolean send(MacPlayer session,Object packet,long nanos) {
        var connection=connection(session);
        boolean marker=false;
        for(var recorded:connection.clientbound(nanos,packet)) {
            if(recorded.stage()==Recording.Stage.REGISTRY) session.configuration(recorded,null);
            else if(recorded.stage()==Recording.Stage.CONFIGURATION) session.configuration(recorded,connection.replay().configurationTags(recorded));
            else marker=session.send(connection.replay().send(recorded),nanos);
        }
        return marker;
    }
    private static synchronized Connection connection(MacPlayer session) {
        return CONNECTIONS.computeIfAbsent(session,s->new Connection(s.facts()==null?new PlayerConnectionContext(null,null):s.facts()));
    }
}
