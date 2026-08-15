package dev.ywsabc.lasttrain.route;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * Deterministic planner that assigns a template, interest points and exits to
 * each route segment ahead of the party.
 *
 * <p>Every segment derives its own seed so that one extra random draw can
 * never reshuffle later segments. Version 2 derives
 * {@code segmentSeed = avalanche(mix(campaignSeed, routeIndex, routeRulesVersion, configSeedSalt.lo, configSeedSalt.hi, segmentIndex))},
 * where the two config salt halves come from the SHA-256 digest of all eleven
 * config fields; version 1 (the historical first draft) derives the same mix
 * without any config input and draws from {@link SplittableRandom}. Every
 * interest point derives {@code poiSeed = avalanche(segmentSeed, slotIndex)}.
 * Selection obeys the configured appearance rules (station minimum gap, no
 * consecutive city bypasses, bridge/tunnel budget window) and falls back to a
 * straight segment when nothing else is allowed. Segment 1 is always straight
 * because the starter station already owns the corridor head.</p>
 *
 * <p>Version 2 draws from {@link java.util.Random}, whose sequence is
 * guaranteed stable across JVM versions, so v2 plans only change when one of
 * the seed inputs changes. Version 1 keeps the historical SplittableRandom
 * draws for campaigns committed before the P2.1 review; its output is pinned
 * by golden tests on the supported JDK.</p>
 *
 * <p>Plans are memoized per instance and computed in increasing segment
 * order; querying a lower segment after a higher one returns the memoized
 * plan. A planner can be {@link #resume resumed} from persisted state (cursor
 * plus pending plans) so committed plans are adopted as-is and never
 * re-rolled. Segments at or below the realized floor are no longer retained:
 * querying one throws instead of silently re-rolling. The planner is not
 * thread-safe and belongs on the logical server thread.</p>
 */
public final class RouteSegmentPlanner {
    public static final int DEFAULT_ROUTE_INDEX = 0;
    /**
     * Bump when the selection logic or seed derivation itself changes, so
     * committed routes do not silently re-roll; config value changes are
     * covered by the full config field digest in the seed. Version 1 is the
     * historical first draft: SplittableRandom with no config input in the
     * seed. Version 2 uses java.util.Random and mixes the SHA-256 digest of
     * all config fields into every segment seed.
     */
    public static final int DEFAULT_ROUTE_RULES_VERSION = 2;
    private static final long POI_DOMAIN_SALT = 0x504F495F534C4F54L; // "POI_SLOT"
    private static final long INDEX_CONSTANT_ROUTE = 0x9E3779B97F4A7C15L;
    private static final long INDEX_CONSTANT_RULES = 0xBF58476D1CE4E5B9L;
    private static final long INDEX_CONSTANT_CONFIG_LO = 0xE7037ED1A0B428DBL;
    private static final long INDEX_CONSTANT_CONFIG_HI = 0xD6E8FEB86659FD93L;
    private static final long INDEX_CONSTANT_SEGMENT = 0x94D049BB133111EBL;
    private static final long INDEX_CONSTANT_SLOT = 0xD1B54A32D192ED03L;

