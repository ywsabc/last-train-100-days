package dev.ywsabc.lasttrain.route;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;

/**
 * Pure template → geometry and POI-anchor computation for one planned route
 * segment, free of any world access.
 *
 * <p>The route director realizes a segment from this layout instead of hard
 * coded constants: the template picks the deck support density, the station
 * POI anchor picks the platform position, and a city bypass plan carries a
 * branch spur anchor at its branch exit. The physical main line itself stays
 * the eastbound 64-block corridor for every template; the layout only
 * positions the structures around it.</p>
 */
public record RouteSegmentLayout(
        RouteSegmentPlan plan,
        BlockPos borderProbe,
        BlockPos trackStart,
        List<BlockPos> deckSupportTops,
        BlockPos platformAnchor,
        int platformHalfLength,
        BlockPos branchSpurAnchor) {

    /** Deck support column spacing of straight/city/station segments. */
    public static final int STRAIGHT_SUPPORT_SPACING = 8;
    /** Denser support columns of bridge/tunnel segments. */
    public static final int BRIDGE_TUNNEL_SUPPORT_SPACING = 4;
    /** Half length of the legacy every-fourth-segment waypoint platform. */
    public static final int LEGACY_WAYPOINT_PLATFORM_HALF_LENGTH = 9;
    /** Half length of a station platform centered on its station POI anchor. */
    public static final int STATION_PLATFORM_HALF_LENGTH = 4;

    public RouteSegmentLayout {
        plan = Objects.requireNonNull(plan, "plan");
        borderProbe = Objects.requireNonNull(borderProbe, "borderProbe");
        trackStart = Objects.requireNonNull(trackStart, "trackStart");
        deckSupportTops = List.copyOf(Objects.requireNonNull(deckSupportTops, "deckSupportTops"));
        if ((platformAnchor == null) != (platformHalfLength <= 0)) {
            throw new IllegalArgumentException(
                    "A platform anchor must carry a positive half length, and vice versa");
        }
    }

    /** Whether this segment builds a platform (station anchor or legacy waypoint). */
    public boolean hasPlatform() {
        return platformAnchor != null;
    }

    /** Whether this segment builds a city branch spur. */
    public boolean hasBranchSpur() {
        return branchSpurAnchor != null;
    }

    /**
     * Computes the realization layout of one planned segment relative to the
     * starter station anchor.
     *
     * @param stationAnchor the starter station anchor block
     * @param segment route segment index, starting at 1; must equal
     *     {@code plan.segmentIndex()}
     * @param plan the committed plan of the segment
     */
    public static RouteSegmentLayout compute(
            BlockPos stationAnchor,
            int segment,
            RouteSegmentPlan plan) {
        Objects.requireNonNull(stationAnchor, "stationAnchor");
        Objects.requireNonNull(plan, "plan");
        if (plan.segmentIndex() != segment) {
            throw new IllegalArgumentException(
                    "Segment " + segment + " cannot be realized from the plan of segment "
                            + plan.segmentIndex());
        }
        int startOffset = RouteGeometry.segmentStartOffset(segment);
        int endOffset = RouteGeometry.segmentEndOffset(segment);
        BlockPos borderProbe = stationAnchor.offset(endOffset, 1, 0);
        BlockPos trackStart = stationAnchor.offset(startOffset, 1, 0);

        int spacing = plan.template() == SegmentTemplate.BRIDGE_TUNNEL
                ? BRIDGE_TUNNEL_SUPPORT_SPACING
                : STRAIGHT_SUPPORT_SPACING;
        List<BlockPos> supports = new ArrayList<>();
        for (int offset = startOffset; offset <= endOffset; offset += spacing) {
            supports.add(stationAnchor.offset(offset, -1, -1));
            supports.add(stationAnchor.offset(offset, -1, 1));
        }

        BlockPos platform = null;
        int halfLength = 0;
        switch (plan.template()) {
            case STATION -> {
                RoutePoi station = plan.pois().get(0);
                platform = stationAnchor.offset(startOffset + station.anchorOffset(), 0, 0);
                halfLength = STATION_PLATFORM_HALF_LENGTH;
            }
            case STRAIGHT, CITY_BYPASS -> {
                if (segment % 4 == 0) {
                    platform = stationAnchor.offset(
                            endOffset - LEGACY_WAYPOINT_PLATFORM_HALF_LENGTH,
                            0,
                            0);
                    halfLength = LEGACY_WAYPOINT_PLATFORM_HALF_LENGTH;
                }
            }
            case BRIDGE_TUNNEL -> {
                // Bridge decks are dense-support corridors without a platform.
            }
        }

        BlockPos branch = null;
        if (plan.template() == SegmentTemplate.CITY_BYPASS) {
            for (RouteExit exit : plan.exits()) {
                if (exit.kind() == RouteExitKind.BRANCH) {
                    branch = stationAnchor.offset(startOffset + exit.anchorOffset(), 0, 0);
                }
            }
        }
        return new RouteSegmentLayout(plan, borderProbe, trackStart, supports, platform, halfLength, branch);
    }
}
