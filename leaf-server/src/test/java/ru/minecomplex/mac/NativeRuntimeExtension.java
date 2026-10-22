package ru.minecomplex.mac;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import ru.minecomplex.mac.packet.NativeRuntime;

/**
 * Every test class runs with native 26.3 available ({@link NativeRuntime}): the core's types (stacks, wrappers)
 * are native 26.3 classes whose initialization needs its API, as the plugin's do on the server.
 */
public final class NativeRuntimeExtension implements BeforeAllCallback {
    @Override
    public void beforeAll(ExtensionContext context) {
        NativeRuntime.ensure();
        if(io.papermc.paper.configuration.GlobalConfiguration.get()==null) {
            var configuration=new io.papermc.paper.configuration.GlobalConfiguration();
            configuration.anticheat=configuration.new Anticheat();
            configuration.anticheat.obfuscation=configuration.anticheat.new Obfuscation();
            configuration.anticheat.obfuscation.items=configuration.anticheat.obfuscation.new Items();
            // The native item codec initializes Paper's sanitizer even when obfuscation is disabled.
            // Supply only its registryAccess dependency; no server thread, worlds or network are started.
            try {
                if(net.minecraft.server.MinecraftServer.getServer()==null) {
                    var type=new net.bytebuddy.ByteBuddy().subclass(net.minecraft.server.MinecraftServer.class,net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy.Default.NO_CONSTRUCTORS)
                            .method(net.bytebuddy.matcher.ElementMatchers.named("registryAccess"))
                            .intercept(net.bytebuddy.implementation.FixedValue.reference(NativeRuntime.registries()))
                            .make().load(getClass().getClassLoader()).getLoaded();
                    var server=new org.objenesis.ObjenesisStd().newInstance(type);
                    var field=net.minecraft.server.MinecraftServer.class.getDeclaredField("SERVER");
                    field.setAccessible(true);field.set(null,server);
                }
                configuration.anticheat.obfuscation.items.bindDataSanitizer();
            } catch(ReflectiveOperationException e) { throw new AssertionError(e); }
            try {
                var setter=configuration.getClass().getDeclaredMethod("set",configuration.getClass());
                setter.setAccessible(true);setter.invoke(null,configuration);
            } catch(ReflectiveOperationException e) { throw new AssertionError(e); }
        }
    }
}
