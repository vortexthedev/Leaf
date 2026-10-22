package ru.minecomplex.mac.replay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.engine.MovementCheckRunner;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class IncidentWindowTest {
    private static MovementCheckRunner.TickReport tick(long frame, double offset) {
        return new MovementCheckRunner.TickReport(frame, MovementCheckRunner.Status.COMPARED, offset, Vec3d.ZERO, Vec3d.ZERO,
                List.of("EntityMove"), Set.of(Unmodeled.RIDING), "unknown initial velocity", "pig");
    }

    @Test void keepsFortyPriorTicksAndTwentyFollowingTicksEvenWhenUncertain() {
        var captures = new ArrayList<IncidentWindow.Incident>();
        var window = new IncidentWindow(40, 20, 200, 8, 1e-5, captures::add);
        for (int i = 0; i <= 70; i++) window.tick(tick(i, i >= 50 ? .001 : 0), "state " + i);
        assertEquals(1, captures.size());
        var incident = captures.getFirst();
        assertEquals(50, incident.trigger());
        assertEquals(70, incident.lastFrame());
        assertEquals(61, incident.ticks().size());
        assertTrue(incident.ticks().getFirst().endsWith("state 10"));
        assertTrue(incident.ticks().getLast().endsWith("state 70"));
        assertTrue(incident.completeTail());
        assertTrue(incident.ticks().get(40).contains("RIDING"));
    }

    @Test void cooldownAndSessionLimitPreventContinuousMismatchSpam() {
        var captures = new ArrayList<IncidentWindow.Incident>();
        var window = new IncidentWindow(2, 2, 5, 2, 1e-5, captures::add);
        for (int i = 0; i < 100; i++) window.tick(tick(i, .1), "");
        assertEquals(List.of(0L, 8L), captures.stream().map(IncidentWindow.Incident::trigger).toList());
    }

    @Test void disconnectSavesIncompleteTailAndCloseIsIdempotent() {
        var captures = new ArrayList<IncidentWindow.Incident>();
        var window = new IncidentWindow(40, 20, 200, 8, 1e-5, captures::add);
        window.tick(tick(0, 0), "");
        window.tick(tick(1, .1), "");
        window.close(); window.close();
        assertEquals(1, captures.size());
        assertFalse(captures.getFirst().completeTail());
        assertEquals(2, captures.getFirst().ticks().size());
    }

    @Test void manualCaptureAndNonFinitePredictionsAreEligible() {
        var captures = new ArrayList<IncidentWindow.Incident>();
        var window = new IncidentWindow(2, 0, 0, 3, 1e-5, captures::add);
        window.tick(tick(0, Double.NaN), "");
        window.tick(tick(1, 0), "");
        window.capture("manual");
        window.close();
        assertEquals(2, captures.size());
        assertEquals("manual", captures.getLast().reason());
    }
}
