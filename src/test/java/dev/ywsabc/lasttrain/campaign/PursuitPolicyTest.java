package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PursuitPolicyTest {
    @Test
    void parkedCampaignTicksRaiseAttentionAndCloseTheHordeDistance() {
        PursuitPolicy.Sample previous = PursuitPolicy.Sample.initial();
        PursuitPolicy.Sample next = PursuitPolicy.sample(
                previous.attention(),
                previous.pursuitDistance(),
                previous.routeSegment(),
                previous.routeSegment(),
                1);

        assertEquals(
                previous.attention() + PursuitPolicy.PARKED_ATTENTION_GROWTH_PER_SAMPLE,
                next.attention());
        assertEquals(
                previous.pursuitDistance() - PursuitPolicy.PARKED_PURSUIT_DRAIN_PER_SAMPLE,
                next.pursuitDistance());
    }

    @Test
    void forwardRouteProgressCoolsAttentionAndExtendsPursuitDistance() {
        int previousAttention = 40;
        int previousPursuit = 2_000;
        int segments = 2;
        int previousRoute = 5;

        PursuitPolicy.Sample next = PursuitPolicy.sample(
                previousAttention,
                previousPursuit,
                previousRoute + segments,
                previousRoute,
                1);

        assertEquals(
                previousAttention
                        - segments * PursuitPolicy.MOVING_ATTENTION_COOLING_PER_SEGMENT,
                next.attention());
        assertEquals(
                previousPursuit + segments * PursuitPolicy.PURSUIT_GAIN_PER_SEGMENT,
                next.pursuitDistance());
    }

    @Test
    void attentionNeverCoolsBelowTheChapterFloor() {
        int floor = PursuitPolicy.minAttention(40);
        PursuitPolicy.Sample next = PursuitPolicy.sample(
                floor,
                1_000,
                12,
                10,
                40);

        assertEquals(floor, next.attention());
    }

    @Test
    void pursuitGainAndLossAreClampedToTheAbstractRange() {
        assertEquals(
                PursuitPolicy.MAX_PURSUIT_DISTANCE,
                PursuitPolicy.sample(
                        30,
                        PursuitPolicy.MAX_PURSUIT_DISTANCE - 1,
                        100,
                        50,
                        1).pursuitDistance());
        assertEquals(
                0,
                PursuitPolicy.sample(
                        30,
                        1,
                        1,
                        1,
                        1).pursuitDistance());
        assertEquals(
                PursuitPolicy.MAX_ATTENTION,
                PursuitPolicy.sample(
                        PursuitPolicy.MAX_ATTENTION,
                        5_000,
                        1,
                        1,
                        1).attention());
    }

    @Test
    void attentionLevelsUseStableDescriptiveBands() {
        assertEquals(
                PursuitPolicy.AttentionLevel.QUIET,
                PursuitPolicy.attentionLevel(0));
        assertEquals(
                PursuitPolicy.AttentionLevel.QUIET,
                PursuitPolicy.attentionLevel(19));
        assertEquals(
                PursuitPolicy.AttentionLevel.WATCHED,
                PursuitPolicy.attentionLevel(20));
        assertEquals(
                PursuitPolicy.AttentionLevel.GATHERING,
                PursuitPolicy.attentionLevel(45));
        assertEquals(
                PursuitPolicy.AttentionLevel.SIEGE,
                PursuitPolicy.attentionLevel(70));
        assertEquals(
                PursuitPolicy.AttentionLevel.OUT_OF_CONTROL,
                PursuitPolicy.attentionLevel(85));
        assertEquals(
                PursuitPolicy.AttentionLevel.OUT_OF_CONTROL,
                PursuitPolicy.attentionLevel(100));
    }

    @Test
    void gunfireAndExplosionsCreateImmediatePressure() {
        assertEquals(
                30 + PursuitPolicy.GUNFIRE_ATTENTION,
                PursuitPolicy.afterGunfireAttention(30));
        assertEquals(
                1_000 - PursuitPolicy.GUNFIRE_PURSUIT_COST,
                PursuitPolicy.afterGunfirePursuit(1_000));
        assertEquals(
                PursuitPolicy.MAX_ATTENTION,
                PursuitPolicy.afterExplosionAttention(PursuitPolicy.MAX_ATTENTION));
        assertEquals(
                0,
                PursuitPolicy.afterExplosionPursuit(1));
    }

    @Test
    void siegeTriggersOnlyForRunningPreFinaleCampaignsWithoutActiveMission() {
        assertTrue(PursuitPolicy.shouldTriggerSiege(
                CampaignStatus.RUNNING, 50, 0, false));
        assertFalse(PursuitPolicy.shouldTriggerSiege(
                CampaignStatus.RUNNING, 50, 0, true));
        assertFalse(PursuitPolicy.shouldTriggerSiege(
                CampaignStatus.RUNNING, CampaignSavedData.FINAL_DAY, 0, false));
        assertFalse(PursuitPolicy.shouldTriggerSiege(
                CampaignStatus.NOT_STARTED, 50, 0, false));
        assertFalse(PursuitPolicy.shouldTriggerSiege(
                CampaignStatus.COMPLETED, 50, 0, false));
    }
}
