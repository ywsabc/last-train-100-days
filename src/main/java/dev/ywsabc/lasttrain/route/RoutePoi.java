package dev.ywsabc.lasttrain.route;

import java.util.Objects;

/**
 * One deterministic interest point slot on a planned segment.
 *
 * @param type interest point kind
 * @param anchorOffset block offset relative to the segment entry (0 = entry boundary)
 */
public record RoutePoi(RoutePoiType type, int anchorOffset) {
    public RoutePoi {
        Objects.requireNonNull(type, "type");
        if (anchorOffset < 1 || anchorOffset >= RouteGeometry.SEGMENT_LENGTH) {
            throw new IllegalArgumentException(
                    "POI anchor offset must lie inside the segment, got " + anchorOffset);
        }
    }
}
