package ru.minecomplex.mac.bukkit.net;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import io.netty.channel.Channel;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;
import ru.minecomplex.mac.bukkit.debug.ServerBlockTags;
import ru.minecomplex.mac.replay.Recording;
import ru.minecomplex.mac.replay.ReplayFormat;
import ru.minecomplex.mac.replay.TagsFile;
import ru.minecomplex.mac.player.MacPlayer;
import ru.minecomplex.mac.packet.NativeRuntime;
import org.dreeam.leaf.network.PacketUser;

/** Sessions of connections in the play state, keyed by channel. Thread-safe registry; sessions themselves are not. */
public final class Sessions {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final Map<Channel, SessionHandle> byChannel = new ConcurrentHashMap<>();
    /**
     * The configuration phase of a connection not yet in play: its registry packets and its last {@code update_tags}, in
     * the order they were sent ({@link MacPlayer#configuration}).
     */
    private final Map<Channel, java.util.List<Configured>> configuration = new ConcurrentHashMap<>();

    /** A configuration-phase packet ({@code tags}: the decoded {@code update_tags}, else {@code null}). */
    private record Configured(Recording.Packet packet,
                              net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket tags) {
    }
    private final Logger logger;
    /** Server name and version, for recording descriptions. */
    private final String server;
    private final ServerBlockTags tags;
    private final ServerBiomes biomes;
    private final Path recordings;
    private final AutomaticDiagnostics automatic;
    private volatile boolean closing;
    /** Lower-case names whose next session is recorded from its start. */
    private final Set<String> armed = ConcurrentHashMap.newKeySet();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "MAC recordings");
        thread.setDaemon(true);
        return thread;
    });

    /** @param recordings directory of session recordings ({@code /mac record}) */
    public Sessions(Logger logger, String server, ServerBlockTags tags, ServerBiomes biomes, Path recordings,
                    AutomaticDiagnostics automatic) {
        this.logger = logger;
        this.server = server;
        this.tags = tags;
        this.biomes = biomes;
        this.recordings = recordings;
        this.automatic = automatic;
    }

    /** On the channel's event loop, when the connection enters play. {@code null} for unsupported protocols. */
    SessionHandle start(PacketUser user) {
        if (closing) return null;
        Channel channel = (Channel) user.getChannel();
        int protocolId = user.getProtocolVersion();
        if (protocolId != NativeRuntime.PROTOCOL) {
            logger.warning("Protocol " + protocolId + " of " + user.getName() + " is not supported; the player is not checked");
            return null;
        }
        SessionDiagnostics diagnostics = new SessionDiagnostics();
        MacPlayer session = new MacPlayer(diagnostics,
                new ru.minecomplex.mac.player.connection.PlayerConnectionContext(user.getName(), user.getUniqueId()));
        session.probe().tags(tags.tags());
        session.probe().biomes(biomes);
        SessionHandle handle = new SessionHandle(user, channel, session, diagnostics);
        handle.automatic(automatic, biomes);
        if (armed.remove(user.getName().toLowerCase(Locale.ROOT))) {
            handle.startRecording();
            logger.info("Recording the session of " + user.getName());
        }
        byChannel.put(channel, handle);
        channel.closeFuture().addListener(future -> {
            byChannel.remove(channel, handle);
            handle.closeAutomatic();
            save(handle, null);
        });
        return handle;
    }

    /**
     * On the channel's event loop: a configuration-phase packet the session will need ({@code tags}: an
     * {@code update_tags}; {@code null}: a registry packet). Keep their order: different tag packets may name different
     * registries, so a later item/fluid update cannot replace the earlier block update.
     */
    void configuration(PacketUser user, Recording.Packet packet,
                       net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket tags) {
        Channel channel = (Channel) user.getChannel();
        var packets = configuration.computeIfAbsent(channel, ignored -> {
            channel.closeFuture().addListener(future -> configuration.remove(channel));
            return java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        });
        packets.add(new Configured(packet, tags));
    }

    /** A new configuration phase begins (the client left play): the earlier one's packets no longer apply. */
    void clearConfiguration(PacketUser user) {
        configuration.remove((Channel) user.getChannel());
    }

    /** Hands the configuration phase to the session that starts the connection's play phase. */
    void takeConfiguration(PacketUser user, MacPlayer session) {
        var packets = configuration.remove((Channel) user.getChannel());
        if (packets == null || packets.stream().noneMatch(configured -> configured.tags() != null
                && ru.minecomplex.mac.world.ResolvedBlockTags.hasBlockRegistry(configured.tags()))) {
            logger.warning("No block update_tags of " + user.getName() + " was seen before play: the server's own tags stand in");
        }
        if (packets == null) return;
        synchronized (packets) {
            for (Configured configured : packets) session.configuration(configured.packet(), configured.tags());
        }
    }

    SessionHandle get(PacketUser user) {
        return byChannel.get((Channel) user.getChannel());
    }

    void stop(PacketUser user) {
        SessionHandle handle = byChannel.remove((Channel) user.getChannel());
        if (handle != null) {
            handle.closeAutomatic();
            save(handle, null);
        }
    }

    /** Records the next session of the player from its start (a recording replays only from a session start). */
    public void arm(String name) {
        armed.add(name.toLowerCase(Locale.ROOT));
    }

    /** Whether the next session of the player will be recorded. */
    public boolean armed(String name) {
        return armed.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Ends the recording of a session, if it is recorded, and writes it to {@code <name>-<start>.macr.gz} with the block
     * tags it ran with in {@code <name>-<start>.tags} ({@link ru.minecomplex.mac.replay.Replayer}). {@code done} gets the
     * path written, or the failure, on the writer thread.
     */
    public void save(SessionHandle handle, Consumer<String> done) {
        Runnable capture = () -> {
            String name = handle.user().getName();
            long started = handle.recordingStartMillis();
            Recording recording = handle.takeRecording(String.format(Locale.ROOT,
                    "player=%s uuid=%s server=%s started=%s",
                    name, handle.user().getUniqueId(), server, Instant.ofEpochMilli(started)));
            if (recording == null) {
                return;
            }
            writer.execute(() -> {
                String base = name + "-" + FILE_TIME.format(Instant.ofEpochMilli(started));
                Path file = recordings.resolve(base + ".macr.gz");
                try {
                    Files.createDirectories(recordings);
                    try (OutputStream out = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
                        ReplayFormat.write(recording, out);
                    }
                    try (Writer out = Files.newBufferedWriter(recordings.resolve(base + ".tags"), StandardCharsets.UTF_8)) {
                        TagsFile.write(tags.keys(), out);
                    }
                    logger.info("Session recording of " + name + ": " + file + " (" + recording.entries().size() + " inputs)");
                    if (done != null) done.accept(file.toString());
                } catch (IOException | RuntimeException e) {
                    logger.log(Level.WARNING, "Cannot write the session recording of " + name, e);
                    if (done != null) done.accept("ошибка записи: " + e);
                }
            });
        };
        if (handle.channel().eventLoop().inEventLoop()) capture.run();
        else handle.channel().eventLoop().execute(capture);
    }

    public SessionHandle byUuid(UUID uuid) {
        for (SessionHandle handle : byChannel.values()) {
            if (uuid.equals(handle.user().getUniqueId())) {
                return handle;
            }
        }
        return null;
    }

    public Collection<SessionHandle> all() {
        return byChannel.values();
    }

    /** Queue all connection-confined final captures before shutting down the diagnostic writer. */
    public void close() {
        closing = true;
        var channels = new java.util.HashSet<Channel>(byChannel.keySet());
        for (var user : org.dreeam.leaf.network.LeafPackets.users()) channels.add(user.getChannel());
        var closes = channels.stream().map(channel -> {
            var done = new java.util.concurrent.CompletableFuture<Void>();
            channel.eventLoop().execute(() -> {
                try {
                    var handle = byChannel.remove(channel);
                    if (handle != null) {
                        handle.closeAutomatic();
                        save(handle, null);
                    }
                    done.complete(null);
                }
                catch (RuntimeException e) { done.completeExceptionally(e); }
            });
            return done;
        }).toArray(java.util.concurrent.CompletableFuture[]::new);
        var completed = java.util.concurrent.CompletableFuture.allOf(closes);
        completed.whenComplete((ignored, failure) -> {
            configuration.clear();
            automatic.close();
            writer.shutdown();
        });
        try { completed.get(5, java.util.concurrent.TimeUnit.SECONDS); }
        catch (Exception e) { logger.log(Level.WARNING, "Cannot finalize all MAC diagnostic sessions", e); }
        automatic.awaitWrites();
        try {
            if (!writer.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                logger.warning("MAC recording writer is still finishing");
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
