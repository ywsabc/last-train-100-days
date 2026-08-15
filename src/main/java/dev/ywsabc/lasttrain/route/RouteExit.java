package dev.ywsabc.lasttrain.route;

import java.util.Objects;

/**
 * One track exit of a planned segment, positioned relative to the segment entry.
 *
 * @param kind exit role
 * @param anchorOffset block offset relative to the segment entry (0 = entry boundary)
 */
public record RouteExit(RouteExitKind kind, int anchorOffset) {
    public RouteExit {
        Objects.requireNonNull(kind, "kind");
        if (kind == RouteExitKind.MAIN_LINE) {
            if (anchorOffset != RouteGeometry.SEGMENT_LENGTH) {
                throw new IllegalArgumentException(
                        "Main-line exit must sit at the segment end (offset "
                                + RouteGeometry.SEGMENT_LENGTH
                                + "), got "
                                + anchorOffset);
            }
        } else if (anchorOffset < 1 || anchorOffset >= RouteGeometry.SEGMENT_LENGTH) {
            throw new IllegalArgumentException(
                    "Branch exit offset must lie inside the segment, got " + anchorOffset);
        }
    }
}
