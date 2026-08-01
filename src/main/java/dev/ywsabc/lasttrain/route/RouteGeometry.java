package dev.ywsabc.lasttrain.route;

import dev.ywsabc.lasttrain.campaign.CampaignSavedData;

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
        if (currentRouteSegment < 1) {
            return 1;
        }
        if (currentRouteSegment >= CampaignSavedData.MAX_ROUTE_SEGMENT) {
            return CampaignSavedData.MAX_ROUTE_SEGMENT;
        }
        return currentRouteSegment + 1;
    }

    public static int missionCenterOffset(int currentRouteSegment) {
        int segment = missionSegment(currentRouteSegment);
        return Math.addExact(segmentStartOffset(segment), SEGMENT_LENGTH / 2);
    }
}
