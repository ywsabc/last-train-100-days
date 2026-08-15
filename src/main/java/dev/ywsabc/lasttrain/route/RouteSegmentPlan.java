package dev.ywsabc.lasttrain.route;

import java.util.List;
import java.util.Objects;

/**
 * The committed plan of one route segment: template, interest points and
 * exits, all derived from the segment seed.
 *
 * <p>Invariant: every plan carries exactly one {@link RouteExitKind#MAIN_LINE}
 * exit, so a side branch can never replace the way forward. Construction
 * rejects plans that break the invariant.</p>
 */
public record RouteSegmentPlan(
        int segmentIndex,
        long segmentSeed,
        SegmentTemplate template,
        List<RoutePoi> pois,
        List<RouteExit> exits) {

    public RouteSegmentPlan {
        if (segmentIndex < 1) {
            throw new IllegalArgumentException("Route segments start at 1");
        }
        Objects.requireNonNull(template, "template");
        pois = List.copyOf(Objects.requireNonNull(pois, "pois"));
        exits = List.copyOf(Objects.requireNonNull(exits, "exits"));
        long mainExitCount = exits.stream()
                .filter(exit -> exit.kind() == RouteExitKind.MAIN_LINE)
                .count();
        if (mainExitCount != 1) {
            throw new IllegalArgumentException(
                    "Every route segment must have exactly one main-line exit, got "
                            + mainExitCount);
        }
    }

    /** The single main-line exit; plans without one are rejected on construction. */
    public RouteExit mainExit() {
        for (RouteExit exit : exits) {
            if (exit.kind() == RouteExitKind.MAIN_LINE) {
                return exit;
            }
        }
        throw new IllegalStateException("Segment " + segmentIndex + " lost its main-line exit");
    }
}
