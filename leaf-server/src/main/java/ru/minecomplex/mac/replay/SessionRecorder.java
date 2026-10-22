package ru.minecomplex.mac.replay;


/** Observes every input of a session, before processing, on the session's thread. */
public interface SessionRecorder {

    /** A packet as native 26.3 delivered it ({@link Recording.Packet}). */
    void packet(Recording.Packet packet);

    void markerAllocated(long nanos, int id);
}
