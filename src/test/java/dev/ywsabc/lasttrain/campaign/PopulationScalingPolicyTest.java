package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.mission.MissionType;
import org.junit.jupiter.api.Test;

class PopulationScalingPolicyTest {
    @Test
    void onePlayerAlwaysKeepsBaselineTargets() {
        for (MissionType type : MissionType.values()) {
            assertEquals(
                    type.defaultTarget(),
                    PopulationScalingPolicy.missionTarget(type, 1));
        }
    }

    @Test
    void scaledTargetsAreMonotonicAndNeverShrinkBelowOne() {
        for (MissionType type : MissionType.values()) {
            int previous = PopulationScalingPolicy.missionTarget(type, 1);
            assertTrue(previous >= 1);
            for (int players = 2; players <= PopulationScalingPolicy.MAX_PLAYERS; players++) {
                int target = PopulationScalingPolicy.missionTarget(type, players);
                assertTrue(target >= previous);
                previous = target;
            }
        }
    }

    @Test
    void observationMustStayStableForTheFullWindowInBothDirections() {
        PopulationScalingPolicy.Hysteresis state =
                PopulationScalingPolicy.Hysteresis.initial();

        for (int tick = 1; tick < PopulationScalingPolicy.CONFIRM_TICKS; tick++) {
            state = PopulationScalingPolicy.observe(state, 5);
            assertEquals(1, state.effectivePlayers());
            assertEquals(5, state.pendingPlayers());
            assertEquals(tick, state.holdTicks());
        }
        state = PopulationScalingPolicy.observe(state, 5);
        assertEquals(5, state.effectivePlayers());
        assertEquals(0, state.holdTicks());

        for (int tick = 1; tick < PopulationScalingPolicy.CONFIRM_TICKS; tick++) {
            state = PopulationScalingPolicy.observe(state, 2);
            assertEquals(5, state.effectivePlayers());
            assertEquals(2, state.pendingPlayers());
            assertEquals(tick, state.holdTicks());
        }
        state = PopulationScalingPolicy.observe(state, 2);
        assertEquals(2, state.effectivePlayers());
        assertEquals(0, state.holdTicks());
    }

    @Test
    void returningToTheCurrentEffectivePlayersResetsTheWindow() {
        PopulationScalingPolicy.Hysteresis state =
                PopulationScalingPolicy.Hysteresis.initial();
        state = PopulationScalingPolicy.observe(state, 6);
        assertEquals(1, state.effectivePlayers());
        assertTrue(state.holdTicks() > 0);

        state = PopulationScalingPolicy.observe(state, 1);
        assertEquals(1, state.effectivePlayers());
        assertEquals(1, state.pendingPlayers());
        assertEquals(0, state.holdTicks());
    }

    @Test
    void observedPlayersAreClampedToTheBalancedRange() {
        assertEquals(1, PopulationScalingPolicy.clampPlayers(0));
        assertEquals(1, PopulationScalingPolicy.clampPlayers(-5));
        assertEquals(6, PopulationScalingPolicy.clampPlayers(6));
        assertEquals(6, PopulationScalingPolicy.clampPlayers(20));
    }

    @Test
    void multipliersUseTheDocumentedCaps() {
        assertEquals(3.75D, PopulationScalingPolicy.materialMultiplier(6), 0.0001D);
        assertEquals(4.50D, PopulationScalingPolicy.enemyMultiplier(6), 0.0001D);
        assertEquals(3.25D, PopulationScalingPolicy.rewardMultiplier(6), 0.0001D);
        assertEquals(1.0D, PopulationScalingPolicy.materialMultiplier(1), 0.0001D);
        assertEquals(1.0D, PopulationScalingPolicy.enemyMultiplier(1), 0.0001D);
    }
}
