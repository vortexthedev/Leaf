package ru.minecomplex.mac.engine.movementtick;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import ru.minecomplex.mac.engine.Unmodeled;

/**
 * The serverbound packets a phase of a ride tick predicted, in client order, and what it could not simulate. Predicted
 * payloads, not observed client events.
 */
public record TickPackets(List<Object> packets, Set<Unmodeled> unmodeled) {
    /** A ride tick whose root vehicle is not resolved: nothing predicted, the ride uncertain. */
    public static final TickPackets UNRESOLVED = new TickPackets(List.of(), Set.of(Unmodeled.RIDING));

    /** The same packets with more gaps. */
    public TickPackets withGaps(Set<Unmodeled> more) {
        if (more.isEmpty()) return this;
        var gaps = EnumSet.noneOf(Unmodeled.class);
        gaps.addAll(unmodeled);
        gaps.addAll(more);
        return new TickPackets(packets, Set.copyOf(gaps));
    }

    /** The packets of this phase followed by those of a later one, with the gaps of both. */
    public TickPackets then(TickPackets later) {
        var all = new ArrayList<Object>(packets.size() + later.packets.size());
        all.addAll(packets);
        all.addAll(later.packets);
        return new TickPackets(List.copyOf(all), unmodeled).withGaps(later.unmodeled);
    }
}
