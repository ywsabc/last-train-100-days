package dev.ywsabc.lasttrain.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PlayerReturnPolicyTest {
    @Test
    void alwaysWaitsOneSecondForSableRecovery() {
        assertEquals(
                PlayerReturnPolicy.Decision.WAIT,
                decide(PlayerReturnPolicy.INITIAL_DELAY_TICKS - 1, true, false, true, true, true));
        assertEquals(
                PlayerReturnPolicy.Decision.COMPLETE_IN_PLACE,
                decide(PlayerReturnPolicy.INITIAL_DELAY_TICKS, true, false, true, true, true));
    }

    @Test
    void neverTeleportsPlayerAlreadyTrackingStarterTrain() {
        assertEquals(
                PlayerReturnPolicy.Decision.COMPLETE_IN_PLACE,
                decide(80, true, false, true, true, true));
        assertEquals(
                PlayerReturnPolicy.Decision.COMPLETE_IN_PLACE,
                decide(PlayerReturnPolicy.MAX_WAIT_TICKS, true, false, true, true, true));
    }

    @Test
    void triesAvailableTrainOnlyWhenRetryIsDue() {
        assertEquals(
                PlayerReturnPolicy.Decision.TRY_TRAIN,
                decide(20, true, false, false, true, true));
        assertEquals(
                PlayerReturnPolicy.Decision.WAIT,
                decide(21, true, false, false, true, false));
        assertEquals(
                PlayerReturnPolicy.Decision.WAIT,
                decide(40, true, false, false, false, true));
    }

    @Test
    void fallsBackAtBoundedDeadline() {
        assertEquals(
                PlayerReturnPolicy.Decision.WAIT,
                decide(PlayerReturnPolicy.MAX_WAIT_TICKS - 1, true, false, false, false, true));
        assertEquals(
                PlayerReturnPolicy.Decision.FALLBACK_TO_STATION,
                decide(PlayerReturnPolicy.MAX_WAIT_TICKS, true, false, false, true, true));
    }

    @Test
    void discardsDisconnectedAndSpectatorPlayers() {
        assertEquals(
                PlayerReturnPolicy.Decision.DISCARD,
                decide(20, false, false, false, true, true));
        assertEquals(
                PlayerReturnPolicy.Decision.DISCARD,
                decide(20, true, true, false, true, true));
    }

    @Test
    void rallySelectionPrefersTrainThenLatestStationThenStarter() {
        assertEquals(
                PlayerReturnPolicy.RallyTarget.TRAIN,
                PlayerReturnPolicy.selectRallyTarget(true, true, true));
        assertEquals(
                PlayerReturnPolicy.RallyTarget.ACTIVATED_STATION,
                PlayerReturnPolicy.selectRallyTarget(false, true, true));
        assertEquals(
                PlayerReturnPolicy.RallyTarget.STARTER_STATION,
                PlayerReturnPolicy.selectRallyTarget(false, false, true));
        assertEquals(
                PlayerReturnPolicy.RallyTarget.NONE,
                PlayerReturnPolicy.selectRallyTarget(false, false, false));
    }

    private static PlayerReturnPolicy.Decision decide(
            int elapsed,
            boolean online,
            boolean spectator,
            boolean tracking,
            boolean available,
            boolean due) {
        return PlayerReturnPolicy.decide(
                elapsed,
                online,
                spectator,
                tracking,
                available,
                due);
    }
}
