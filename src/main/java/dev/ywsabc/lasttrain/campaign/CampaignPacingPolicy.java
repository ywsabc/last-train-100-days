package dev.ywsabc.lasttrain.campaign;

import dev.ywsabc.lasttrain.mission.MissionType;
import dev.ywsabc.lasttrain.route.RouteProgressPolicy;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Pure campaign pacing rules for the two-dimensional day/mileage schedule.
 *
 * <p>The policy intentionally separates chapter gates from the physical
 * mission implementation. A key mission can therefore be deferred without
 * inventing a client-side signal or changing a previously committed route
 * segment.</p>
 */
public final class CampaignPacingPolicy {
    public static final int FINAL_HUB_CLUE_MIN_DAY = 81;

    private static final List<KeyMission> KEY_MISSION_ORDER = List.of(KeyMission.values());

    private CampaignPacingPolicy() {
    }

    public static int expectedRouteSegment(int day) {
        return RouteProgressPolicy.expectedRouteSegment(day);
    }

    public static RouteProgressPolicy.PaceAssessment pace(int day, int routeSegment) {
        return RouteProgressPolicy.assess(day, routeSegment);
    }

    public static RouteProgressPolicy.PaceAssessment pace(
            int day,
            int routeSegment,
            RouteProgressPolicy.MileageLine mileageLine) {
        return RouteProgressPolicy.assess(day, routeSegment, mileageLine);
    }

    public static Chapter chapterForDay(int day) {
        int boundedDay = Math.clamp(
                day,
                RouteProgressPolicy.FIRST_CAMPAIGN_DAY,
                RouteProgressPolicy.FINAL_CAMPAIGN_DAY);
        if (boundedDay <= Chapter.PROLOGUE.endDay) {
            return Chapter.PROLOGUE;
        }
        if (boundedDay <= Chapter.SCARCITY.endDay) {
            return Chapter.SCARCITY;
        }
        if (boundedDay <= Chapter.SPREAD.endDay) {
            return Chapter.SPREAD;
        }
        if (boundedDay <= Chapter.COLLAPSE.endDay) {
            return Chapter.COLLAPSE;
        }
        if (boundedDay <= Chapter.FINAL_LEG.endDay) {
            return Chapter.FINAL_LEG;
        }
        return Chapter.FINALE;
    }

    /**
     * A key location cannot be selected before both its chapter day and its
     * minimum verified route segment have been reached.
     */
    public static boolean isKeyMissionEligible(
            KeyMission keyMission,
            int day,
            int routeSegment) {
        Objects.requireNonNull(keyMission, "keyMission");
        return day >= keyMission.chapter.startDay
                && routeSegment >= keyMission.minimumRouteSegment;
    }

    /** Returns the first not-yet-committed key mission that may be offered now. */
    public static Optional<KeyMission> nextKeyMission(
            int day,
            int routeSegment,
            Set<KeyMission> committedKeyMissions) {
        Objects.requireNonNull(committedKeyMissions, "committedKeyMissions");
        return KEY_MISSION_ORDER.stream()
                .filter(keyMission -> !committedKeyMissions.contains(keyMission))
                .filter(keyMission -> isKeyMissionEligible(keyMission, day, routeSegment))
                .findFirst();
    }

    public static boolean isMainlineMission(MissionType type) {
        return type == MissionType.RAIL_BREAK
                || type == MissionType.STATION_POWER
                || type == MissionType.STATION_GATE;
    }

    public static boolean allowsMainlineMission(int day) {
        return day < FinalePolicy.FINALE_HUB_START_DAY;
    }

    /**
     * Leading teams are never put behind a day gate. This explicit predicate
     * keeps that guarantee testable even when a future director adds more
     * optional content types.
     */
    public static boolean mainlineProgressAvailable(int day, int routeSegment) {
        return day >= RouteProgressPolicy.FIRST_CAMPAIGN_DAY && routeSegment >= 0;
    }

    public static boolean requiresForcedWait(int day, int routeSegment) {
        return false;
    }

    public static MissionWeights missionWeights(int day, int routeSegment) {
        return missionWeights(
                day,
                routeSegment,
                RouteProgressPolicy.DEFAULT_MILEAGE_LINE);
    }

    public static MissionWeights missionWeights(
            int day,
            int routeSegment,
            RouteProgressPolicy.MileageLine mileageLine) {
        return switch (pace(day, routeSegment, mileageLine).pace()) {
            case BEHIND -> new MissionWeights(
                    25,
                    140,
                    35,
                    100);
            case ON_TRACK -> new MissionWeights(
                    80,
                    55,
                    70,
                    100);
            case AHEAD -> new MissionWeights(
                    80,
                    55,
                    150,
                    100);
        };
    }

