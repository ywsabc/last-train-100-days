package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.mission.MissionStage;
import dev.ywsabc.lasttrain.mission.MissionType;
import dev.ywsabc.lasttrain.server.TrainRecoveryPolicy;
import java.util.UUID;
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
        CampaignSavedData data = recoverableCampaign();
        assertTrue(data.requestTrainRescue());
        assertTrue(data.markTrainRescueVerifying());
        for (int tick = 1; tick <= 12; tick++) {
            data.recordTrainRescueVerificationTick();
        }

        CampaignSavedData loaded = CampaignSavedData.load(
                data.save(new CompoundTag(), null),
                null);
        assertEquals(data.rescueCount(), loaded.rescueCount());
        assertEquals(data.lastRescueDay(), loaded.lastRescueDay());
        assertEquals(data.trainMissingTicks(), loaded.trainMissingTicks());
        assertEquals(data.trainImmobileTicks(), loaded.trainImmobileTicks());
        assertEquals(data.trainRescuePhase(), loaded.trainRescuePhase());
        assertEquals(data.trainRescueTargetSegment(), loaded.trainRescueTargetSegment());
        assertEquals(data.trainRescueVerificationTicks(), loaded.trainRescueVerificationTicks());
        assertEquals(CampaignStatus.SAFE_MODE, loaded.status());
        assertTrue(loaded.safeModeReasons().contains(
                SafeModeReason.TRAIN_RECOVERY_IN_PROGRESS));
    }

    @Test
    void rescueAppliesCostsCooldownAndResetsDetectionTimers() {
        CampaignSavedData data = recoverableCampaign();
        assertEquals(
                TrainRecoveryPolicy.Directive.RESCUE_READY,
                data.observeTrain(true, true, false, false, false, 1));

        assertTrue(data.requestTrainRescue());
        assertEquals(CampaignStatus.SAFE_MODE, data.status());
        assertEquals(TrainRecoveryPolicy.RescuePhase.REQUESTED, data.trainRescuePhase());
        assertTrue(data.markTrainRescueVerifying());
        assertTrue(data.completeTrainRescue());
        assertEquals(1, data.rescueCount());
        assertEquals(data.day(), data.lastRescueDay());
        assertEquals(
                PursuitPolicy.INITIAL_ATTENTION + TrainRecoveryPolicy.RESCUE_ATTENTION_COST,
                data.attention());
        assertEquals(TrainRecoveryPolicy.RESCUE_THREAT_COST, data.threat());
        assertEquals(0, data.trainMissingTicks());
        assertEquals(0, data.trainImmobileTicks());
        assertEquals(CampaignStatus.RUNNING, data.status());
        assertEquals(TrainRecoveryPolicy.RescuePhase.NONE, data.trainRescuePhase());

        assertFalse(data.requestTrainRescue());

        data.advanceDays(TrainRecoveryPolicy.RESCUE_COOLDOWN_DAYS);
        confirmMissingTrain(data);
        assertTrue(data.requestTrainRescue());
        assertTrue(data.markTrainRescueVerifying());
        assertTrue(data.completeTrainRescue());
        assertEquals(2, data.rescueCount());
    }

    @Test
    void rescueRefusesBeforeCampaignStartAndAtCountLimit() {
        CampaignSavedData data = new CampaignSavedData();
        assertFalse(data.requestTrainRescue());

        assertTrue(data.start());
        assertFalse(data.requestTrainRescue());
        data.markStarterTrainAssembled(UUID.randomUUID());
        for (int rescue = 0; rescue < TrainRecoveryPolicy.RESCUE_COUNT_LIMIT; rescue++) {
            confirmMissingTrain(data);
            assertTrue(data.requestTrainRescue());
            assertTrue(data.markTrainRescueVerifying());
            assertTrue(data.completeTrainRescue());
            data.advanceDays(TrainRecoveryPolicy.RESCUE_COOLDOWN_DAYS);
        }
        data.advanceDays(TrainRecoveryPolicy.RESCUE_COOLDOWN_DAYS);
        confirmMissingTrain(data);
        assertFalse(data.requestTrainRescue());
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
    void rescueAnchorIgnoresSupportOptionalAndProposedMissions() {
        // A SUPPORT mission occupies the mainline slot but never blocks the
        // route: it must not restrict where a rescue may return the train.
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        assertTrue(data.advanceRoute(5));
        data.clearMission();
        assertTrue(data.createMission(MissionType.SUPPLY_RECOVERY));
        assertTrue(data.advanceRoute(3));
        assertEquals(8, data.routeSegment());
        assertEquals(8, data.rescueAnchorSegment());

        // An accepted OPTIONAL mission runs beside the main line and must not
        // restrict the rescue either.
        assertTrue(data.proposeOptionalMission(MissionType.RESCUE_SURVIVOR));
        assertEquals(
                CampaignSavedData.ProposalAnswer.ACCEPTED,
                data.acceptProposal(null, null));
        assertEquals(8, data.rescueAnchorSegment());

        // A PROPOSED (not yet accepted) optional offer is not a checkpoint.
        assertTrue(data.proposeOptionalMission(MissionType.SALVAGE_CAR));
        assertEquals(8, data.rescueAnchorSegment());
    }

    @Test
    void readyMainlineMissionsReleasedTheirRescueCheckpoint() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        assertTrue(data.advanceRoute(5));
        data.clearMission();
        assertTrue(data.createMission(MissionType.RAIL_BREAK));
        assertTrue(data.advanceRoute(3));
        assertEquals(8, data.routeSegment());
        assertEquals(5, data.rescueAnchorSegment());

        // The barrier is physically resolved: only ACTIVE route-blocking
        // mainline missions hold the rescue anchor.
        data.activeMission().setObservedProgress(data.activeMission().target());
        assertEquals(MissionStage.READY_TO_TURN_IN, data.activeMission().stage());
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
        assertTrue(loaded.safeModeReasons().contains(
                SafeModeReason.VEHICLE_STACK_UNAVAILABLE));
    }

    @Test
    void legacyUnknownSafeModeReasonCannotBeClearedByHealthyTrainSample() {
        CompoundTag legacy = new CompoundTag();
        legacy.putInt("schema_version", CampaignSavedData.CURRENT_SCHEMA);
        legacy.putString("campaign_id", UUID.randomUUID().toString());
        legacy.putString("status", CampaignStatus.SAFE_MODE.name());
        CampaignSavedData loaded = CampaignSavedData.load(legacy, null);

        assertTrue(loaded.safeModeReasons().contains(SafeModeReason.UNKNOWN));
        loaded.observeTrain(true, true, true, true, false, 1);

        assertEquals(CampaignStatus.SAFE_MODE, loaded.status());
        assertTrue(loaded.safeModeReasons().contains(SafeModeReason.UNKNOWN));
    }

    @Test
    void verificationTimeoutRetriesTheSameDurableAnchor() {
        CampaignSavedData data = recoverableCampaign();
        assertTrue(data.advanceRouteTo(4));
        assertTrue(data.requestTrainRescue());
        int target = data.trainRescueTargetSegment();
        assertTrue(data.markTrainRescueVerifying());

        for (int tick = 0; tick < TrainRecoveryPolicy.VERIFICATION_TIMEOUT_TICKS; tick++) {
            data.recordTrainRescueVerificationTick();
        }
        assertTrue(data.retryTrainRescue());
        assertEquals(TrainRecoveryPolicy.RescuePhase.REQUESTED, data.trainRescuePhase());
        assertEquals(target, data.trainRescueTargetSegment());
        assertEquals(0, data.rescueCount());
        assertEquals(CampaignStatus.SAFE_MODE, data.status());
    }

    @Test
    void rescueCompletionClearsOnlyRecoveryOwnedSafeModeReasons() {
        CampaignSavedData data = recoverableCampaign();
        assertTrue(data.requestTrainRescue());
        data.markTrainRecoveryBackendFailure();
        assertTrue(data.safeModeReasons().contains(
                SafeModeReason.TRAIN_RECOVERY_BACKEND_FAILURE));
        assertTrue(data.markTrainRescueVerifying());
        assertTrue(data.completeTrainRescue());

        assertEquals(CampaignStatus.RUNNING, data.status());
        assertFalse(data.safeModeReasons().contains(
                SafeModeReason.TRAIN_RECOVERY_IN_PROGRESS));
        assertFalse(data.safeModeReasons().contains(
                SafeModeReason.TRAIN_RECOVERY_BACKEND_FAILURE));
    }

    @Test
    void unrelatedSafeModeReasonRefusesPhysicalWorldMutation() {
        CompoundTag legacy = new CompoundTag();
        legacy.putInt("schema_version", CampaignSavedData.CURRENT_SCHEMA);
        legacy.putString("campaign_id", UUID.randomUUID().toString());
        legacy.putString("status", CampaignStatus.SAFE_MODE.name());
        legacy.putBoolean("starter_train_assembled", true);
        legacy.putString("starter_train_sublevel_id", UUID.randomUUID().toString());
        legacy.putInt("train_missing_ticks", TrainRecoveryPolicy.MISSING_GRACE_TICKS);
        CampaignSavedData loaded = CampaignSavedData.load(legacy, null);

        assertTrue(loaded.safeModeReasons().contains(SafeModeReason.UNKNOWN));
        assertFalse(loaded.requestTrainRescue());
        assertEquals(TrainRecoveryPolicy.RescuePhase.NONE, loaded.trainRescuePhase());
    }

    private static CampaignSavedData recoverableCampaign() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        data.markStarterTrainAssembled(UUID.randomUUID());
        confirmMissingTrain(data);
        return data;
    }

    private static void confirmMissingTrain(CampaignSavedData data) {
        for (int tick = 0; tick < TrainRecoveryPolicy.MISSING_GRACE_TICKS; tick++) {
            data.observeTrain(true, true, false, false, false, 1);
        }
    }
}
