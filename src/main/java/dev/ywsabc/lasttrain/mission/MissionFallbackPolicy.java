package dev.ywsabc.lasttrain.mission;

/**
 * Pure deadline policy for recoverable mission fallback.
 *
 * <p>Ordinary campaign missions are roadblocks, not permanent brick walls.
 * If the team does not finish one within its grace period, the director clears
 * the roadblock, applies a threat penalty, and lets the train continue. The
 * day-100 finale is excluded: it is the campaign completion gate and can only
 * be cleared or turned in, never silently failed by the clock.</p>
 */
public final class MissionFallbackPolicy {
    public static final int THREAT_PENALTY = 6;

    private MissionFallbackPolicy() {
    }

    public static int graceDays(MissionType type) {
        return switch (type) {
            case RAIL_BREAK -> 4;
            case STATION_POWER -> 5;
            case STATION_GATE -> 4;
            case SUPPLY_RECOVERY -> 3;
            case ZOMBIE_BLOCKADE -> 5;
            case RESCUE_SURVIVOR -> OptionalMissionPolicy.RESCUE_GRACE_DAYS;
            case SALVAGE_CAR -> OptionalMissionPolicy.SALVAGE_GRACE_DAYS;
        };
    }

    public static boolean shouldFallback(
            ActiveMission mission,
            int currentDay,
            boolean finaleMission) {
        if (mission == null
                || finaleMission
                || currentDay < mission.createdDay()) {
            return false;
        }
        return currentDay - mission.createdDay() >= graceDays(mission.type());
    }
}
