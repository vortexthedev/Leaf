package ru.minecomplex.mac.replay;


import java.util.function.Consumer;
import ru.minecomplex.mac.frame.FrameListener;
import ru.minecomplex.mac.engine.MovementCheckRunner;
import ru.minecomplex.mac.player.MacPlayer;
import ru.minecomplex.mac.world.BlockTags;

/**
 * Feeds a recording through a fresh {@link MacPlayer}, exactly as the transport did: each recorded packet becomes a
 * native 26.3 event over its bytes ({@link ReplayConnection}) and enters the session through the transport's entries, in
 * recorded order.
 */
public final class Replayer {
    private Replayer() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }


    public static MacPlayer replay(Recording recording, FrameListener listener) {
        return replay(recording, listener, null, null);
    }

    /**
     * {@link #replay(Recording, FrameListener)} with the block tags the session ran with ({@code null}: none) and the
     * movement probe's reports ({@code null}: not listened to), as the plugin sets them up.
     */
    public static MacPlayer replay(Recording recording, FrameListener listener, BlockTags tags,
                                       Consumer<MovementCheckRunner.TickReport> reports) {
        return replay(recording, listener, tags, reports, null);
    }

    public static MacPlayer replay(Recording recording, FrameListener listener, BlockTags tags,
                                       Consumer<MovementCheckRunner.TickReport> reports, BiomeTrace biomes) {
        var facts = recording.facts();
        MacPlayer session = new MacPlayer(listener, facts);
        if (tags != null && session.probe() != null) session.probe().tags(tags);
        if (reports != null) session.probeListener(reports);
        if (biomes != null) {
            session.probe().biomes(biomes);
            session.diagnosticListener((frame, report) -> biomes.nextFrame(frame.index() + 1));
        }
        ReplayConnection connection = new ReplayConnection(facts);
        for (Recording.Entry entry : recording.entries()) {
            switch (entry) {
                case Recording.Packet packet -> {
                    switch (packet.stage()) {
                        case SERVERBOUND -> session.receive(connection.receive(packet), packet.nanos());
                        case CLIENTBOUND -> {
                            session.send(connection.send(packet), packet.nanos());
                        }
                        case CONFIGURATION -> session.configuration(packet, connection.configurationTags(packet));
                        case REGISTRY -> {
                            connection.registry(packet);
                            session.configuration(packet, null);
                        }
                    }
                }
                case Recording.MarkerAllocated allocated -> {
                    int id = session.allocateMarker(allocated.nanos());
                    if (id != allocated.id()) {
                        throw new IllegalStateException("Replay diverged: allocated marker " + id + ", recording has " + allocated.id());
                    }
                }
            }
        }
        return session;
    }
}
