package ru.minecomplex.mac.player.transaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class SyncTimelineTest {

    @Test
    void windowBeforeAnyMarkerCoversEverythingObserved() {
        SyncTimeline sync = new SyncTimeline();
        assertEquals(SyncWindow.NOTHING_SENT, sync.window(-1));
        assertEquals(new SyncWindow(-1, 11), sync.window(10));
    }

    @Test
    void outstandingMarkerBoundsTheWindow() {
        SyncTimeline sync = new SyncTimeline();
        sync.markerObserved(7, 5, 100);
        SyncWindow window = sync.window(10);
        assertEquals(new SyncWindow(-1, 5), window);
        assertTrue(window.isUncertain(4));
        assertFalse(window.isPossible(5));
        assertFalse(window.isPossible(9));
    }

    @Test
    void pongAcknowledgesAndOpensNextWindow() {
        SyncTimeline sync = new SyncTimeline();
        sync.markerObserved(7, 5, 100);
        sync.markerObserved(8, 9, 200);
        PongResult result = sync.pong(7, 1_100);
        assertTrue(result.ours());
        assertEquals(0, result.skipped());
        assertEquals(5, result.marker().outboundSeq());
        assertEquals(1_000, sync.lastRoundTripNanos());
        assertEquals(new SyncWindow(5, 9), sync.window(12));
        sync.pong(8, 2_000);
        assertEquals(new SyncWindow(9, 13), sync.window(12));
        assertEquals(2, sync.acknowledgedCount());
    }

    @Test
    void laterPongSkipsEarlierMarkers() {
        SyncTimeline sync = new SyncTimeline();
        sync.markerObserved(1, 3, 0);
        sync.markerObserved(2, 4, 0);
        sync.markerObserved(3, 8, 0);
        PongResult result = sync.pong(3, 10);
        assertEquals(2, result.skipped());
        assertEquals(8, sync.certainThrough());
        assertEquals(0, sync.outstandingCount());
        assertEquals(2, sync.skippedCount());
    }

    @Test
    void foreignAndDuplicatePongsDoNotMoveTheWindow() {
        SyncTimeline sync = new SyncTimeline();
        sync.markerObserved(1, 3, 0);
        assertSame(PongResult.Kind.FOREIGN, sync.pong(99, 5).kind());
        sync.pong(1, 5);
        assertSame(PongResult.Kind.FOREIGN, sync.pong(1, 6).kind());
        assertEquals(3, sync.certainThrough());
    }

    @Test
    void markersMustBeObservedInWireOrder() {
        SyncTimeline sync = new SyncTimeline();
        sync.markerObserved(1, 5, 0);
        assertThrows(IllegalStateException.class, () -> sync.markerObserved(2, 5, 0));
    }

    @Test
    void isOutstanding() {
        SyncTimeline sync = new SyncTimeline();
        sync.markerObserved(1, 5, 0);
        assertTrue(sync.isOutstanding(1));
        sync.pong(1, 1);
        assertFalse(sync.isOutstanding(1));
    }

    @Test
    void markerIdsCarryPrefix() {
        MarkerIds ids = new MarkerIds();
        int first = ids.next();
        int second = ids.next();
        assertTrue(MarkerIds.hasPrefix(first));
        assertEquals(first + 1, second);
        assertFalse(MarkerIds.hasPrefix(-5));
    }
}
