package dev.ywsabc.lasttrain.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.campaign.CampaignStatus;
import dev.ywsabc.lasttrain.campaign.PursuitPolicy;
import org.junit.jupiter.api.Test;

class TrainRecoveryPolicyTest {
    private static TrainRecoveryPolicy.TrainSituation situation(
            boolean vehicleStackLoaded,
            boolean trainReferenceKnown,
            boolean trainLocatable,
            boolean trainMoving,
            int missingTicks,
            int immobileTicks,
            boolean playerInDangerCollision,
            int rescueCount,
            int lastRescueDay,
            int currentDay,
            CampaignStatus status) {
        return new TrainRecoveryPolicy.TrainSituation(
                vehicleStackLoaded,
                trainReferenceKnown,
                trainLocatable,
                trainMoving,
                missingTicks,
                immobileTicks,
                playerInDangerCollision,
                rescueCount,
                lastRescueDay,
                currentDay,
                status);
    }

    private static TrainRecoveryPolicy.TrainSituation healthy() {
        return situation(
                true, true, true, true,
                0, 0, false,
                0, 0, 1,
                CampaignStatus.RUNNING);
    }

    @Test
    void healthyMovingTrainNeedsNoAction() {
        assertEquals(
                TrainRecoveryPolicy.Directive.NONE,
                TrainRecoveryPolicy.assess(healthy()));
    }

    @Test
    void missingTrainIsObservedInsideGraceAndReadyAfterIt() {
        TrainRecoveryPolicy.TrainSituation beforeGrace =
                situation(true, true, false, false, 99, 0, false, 0, 0, 1, CampaignStatus.RUNNING);
        assertEquals(
                TrainRecoveryPolicy.Directive.OBSERVING,
                TrainRecoveryPolicy.assess(beforeGrace));

        TrainRecoveryPolicy.TrainSituation afterGrace =
                situation(
                        true, true, false, false,
                        TrainRecoveryPolicy.MISSING_GRACE_TICKS, 0,
                        false, 0, 0, 1,
                        CampaignStatus.RUNNING);
        assertEquals(
                TrainRecoveryPolicy.Directive.RESCUE_READY,
                TrainRecoveryPolicy.assess(afterGrace));
    }

    @Test
    void immobileTrainIsObservedInsideGraceAndReadyAfterIt() {
        TrainRecoveryPolicy.TrainSituation beforeGrace =
                situation(true, true, true, false, 0, 199, false, 0, 0, 1, CampaignStatus.RUNNING);
        assertEquals(
                TrainRecoveryPolicy.Directive.OBSERVING,
                TrainRecoveryPolicy.assess(beforeGrace));

        TrainRecoveryPolicy.TrainSituation afterGrace =
                situation(
                        true, true, true, false,
                        0, TrainRecoveryPolicy.IMMOBILE_GRACE_TICKS,
                        false, 0, 0, 1,
                        CampaignStatus.RUNNING);
        assertEquals(
                TrainRecoveryPolicy.Directive.RESCUE_READY,
                TrainRecoveryPolicy.assess(afterGrace));
    }

    @Test
    void playerInDangerCollisionBlocksRescue() {
        TrainRecoveryPolicy.TrainSituation dangerous =
                situation(
                        true, true, false, false,
                        TrainRecoveryPolicy.MISSING_GRACE_TICKS, 0,
                        true, 0, 0, 1,
                        CampaignStatus.RUNNING);
        assertEquals(
                TrainRecoveryPolicy.Directive.RESCUE_BLOCKED,
                TrainRecoveryPolicy.assess(dangerous));
    }

    @Test
    void rescueCooldownAndCountLimitAreRespected() {
        TrainRecoveryPolicy.TrainSituation oneDayShort =
                situation(
                        true, true, true, false,
                        0, TrainRecoveryPolicy.IMMOBILE_GRACE_TICKS,
                        false, 1, 7, 9,
                        CampaignStatus.RUNNING);
        assertEquals(
                TrainRecoveryPolicy.Directive.COOLDOWN,
                TrainRecoveryPolicy.assess(oneDayShort));

        TrainRecoveryPolicy.TrainSituation cooldownElapsed =
                situation(
                        true, true, true, false,
                        0, TrainRecoveryPolicy.IMMOBILE_GRACE_TICKS,
                        false, 1, 7, 10,
                        CampaignStatus.RUNNING);
        assertEquals(
                TrainRecoveryPolicy.Directive.RESCUE_READY,
                TrainRecoveryPolicy.assess(cooldownElapsed));

        TrainRecoveryPolicy.TrainSituation atCountLimit =
                situation(
                        true, true, true, false,
                        0, TrainRecoveryPolicy.IMMOBILE_GRACE_TICKS,
                        false, TrainRecoveryPolicy.RESCUE_COUNT_LIMIT, 0, 99,
                        CampaignStatus.RUNNING);
        assertEquals(
                TrainRecoveryPolicy.Directive.COOLDOWN,
                TrainRecoveryPolicy.assess(atCountLimit));
    }

