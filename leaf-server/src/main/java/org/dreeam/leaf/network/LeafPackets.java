package org.dreeam.leaf.network;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The server's packet system. Handlers see and change the vanilla packet objects as they are received
 * and sent; nothing is encoded or decoded for them.
 * <pre>{@code
 * LeafPackets.listen(plugin, GamePacketTypes.SERVERBOUND_CHAT, event -> {
 *     ServerboundChatPacket chat = event.getPacket();
 *     ...
 * });
 * LeafPackets.listen(plugin, GamePacketTypes.CLIENTBOUND_SET_ENTITY_DATA, PacketPriority.HIGH, true, event -> {
 *     ClientboundSetEntityDataPacket data = event.getPacket();
 *     event.setPacket(new ClientboundSetEntityDataPacket(data.id(), filtered(data.packedItems())));
 * });
 * LeafPackets.listenConnections(plugin, new ConnectionListener() {
 *     public void onDisconnect(PacketUser user) { ... }
 * });
 * LeafPackets.user(player).sendSilently(packet);
 * }</pre>
 * Handlers and listeners run on the connection's Netty event loop, see {@link PacketHandler}.
 * <p>
 * Sent {@link ClientboundBundlePacket}s are passed to handlers of the bundle type first, then each packet
 * inside the bundle is passed to the handlers of its own type.
 */
public final class LeafPackets {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final PacketRegistration[] NO_HANDLERS = new PacketRegistration[0];
    private static final ConnectionRegistration[] NO_LISTENERS = new ConnectionRegistration[0];
    private static final Comparator<PacketRegistration> ORDER = Comparator
        .<PacketRegistration, PacketPriority>comparing(registration -> registration.priority)
        .thenComparingLong(registration -> registration.order);

    private static final Object LOCK = new Object();
    private static final List<PacketRegistration> HANDLERS = new ArrayList<>();
    private static final List<ConnectionRegistration> LISTENERS = new ArrayList<>();
    private static long nextOrder;

    private static volatile Handlers serverbound = Handlers.EMPTY;
    private static volatile Handlers clientbound = Handlers.EMPTY;
    private static volatile ConnectionRegistration[] connectionListeners = NO_LISTENERS;

    private static final Set<PacketUser> USERS = ConcurrentHashMap.newKeySet();
    private static final Set<PacketUser> USERS_VIEW = Collections.unmodifiableSet(USERS);

    private LeafPackets() {
    }

    // Packet handlers

    public static <P extends Packet<?>> PacketRegistration listen(final Plugin plugin, final PacketType<P> type, final PacketHandler<P> handler) {
        return listen(plugin, type, PacketPriority.NORMAL, false, handler);
    }

    public static <P extends Packet<?>> PacketRegistration listen(final Plugin plugin, final PacketType<P> type, final PacketPriority priority, final PacketHandler<P> handler) {
        return listen(plugin, type, priority, false, handler);
    }

    /**
     * @param type            for example {@code GamePacketTypes.SERVERBOUND_CHAT}; the direction comes from the type
     * @param ignoreCancelled {@code true} to skip packets an earlier handler has cancelled
     */
    @SuppressWarnings("unchecked")
    public static <P extends Packet<?>> PacketRegistration listen(
        final Plugin plugin,
        final PacketType<P> type,
        final PacketPriority priority,
        final boolean ignoreCancelled,
        final PacketHandler<P> handler
    ) {
        Objects.requireNonNull(type, "type");
        return register(plugin, type.flow(), type, priority, ignoreCancelled, (PacketHandler<Packet<?>>) (PacketHandler<?>) handler);
    }

    public static PacketRegistration listenAll(final Plugin plugin, final PacketFlow flow, final PacketHandler<Packet<?>> handler) {
        return listenAll(plugin, flow, PacketPriority.NORMAL, false, handler);
    }

    /**
     * Listens to every packet of one direction. Prefer {@link #listen} with a type: a handler for every packet
     * creates an event for every packet of every connection.
     */
    public static PacketRegistration listenAll(
        final Plugin plugin,
        final PacketFlow flow,
        final PacketPriority priority,
        final boolean ignoreCancelled,
        final PacketHandler<Packet<?>> handler
    ) {
        Objects.requireNonNull(flow, "flow");
        return register(plugin, flow, null, priority, ignoreCancelled, handler);
    }

