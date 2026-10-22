package ru.minecomplex.mac.player.transaction;

/**
 * Wire positions of every packet of one player, in both directions, and the epochs that partition them.
 * Sequence numbers are assigned in observation order on the native layer, which is wire order.
 *
 * <p>Epochs count <em>sent</em> transitions (observed on the wire). Whether the client has processed a transition
 * is a question for the synchronization window, not for this class.
 */
public final class PlayerTimeline {
    private long inboundSeq = -1;
    private long outboundSeq = -1;
    private int worldEpoch;
    private long worldEpochStartSeq = -1;
    private int teleportEpoch;
    private long frames;

    public long nextInbound() {
        return ++inboundSeq;
    }

    public long nextOutbound() {
        return ++outboundSeq;
    }

    /** Wire position of the last observed serverbound packet, {@code -1} if none. */
    public long lastInbound() {
        return inboundSeq;
    }

    /** Wire position of the last observed clientbound packet, {@code -1} if none. */
    public long lastOutbound() {
        return outboundSeq;
    }

    /** A {@code login} or {@code respawn} was observed at {@code seq}: the client will replace its level. */
    public int startWorldEpoch(long seq) {
        worldEpochStartSeq = seq;
        return ++worldEpoch;
    }

    /** A teleport of the player was observed. */
    public int startTeleportEpoch() {
        return ++teleportEpoch;
    }

    public long countFrame() {
        return frames++;
    }

    public int worldEpoch() {
        return worldEpoch;
    }

    public long worldEpochStartSeq() {
        return worldEpochStartSeq;
    }

    public int teleportEpoch() {
        return teleportEpoch;
    }

    public long frames() {
        return frames;
    }
}
