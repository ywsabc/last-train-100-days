package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.mission.MissionType;
import org.junit.jupiter.api.Test;

class InfectionPolicyTest {
    @Test
    void fiveStagesUseTheDocumentedCampaignBoundaries() {
        assertEquals(5, InfectionPolicy.Stage.values().length);
        assertEquals(InfectionPolicy.Stage.LATENT, InfectionPolicy.stageForTicks(0));
        assertEquals(
                InfectionPolicy.Stage.LATENT,
                InfectionPolicy.stageForTicks(InfectionPolicy.SCARCITY_THRESHOLD_TICKS - 1));
        assertEquals(
                InfectionPolicy.Stage.SCARCITY,
                InfectionPolicy.stageForTicks(InfectionPolicy.SCARCITY_THRESHOLD_TICKS));
        assertEquals(
                InfectionPolicy.Stage.SPREAD,
                InfectionPolicy.stageForTicks(InfectionPolicy.SPREAD_THRESHOLD_TICKS));
        assertEquals(
                InfectionPolicy.Stage.COLLAPSE,
                InfectionPolicy.stageForTicks(InfectionPolicy.COLLAPSE_THRESHOLD_TICKS));
        assertEquals(
                InfectionPolicy.Stage.FINALE,
                InfectionPolicy.stageForTicks(InfectionPolicy.FINALE_THRESHOLD_TICKS));
    }

    @Test
    void activeTickCrossesThresholdWithoutReadingCampaignDay() {
        InfectionPolicy.Sample before = InfectionPolicy.snapshot(
                InfectionPolicy.Stage.LATENT.index(),
                InfectionPolicy.SCARCITY_THRESHOLD_TICKS - 1);

        InfectionPolicy.Sample after = InfectionPolicy.sample(
                before,
                PursuitPolicy.MAX_PURSUIT_DISTANCE,
                true);

        assertEquals(InfectionPolicy.SCARCITY_THRESHOLD_TICKS, after.infectionTicks());
        assertEquals(InfectionPolicy.Stage.SCARCITY, after.stage());
    }

    @Test
    void stageAndTicksNeverRegress() {
        InfectionPolicy.Sample inconsistentOldSnapshot = InfectionPolicy.snapshot(
                InfectionPolicy.Stage.COLLAPSE.index(),
                4L);

        InfectionPolicy.Sample afterTick = InfectionPolicy.sample(
                inconsistentOldSnapshot,
                PursuitPolicy.MAX_PURSUIT_DISTANCE,
                true);
        InfectionPolicy.Sample afterEvent = InfectionPolicy.afterGunfire(afterTick, true);

        assertEquals(InfectionPolicy.Stage.COLLAPSE, afterTick.stage());
        assertEquals(InfectionPolicy.Stage.COLLAPSE, afterEvent.stage());
        assertTrue(afterTick.infectionTicks() >= inconsistentOldSnapshot.infectionTicks());
        assertTrue(afterEvent.infectionTicks() >= afterTick.infectionTicks());
    }

    @Test
    void gunfireExplosionAndHordeEventsAddExplicitProgress() {
        InfectionPolicy.Sample initial = InfectionPolicy.Sample.initial();
        InfectionPolicy.Sample gunfire = InfectionPolicy.afterGunfire(initial, true);
        InfectionPolicy.Sample explosion = InfectionPolicy.afterExplosion(gunfire, true);
        InfectionPolicy.Sample horde = InfectionPolicy.afterHorde(explosion, true);

        assertEquals(InfectionPolicy.GUNFIRE_PROGRESS_TICKS, gunfire.infectionTicks());
        assertEquals(
                InfectionPolicy.GUNFIRE_PROGRESS_TICKS
                        + InfectionPolicy.EXPLOSION_PROGRESS_TICKS,
                explosion.infectionTicks());
        assertEquals(
                InfectionPolicy.GUNFIRE_PROGRESS_TICKS
                        + InfectionPolicy.EXPLOSION_PROGRESS_TICKS
                        + InfectionPolicy.HORDE_PROGRESS_TICKS,
                horde.infectionTicks());

        InfectionPolicy.Sample nearBoundary = InfectionPolicy.snapshot(
                InfectionPolicy.Stage.LATENT.index(),
                InfectionPolicy.SCARCITY_THRESHOLD_TICKS
                        - InfectionPolicy.GUNFIRE_PROGRESS_TICKS);
        assertEquals(
                InfectionPolicy.Stage.SCARCITY,
                InfectionPolicy.afterGunfire(nearBoundary, true).stage());
    }

    @Test
    void noActivePlayersPauseTickAndEventProgress() {
        InfectionPolicy.Sample current = InfectionPolicy.snapshot(
                InfectionPolicy.Stage.SPREAD.index(),
                InfectionPolicy.SPREAD_THRESHOLD_TICKS + 123L);

        assertSame(
                current,
                InfectionPolicy.sample(current, InfectionPolicy.CAUGHT_PURSUIT_DISTANCE, false));
        assertSame(current, InfectionPolicy.afterGunfire(current, false));
        assertSame(current, InfectionPolicy.afterExplosion(current, false));
        assertSame(current, InfectionPolicy.afterHorde(current, false));
    }

    @Test
    void closerPursuitDistanceAcceleratesEveryActiveTick() {
        InfectionPolicy.Sample initial = InfectionPolicy.Sample.initial();
        InfectionPolicy.Sample far = InfectionPolicy.sample(
                initial,
                PursuitPolicy.MAX_PURSUIT_DISTANCE,
                true);
        InfectionPolicy.Sample caught = InfectionPolicy.sample(
                initial,
                InfectionPolicy.CAUGHT_PURSUIT_DISTANCE,
                true);

        assertEquals(1L, far.infectionTicks());
        assertEquals(
                1L + InfectionPolicy.pursuitAcceleration(0),
                caught.infectionTicks());
        assertTrue(caught.infectionTicks() > far.infectionTicks());
    }

    @Test
    void stageEffectsRaiseAttentionSiegePressureAndEventFrequency() {
        InfectionPolicy.Stage latent = InfectionPolicy.Stage.LATENT;
        InfectionPolicy.Stage finale = InfectionPolicy.Stage.FINALE;

        assertTrue(finale.effects().minAttention() > latent.effects().minAttention());
        assertTrue(
                PursuitPolicy.parkedPursuitDrain(finale)
                        > PursuitPolicy.parkedPursuitDrain(latent));
        assertTrue(
                PopulationScalingPolicy.missionTarget(
                                MissionType.ZOMBIE_BLOCKADE,
                                1,
                                finale)
                        > PopulationScalingPolicy.missionTarget(
                                MissionType.ZOMBIE_BLOCKADE,
                                1,
                                latent));
        assertTrue(
                InfectionPolicy.eventChance(0.25D, finale)
                        > InfectionPolicy.eventChance(0.25D, latent));
        assertEquals(
                finale.effects().minAttention(),
                PursuitPolicy.minAttention(CampaignMode.STORY_100_DAYS, 1, finale));
    }

    @Test
    void snapshotDoesNotRecomputeLegacyStageFromExistingTicks() {
        InfectionPolicy.Sample legacy = InfectionPolicy.snapshot(
                InfectionPolicy.Stage.LATENT.index(),
                InfectionPolicy.FINALE_THRESHOLD_TICKS);

        assertEquals(InfectionPolicy.Stage.LATENT, legacy.stage());
        assertEquals(InfectionPolicy.FINALE_THRESHOLD_TICKS, legacy.infectionTicks());
    }
}