    private final long campaignSeed;
    private final int routeIndex;
    private final int routeRulesVersion;
    private final RouteTemplateConfig config;
    /** Config digest cached per instance; null for version 1. */
    private final RouteTemplateConfig.SeedSalt configSalt;
    private final Map<Integer, RouteSegmentPlan> plans = new HashMap<>();
    private final ArrayDeque<Integer> recentBridges = new ArrayDeque<>();
    private int lastPlannedSegment;
    /** Segments at or below this index were realized and are no longer retained. */
    private final int realizedFloor;
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
        this(campaignSeed, routeIndex, routeRulesVersion, config, 0);
    }

    private RouteSegmentPlanner(
            long campaignSeed,
            int routeIndex,
            int routeRulesVersion,
            RouteTemplateConfig config,
            int realizedFloor) {
        if (routeIndex < 0) {
            throw new IllegalArgumentException("Route index must not be negative");
        }
        if (routeRulesVersion < 1) {
            throw new IllegalArgumentException("Route rules version must be positive");
        }
        if (realizedFloor < 0) {
            throw new IllegalArgumentException("The realized floor must not be negative");
        }
        this.campaignSeed = campaignSeed;
        this.routeIndex = routeIndex;
        this.routeRulesVersion = routeRulesVersion;
        this.config = Objects.requireNonNull(config, "config");
        this.configSalt = routeRulesVersion >= 2 ? this.config.seedSalt() : null;
        this.realizedFloor = realizedFloor;
    }

    /**
     * Resumes planning from persisted state: the pending (planned but not yet
     * realized) segment plans are adopted as-is, the rolling state continues
     * from {@code cursor}, and the next {@link #plan} extends beyond
     * {@code cursor.nextSegment() - 1}.
     *
     * @param campaignSeed the campaign's persistent seed
     * @param routeIndex which route of the campaign this belongs to
     * @param routeRulesVersion the rules version the route committed
     * @param config the applied {@link RouteTemplateConfig} for future segments
     * @param realizedFloor the highest already-realized segment; retained
     *     plans must all lie above it
     * @param cursor rolling selection state persisted with the pending plans
     * @param pendingPlans committed plans for segments {@code [realizedFloor+1, cursor.nextSegment()-1]},
     *     ordered by segment index; empty is legal only when
     *     {@code cursor.nextSegment() == realizedFloor + 1}
     * @throws IllegalArgumentException when the persisted state is inconsistent;
     *     callers then fall back to a fresh planner
     */
    public static RouteSegmentPlanner resume(
            long campaignSeed,
            int routeIndex,
            int routeRulesVersion,
            RouteTemplateConfig config,
            int realizedFloor,
            Cursor cursor,
            List<RouteSegmentPlan> pendingPlans) {
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(pendingPlans, "pendingPlans");
        if (realizedFloor < 0) {
            throw new IllegalArgumentException("The realized floor must not be negative");
        }
        if (cursor.nextSegment() < realizedFloor + 1) {
            throw new IllegalArgumentException(
                    "Planner cursor " + cursor.nextSegment() + " falls behind the realized floor "
                            + realizedFloor);
        }
        int expected = realizedFloor + 1;
        for (RouteSegmentPlan plan : pendingPlans) {
            if (plan == null || plan.segmentIndex() != expected) {
                throw new IllegalArgumentException(
                        "Pending route plans must be contiguous from the realized floor, expected segment "
                                + expected);
            }
            expected++;
        }
        if (cursor.nextSegment() != expected) {
            throw new IllegalArgumentException(
                    "Planner cursor " + cursor.nextSegment() + " does not follow the last pending plan");
        }
        RouteSegmentPlanner planner = new RouteSegmentPlanner(
                campaignSeed,
                routeIndex,
                routeRulesVersion,
                config,
                realizedFloor);
        planner.lastPlannedSegment = cursor.nextSegment() - 1;
        planner.lastStationSegment = cursor.lastStationSegment();
        planner.lastCityBypassSegment = cursor.lastCityBypassSegment();
        planner.recentBridges.addAll(cursor.recentBridges());
        for (RouteSegmentPlan plan : pendingPlans) {
            planner.plans.put(plan.segmentIndex(), plan);
        }
        return planner;
    }

    /**
     * Persistable snapshot of the rolling selection state after the last
     * planned segment: the next segment to plan, the latest station/city
     * segments for the gap rules and the bridge segments still inside the
     * budget window (oldest first).
     */
    public record Cursor(
            int nextSegment,
            int lastStationSegment,
            int lastCityBypassSegment,
            List<Integer> recentBridges) {
        public Cursor {
            if (nextSegment < 1) {
                throw new IllegalArgumentException("Planner cursor must point at a positive segment");
            }
            if (lastStationSegment < 1) {
                throw new IllegalArgumentException("Last station segment must be positive");
            }
            if (lastCityBypassSegment < 0) {
                throw new IllegalArgumentException("Last city bypass segment must not be negative");
            }
            recentBridges = List.copyOf(Objects.requireNonNull(recentBridges, "recentBridges"));
        }

        /** The cursor of a planner that has not planned anything yet. */
        public static Cursor fresh() {
            return new Cursor(1, 1, 0, List.of());
        }
    }

    /** Persistable snapshot of the current rolling state. */
    public Cursor cursor() {
        return new Cursor(
                lastPlannedSegment + 1,
                lastStationSegment,
                lastCityBypassSegment,
                List.copyOf(recentBridges));
    }

    /** The highest planned segment index. */
    public int lastPlannedSegment() {
        return lastPlannedSegment;
    }

    /** All retained plans above {@code floorSegment}, in segment order. */
    public List<RouteSegmentPlan> plansAbove(int floorSegment) {
        return plans.entrySet().stream()
                .filter(entry -> entry.getKey() > floorSegment)
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
    }

    /**
     * @param segmentIndex route segment to plan, starting at 1
     * @return the deterministic plan, computed once and memoized per instance
     */
    public RouteSegmentPlan plan(int segmentIndex) {
        if (segmentIndex < 1) {
            throw new IllegalArgumentException("Route segments start at 1");
        }
        if (segmentIndex <= realizedFloor) {
            throw new IllegalStateException(
                    "Route segment " + segmentIndex + " was realized and is no longer retained; "
                            + "only pending segments above " + realizedFloor + " can be planned");
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
     * <p>Version 2 mixes the SHA-256 digest of all eleven config fields into
     * the seed, so config swaps under the same version re-roll future
     * segments. Version 1 keeps the historical derivation without any config
     * input.</p>
     *
     * @param campaignSeed the campaign's persistent seed
     * @param routeIndex which route of the campaign this belongs to
     * @param routeRulesVersion version of the planning rules the route committed
     * @param config the applied {@link RouteTemplateConfig}; only version 2+
     *     mixes its digest into the seed
     * @param segmentIndex route segment, starting at 1
     */
    public static long segmentSeed(
            long campaignSeed,
            int routeIndex,
            int routeRulesVersion,
            RouteTemplateConfig config,
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
        Objects.requireNonNull(config, "config");
        return mixSegmentSeed(
                campaignSeed,
                routeIndex,
                routeRulesVersion,
                routeRulesVersion >= 2 ? config.seedSalt() : null,
                segmentIndex);
    }

    /** Shared mix; version 1 leaves the config salt out entirely. */
    private static long mixSegmentSeed(
            long campaignSeed,
            int routeIndex,
            int routeRulesVersion,
            RouteTemplateConfig.SeedSalt configSalt,
            int segmentIndex) {
        long mixed = campaignSeed
                ^ INDEX_CONSTANT_ROUTE * routeIndex
                ^ INDEX_CONSTANT_RULES * routeRulesVersion
                ^ INDEX_CONSTANT_SEGMENT * segmentIndex;
        if (configSalt != null) {
            mixed ^= INDEX_CONSTANT_CONFIG_LO * configSalt.lo();
            mixed ^= INDEX_CONSTANT_CONFIG_HI * configSalt.hi();
        }
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
        long seed = mixSegmentSeed(
                campaignSeed,
                routeIndex,
                routeRulesVersion,
                configSalt,
                segment);
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
                rollTemplate(draw(seed), stationAllowed, cityAllowed, bridgeAllowed);
        switch (template) {
            case STATION -> lastStationSegment = segment;
            case CITY_BYPASS -> lastCityBypassSegment = segment;
            case BRIDGE_TUNNEL -> recentBridges.addLast(segment);
            default -> {
            }
        }
        return template;
    }

    /**
     * The random generator of one seed: version 2 draws from
     * {@link java.util.Random} (stable across JVM versions), version 1 keeps
     * the historical {@link SplittableRandom} draws.
     */
    private RandomGenerator draw(long seed) {
        return routeRulesVersion >= 2 ? new Random(seed) : new SplittableRandom(seed);
    }

    private SegmentTemplate rollTemplate(
            RandomGenerator random,
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
        RandomGenerator random = draw(poiSeed(segmentSeed, slotIndex));
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
