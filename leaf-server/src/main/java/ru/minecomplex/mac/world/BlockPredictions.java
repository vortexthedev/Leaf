package ru.minecomplex.mac.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * {@code ClientLevel.blockStatePredictionHandler} ({@code BlockStatePredictionHandler}): the client changes blocks itself
 * inside {@code MultiPlayerGameMode.startPrediction} (breaking, placing, using a block) and sends the action with the
 * prediction's sequence. Every block it changes while predicting keeps the state the server last confirmed there
 * ({@code retainKnownServerState}); a server block update for such a position only replaces that kept state
 * ({@code updateKnownServerState}), and {@code block_changed_ack} ends the predictions up to its sequence: a block whose
 * kept state differs from the current one is set back to it, and a player colliding with it is put back where it stood
 * when the prediction began ({@code ClientLevel.syncBlockState}; from 26.1 not after a teleport handled since).
 *
 * <p>Owned by one {@link CompensatedWorld}; a new level starts a new handler.
 */
public final class BlockPredictions {
    /** {@code ServerVerifiedState}. */
    public static final class Verified {
        public int sequence;
        public int state;
        public final Vec3d playerPos;

        Verified(int sequence, int state, Vec3d playerPos) {
            this.sequence = sequence;
            this.state = state;
            this.playerPos = playerPos;
        }

        public Verified copy() {
            return new Verified(sequence, state, playerPos);
        }
    }

    /** A block an acknowledgement set back to the server's state, and where the player stood when it was predicted. */
    public record Restored(BlockPosition pos, int state, Vec3d playerPos) {
    }

    /**
     * Kept server states by position. Vanilla iterates a {@code Long2ObjectOpenHashMap} when it ends predictions; the
     * order matters only when two restored blocks both move the player, which the caller reports instead.
     */
    public final Map<BlockPosition, Verified> states = new LinkedHashMap<>();
    public int currentSequence;
    public boolean predicting;
    /** The player position when the current prediction began ({@code player.position()}). */
    public Vec3d playerPos;
    /** {@code lastTeleportSequence} (26.1+): the sequence current when the last teleport was handled. */
    public int lastTeleportSequence = -1;
    /**
     * Predictions whose local effect the model could not resolve (an instant break it does not compute, an unported
     * neighbour callback): the client's blocks are uncertain until their acknowledgement.
     */
    public final TreeSet<Integer> uncertain = new TreeSet<>();
    /** Blocks the last acknowledgements set back, for the caller to test against the player. */
    public final List<Restored> restored = new ArrayList<>();

    public BlockPredictions() {
    }

    public BlockPredictions copy() {
        BlockPredictions copy = new BlockPredictions();
        states.forEach((pos, verified) -> copy.states.put(pos, verified.copy()));
        copy.currentSequence = currentSequence;
        copy.predicting = predicting;
        copy.playerPos = playerPos;
        copy.lastTeleportSequence = lastTeleportSequence;
        copy.uncertain.addAll(uncertain);
        copy.restored.addAll(restored);
        return copy;
    }

    /** {@code retainKnownServerState}: the first prediction at a position keeps the state before it. */
    public void retain(BlockPosition pos, int previous) {
        Verified verified = states.get(pos);
        if (verified != null) verified.sequence = currentSequence;
        else states.put(pos, new Verified(currentSequence, previous, playerPos));
    }

    /** {@code updateKnownServerState}: whether the position is predicted (its server state is only kept). */
    public boolean updateKnown(BlockPosition pos, int state) {
        Verified verified = states.get(pos);
        if (verified == null) return false;
        verified.state = state;
        return true;
    }
}
