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

    public enum Decision {
        WAIT,
        TRY_TRAIN,
        COMPLETE_IN_PLACE,
        FALLBACK_TO_STATION,
        DISCARD
    }
}
