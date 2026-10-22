package org.dreeam.leaf.mac;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import net.minecraft.server.MinecraftServer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.dreeam.leaf.network.PacketEvent;
import org.dreeam.leaf.network.PacketUser;
import net.minecraft.network.protocol.Packet;
import ru.minecomplex.mac.bukkit.command.MacCommand;
import ru.minecomplex.mac.bukkit.debug.MovementDebug;
import ru.minecomplex.mac.bukkit.debug.ServerBlockTags;
import ru.minecomplex.mac.bukkit.net.AutomaticDiagnostics;
import ru.minecomplex.mac.bukkit.net.LeafPacketListener;
import ru.minecomplex.mac.bukkit.net.ServerBiomes;
import ru.minecomplex.mac.bukkit.net.Sessions;
import ru.minecomplex.mac.packet.NativeRuntime;

/** Server-owned MAC lifecycle. Simulation and packet snapshots stay on each connection's event loop. */
public final class MacRuntime {
    private static volatile MacRuntime instance;
    private final Sessions sessions;
    private final LeafPacketListener packets;
    private final MovementDebug debug;

    private MacRuntime(MinecraftServer server) throws IOException, InvalidConfigurationException {
        if (net.minecraft.SharedConstants.getCurrentVersion().protocolVersion() != NativeRuntime.PROTOCOL) {
            throw new IllegalStateException("MAC requires Leaf 26.3, protocol " + NativeRuntime.PROTOCOL);
        }
        if (server.server.getPluginManager().getPlugin("MAC") != null) {
            throw new IllegalStateException("MAC is built into Leaf; remove the separate MAC plugin before starting");
        }
        NativeRuntime.install(server.registryAccess());
        ru.minecomplex.mac.entity.EntityTypes.get();
        ru.minecomplex.mac.inventory.item.ItemBehavior.get();
        Path configuration = Path.of("config", "mac.yml");
        Files.createDirectories(configuration.getParent());
        if (!Files.exists(configuration)) {
            try (var input = MacRuntime.class.getResourceAsStream("/mac/config.yml")) {
                if (input == null) throw new IOException("Missing MAC default configuration");
                Files.copy(input, configuration);
            }
        }
        var config = new YamlConfiguration();
        config.load(configuration.toFile());
        var logger = Logger.getLogger("MAC");
        String description = server.server.getName() + " " + server.server.getMinecraftVersion();
        var tags = new ServerBlockTags();
        var automatic = new AutomaticDiagnostics(logger, Path.of("mac", "diagnostics"),
                "server=" + description + " java=" + System.getProperty("java.version"), tags, config);
        sessions = new Sessions(logger, description, tags, new ServerBiomes(logger), Path.of("mac", "recordings"), automatic);
        debug = new MovementDebug(server::execute);
        packets = new LeafPacketListener(logger, sessions, server.registryAccess());
        var executor = new MacCommand(server::execute, sessions, debug);
        if (server.server.getPluginManager().getPermission("mac.admin") == null) {
            server.server.getPluginManager().addPermission(new Permission("mac.admin", PermissionDefault.OP));
        }
        server.server.getCommandMap().register("leaf", new Command("mac", "MAC anticheat", "/mac", List.of()) {
            { setPermission("mac.admin"); }

            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return !testPermission(sender) || executor.onCommand(sender, this, label, args);
            }

            @Override
            public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                return testPermissionSilent(sender) ? executor.onTabComplete(sender, this, alias, args) : List.of();
            }
        });
        logger.info("Built-in MAC enabled for " + description);
    }

    public static void start(MinecraftServer server) {
        if (instance != null) throw new IllegalStateException("MAC is already started");
        try {
            instance = new MacRuntime(server);
        } catch (IOException | InvalidConfigurationException failure) {
            throw new IllegalStateException("Cannot initialize built-in MAC", failure);
        }
    }

    public static boolean active() {
        return instance != null;
    }

    /** Called before plugin inbound handlers, and after plugin outbound handlers. */
    public static void packet(PacketEvent<Packet<?>> event) {
        MacRuntime runtime = instance;
        if (runtime == null) return;
        try {
            if (event.isSent()) runtime.packets.send(event);
            else runtime.packets.receive(event);
        } catch (RuntimeException failure) {
            runtime.packets.disconnected(event.getUser());
            Logger.getLogger("MAC").log(java.util.logging.Level.SEVERE,
                    "MAC stopped tracking a connection after a packet capture failure", failure);
        }
    }

    public static void disconnected(PacketUser user) {
        MacRuntime runtime = instance;
        if (runtime != null) runtime.packets.disconnected(user);
    }

    public static void tickEnd() {
        MacRuntime runtime = instance;
        if (runtime != null) {
            for (var handle : runtime.sessions.all()) handle.requestMarkerAsync();
        }
    }

    public static void shutdown() {
        MacRuntime runtime = instance;
        instance = null;
        if (runtime == null) return;
        runtime.debug.stopAll();
        runtime.packets.close();
        runtime.sessions.close();
    }
}
