package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.mission.MissionType;
import dev.ywsabc.lasttrain.server.TrainRecoveryPolicy;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class CampaignSavedDataTrainRecoveryTest {
    @Test
    void missingTrainTicksCountOnlyWhilePlayersAreOnline() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());

        TrainRecoveryPolicy.Directive offline = data.observeTrain(
                true, true, false, false, false, 0);
        assertEquals(TrainRecoveryPolicy.Directive.OBSERVING, offline);
        assertEquals(0, data.trainMissingTicks());

        TrainRecoveryPolicy.Directive directive = TrainRecoveryPolicy.Directive.NONE;
        for (int tick = 1; tick < TrainRecoveryPolicy.MISSING_GRACE_TICKS; tick++) {
            directive = data.observeTrain(true, true, false, false, false, 1);
        }
        assertEquals(TrainRecoveryPolicy.Directive.OBSERVING, directive);
        assertEquals(
                TrainRecoveryPolicy.MISSING_GRACE_TICKS - 1,
                data.trainMissingTicks());

        // The observation that reaches the grace boundary still assesses the
        // counter before incrementing it.
        directive = data.observeTrain(true, true, false, false, false, 1);
        assertEquals(TrainRecoveryPolicy.Directive.OBSERVING, directive);
        assertEquals(TrainRecoveryPolicy.MISSING_GRACE_TICKS, data.trainMissingTicks());

        directive = data.observeTrain(true, true, false, false, false, 1);
        assertEquals(TrainRecoveryPolicy.Directive.RESCUE_READY, directive);
    }

    @Test
    void recoveryFieldsRoundTripThroughNbt() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        assertTrue(data.applyTrainRescue());
        for (int tick = 1; tick <= 40; tick++) {
            data.observeTrain(true, true, true, false, false, 1);
        }

        CampaignSavedData loaded = CampaignSavedData.load(
                data.save(new CompoundTag(), null),
                null);
        assertEquals(data.rescueCount(), loaded.rescueCount());
        assertEquals(data.lastRescueDay(), loaded.lastRescueDay());
        assertEquals(data.trainMissingTicks(), loaded.trainMissingTicks());
        assertEquals(data.trainImmobileTicks(), loaded.trainImmobileTicks());
    }

    @Test
    void rescueAppliesCostsCooldownAndResetsDetectionTimers() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        for (int tick = 1; tick <= TrainRecoveryPolicy.MISSING_GRACE_TICKS; tick++) {
            data.observeTrain(true, true, false, false, false, 1);
        }
        assertEquals(
                TrainRecoveryPolicy.Directive.RESCUE_READY,
                data.observeTrain(true, true, false, false, false, 1));

        assertTrue(data.applyTrainRescue());
        assertEquals(1, data.rescueCount());
        assertEquals(data.day(), data.lastRescueDay());
        assertEquals(
                PursuitPolicy.INITIAL_ATTENTION + TrainRecoveryPolicy.RESCUE_ATTENTION_COST,
                data.attention());
        assertEquals(TrainRecoveryPolicy.RESCUE_THREAT_COST, data.threat());
        assertEquals(0, data.trainMissingTicks());
        assertEquals(0, data.trainImmobileTicks());

        assertFalse(data.applyTrainRescue());

        data.advanceDays(TrainRecoveryPolicy.RESCUE_COOLDOWN_DAYS);
        assertTrue(data.applyTrainRescue());
        assertEquals(2, data.rescueCount());
    }

    @Test
    void rescueRefusesBeforeCampaignStartAndAtCountLimit() {
        CampaignSavedData data = new CampaignSavedData();
        assertFalse(data.applyTrainRescue());

        assertTrue(data.start());
        for (int rescue = 0; rescue < TrainRecoveryPolicy.RESCUE_COUNT_LIMIT; rescue++) {
            assertTrue(data.applyTrainRescue());
            data.advanceDays(TrainRecoveryPolicy.RESCUE_COOLDOWN_DAYS);
        }
        data.advanceDays(TrainRecoveryPolicy.RESCUE_COOLDOWN_DAYS);
        assertFalse(data.applyTrainRescue());
        assertEquals(TrainRecoveryPolicy.RESCUE_COUNT_LIMIT, data.rescueCount());
    }

    @Test
    void rescueAnchorNeverSkipsAnActiveMissionCheckpoint() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        assertTrue(data.advanceRoute(5));
        data.clearMission();
        assertTrue(data.createMission(MissionType.RAIL_BREAK));
        assertTrue(data.advanceRoute(3));
        assertEquals(8, data.routeSegment());
        assertEquals(5, data.rescueAnchorSegment());

        data.clearMission();
        assertEquals(8, data.rescueAnchorSegment());
    }

    @Test
    void missingVehicleStackMovesCampaignToSafeModeAndBack() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());

        assertEquals(
                TrainRecoveryPolicy.Directive.SAFE_MODE,
                data.observeTrain(false, true, false, false, false, 1));
        assertEquals(CampaignStatus.SAFE_MODE, data.status());

        assertEquals(CampaignSavedData.TickOutcome.NONE, data.tick());
        assertEquals(1, data.day());

        assertEquals(
                TrainRecoveryPolicy.Directive.OBSERVING,
                data.observeTrain(true, true, false, false, false, 1));
        assertEquals(CampaignStatus.RUNNING, data.status());
    }

    @Test
    void detectionTimersResetWhenTheTrainRecovers() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        for (int tick = 1; tick <= 50; tick++) {
            data.observeTrain(true, true, false, false, false, 1);
        }
        assertEquals(50, data.trainMissingTicks());

        data.observeTrain(true, true, true, true, false, 1);
        assertEquals(0, data.trainMissingTicks());
        assertEquals(0, data.trainImmobileTicks());

        for (int tick = 1; tick <= 90; tick++) {
            data.observeTrain(true, true, true, false, false, 1);
        }
        assertEquals(0, data.trainMissingTicks());
        assertEquals(90, data.trainImmobileTicks());
    }

    @Test
    void trainWithoutReferenceIsNotCounted() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        assertEquals(
                TrainRecoveryPolicy.Directive.NONE,
                data.observeTrain(true, false, false, false, false, 1));
        assertEquals(0, data.trainMissingTicks());
        assertEquals(0, data.trainImmobileTicks());
    }

    @Test
    void legacySavesWithoutRecoveryKeysUseSafeDefaults() {
        CompoundTag old = new CompoundTag();
        old.putInt("schema_version", CampaignSavedData.CURRENT_SCHEMA);
        old.putString("campaign_id", "00000000-0000-0000-0000-000000000000");
        old.putString("status", CampaignStatus.NOT_STARTED.name());
        old.putInt("route_segment", 9);

        CampaignSavedData loaded = CampaignSavedData.load(old, null);

        assertEquals(0, loaded.rescueCount());
        assertEquals(0, loaded.lastRescueDay());
        assertEquals(0, loaded.trainMissingTicks());
        assertEquals(0, loaded.trainImmobileTicks());
    }

    @Test
    void safeModeRoundTripsThroughNbt() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        data.observeTrain(false, true, false, false, false, 1);
        assertEquals(CampaignStatus.SAFE_MODE, data.status());

        CampaignSavedData loaded = CampaignSavedData.load(
                data.save(new CompoundTag(), null),
                null);
        assertEquals(CampaignStatus.SAFE_MODE, loaded.status());
    }
}