    /**
     * Deterministically chooses an ordinary mission type using the current
     * pace profile. The low-value forced-obstacle bucket is split evenly over
     * the three ordinary mainline obstacles; supply recovery is its own
     * guarantee bucket.
     */
    public static MissionType selectMissionType(
            SplittableRandom random,
            int day,
            int routeSegment) {
        Objects.requireNonNull(random, "random");
        MissionWeights weights = missionWeights(day, routeSegment);
        int lowValueWeight = weights.lowValueForcedObstacleWeight();
        int supplyWeight = weights.fuelSupplyGuaranteeWeight();
        int total = lowValueWeight + supplyWeight;
        if (total <= 0) {
            return MissionType.SUPPLY_RECOVERY;
        }
        int roll = random.nextInt(total);
        if (roll >= lowValueWeight) {
            return MissionType.SUPPLY_RECOVERY;
        }
        return switch (roll % 3) {
            case 0 -> MissionType.RAIL_BREAK;
            case 1 -> MissionType.STATION_POWER;
            default -> MissionType.STATION_GATE;
        };
    }

    public enum Chapter {
        PROLOGUE(1, 10),
        SCARCITY(11, 30),
        SPREAD(31, 55),
        COLLAPSE(56, 80),
        FINAL_LEG(81, 99),
        FINALE(100, 100);

        private final int startDay;
        private final int endDay;

        Chapter(int startDay, int endDay) {
            this.startDay = startDay;
            this.endDay = endDay;
        }

        public int startDay() {
            return startDay;
        }

        public int endDay() {
            return endDay;
        }
    }

    /**
     * The five chapter milestones are kept separate from generic mission
     * types because several milestones can use the same world objective
     * implementation while retaining different pacing gates.
     */
    public enum KeyMission {
        PROLOGUE_DEPARTURE(Chapter.PROLOGUE, 1, MissionType.RAIL_BREAK),
        FIRST_CITY_STATION(Chapter.SCARCITY, 8, MissionType.STATION_GATE),
        TUNNEL(Chapter.SPREAD, 24, MissionType.RAIL_BREAK),
        COMPOUND_LOCATION(Chapter.COLLAPSE, 40, MissionType.STATION_POWER),
        FINALE_HUB_CLUE(Chapter.FINAL_LEG, 56, MissionType.SUPPLY_RECOVERY);

        private final Chapter chapter;
        private final int minimumRouteSegment;
        private final MissionType missionType;

        KeyMission(Chapter chapter, int minimumRouteSegment, MissionType missionType) {
            this.chapter = chapter;
            this.minimumRouteSegment = minimumRouteSegment;
            this.missionType = missionType;
        }

        public Chapter chapter() {
            return chapter;
        }

        public int minimumRouteSegment() {
            return minimumRouteSegment;
        }

        public MissionType missionType() {
            return missionType;
        }
    }

    public enum MissionCategory {
        LOW_VALUE_FORCED_OBSTACLE,
        FUEL_SUPPLY_GUARANTEE,
        OPTIONAL_HIGH_RISK_LOCATION,
        MAINLINE_PROGRESS_OPPORTUNITY
    }

    public record MissionWeights(
            int lowValueForcedObstacleWeight,
            int fuelSupplyGuaranteeWeight,
            int optionalHighRiskLocationWeight,
            int mainlineProgressOpportunityWeight) {
        public MissionWeights {
            if (lowValueForcedObstacleWeight < 0
                    || fuelSupplyGuaranteeWeight < 0
                    || optionalHighRiskLocationWeight < 0
                    || mainlineProgressOpportunityWeight < 0) {
                throw new IllegalArgumentException("Pacing weights must not be negative");
            }
        }

        public int weight(MissionCategory category) {
            return switch (Objects.requireNonNull(category, "category")) {
                case LOW_VALUE_FORCED_OBSTACLE -> lowValueForcedObstacleWeight;
                case FUEL_SUPPLY_GUARANTEE -> fuelSupplyGuaranteeWeight;
                case OPTIONAL_HIGH_RISK_LOCATION -> optionalHighRiskLocationWeight;
                case MAINLINE_PROGRESS_OPPORTUNITY -> mainlineProgressOpportunityWeight;
            };
        }
    }
}
