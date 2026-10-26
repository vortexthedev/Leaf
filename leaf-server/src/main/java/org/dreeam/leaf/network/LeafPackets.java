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
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.common.CommonPacketTypes;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
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
 * Network handlers and connection listeners run on the connection's Netty event loop, see {@link PacketHandler}.
 * Opt-in {@link #listenHandling} handlers run on the server handling thread, after its thread check.
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

    private static final List<PacketFrameRegistration> FRAME_HANDLERS = new ArrayList<>();
    private static volatile PacketFrameRegistration[] frameHandlers = new PacketFrameRegistration[0];

    public static PacketFrameRegistration listenFrames(Plugin plugin, PacketPriority priority, boolean ignoreCancelled, PacketFrameHandler handler) {
        checkEnabled(plugin);
        Objects.requireNonNull(priority); Objects.requireNonNull(handler);
        synchronized (LOCK) {
            var registration = new PacketFrameRegistration(plugin, priority, ignoreCancelled, handler, nextOrder++);
            FRAME_HANDLERS.add(registration); rebuildFrames(); return registration;
        }
    }
    private static void rebuildFrames() {
        frameHandlers = FRAME_HANDLERS.stream().sorted(Comparator
            .<PacketFrameRegistration, PacketPriority>comparing(r -> r.priority).thenComparingLong(r -> r.order))
            .toArray(PacketFrameRegistration[]::new);
    }
    /** Called by the native decoder before codec execution; consumes no bytes and exposes no live buffer. */
    public static boolean allowFrame(io.netty.channel.ChannelHandlerContext ctx, ConnectionProtocol protocol, int capacity, int readableBytes) {
        final PacketFrameRegistration[] registrations = frameHandlers;
        if (registrations.length == 0) return true;
        final net.minecraft.network.Connection connection = ctx.pipeline().get(net.minecraft.network.Connection.class);
        if (connection == null) return true;
        final PacketFrameEvent event = new PacketFrameEvent(connection.leafUser, protocol, capacity, readableBytes);
        for (var registration : registrations) {
            if (registration.ignoreCancelled && event.isCancelled()) continue;
            event.readOnly = registration.priority == PacketPriority.MONITOR;
            try { registration.handler.handle(event); }
            catch (Throwable failure) { LOGGER.error("Could not pass native frame to {}", registration.plugin.getName(), failure); }
        }
        return !event.isCancelled();
    }

    private static final List<PacketDecodeRegistration> DECODE_HANDLERS = new ArrayList<>();
    private static volatile PacketDecodeRegistration[] decodeHandlers = new PacketDecodeRegistration[0];
    private static final ThreadLocal<DecodeContext> DECODE_CONTEXT = new ThreadLocal<>();
    public static final class DecodeContext {
        private final PacketUser user;
        private final ConnectionProtocol protocol;
        java.util.Map<net.minecraft.nbt.ListTag,java.util.List<net.minecraft.nbt.Tag>> rawLists=java.util.Collections.emptyMap();
        java.util.Map<net.minecraft.nbt.ListTag,Byte> rawListTypes=java.util.Collections.emptyMap();
        DecodeContext(PacketUser user,ConnectionProtocol protocol){this.user=user;this.protocol=protocol;}
        public PacketUser user(){return user;}
        public ConnectionProtocol protocol(){return protocol;}
    }
    /** Preserve raw list elements before vanilla unwraps compound entries with an empty key. */
    public static void decodedListElement(net.minecraft.nbt.ListTag list,net.minecraft.nbt.Tag element){
        var context=DECODE_CONTEXT.get();if(context!=null){
            if(context.rawLists.isEmpty())context.rawLists=new java.util.IdentityHashMap<>();
            context.rawLists.computeIfAbsent(list,ignored->new java.util.ArrayList<>()).add(element);
        }
    }
    /** Retain even an empty list's declared wire type; vanilla's logical empty ListTag has no element type. */
    public static void decodedListType(net.minecraft.nbt.ListTag list,byte type){
        var context=DECODE_CONTEXT.get();if(context!=null){
            if(context.rawListTypes.isEmpty())context.rawListTypes=new java.util.IdentityHashMap<>();
            context.rawListTypes.put(list,type);
        }
    }
    public static PacketDecodeRegistration listenDecode(Plugin plugin,PacketPriority priority,boolean ignoreCancelled,PacketDecodeHandler handler) {
        checkEnabled(plugin);Objects.requireNonNull(priority);Objects.requireNonNull(handler);
        synchronized(LOCK){var r=new PacketDecodeRegistration(plugin,priority,ignoreCancelled,handler,nextOrder++);DECODE_HANDLERS.add(r);rebuildDecode();return r;}
    }
    private static void rebuildDecode(){decodeHandlers=DECODE_HANDLERS.stream().sorted(Comparator
        .<PacketDecodeRegistration,PacketPriority>comparing(r->r.priority).thenComparingLong(r->r.order)).toArray(PacketDecodeRegistration[]::new);}
    public static @Nullable DecodeContext enterDecode(io.netty.channel.ChannelHandlerContext ctx,ConnectionProtocol protocol) {
        var previous=DECODE_CONTEXT.get();
        var connection=decodeHandlers.length==0?null:ctx.pipeline().get(net.minecraft.network.Connection.class);
        if(connection==null)DECODE_CONTEXT.remove();else DECODE_CONTEXT.set(new DecodeContext(connection.leafUser,protocol));
        return previous;
    }
    public static void exitDecode(@Nullable DecodeContext previous){if(previous==null)DECODE_CONTEXT.remove();else DECODE_CONTEXT.set(previous);}
    private static final class DecodeRejected extends RuntimeException { DecodeRejected(){super(null,null,false,false);} }
    public static boolean isDecodeRejected(Throwable failure){
        while(failure!=null){if(failure instanceof DecodeRejected)return true;failure=failure.getCause();}return false;
    }
    public static void inspectSlots(int count){inspectDecode(PacketDecodeEvent.Kind.SLOT_COUNT,count,null);}
    public static void inspectNbt(net.minecraft.nbt.Tag tag){inspectDecode(PacketDecodeEvent.Kind.NBT,0,tag);}
    private static void inspectDecode(PacketDecodeEvent.Kind kind,int count,net.minecraft.nbt.@Nullable Tag tag){
        var context=DECODE_CONTEXT.get();if(context==null)return;
        var event=new PacketDecodeEvent(context.user(),context.protocol(),kind,count,tag,context.rawLists,context.rawListTypes);
        for(var r:decodeHandlers){
            if(r.ignoreCancelled&&event.isCancelled())continue;event.readOnly=r.priority==PacketPriority.MONITOR;
            try{r.handler.handle(event);}catch(Throwable failure){LOGGER.error("Could not pass decoded structure to {}",r.plugin.getName(),failure);}
        }
        if(event.isCancelled())throw new DecodeRejected();
    }

    private static final Object LOCK = new Object();
    private static final List<PacketRegistration> HANDLERS = new ArrayList<>();
    private static final List<ConnectionRegistration> LISTENERS = new ArrayList<>();
    private static long nextOrder;

    private static volatile Handlers serverbound = Handlers.EMPTY;
    private static volatile Handlers clientbound = Handlers.EMPTY;
    private static volatile Handlers serverHandling = Handlers.EMPTY;
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
        return register(plugin, flow, type, priority, ignoreCancelled, handler, false);
    }

    /**
     * Runs immediately before native server handling, with authoritative player/menu state safe to inspect.
     * Play container_click, position-bearing movement, set_creative_mode_slot and discarded custom_payload have hooks.
     * This remains a separate phase from network listeners; configuration payloads are not intercepted here.
     */
    @SuppressWarnings("unchecked")
    public static <P extends Packet<?>> PacketRegistration listenHandling(
        final Plugin plugin, final PacketType<P> type, final PacketPriority priority,
        final boolean ignoreCancelled, final PacketHandler<P> handler
    ) {
        if (type != GamePacketTypes.SERVERBOUND_CONTAINER_CLICK
            && type != GamePacketTypes.SERVERBOUND_SET_CREATIVE_MODE_SLOT
            && type != GamePacketTypes.SERVERBOUND_MOVE_PLAYER_POS
            && type != GamePacketTypes.SERVERBOUND_MOVE_PLAYER_POS_ROT
            && type != GamePacketTypes.SERVERBOUND_USE_ITEM_ON
            && type != GamePacketTypes.SERVERBOUND_PICK_ITEM_FROM_BLOCK
            && type != GamePacketTypes.SERVERBOUND_PICK_ITEM_FROM_ENTITY
            && type != CommonPacketTypes.SERVERBOUND_CUSTOM_PAYLOAD) {
            throw new IllegalArgumentException("No server handling hook for " + type);
        }
        return register(plugin, PacketFlow.SERVERBOUND, type, priority, ignoreCancelled,
            (PacketHandler<Packet<?>>) (PacketHandler<?>) handler, true);
    }

    private static PacketRegistration register(
        final Plugin plugin, final PacketFlow flow, final @Nullable PacketType<?> type,
        final PacketPriority priority, final boolean ignoreCancelled, final PacketHandler<Packet<?>> handler,
        final boolean handling
    ) {
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(handler, "handler");
        checkEnabled(plugin);

        synchronized (LOCK) {
            final PacketRegistration registration = new PacketRegistration(plugin, flow, type, priority, ignoreCancelled, handler, nextOrder++, handling);
            HANDLERS.add(registration);
            if (handling) {
                rebuildHandling();
            } else {
                rebuild(flow);
            }
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
                    if (packet.handling) {
                        rebuildHandling();
                    } else {
                        rebuild(packet.flow);
                    }
                }
            } else if (DECODE_HANDLERS.remove(registration)) {
                rebuildDecode();
            } else if (FRAME_HANDLERS.remove(registration)) {
                rebuildFrames();
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
                rebuildHandling();
            }
            if (DECODE_HANDLERS.removeIf(registration -> registration.plugin == plugin)) rebuildDecode();
            if (FRAME_HANDLERS.removeIf(registration -> registration.plugin == plugin)) rebuildFrames();
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
        final Handlers handlers = buildHandlers(flow, false);
        if (flow == PacketFlow.SERVERBOUND) {
            serverbound = handlers;
        } else {
            clientbound = handlers;
        }
    }

    private static void rebuildHandling() {
        serverHandling = buildHandlers(PacketFlow.SERVERBOUND, true);
    }

    private static Handlers buildHandlers(final PacketFlow flow, final boolean handling) {
        final List<PacketRegistration> any = new ArrayList<>();
        final Reference2ObjectOpenHashMap<PacketType<?>, List<PacketRegistration>> typed = new Reference2ObjectOpenHashMap<>();
        for (final PacketRegistration registration : HANDLERS) {
            if (registration.flow != flow || registration.handling != handling) {
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

        return handlers;
    }

    // Dispatch, called on the event loop by PacketInterceptor

    /** Called only after the native server handler's thread check, before any container click side effects. */
    public static @Nullable ServerboundContainerClickPacket handleContainerClick(
        final ServerPlayer player, final ServerboundContainerClickPacket packet
    ) {
        final Handlers handlers = serverHandling;
        if (handlers == Handlers.EMPTY) {
            return packet;
        }
        final PacketUser user = user(player);
        return user == null ? packet : (ServerboundContainerClickPacket) call(handlers, user, packet, null, true);
    }

    /** Called on the native handling thread before use-on or pick-item side effects. */
    @SuppressWarnings("unchecked")
    public static <P extends Packet<?>> @Nullable P handleInteraction(final ServerPlayer player, final P packet) {
        final Handlers handlers = serverHandling;
        if (handlers == Handlers.EMPTY) return packet;
        final PacketUser user = user(player);
        return user == null ? packet : (P) call(handlers, user, packet, null, true);
    }

    /** Called after the native movement thread check, before validation or movement side effects. */
    public static @Nullable ServerboundMovePlayerPacket handlePlayerMove(
        final ServerPlayer player, final ServerboundMovePlayerPacket packet
    ) {
        final Handlers handlers = serverHandling;
        if (!packet.hasPosition() || handlers == Handlers.EMPTY) {
            return packet;
        }
        final PacketUser user = user(player);
        return user == null ? packet : (ServerboundMovePlayerPacket) call(handlers, user, packet, null, true);
    }

    /** Called only on the native play handler thread, before creative inventory side effects. */
    public static @Nullable ServerboundSetCreativeModeSlotPacket handleCreativeSlot(
        final ServerPlayer player, final ServerboundSetCreativeModeSlotPacket packet
    ) {
        final Handlers handlers = serverHandling;
        if (handlers == Handlers.EMPTY) {
            return packet;
        }
        final PacketUser user = user(player);
        return user == null ? packet : (ServerboundSetCreativeModeSlotPacket) call(handlers, user, packet, null, true);
    }

    /** Called for discarded play payloads after the native server-thread check, before plugin dispatch. */
    public static @Nullable ServerboundCustomPayloadPacket handleCustomPayload(
        final ServerPlayer player, final ServerboundCustomPayloadPacket packet
    ) {
        final Handlers handlers = serverHandling;
        if (handlers == Handlers.EMPTY) {
            return packet;
        }
        final PacketUser user = user(player);
        return user == null ? packet : (ServerboundCustomPayloadPacket) call(handlers, user, packet, null, true);
    }

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
        return call(handlers, user, packet, sentTasks, false);
    }

    private static @Nullable Packet<?> call(final Handlers handlers, final PacketUser user, final Packet<?> packet, final @Nullable List<Runnable> sentTasks, final boolean handling) {
        final PacketRegistration[] registrations = handlers.get(packet.type());
        if (registrations.length == 0) {
            return packet;
        }

        final PacketEvent<Packet<?>> event = new PacketEvent<>(user, packet, sentTasks, handling);
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
