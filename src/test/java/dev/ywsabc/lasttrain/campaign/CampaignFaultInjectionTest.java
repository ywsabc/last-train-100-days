package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.mission.ActiveMission;
import dev.ywsabc.lasttrain.mission.MissionPoolPolicy;
import dev.ywsabc.lasttrain.mission.MissionStage;
import dev.ywsabc.lasttrain.mission.MissionType;
import dev.ywsabc.lasttrain.mission.OptionalMissionPolicy;
import dev.ywsabc.lasttrain.mission.RewardOutboxPolicy;
import dev.ywsabc.lasttrain.server.TrainRecoveryPolicy;
import dev.ywsabc.lasttrain.testing.FaultInjection;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Campaign-state degradation paths under injected failures.
 *
 * <p>Every hook routes the injected failure through the production fallback
 * that already exists: corrupt save entries are dropped like malformed
 * records, chunk-unloaded settlement defers to the next tick, a reward crash
 * leaves the PENDING receipt hanging for the outbox retry, and a missing
 * vehicle stack parks the campaign in {@link CampaignStatus#SAFE_MODE}. Each
 * case also proves recovery after the injection is lifted.</p>
 */
class CampaignFaultInjectionTest {
    @AfterEach
    void clearInjections() {
        FaultInjection.clear();
    }

    @Test
    void corruptSaveLoadEntriesAreDroppedWhileInjectedAndReturnAfterUnregister() {
        CampaignSavedData source = new CampaignSavedData();
        assertTrue(source.start());
        for (int index = 0; index < OptionalMissionPolicy.MAX_ACTIVE_OPTIONAL_MISSIONS; index++) {
            assertTrue(source.proposeOptionalMission(MissionType.RESCUE_SURVIVOR));
            assertEquals(
                    CampaignSavedData.ProposalAnswer.ACCEPTED,
                    source.acceptProposal(null, null));
        }
        assertEquals(
                OptionalMissionPolicy.MAX_ACTIVE_OPTIONAL_MISSIONS,
                source.optionalMissions().size());
        CompoundTag tag = source.save(new CompoundTag(), null);

        FaultInjection.register(FaultInjection.FailurePoint.SAVE_LOAD_CORRUPT_ENTRY, 2);
        CampaignSavedData partiallyLoaded = CampaignSavedData.load(tag, null);
        assertEquals(1, partiallyLoaded.optionalMissions().size());
        assertEquals(source.day(), partiallyLoaded.day());
        assertEquals(source.status(), partiallyLoaded.status());
        assertEquals(source.routeSegment(), partiallyLoaded.routeSegment());
        assertEquals(source.threat(), partiallyLoaded.threat());

        FaultInjection.unregister(FaultInjection.FailurePoint.SAVE_LOAD_CORRUPT_ENTRY);
        CampaignSavedData fullyLoaded = CampaignSavedData.load(tag, null);
        assertEquals(
                OptionalMissionPolicy.MAX_ACTIVE_OPTIONAL_MISSIONS,
                fullyLoaded.optionalMissions().size());
    }

    @Test
    void corruptReceiptEntriesAreDroppedWithoutTouchingOtherLoadedState() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema_version", CampaignSavedData.CURRENT_SCHEMA);
        tag.putString("campaign_id", UUID.randomUUID().toString());
        tag.putString("status", CampaignStatus.RUNNING.name());
        tag.putInt("day", 5);
        ListTag receipts = new ListTag();
        for (int index = 0; index < 3; index++) {
            CompoundTag receipt = new CompoundTag();
            receipt.putString("mission_id", UUID.randomUUID().toString());
            receipt.putString("type", MissionType.RESCUE_SURVIVOR.serializedName());
            receipt.putString("state", RewardOutboxPolicy.ReceiptState.PENDING.name());
            receipts.add(receipt);
        }
        tag.put("reward_receipts", receipts);

        FaultInjection.register(FaultInjection.FailurePoint.SAVE_LOAD_CORRUPT_ENTRY, 2);
        CampaignSavedData partiallyLoaded = CampaignSavedData.load(tag, null);
        assertEquals(1, partiallyLoaded.rewardReceipts().size());
        assertEquals(5, partiallyLoaded.day());
        assertEquals(CampaignStatus.RUNNING, partiallyLoaded.status());

        FaultInjection.clear();
        assertEquals(3, CampaignSavedData.load(tag, null).rewardReceipts().size());
    }

    @Test
    void chunkUnloadedSettlementDefersAndSettlesOnTheNextTick() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        assertTrue(data.proposeOptionalMission(MissionType.SALVAGE_CAR));
        assertEquals(
                CampaignSavedData.ProposalAnswer.ACCEPTED,
                data.acceptProposal(null, null));
        UUID missionId = data.optionalMissions().get(0).id();

        // Advance past the 7-day salvage grace deadline in absolute ticks on
        // the accelerated clock, staying far from the day-100 finale.
        FastForwardMode.enable(data, CampaignSavedData.DEFAULT_ACTIVE_TICKS_PER_DAY);
        long deadline = data.optionalMission(missionId).orElseThrow().deadlineTick();
        FastForwardMode.advanceActiveTicks(data, deadline - data.totalActiveTicks() + 1);
        assertTrue(
                OptionalMissionPolicy.shouldTimeout(
                        data.optionalMission(missionId).orElseThrow().deadlineTick(),
                        data.totalActiveTicks()));

        // The injected "site chunk still unloaded" tick settles nothing and
        // strands no state; the next tick settles the expired mission.
        FaultInjection.register(FaultInjection.FailurePoint.MISSION_SETTLE_CHUNK_UNLOADED, 1);
        assertEquals(0, data.settleOptionalTimeouts());
        assertEquals(1, data.optionalMissions().size());
        assertEquals(
                MissionStage.ACTIVE,
                data.optionalMission(missionId).orElseThrow().stage());

        assertEquals(1, data.settleOptionalTimeouts());
        assertTrue(data.optionalMissions().isEmpty());
        assertTrue(data.missionHistory().stream().anyMatch(entry ->
                entry.type() == MissionType.SALVAGE_CAR
                        && entry.outcome() == MissionPoolPolicy.Outcome.FAILED));
    }

    @Test
    void rewardPersistFailureHangsThePendingReceiptAndTheOutboxRetries() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        assertTrue(data.proposeOptionalMission(MissionType.RESCUE_SURVIVOR));
        assertEquals(
                CampaignSavedData.ProposalAnswer.ACCEPTED,
                data.acceptProposal(null, null));
        ActiveMission mission = data.optionalMissions().get(0);
        UUID missionId = mission.id();
        assertTrue(data.recordSurvivorRescued(missionId));
        assertEquals(MissionStage.READY_TO_TURN_IN, mission.stage());
        assertTrue(data.completeOptionalMission(missionId));
        assertEquals(
                RewardOutboxPolicy.ReceiptState.PENDING,
                data.rewardReceipt(missionId).orElseThrow().state());

        // The injected mid-persist crash leaves the durable PENDING receipt
        // and the REWARD_PENDING mission intact: no loss, no double grant.
        FaultInjection.register(FaultInjection.FailurePoint.REWARD_PERSIST, 1);
        assertFalse(data.markRewardClaimed(missionId));
        assertEquals(
                RewardOutboxPolicy.ReceiptState.PENDING,
                data.rewardReceipt(missionId).orElseThrow().state());
        assertEquals(
                MissionStage.REWARD_PENDING,
                data.optionalMission(missionId).orElseThrow().stage());
        assertEquals(1, data.optionalMissions().size());

        // The next outbox dispatch tick retries and completes the entry.
        assertTrue(data.markRewardClaimed(missionId));
        assertEquals(
                RewardOutboxPolicy.ReceiptState.CLAIMED,
                data.rewardReceipt(missionId).orElseThrow().state());
        assertTrue(data.optionalMissions().isEmpty());
        assertTrue(data.missionHistory().stream().anyMatch(entry ->
                entry.type() == MissionType.RESCUE_SURVIVOR
                        && entry.outcome() == MissionPoolPolicy.Outcome.COMPLETED));
    }

    @Test
    void missingVehicleStackParksTheCampaignInSafeModeAndRecovers() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        assertEquals(CampaignStatus.RUNNING, data.status());

        FaultInjection.register(FaultInjection.FailurePoint.VEHICLE_STACK_UNAVAILABLE, 2);
        assertEquals(
                TrainRecoveryPolicy.Directive.SAFE_MODE,
                data.observeTrain(true, true, true, true, false, 1));
        assertEquals(CampaignStatus.SAFE_MODE, data.status());
        assertEquals(
                TrainRecoveryPolicy.Directive.SAFE_MODE,
                data.observeTrain(true, true, true, true, false, 1));
        assertEquals(CampaignStatus.SAFE_MODE, data.status());

        // Injection exhausted: a healthy stack returns the campaign to RUNNING.
        assertEquals(
                TrainRecoveryPolicy.Directive.NONE,
                data.observeTrain(true, true, true, true, false, 1));
        assertEquals(CampaignStatus.RUNNING, data.status());

        FaultInjection.register(FaultInjection.FailurePoint.VEHICLE_STACK_UNAVAILABLE, 1);
        assertEquals(
                TrainRecoveryPolicy.Directive.SAFE_MODE,
                data.observeTrain(true, true, true, true, false, 1));
        assertEquals(CampaignStatus.SAFE_MODE, data.status());

        FaultInjection.unregister(FaultInjection.FailurePoint.VEHICLE_STACK_UNAVAILABLE);
        assertEquals(
                TrainRecoveryPolicy.Directive.NONE,
                data.observeTrain(true, true, true, true, false, 1));
        assertEquals(CampaignStatus.RUNNING, data.status());
    }
}
