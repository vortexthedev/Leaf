package ru.minecomplex.mac.packet;

import com.mojang.serialization.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.*;
import net.minecraft.tags.TagLoader;

/**
 * Client-style connection registry loading without RegistryLoadTask's server-global Paper registration.
 * Source: 26.3 NetworkRegistryLoadTask and RegistryDataLoader.createContext/freeze/validate.
 */
public final class NativeRegistryLoader {
    private NativeRegistryLoader() { }
    @SuppressWarnings({"unchecked","rawtypes"})
    public static RegistryAccess.Frozen load(Map<ResourceKey<? extends Registry<?>>,RegistryDataLoader.NetworkedRegistryData> entries,
                                            RegistryAccess.Frozen base, Map<ResourceKey<? extends Registry<?>>,net.minecraft.tags.TagNetworkSerialization.NetworkPayload> tags) {
        var registries=new LinkedHashMap<ResourceKey<? extends Registry<?>>,MappedRegistry<?>>();
        var getters=new LinkedHashMap<ResourceKey<? extends Registry<?>>,HolderGetter<?>>();
        var pendingStaticTags=new ArrayList<Registry.PendingTags<?>>();
        tags.forEach((key,payload)->{
            if(!RegistrySynchronization.isNetworkable(key) && !payload.isEmpty()) {
                Registry registry=base.lookupOrThrow((ResourceKey)key);
                pendingStaticTags.add(registry.prepareTagReload(payload.resolve(registry)));
            }
        });
        TagLoader.buildUpdatedLookups(base,pendingStaticTags).forEach(r->getters.put(r.key(),r));
        for(var data:RegistryDataLoader.SYNCHRONIZED_REGISTRIES) {
            var registry=new MappedRegistry(data.key(),Lifecycle.experimental());
            registries.put(data.key(),registry);
            getters.put(data.key(),registry.createRegistrationLookup());
        }
        var lookup=new RegistryOps.RegistryInfoLookup() {
            @Override public <T> Optional<HolderGetter<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
                return Optional.ofNullable((HolderGetter<T>)getters.get(key));
            }
            @Override public HolderLookup.Provider lookupForValueCopyViaBuilders() { return base; }
        };
        var nbt=RegistryOps.create(NbtOps.INSTANCE,lookup);
        var json=RegistryOps.create(JsonOps.INSTANCE,lookup);
        var registrationInfo=new RegistrationInfo(Optional.empty(),Lifecycle.experimental());
        for(var data:RegistryDataLoader.SYNCHRONIZED_REGISTRIES) {
            var received=entries.get(data.key());
            if(received==null) continue;
            MappedRegistry registry=registries.get(data.key());
            Codec codec=data.elementCodec();
            for(var entry:received.elements()) {
                Object value;
                if(entry.data().isPresent()) value=codec.parse(nbt,entry.data().get()).getOrThrow();
                else {
                    var file=FileToIdConverter.registry(data.key()).idToFile(entry.id());
                    var resource=NativeRuntime.vanilla().getResource(file).orElseThrow(()->new IllegalArgumentException("Missing known-pack entry "+file));
                    try(var reader=resource.openAsReader()) {
                        value=codec.parse(json,net.minecraft.util.StrictJsonParser.parse(reader)).getOrThrow();
                    } catch(java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                }
                registry.register(ResourceKey.create(data.key(),entry.id()),value,registrationInfo);
            }

        }
        for (var data:RegistryDataLoader.SYNCHRONIZED_REGISTRIES) {
            var received=entries.get(data.key());
            if(received!=null) { MappedRegistry registry=registries.get(data.key()); registry.bindTags(TagLoader.loadTagsFromNetwork(received.tags(),registry)); }
        }
        Map<ResourceKey<?>,Exception> errors=new LinkedHashMap<>();
        for(var data:RegistryDataLoader.SYNCHRONIZED_REGISTRIES) {
            var registry=registries.get(data.key());
            registry.freeze();
            ((RegistryValidator)data.validator()).validate(registry,errors);
        }
        if(!errors.isEmpty()) {
            var failure=new IllegalArgumentException("Invalid connection registries: "+errors.keySet());
            errors.values().forEach(failure::addSuppressed);throw failure;
        }
        return new RegistryAccess.ImmutableRegistryAccess(List.copyOf(registries.values())).freeze();
    }
}
