package dev.ywsabc.lasttrain.route;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Deterministic planner that assigns a template, interest points and exits to
 * each route segment ahead of the party.
 *
 * <p>Every segment derives its own seed so that one extra random draw can
 * never reshuffle later segments:
 * {@code segmentSeed = hash(campaignSeed, routeIndex, routeRulesVersion, configFingerprint, segmentIndex)},
 * and every interest point derives
 * {@code poiSeed = hash(segmentSeed, slotIndex)}. Selection obeys the
 * configured appearance rules (station minimum gap, no consecutive city
 * bypasses, bridge/tunnel budget window) and falls back to a straight segment
 * when nothing else is allowed. Segment 1 is always straight because the
 * starter station already owns the corridor head.</p>
 *
 * <p>All randomness comes from {@link java.util.Random}, whose sequence is
 * guaranteed stable across JVM versions, so plans only change when one of the
 * seed inputs changes.</p>
 *
 * <p>Plans are memoized per instance and computed in increasing segment
 * order; querying a lower segment after a higher one returns the memoized
 * plan. The planner is not thread-safe and belongs on the logical server
 * thread.</p>
 */
public final class RouteSegmentPlanner {
    public static final int DEFAULT_ROUTE_INDEX = 0;
    /**
     * Bump when the selection logic itself changes, so committed routes do not
     * silently re-roll; config value changes are covered by the config fingerprint.
     */
    public static final int DEFAULT_ROUTE_RULES_VERSION = 1;
    private static final long POI_DOMAIN_SALT = 0x504F495F534C4F54L; // "POI_SLOT"
    private static final long INDEX_CONSTANT_ROUTE = 0x9E3779B97F4A7C15L;
    private static final long INDEX_CONSTANT_RULES = 0xBF58476D1CE4E5B9L;
    private static final long INDEX_CONSTANT_CONFIG = 0xE7037ED1A0B428DBL;
    private static final long INDEX_CONSTANT_SEGMENT = 0x94D049BB133111EBL;
    private static final long INDEX_CONSTANT_SLOT = 0xD1B54A32D192ED03L;

    private final long campaignSeed;
    private final int routeIndex;
    private final int routeRulesVersion;
    private final RouteTemplateConfig config;
    private final long configFingerprint;
    private final Map<Integer, RouteSegmentPlan> plans = new HashMap<>();
    private final ArrayDeque<Integer> recentBridges = new ArrayDeque<>();
    private int lastPlannedSegment;
    /** The starter station occupies segment 1, so the station gap counts from there. */
    private int lastStationSegment = 1;
    private int lastCityBypassSegment;

    public RouteSegmentPlanner(long campaignSeed) {
        this(campaignSeed, DEFAULT_ROUTE_INDEX, DEFAULT_ROUTE_RULES_VERSION, RouteTemplateConfig.DEFAULT);
    }

    public RouteSegmentPlanner(long campaignSeed, int routeIndex, int routeRulesVersion) {
        this(campaignSeed, routeIndex, routeRulesVersion, RouteTemplateConfig.DEFAULT);
    }

    public RouteSegmentPlanner(
            long campaignSeed,
            int routeIndex,
            int routeRulesVersion,
            RouteTemplateConfig config) {
        if (routeIndex < 0) {
            throw new IllegalArgumentException("Route index must not be negative");
        }
        if (routeRulesVersion < 1) {
            throw new IllegalArgumentException("Route rules version must be positive");
        }
        this.campaignSeed = campaignSeed;
        this.routeIndex = routeIndex;
        this.routeRulesVersion = routeRulesVersion;
        this.config = Objects.requireNonNull(config, "config");
        this.configFingerprint = this.config.fingerprint();
    }

    /**
     * @param segmentIndex route segment to plan, starting at 1
     * @return the deterministic plan, computed once and memoized per instance
     */
    public RouteSegmentPlan plan(int segmentIndex) {
        if (segmentIndex < 1) {
            throw new IllegalArgumentException("Route segments start at 1");
        }
        RouteSegmentPlan known = plans.get(segmentIndex);
        if (known != null) {
            return known;
        }
        for (int segment = lastPlannedSegment + 1; segment <= segmentIndex; segment++) {
            plans.put(segment, compute(segment));
        }
        lastPlannedSegment = Math.max(lastPlannedSegment, segmentIndex);
        return plans.get(segmentIndex);
    }

    /**
     * Derives the stable seed of one route segment.
     *
     * @param campaignSeed the campaign's persistent seed
     * @param routeIndex which route of the campaign this belongs to
     * @param routeRulesVersion version of the planning rules the route committed
     * @param configFingerprint content fingerprint of the applied
     *     {@link RouteTemplateConfig}, so config swaps under the same version
     *     re-roll future segments
     * @param segmentIndex route segment, starting at 1
     */
    public static long segmentSeed(
            long campaignSeed,
            int routeIndex,
            int routeRulesVersion,
            long configFingerprint,
            int segmentIndex) {
        if (routeIndex < 0) {
            throw new IllegalArgumentException("Route index must not be negative");
        }
        if (routeRulesVersion < 1) {
            throw new IllegalArgumentException("Route rules version must be positive");
        }
        if (segmentIndex < 1) {
            throw new IllegalArgumentException("Route segments start at 1");
        }
        long mixed = campaignSeed
                ^ INDEX_CONSTANT_ROUTE * routeIndex
                ^ INDEX_CONSTANT_RULES * routeRulesVersion
                ^ INDEX_CONSTANT_CONFIG * configFingerprint
                ^ INDEX_CONSTANT_SEGMENT * segmentIndex;
        return avalanche(mixed);
    }

