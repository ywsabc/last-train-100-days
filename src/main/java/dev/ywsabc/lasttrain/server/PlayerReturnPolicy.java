package dev.ywsabc.lasttrain.server;

/** Pure timing policy for returning a joining or respawned player to the train. */
public final class PlayerReturnPolicy {
    public static final int INITIAL_DELAY_TICKS = 20;
    public static final int MAX_WAIT_TICKS = 200;
    public static final int RETRY_INTERVAL_TICKS = 20;

    private PlayerReturnPolicy() {
    }

    public static Decision decide(
            int elapsedTicks,
            boolean online,
            boolean spectator,
            boolean trackingStarterTrain,
            boolean trainPointAvailable,
            boolean attemptDue) {
        if (!online || spectator) {
            return Decision.DISCARD;
        }
        if (elapsedTicks < INITIAL_DELAY_TICKS) {
            return Decision.WAIT;
        }
        if (trackingStarterTrain) {
            return Decision.COMPLETE_IN_PLACE;
        }
        if (elapsedTicks >= MAX_WAIT_TICKS) {
            return Decision.FALLBACK_TO_STATION;
        }
        if (trainPointAvailable && attemptDue) {
            return Decision.TRY_TRAIN;
        }
        return Decision.WAIT;
    }

    /** 列车可安全登乘时永远优先，其次是最近激活站，最后才是起点站。 */
    public static RallyTarget selectRallyTarget(
            boolean trainBoardable,
            boolean activatedStationAvailable,
            boolean starterStationAvailable) {
        if (trainBoardable) {
            return RallyTarget.TRAIN;
        }
        if (activatedStationAvailable) {
            return RallyTarget.ACTIVATED_STATION;
        }
        if (starterStationAvailable) {
            return RallyTarget.STARTER_STATION;
        }
        return RallyTarget.NONE;
    }

    public enum Decision {
        WAIT,
        TRY_TRAIN,
        COMPLETE_IN_PLACE,
        FALLBACK_TO_STATION,
        DISCARD
    }

    public enum RallyTarget {
        TRAIN,
        ACTIVATED_STATION,
        STARTER_STATION,
        NONE
    }
}
