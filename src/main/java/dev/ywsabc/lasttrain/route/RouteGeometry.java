package dev.ywsabc.lasttrain.route;

/**
 * Deterministic geometry for the campaign's guaranteed eastbound corridor.
 *
 * <p>TongDa Railway and Lost Cities still provide the surrounding procedural
 * railway/city content. This corridor is the continuity safety net: it gives
 * the physical starter train a route even when two third-party generation
 * regions do not connect.</p>
 */
public final class RouteGeometry {
    public static final int FIRST_TRACK_OFFSET = 25;
    public static final int SEGMENT_LENGTH = 64;

    private RouteGeometry() {
    }

    public static int segmentStartOffset(int segment) {
        if (segment < 1) {
            throw new IllegalArgumentException("Route segments start at 1");
        }
        return Math.addExact(
                FIRST_TRACK_OFFSET,
                Math.multiplyExact(segment - 1, SEGMENT_LENGTH));
    }

    public static int segmentEndOffset(int segment) {
        return Math.addExact(segmentStartOffset(segment), SEGMENT_LENGTH - 1);
    }

    public static int segmentForOffset(int eastboundOffset) {
        if (eastboundOffset < FIRST_TRACK_OFFSET) {
            return 0;
        }
        return Math.floorDiv(eastboundOffset - FIRST_TRACK_OFFSET, SEGMENT_LENGTH) + 1;
    }

    public static int missionSegment(int currentRouteSegment) {
        return Math.max(1, Math.addExact(currentRouteSegment, 1));
    }

    public static int missionCenterOffset(int currentRouteSegment) {
        int segment = missionSegment(currentRouteSegment);
        return Math.addExact(segmentStartOffset(segment), SEGMENT_LENGTH / 2);
    }
}