    private static PacketRegistration register(
        final Plugin plugin,
        final PacketFlow flow,
        final @Nullable PacketType<?> type,
        final PacketPriority priority,
        final boolean ignoreCancelled,
        final PacketHandler<Packet<?>> handler
    ) {
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(handler, "handler");
        checkEnabled(plugin);

        synchronized (LOCK) {
            final PacketRegistration registration = new PacketRegistration(plugin, flow, type, priority, ignoreCancelled, handler, nextOrder++);
            HANDLERS.add(registration);
            rebuild(flow);
            return registration;
        }
    }

    // Connection listeners

    public static ConnectionRegistration listenConnections(final Plugin plugin, final ConnectionListener listener) {
        Objects.requireNonNull(listener, "listener");
        checkEnabled(plugin);

        synchronized (LOCK) {
            final ConnectionRegistration registration = new ConnectionRegistration(plugin, listener);
            LISTENERS.add(registration);
            connectionListeners = LISTENERS.toArray(NO_LISTENERS);
            return registration;
        }
    }

    // Users

    /**
     * @return every open connection, including ones that have not logged in yet
     */
    public static Collection<PacketUser> users() {
        return USERS_VIEW;
    }

    /**
     * @return the connection of an online player, or {@code null} for a player without one (fake players)
     */
    public static @Nullable PacketUser user(final Player player) {
        return user(((CraftPlayer) player).getHandle());
    }

    public static @Nullable PacketUser user(final ServerPlayer player) {
        return player.connection != null ? player.connection.connection.leafUser : null;
    }

    // Removal

    static void unregister(final Registration registration) {
        synchronized (LOCK) {
            if (registration instanceof PacketRegistration packet) {
                if (HANDLERS.remove(packet)) {
                    rebuild(packet.flow);
                }
            } else if (LISTENERS.remove(registration)) {
                connectionListeners = LISTENERS.toArray(NO_LISTENERS);
            }
        }
    }

    /**
     * Removes every handler and listener of the plugin. Called when the plugin is disabled.
     */
    public static void unregisterAll(final Plugin plugin) {
        synchronized (LOCK) {
            if (HANDLERS.removeIf(registration -> registration.plugin == plugin)) {
                rebuild(PacketFlow.SERVERBOUND);
                rebuild(PacketFlow.CLIENTBOUND);
            }
            if (LISTENERS.removeIf(registration -> registration.plugin == plugin)) {
                connectionListeners = LISTENERS.toArray(NO_LISTENERS);
            }
        }
    }

    private static void checkEnabled(final Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        if (!plugin.isEnabled()) {
            throw new IllegalStateException("Plugin " + plugin.getName() + " is not enabled");
        }
    }

    // Called with LOCK held
    private static void rebuild(final PacketFlow flow) {
        final List<PacketRegistration> any = new ArrayList<>();
        final Reference2ObjectOpenHashMap<PacketType<?>, List<PacketRegistration>> typed = new Reference2ObjectOpenHashMap<>();
        for (final PacketRegistration registration : HANDLERS) {
            if (registration.flow != flow) {
                continue;
            }
            if (registration.type == null) {
                any.add(registration);
            } else {
                List<PacketRegistration> list = typed.get(registration.type);
                if (list == null) {
                    list = new ArrayList<>();
                    typed.put(registration.type, list);
                }
                list.add(registration);
            }
        }

        final Handlers handlers;
        if (any.isEmpty() && typed.isEmpty()) {
            handlers = Handlers.EMPTY;
        } else {
            // Each type gets one sorted array that already contains the handlers for every packet
            final Reference2ObjectOpenHashMap<PacketType<?>, PacketRegistration[]> merged = new Reference2ObjectOpenHashMap<>(typed.size());
            for (final var entry : typed.reference2ObjectEntrySet()) {
                final List<PacketRegistration> list = entry.getValue();
                list.addAll(any);
                list.sort(ORDER);
                merged.put(entry.getKey(), list.toArray(NO_HANDLERS));
            }
            any.sort(ORDER);
            handlers = new Handlers(merged, any.toArray(NO_HANDLERS));
        }

        if (flow == PacketFlow.SERVERBOUND) {
            serverbound = handlers;
        } else {
            clientbound = handlers;
        }
    }

    // Dispatch, called on the event loop by PacketInterceptor

