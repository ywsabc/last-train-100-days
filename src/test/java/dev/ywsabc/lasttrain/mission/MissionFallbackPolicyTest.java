package dev.ywsabc.lasttrain.mission;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MissionFallbackPolicyTest {
    @Test
    void ordinaryMissionsFailAfterTheirTypeGracePeriod() {
        ActiveMission railBreak = ActiveMission.create(MissionType.RAIL_BREAK, 10, 1);
        for (int day = 10; day < 10 + MissionFallbackPolicy.graceDays(MissionType.RAIL_BREAK); day++) {
            assertFalse(MissionFallbackPolicy.shouldFallback(railBreak, day, false));
        }
        assertTrue(MissionFallbackPolicy.shouldFallback(
                railBreak,
                10 + MissionFallbackPolicy.graceDays(MissionType.RAIL_BREAK),
                false));
    }

    @Test
    void eachTypeHasANonNegativeGracePeriod() {
        for (MissionType type : MissionType.values()) {
            assertTrue(MissionFallbackPolicy.graceDays(type) > 0);
        }
    }

    @Test
    void finaleMissionNeverFallsBackOnTheClock() {
        ActiveMission finale = ActiveMission.create(MissionType.ZOMBIE_BLOCKADE, 1, 5);
        assertFalse(MissionFallbackPolicy.shouldFallback(finale, 100, true));
        assertFalse(MissionFallbackPolicy.shouldFallback(finale, 200, true));
    }

    @Test
    void missingOrClockSkewedMissionsNeverFallBack() {
        assertFalse(MissionFallbackPolicy.shouldFallback(null, 100, false));
        ActiveMission mission = ActiveMission.create(MissionType.SUPPLY_RECOVERY, 20, 2);
        assertFalse(MissionFallbackPolicy.shouldFallback(mission, 19, false));
    }
}
