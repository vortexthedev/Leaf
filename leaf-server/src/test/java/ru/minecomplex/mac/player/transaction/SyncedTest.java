package ru.minecomplex.mac.player.transaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class SyncedTest {

    @Test
    void candidatesArePrefixResults() {
        Synced<String> value = new Synced<>("initial");
        value.sent(3, "A");
        value.sent(7, "B");
        value.sent(12, "C");

        SyncWindow beforeAnyAck = new SyncWindow(-1, 10);
        assertEquals(List.of("initial", "A", "B"), value.candidates(beforeAnyAck));
        assertEquals("initial", value.settled(beforeAnyAck));
        assertFalse(value.isCertain(beforeAnyAck));

        SyncWindow afterAck = new SyncWindow(8, 13);
        assertEquals("B", value.settled(afterAck));
        assertEquals(List.of("B", "C"), value.candidates(afterAck));
        assertEquals("C", value.latestSent());
    }

    @Test
    void valuesOutsideTheWindowAreImpossible() {
        Synced<Integer> value = new Synced<>(0);
        value.sent(10, 1);
        SyncWindow window = new SyncWindow(4, 10);
        assertEquals(List.of(0), value.candidates(window));
        assertTrue(value.isCertain(window));
    }

    @Test
    void windowsMustNotGoBackwards() {
        Synced<Integer> value = new Synced<>(0);
        value.sent(2, 1);
        value.settled(new SyncWindow(5, 6));
        assertThrows(IllegalStateException.class, () -> value.candidates(new SyncWindow(1, 6)));
    }

    @Test
    void changeLogRangesAndTruncation() {
        ChangeLog<String> log = new ChangeLog<>();
        log.append(1, "a");
        log.append(4, "b");
        log.append(9, "c");
        assertEquals(List.of("b"), log.between(1, 9).stream().map(ChangeLog.Entry::change).toList());
        log.truncateThrough(4);
        assertEquals(1, log.size());
        assertEquals(9, log.first().seq());
        assertThrows(IllegalStateException.class, () -> log.append(9, "d"));
    }

    @Test
    void windowSpanAndValidation() {
        SyncWindow first = new SyncWindow(3, 8);
        SyncWindow later = new SyncWindow(6, 12);
        assertEquals(new SyncWindow(3, 12), first.span(later));
        assertThrows(IllegalArgumentException.class, () -> new SyncWindow(5, 5));
    }
}
