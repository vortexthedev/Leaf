package ru.minecomplex.mac.world;

import org.junit.jupiter.api.Test;

import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import ru.minecomplex.mac.Wire;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import net.minecraft.resources.Identifier;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import ru.minecomplex.mac.replica.WorldModel;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class SyncedServerTagsTest {
    private record Tag(String key,List<Integer> values) { }
    @org.junit.jupiter.api.BeforeAll
    static void initializeNativePackets() { ru.minecomplex.mac.packet.NativeRuntime.ensure(); }

    private static ClientboundUpdateTagsPacket packet(String registry, Tag... tags) {
        var map = new java.util.LinkedHashMap<Identifier,it.unimi.dsi.fastutil.ints.IntList>();
        for (var tag:tags) map.put(Identifier.parse(tag.key()),new it.unimi.dsi.fastutil.ints.IntArrayList(tag.values()));
        return new ClientboundUpdateTagsPacket(Map.of(net.minecraft.resources.ResourceKey.createRegistryKey(Identifier.withDefaultNamespace(registry)),new net.minecraft.tags.TagNetworkSerialization.NetworkPayload(map)));
    }
    private static Tag climbable(BlockRegistry registry, String name) {
        return new Tag("minecraft:climbable", List.of(registry.block(name).registryId()));
    }
    private static boolean contains(BlockTags tags, BlockRegistry registry, String block) {
        return tags.contains(BlockTag.CLIMBABLE, registry.block(block));
    }

    @Test
    void otherRegistriesPreserveTheCurrentBlockSnapshot() {
        var registry = BlockRegistry.get();
        var original = ResolvedBlockTags.of(packet("block", climbable(registry, "minecraft:ladder")));
        assertSame(original, ResolvedBlockTags.apply(original, packet("item")));
        assertSame(original, ResolvedBlockTags.apply(original, packet("fluid")));
        assertSame(original, ResolvedBlockTags.apply(original, new ClientboundUpdateTagsPacket(Map.of())));
        assertNull(ResolvedBlockTags.apply(null, packet("item")));
        assertTrue(contains(original, registry, "minecraft:ladder"));
    }

    @Test
    void anExplicitBlockRegistryReplacesRatherThanMergesItsTags() {
        var registry = BlockRegistry.get();
        var original = ResolvedBlockTags.of(packet("block", climbable(registry, "minecraft:ladder"),
                new Tag("minecraft:fences", List.of(registry.block("minecraft:oak_fence").registryId()))));
        var replacement = ResolvedBlockTags.apply(original, packet("block", climbable(registry, "minecraft:vine")));
        assertFalse(contains(replacement, registry, "minecraft:ladder"));
        assertTrue(contains(replacement, registry, "minecraft:vine"));
        assertFalse(replacement.contains(BlockTag.FENCES, registry.block("minecraft:oak_fence")));
        assertTrue(original.contains(BlockTag.FENCES, registry.block("minecraft:oak_fence")));
        assertTrue(contains(original, registry, "minecraft:ladder"));
        assertFalse(contains(ResolvedBlockTags.apply(original, packet("block")), registry, "minecraft:ladder"));
    }

    @Test
    void onlyTrackedTagsAreReadAndTheirMembersAreCopied() {
        var registry = BlockRegistry.get();
        var members = new it.unimi.dsi.fastutil.ints.IntArrayList(new int[]{registry.block("minecraft:ladder").registryId()});
        var ignored = new it.unimi.dsi.fastutil.ints.AbstractIntList() {
            @Override public int getInt(int index) { throw new AssertionError("Untracked tag members were read"); }
            @Override public int size() { return 1; }
        };
        var payload=new net.minecraft.tags.TagNetworkSerialization.NetworkPayload(Map.of(
                Identifier.withDefaultNamespace("climbable"),members,Identifier.withDefaultNamespace("not_used_by_mac"),ignored));
        var resolved=ResolvedBlockTags.of(new ClientboundUpdateTagsPacket(Map.of(net.minecraft.core.registries.Registries.BLOCK,payload)));
        members.clear();
        assertTrue(contains(resolved,registry,"minecraft:ladder"));
    }

    @Test
    void splitConfigurationRegistriesSurviveUntilLogin() {
        var registry = BlockRegistry.get();
        var model = new WorldModel(registry);
        model.sent(0, packet("block", climbable(registry, "minecraft:ladder")));
        model.sent(1, packet("item"));
        model.sent(2, packet("fluid"));
        model.sent(3, Wire.of(new JoinGame(1, "minecraft:overworld", 0, 32, 2), Wire.FACTS));
        var world = model.acknowledged(new SyncWindow(3, 4));
        assertTrue(contains(world.tags(), registry, "minecraft:ladder"));
    }

    @Test
    void playUpdatesFollowMarkerPrefixesAndDoNotMutateForks() {
        var registry = BlockRegistry.get();
        var model = new WorldModel(registry);
        model.sent(0, packet("block", climbable(registry, "minecraft:ladder")));
        model.sent(1, Wire.of(new JoinGame(1, "minecraft:overworld", 0, 32, 2), Wire.FACTS));
        model.sent(2, packet("item"));
        model.sent(3, packet("block", climbable(registry, "minecraft:vine")));
        var window = new SyncWindow(1, 4);
        var certain = model.acknowledged(window);
        try (var hypothesis = model.hypothesis(window, 1); var fork = hypothesis.fork()) {
            hypothesis.apply(window, 2);
            assertTrue(contains(hypothesis.world().tags(), registry, "minecraft:ladder"));
            hypothesis.apply(window, 3);
            assertTrue(contains(hypothesis.world().tags(), registry, "minecraft:vine"));
            assertFalse(contains(hypothesis.world().tags(), registry, "minecraft:ladder"));
            assertTrue(contains(fork.world().tags(), registry, "minecraft:ladder"));
            assertTrue(contains(certain.tags(), registry, "minecraft:ladder"));
        }
    }
}
