package ru.minecomplex.mac.bukkit.net;

final class NativeTestRuntime {
    static synchronized void initialize() {
        ru.minecomplex.mac.packet.NativeRuntime.ensure();
        if(io.papermc.paper.configuration.GlobalConfiguration.get()!=null)return;
        var configuration=new io.papermc.paper.configuration.GlobalConfiguration();
        configuration.misc=configuration.new Misc();
        try {
            var setter=configuration.getClass().getDeclaredMethod("set",configuration.getClass());
            setter.setAccessible(true);setter.invoke(null,configuration);
        } catch(ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