    @Test
    void missingVehicleStackRequestsSafeModeOnlyForRunningCampaigns() {
        assertEquals(
                TrainRecoveryPolicy.Directive.SAFE_MODE,
                TrainRecoveryPolicy.assess(situation(
                        false, true, false, false,
                        0, 0, false, 0, 0, 1, CampaignStatus.RUNNING)));
        assertEquals(
                TrainRecoveryPolicy.Directive.SAFE_MODE,
                TrainRecoveryPolicy.assess(situation(
                        false, true, false, false,
                        0, 0, false, 0, 0, 1, CampaignStatus.SAFE_MODE)));
        assertEquals(
                TrainRecoveryPolicy.Directive.NONE,
                TrainRecoveryPolicy.assess(situation(
                        false, true, false, false,
                        0, 0, false, 0, 0, 1, CampaignStatus.NOT_STARTED)));
        assertEquals(
                TrainRecoveryPolicy.Directive.NONE,
                TrainRecoveryPolicy.assess(situation(
                        false, true, false, false,
                        0, 0, false, 0, 0, 1, CampaignStatus.COMPLETED)));
    }

    @Test
    void trainWithoutSavedReferenceIsNotAssessed() {
        assertEquals(
                TrainRecoveryPolicy.Directive.NONE,
                TrainRecoveryPolicy.assess(situation(
                        true, false, false, false,
                        TrainRecoveryPolicy.MISSING_GRACE_TICKS, 0,
                        false, 0, 0, 1, CampaignStatus.RUNNING)));
    }

    @Test
    void anchorSegmentNeverSkipsAnUnfinishedMissionCheckpoint() {
        assertEquals(8, TrainRecoveryPolicy.anchorSegment(8, null));
        assertEquals(5, TrainRecoveryPolicy.anchorSegment(8, 5));
        assertEquals(0, TrainRecoveryPolicy.anchorSegment(0, 3));
        assertEquals(0, TrainRecoveryPolicy.anchorSegment(-2, null));
        assertEquals(0, TrainRecoveryPolicy.anchorSegment(8, -4));
    }

    @Test
    void rescueIsAllowedOnCooldownBoundaries() {
        assertTrue(TrainRecoveryPolicy.canRescue(0, 0, 1));
        assertTrue(TrainRecoveryPolicy.canRescue(
                1, 7, 7 + TrainRecoveryPolicy.RESCUE_COOLDOWN_DAYS));
        assertFalse(TrainRecoveryPolicy.canRescue(
                1, 7, 7 + TrainRecoveryPolicy.RESCUE_COOLDOWN_DAYS - 1));
        assertFalse(TrainRecoveryPolicy.canRescue(
                TrainRecoveryPolicy.RESCUE_COUNT_LIMIT, 0, 99));
        assertFalse(TrainRecoveryPolicy.canRescue(-1, 0, 1));
    }

    @Test
    void rescueCostsAreAppliedAndClamped() {
        TrainRecoveryPolicy.RescueCosts costs =
                TrainRecoveryPolicy.applyCosts(12, 4);
        assertEquals(
                12 + TrainRecoveryPolicy.RESCUE_ATTENTION_COST,
                costs.attention());
        assertEquals(4 + TrainRecoveryPolicy.RESCUE_THREAT_COST, costs.threat());

        assertEquals(
                PursuitPolicy.MAX_ATTENTION,
                TrainRecoveryPolicy.applyCosts(90, 4).attention());
        assertEquals(100, TrainRecoveryPolicy.applyCosts(12, 99).threat());
    }

    @Test
    void movementAndDangerDistanceThresholds() {
        assertFalse(TrainRecoveryPolicy.isMoving(0.0D));
        assertFalse(TrainRecoveryPolicy.isMoving(TrainRecoveryPolicy.MOVEMENT_EPSILON_SQUARED));
        assertTrue(TrainRecoveryPolicy.isMoving(TrainRecoveryPolicy.MOVEMENT_EPSILON_SQUARED + 0.001D));
        assertTrue(TrainRecoveryPolicy.playerInDanger(TrainRecoveryPolicy.PLAYER_DANGER_RADIUS_SQUARED));
        assertFalse(TrainRecoveryPolicy.playerInDanger(
                TrainRecoveryPolicy.PLAYER_DANGER_RADIUS_SQUARED + 0.01D));
    }
}