    /**
     * Derives the stable seed of one interest point slot on a segment.
     *
     * @param segmentSeed the owning segment's derived seed
     * @param slotIndex interest point slot, starting at 0
     */
    public static long poiSeed(long segmentSeed, int slotIndex) {
        if (slotIndex < 0) {
            throw new IllegalArgumentException("POI slot index must not be negative");
        }
        return avalanche(segmentSeed ^ POI_DOMAIN_SALT ^ INDEX_CONSTANT_SLOT * slotIndex);
    }

    private RouteSegmentPlan compute(int segment) {
        long seed = segmentSeed(campaignSeed, routeIndex, routeRulesVersion, configFingerprint, segment);
        SegmentTemplate template = selectTemplate(segment, seed);
        List<RoutePoi> pois = poisFor(template, seed);
        if (pois.size() > config.maxPoiSlots()) {
            throw new IllegalStateException(
                    "Segment "
                            + segment
                            + " needs "
                            + pois.size()
                            + " POI slots but the configuration caps slots at "
                            + config.maxPoiSlots());
        }
        List<RouteExit> exits = exitsFor(template, pois);
        return new RouteSegmentPlan(segment, seed, template, pois, exits);
    }

    private SegmentTemplate selectTemplate(int segment, long seed) {
        if (segment == 1) {
            return SegmentTemplate.STRAIGHT;
        }
        while (!recentBridges.isEmpty()
                && recentBridges.peekFirst() <= segment - config.bridgeTunnelWindow()) {
            recentBridges.pollFirst();
        }
        boolean stationAllowed = segment - lastStationSegment > config.stationMinGap();
        boolean cityAllowed = lastCityBypassSegment == 0
                || segment - lastCityBypassSegment > config.cityBypassMinGap();
        boolean bridgeAllowed = recentBridges.size() < config.bridgeTunnelMaxPerWindow();
        SegmentTemplate template =
                rollTemplate(new Random(seed), stationAllowed, cityAllowed, bridgeAllowed);
        switch (template) {
            case STATION -> lastStationSegment = segment;
            case CITY_BYPASS -> lastCityBypassSegment = segment;
            case BRIDGE_TUNNEL -> recentBridges.addLast(segment);
            default -> {
            }
        }
        return template;
    }

    private SegmentTemplate rollTemplate(
            Random random,
            boolean stationAllowed,
            boolean cityAllowed,
            boolean bridgeAllowed) {
        long total = config.straightWeight();
        if (stationAllowed) {
            total += config.stationWeight();
        }
        if (cityAllowed) {
            total += config.cityBypassWeight();
        }
        if (bridgeAllowed) {
            total += config.bridgeTunnelWeight();
        }
        if (total <= 0L) {
            return SegmentTemplate.STRAIGHT;
        }
        long roll = random.nextLong(0, total);
        if (stationAllowed) {
            roll -= config.stationWeight();
            if (roll < 0) {
                return SegmentTemplate.STATION;
            }
        }
        if (cityAllowed) {
            roll -= config.cityBypassWeight();
            if (roll < 0) {
                return SegmentTemplate.CITY_BYPASS;
            }
        }
        if (bridgeAllowed) {
            roll -= config.bridgeTunnelWeight();
            if (roll < 0) {
                return SegmentTemplate.BRIDGE_TUNNEL;
            }
        }
        return SegmentTemplate.STRAIGHT;
    }

    private List<RoutePoi> poisFor(SegmentTemplate template, long segmentSeed) {
        return switch (template) {
            case STATION -> List.of(new RoutePoi(RoutePoiType.STATION, poiAnchor(segmentSeed, 0)));
            case CITY_BYPASS -> List.of(new RoutePoi(RoutePoiType.CITY, poiAnchor(segmentSeed, 0)));
            case STRAIGHT, BRIDGE_TUNNEL -> List.of();
        };
    }

    private int poiAnchor(long segmentSeed, int slotIndex) {
        Random random = new Random(poiSeed(segmentSeed, slotIndex));
        return random.nextInt(config.poiAnchorMin(), config.poiAnchorMax() + 1);
    }

    private List<RouteExit> exitsFor(SegmentTemplate template, List<RoutePoi> pois) {
        RouteExit mainExit = new RouteExit(RouteExitKind.MAIN_LINE, RouteGeometry.SEGMENT_LENGTH);
        if (template != SegmentTemplate.CITY_BYPASS) {
            return List.of(mainExit);
        }
        // The city spur diverges at the interest point and is explicitly a
        // branch, never a second way forward on the main line.
        return List.of(mainExit, new RouteExit(RouteExitKind.BRANCH, pois.get(0).anchorOffset()));
    }

    private static long avalanche(long value) {
        long mixed = value;
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        mixed *= 0x94D049BB133111EBL;
        return mixed ^ (mixed >>> 31);
    }
}
