package ru.minecomplex.mac.packet;

import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.ResourceManager;

/** Native registries for headless fixtures and replay; a live adapter installs its actual frozen registry access. */
public final class NativeRuntime {
    /** Protocol of native Minecraft 26.3: the only one MAC decodes, records and bundles vanilla data for. */
    public static final int PROTOCOL = 777;

    private static volatile RegistryAccess.Frozen registries;
    private static ResourceManager vanilla;
    private NativeRuntime() { }

    public static synchronized void ensure() {
        if (registries != null) return;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var builtins = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var tags = net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(vanilla(), builtins);
        var dynamic = RegistryDataLoader.load(vanilla(), net.minecraft.tags.TagLoader.buildUpdatedLookups(builtins, tags),
                RegistryDataLoader.SYNCHRONIZED_REGISTRIES, Runnable::run).join();
        registries = combine(builtins, dynamic);
        tags.forEach(pending -> pending.apply());
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(pending -> pending.apply());
    }

    public static synchronized ResourceManager vanilla() {
        if (vanilla == null) vanilla = new net.minecraft.server.packs.resources.MultiPackResourceManager(net.minecraft.server.packs.PackType.SERVER_DATA, java.util.List.of(ServerPacksSource.createVanillaPackSource().fullResources()));
        return vanilla;
    }

    public static void install(RegistryAccess access) { registries = access.freeze(); }
    public static RegistryAccess.Frozen registries() { ensure(); return registries; }

    public static RegistryAccess.Frozen combine(RegistryAccess first, RegistryAccess second) {
        var entries = new java.util.LinkedHashMap<net.minecraft.resources.ResourceKey<?>, net.minecraft.core.Registry<?>>();
        Stream.concat(first.registries(), second.registries()).forEach(e -> entries.put(e.key(), e.value()));
        return new RegistryAccess.ImmutableRegistryAccess(java.util.List.copyOf(entries.values())).freeze();
    }

}
