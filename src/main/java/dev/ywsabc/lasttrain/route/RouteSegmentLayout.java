package dev.ywsabc.lasttrain.route;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Pure template → geometry, track shape and POI-anchor computation for one
 * planned route segment, free of any world access.
 *
 * <p>The route director realizes a segment from this layout instead of hard
 * coded constants. The committed plan is the single source of the physical
 * shape: the template picks the deck support density and the interest points,
 * and the layout derives the main-line track steps (position plus direction
 * vector), the turn points where another track diverges, and the branch track
 * sections submitted to the track backend after the main line. The main line
 * itself stays the eastbound 64-block corridor for every committed template;
 * the layout only structures what surrounds it:</p>
 * <ul>
 * <li>{@link SegmentTemplate#STRAIGHT}: eastbound straight line, no turns, no
 * branches;</li>
 * <li>{@link SegmentTemplate#STATION}: main line straight through plus a
 * station platform and a parallel platform siding with its two junction turn
 * points;</li>
 * <li>{@link SegmentTemplate#BRIDGE_TUNNEL}: main line straight through with
 * densified support columns;</li>
 * <li>{@link SegmentTemplate#CITY_BYPASS}: main line straight through plus a
 * real short branch track run diverging north or south at the planned branch
 * exit toward the city interest point — a track run, never a stone
 * platform.</li>
 * </ul>
 */
public record RouteSegmentLayout(
        RouteSegmentPlan plan,
        BlockPos borderProbe,
        BlockPos trackStart,
        List<BlockPos> deckSupportTops,
        BlockPos platformAnchor,
        int platformHalfLength,
        List<RouteTrackStep> mainlineSteps,
        List<BlockPos> turnPoints,
        List<BranchTrackSection> branchTrackSections) {

    /** Deck support column spacing of straight/city/station segments. */
    public static final int STRAIGHT_SUPPORT_SPACING = 8;
    /** Denser support columns of bridge/tunnel segments. */
    public static final int BRIDGE_TUNNEL_SUPPORT_SPACING = 4;
    /** Half length of the legacy every-fourth-segment waypoint platform. */
    public static final int LEGACY_WAYPOINT_PLATFORM_HALF_LENGTH = 9;
    /** Half length of a station platform centered on its station POI anchor. */
    public static final int STATION_PLATFORM_HALF_LENGTH = 4;
    /** Lateral (positive-Z) offset of the station siding beside the main line. */
    public static final int STATION_SIDING_LATERAL_OFFSET = 2;
    /** Track blocks of the short city spur branch run. */
    public static final int CITY_SPUR_LENGTH = 5;

    public RouteSegmentLayout {
        plan = Objects.requireNonNull(plan, "plan");
        borderProbe = Objects.requireNonNull(borderProbe, "borderProbe");
        trackStart = Objects.requireNonNull(trackStart, "trackStart");
        deckSupportTops = List.copyOf(Objects.requireNonNull(deckSupportTops, "deckSupportTops"));
        if ((platformAnchor == null) != (platformHalfLength <= 0)) {
            throw new IllegalArgumentException(
                    "A platform anchor must carry a positive half length, and vice versa");
        }
        mainlineSteps = List.copyOf(Objects.requireNonNull(mainlineSteps, "mainlineSteps"));
        if (mainlineSteps.isEmpty()) {
            throw new IllegalArgumentException("A segment layout must carry main-line steps");
        }
        turnPoints = List.copyOf(Objects.requireNonNull(turnPoints, "turnPoints"));
        branchTrackSections =
                List.copyOf(Objects.requireNonNull(branchTrackSections, "branchTrackSections"));
    }

    /** Whether this segment builds a platform (station anchor or legacy waypoint). */
    public boolean hasPlatform() {
        return platformAnchor != null;
    }

    /**
     * The direction vector of the main line: the direction of the first
     * main-line step, continued by every subsequent step.
     */
    public Direction mainlineDirection() {
        return mainlineSteps.getFirst().direction();
    }

    /**
     * One directed step of the physical main line: the track block position
     * and the direction the track continues in at that position.
     */
    public record RouteTrackStep(BlockPos position, Direction direction) {
        public RouteTrackStep {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(direction, "direction");
            if (direction.getAxis() == Direction.Axis.Y) {
                throw new IllegalArgumentException(
                        "Track steps must be horizontal, got " + direction);
            }
        }
    }

    /** Role of a branch track run beside the main line. */
    public enum BranchTrackKind {
        /** Parallel side track beside a station platform. */
        STATION_SIDING,
        /** Short north/south spur toward a city interest point. */
        CITY_SPUR
    }

    /**
     * One real side-track run submitted to the track backend after the main
     * line: a start block, a horizontal direction, a run length and its role.
     */
    public record BranchTrackSection(
            BlockPos start,
            Direction direction,
            int length,
            BranchTrackKind kind) {
        public BranchTrackSection {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(kind, "kind");
            if (direction.getAxis() == Direction.Axis.Y) {
                throw new IllegalArgumentException(
                        "Branch track runs must be horizontal, got " + direction);
            }
            if (length < 1) {
                throw new IllegalArgumentException(
                        "Branch track runs must be at least one block long, got " + length);
            }
        }
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

        // The main line is derived step by step from the committed plan: the
        // director lays the deck along these positions instead of a fixed
        // eastbound loop. Every committed template keeps the main line
        // eastbound; the step list is what a future turning template would
        // change.
        List<RouteTrackStep> steps = new ArrayList<>();
        for (int offset = startOffset; offset <= endOffset; offset++) {
            steps.add(new RouteTrackStep(stationAnchor.offset(offset, 1, 0), Direction.EAST));
        }

        BlockPos platform = null;
        int halfLength = 0;
        switch (plan.template()) {
            case STATION -> {
                RoutePoi station = stationPoi(plan);
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

        List<BlockPos> turnPoints = new ArrayList<>();
        List<BranchTrackSection> branches = new ArrayList<>();
        switch (plan.template()) {
            case STATION -> {
                // The platform siding runs parallel to the main line and
                // joins it at its two ends: those junctions are the turn
                // points on the main line.
                RoutePoi station = stationPoi(plan);
                int center = startOffset + station.anchorOffset();
                turnPoints.add(stationAnchor.offset(center - halfLength, 1, 0));
                turnPoints.add(stationAnchor.offset(center + halfLength, 1, 0));
                branches.add(new BranchTrackSection(
                        stationAnchor.offset(
                                center - halfLength, 1, STATION_SIDING_LATERAL_OFFSET),
                        Direction.EAST,
                        2 * halfLength + 1,
                        BranchTrackKind.STATION_SIDING));
            }
            case CITY_BYPASS -> {
                // The city spur diverges from the main line at the planned
                // branch exit and heads north or south — deterministically
                // from the segment seed — toward the city interest point.
                for (RouteExit exit : plan.exits()) {
                    if (exit.kind() == RouteExitKind.BRANCH) {
                        BlockPos junction =
                                stationAnchor.offset(startOffset + exit.anchorOffset(), 1, 0);
                        turnPoints.add(junction);
                        branches.add(new BranchTrackSection(
                                junction,
                                (plan.segmentSeed() & 1L) == 0
                                        ? Direction.NORTH
                                        : Direction.SOUTH,
                                CITY_SPUR_LENGTH,
                                BranchTrackKind.CITY_SPUR));
                    }
                }
            }
            case STRAIGHT, BRIDGE_TUNNEL -> {
                // Straight corridors carry no side tracks.
            }
        }
        return new RouteSegmentLayout(
                plan,
                borderProbe,
                trackStart,
                supports,
                platform,
                halfLength,
                steps,
                turnPoints,
                branches);
    }

    /**
     * The single station interest point of a station plan. Construction
     * guarantees it exists; the filtered lookup keeps the layout safe even if
     * a structurally broken plan is ever handed in.
     */
    private static RoutePoi stationPoi(RouteSegmentPlan plan) {
        for (RoutePoi poi : plan.pois()) {
            if (poi.type() == RoutePoiType.STATION) {
                return poi;
            }
        }
        throw new IllegalArgumentException(
                "Segment " + plan.segmentIndex() + " has no station interest point");
    }
}