    /**
     * @param sentTasks where handlers of sent packets add their tasks; {@code null} for received packets
     * @return the packet to pass on, or {@code null} if it was cancelled
     */
    static @Nullable Packet<?> dispatch(final PacketUser user, final Packet<?> packet, final @Nullable List<Runnable> sentTasks) {
        final Handlers handlers = packet.type().flow() == PacketFlow.SERVERBOUND ? serverbound : clientbound;
        if (handlers == Handlers.EMPTY) {
            return packet;
        }

        final Packet<?> result = call(handlers, user, packet, sentTasks);
        if (result instanceof ClientboundBundlePacket bundle) {
            return dispatchBundle(handlers, user, bundle, sentTasks);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Packet<?> dispatchBundle(final Handlers handlers, final PacketUser user, final ClientboundBundlePacket bundle, final @Nullable List<Runnable> sentTasks) {
        List<Packet<? super ClientGamePacketListener>> changed = null;
        int index = 0;
        for (final Packet<? super ClientGamePacketListener> packet : bundle.subPackets()) {
            final Packet<?> result = call(handlers, user, packet, sentTasks);
            if (changed == null && result != packet) {
                changed = new ArrayList<>();
                int copied = 0;
                for (final Packet<? super ClientGamePacketListener> previous : bundle.subPackets()) {
                    if (copied++ == index) {
                        break;
                    }
                    changed.add(previous);
                }
            }
            if (changed != null && result != null) {
                changed.add((Packet<? super ClientGamePacketListener>) result);
            }
            index++;
        }

        if (changed == null) {
            return bundle;
        }
        return changed.isEmpty() ? null : new ClientboundBundlePacket(changed);
    }

    private static @Nullable Packet<?> call(final Handlers handlers, final PacketUser user, final Packet<?> packet, final @Nullable List<Runnable> sentTasks) {
        final PacketRegistration[] registrations = handlers.get(packet.type());
        if (registrations.length == 0) {
            return packet;
        }

        final PacketEvent<Packet<?>> event = new PacketEvent<>(user, packet, sentTasks);
        for (final PacketRegistration registration : registrations) {
            if (registration.ignoreCancelled && event.isCancelled()) {
                continue;
            }
            event.readOnly = registration.priority == PacketPriority.MONITOR;
            try {
                registration.handler.handle(event);
            } catch (final Throwable throwable) {
                LOGGER.error("Could not pass packet {} of {} to {}", event.getType(), user, registration.plugin.getName(), throwable);
            }
        }
        return event.isCancelled() ? null : event.getPacket();
    }

    static void runSentTasks(final PacketUser user, final Runnable[] tasks) {
        for (final Runnable task : tasks) {
            try {
                task.run();
            } catch (final Throwable throwable) {
                LOGGER.error("Could not run a task after sending a packet to {}", user, throwable);
            }
        }
    }

    // Connection events, called on the event loop

    static void connected(final PacketUser user) {
        USERS.add(user);
        for (final ConnectionRegistration registration : connectionListeners) {
            try {
                registration.listener.onConnect(user);
            } catch (final Throwable throwable) {
                LOGGER.error("Could not pass the connection of {} to {}", user, registration.plugin.getName(), throwable);
            }
        }
    }

    static void fireProtocolChange(final PacketUser user, final PacketFlow flow, final ConnectionProtocol from, final ConnectionProtocol to) {
        for (final ConnectionRegistration registration : connectionListeners) {
            try {
                registration.listener.onProtocolChange(user, flow, from, to);
            } catch (final Throwable throwable) {
                LOGGER.error("Could not pass the protocol change of {} to {}", user, registration.plugin.getName(), throwable);
            }
        }
    }

    static void disconnected(final PacketUser user) {
        if (!USERS.remove(user)) {
            return;
        }
        for (final ConnectionRegistration registration : connectionListeners) {
            try {
                registration.listener.onDisconnect(user);
            } catch (final Throwable throwable) {
                LOGGER.error("Could not pass the disconnect of {} to {}", user, registration.plugin.getName(), throwable);
            }
        }
    }

    private record Handlers(Reference2ObjectOpenHashMap<PacketType<?>, PacketRegistration[]> typed, PacketRegistration[] any) {
        static final Handlers EMPTY = new Handlers(new Reference2ObjectOpenHashMap<>(), NO_HANDLERS);

        PacketRegistration[] get(final PacketType<?> type) {
            final PacketRegistration[] registrations = this.typed.get(type);
            return registrations != null ? registrations : this.any;
        }
    }
}
