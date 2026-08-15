package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.mission.ActiveMission;
import dev.ywsabc.lasttrain.mission.MissionPoolPolicy;
import dev.ywsabc.lasttrain.mission.MissionStage;
import dev.ywsabc.lasttrain.mission.MissionType;
import dev.ywsabc.lasttrain.mission.OptionalMissionPolicy;
import dev.ywsabc.lasttrain.mission.RewardOutboxPolicy;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

class CampaignSavedDataOptionalMissionTest {
    @Test
    void proposalAcceptsIntoTheOptionalListAndReleasesTheSlot() {
        CampaignSavedData data = started();
        assertTrue(data.proposeOptionalMission(MissionType.RESCUE_SURVIVOR));
        ActiveMission proposal = data.proposedMission();
        assertNotNull(proposal);
        assertEquals(MissionStage.PROPOSED, proposal.stage());

        assertEquals(
                CampaignSavedData.ProposalAnswer.ACCEPTED,
                data.acceptProposal(proposal.id(), proposal.revision()));
        assertNull(data.proposedMission());
        assertEquals(MissionStage.ACTIVE, data.optionalMission(proposal.id()).orElseThrow().stage());

        // The slot is free again and a mainline mission still runs beside it.
        assertTrue(data.proposeOptionalMission(MissionType.SALVAGE_CAR));
        assertTrue(data.createMission(MissionType.RAIL_BREAK));
        assertNotNull(data.activeMission());
    }

    @Test
    void rejectionSkipsTheProposalAndRecordsHistory() {
        CampaignSavedData data = started();
        assertTrue(data.proposeOptionalMission(MissionType.SALVAGE_CAR));
        ActiveMission proposal = data.proposedMission();

        assertEquals(
                CampaignSavedData.ProposalAnswer.SKIPPED,
                data.rejectProposal(proposal.id(), proposal.revision()));

        assertNull(data.proposedMission());
        assertTrue(data.optionalMissions().isEmpty());
        assertEquals(
                MissionPoolPolicy.Outcome.SKIPPED,
                data.missionHistory().get(0).outcome());
        assertEquals(MissionType.SALVAGE_CAR, data.missionHistory().get(0).type());
    }

    @Test
    void staleIdsAndRevisionsAreRefusedBeforeAnyChange() {
        CampaignSavedData data = started();
        assertEquals(CampaignSavedData.ProposalAnswer.NOT_FOUND, data.acceptProposal(null, null));
        assertTrue(data.proposeOptionalMission(MissionType.RESCUE_SURVIVOR));
        ActiveMission proposal = data.proposedMission();
        UUID wrongId = UUID.randomUUID();

        assertEquals(
                CampaignSavedData.ProposalAnswer.STALE_ID,
                data.acceptProposal(wrongId, null));
        assertEquals(
                CampaignSavedData.ProposalAnswer.STALE_REVISION,
                data.acceptProposal(proposal.id(), proposal.revision() + 5));
        assertNotNull(data.proposedMission());
        assertEquals(MissionStage.PROPOSED, data.proposedMission().stage());

        // A revision older than the live proposal's revision is stale (a
        // loaded save can carry a proposal that already transitioned).
        CompoundTag save = data.save(new CompoundTag(), null);
        ActiveMission advancedProposal = ActiveMission.createProposal(
                MissionType.SALVAGE_CAR,
                data.day(),
                data.routeSegment(),
                0L);
        advancedProposal.transitionTo(MissionStage.PROPOSED);
        advancedProposal.transitionTo(MissionStage.ACTIVE);
        advancedProposal.transitionTo(MissionStage.PROPOSED);
        save.put("proposed_mission", advancedProposal.save(null));
        CampaignSavedData advanced = CampaignSavedData.load(save, null);
        assertEquals(
                CampaignSavedData.ProposalAnswer.STALE_REVISION,
                advanced.acceptProposal(advancedProposal.id(), 0));
        assertEquals(
                CampaignSavedData.ProposalAnswer.ACCEPTED,
                advanced.acceptProposal(advancedProposal.id(), advancedProposal.revision()));
    }

    @Test
    void optionalMissionSlotBudgetIsEnforced() {
        CampaignSavedData data = started();
        for (int index = 0; index < OptionalMissionPolicy.MAX_ACTIVE_OPTIONAL_MISSIONS; index++) {
            assertTrue(data.proposeOptionalMission(MissionType.RESCUE_SURVIVOR));
            ActiveMission proposal = data.proposedMission();
            assertEquals(
                    CampaignSavedData.ProposalAnswer.ACCEPTED,
                    data.acceptProposal(proposal.id(), null));
        }
        assertFalse(data.proposeOptionalMission(MissionType.SALVAGE_CAR));
        assertEquals(CampaignSavedData.ProposalAnswer.NOT_FOUND, data.acceptProposal(UUID.randomUUID(), null));

        // A save with three active optionals and a proposal answers SLOTS_FULL.
        ActiveMission extra = ActiveMission.createProposal(
                MissionType.SALVAGE_CAR,
                data.day(),
                data.routeSegment(),
                0L);
        CompoundTag withProposal = data.save(new CompoundTag(), null);
        withProposal.put("proposed_mission", extra.save(null));
        CampaignSavedData loaded = CampaignSavedData.load(withProposal, null);
        assertEquals(
                CampaignSavedData.ProposalAnswer.SLOTS_FULL,
                loaded.acceptProposal(extra.id(), null));
    }

    @Test
    void mainlineMissionsRefuseTheSameTypeBackToBack() {
        CampaignSavedData data = started();
        assertTrue(data.createMission(MissionType.STATION_POWER));
        data.addMissionProgress(data.activeMission().target());
        assertTrue(data.turnInMission());

        assertFalse(data.createMission(MissionType.STATION_POWER));
        assertTrue(data.createMission(MissionType.RAIL_BREAK));
    }

    @Test
    void consecutiveZombieBlockadesAreHardRefusedEvenFromPressure() {
        CampaignSavedData data = started();
        assertTrue(data.createMission(MissionType.ZOMBIE_BLOCKADE));
        data.addMissionProgress(data.activeMission().target());
        assertTrue(data.turnInMission());

        assertFalse(data.createMission(MissionType.ZOMBIE_BLOCKADE));
        assertEquals(MissionType.ZOMBIE_BLOCKADE, data.lastMainMissionType().orElseThrow());

        // Another mainline mission between two blockades reopens the gate.
        assertTrue(data.createMission(MissionType.STATION_GATE));
        data.addMissionProgress(data.activeMission().target());
        assertTrue(data.turnInMission());
        assertTrue(data.createMission(MissionType.ZOMBIE_BLOCKADE));
    }

    @Test
    void optionalFailureSkipAndRejectionNeverBlockTheMainlineSlot() {
        CampaignSavedData data = started();
        assertTrue(data.proposeOptionalMission(MissionType.RESCUE_SURVIVOR));
        UUID id = data.proposedMission().id();
        assertEquals(CampaignSavedData.ProposalAnswer.ACCEPTED, data.acceptProposal(id, null));

        assertEquals(CampaignSavedData.OptionalSkipResult.SKIPPED, data.skipOptionalMission(id, null));
        assertTrue(data.createMission(MissionType.SUPPLY_RECOVERY));
    }

    @Test
    void skipDemandsAnIdWhenSeveralOptionalsAreActive() {
        CampaignSavedData data = started();
        assertTrue(data.proposeOptionalMission(MissionType.RESCUE_SURVIVOR));
        UUID first = data.proposedMission().id();
        data.acceptProposal(first, null);
        assertTrue(data.proposeOptionalMission(MissionType.SALVAGE_CAR));
        UUID second = data.proposedMission().id();
        data.acceptProposal(second, null);

        assertEquals(CampaignSavedData.OptionalSkipResult.AMBIGUOUS, data.skipOptionalMission(null, null));
        assertEquals(CampaignSavedData.OptionalSkipResult.SKIPPED, data.skipOptionalMission(first, null));
        assertEquals(
                CampaignSavedData.OptionalSkipResult.NOT_FOUND,
                data.skipOptionalMission(UUID.randomUUID(), null));
    }

    @Test
    void salvageRepairsDeduplicateThroughTheSavedData() {
        CampaignSavedData data = started();
        assertTrue(data.proposeOptionalMission(MissionType.SALVAGE_CAR));
        UUID id = data.proposedMission().id();
        data.acceptProposal(id, null);

        assertTrue(data.recordSalvageRepair(id, 0));
        assertFalse(data.recordSalvageRepair(id, 0));
        assertFalse(data.recordSalvageRepair(id, -1));
        assertFalse(data.recordSalvageRepair(id, data.optionalMission(id).orElseThrow().target()));
        assertEquals(1, data.optionalMission(id).orElseThrow().progress());
    }

    @Test
    void rescueCompletionFlowsThroughTheRewardOutbox() {
        CampaignSavedData data = started();
        assertTrue(data.proposeOptionalMission(MissionType.RESCUE_SURVIVOR));
        UUID id = data.proposedMission().id();
        data.acceptProposal(id, null);

        assertTrue(data.recordSurvivorRescued(id));
        assertEquals(MissionStage.READY_TO_TURN_IN, data.optionalMission(id).orElseThrow().stage());

        assertTrue(data.completeOptionalMission(id));
        assertEquals(MissionStage.REWARD_PENDING, data.optionalMission(id).orElseThrow().stage());
        assertEquals(
                RewardOutboxPolicy.ReceiptState.PENDING,
                data.rewardReceipt(id).orElseThrow().state());

        assertTrue(data.markRewardClaimed(id));
        assertTrue(data.optionalMissions().isEmpty());
        assertEquals(
                RewardOutboxPolicy.ReceiptState.CLAIMED,
                data.rewardReceipt(id).orElseThrow().state());
        assertEquals(
                MissionPoolPolicy.Outcome.COMPLETED,
                data.missionHistory().get(data.missionHistory().size() - 1).outcome());
    }

    @Test
    void tickDeadlinesTimeOutProposalsAndActiveMissionsWithoutChunkAccess() {
        CampaignSavedData data = started();
        assertTrue(data.proposeOptionalMission(MissionType.RESCUE_SURVIVOR));
        UUID rescue = data.proposedMission().id();
        data.acceptProposal(rescue, null);
        long deadline = data.optionalMission(rescue).orElseThrow().deadlineTick();
        assertEquals(
                3L * OptionalMissionPolicy.TICKS_PER_DAY,
                deadline - data.optionalMission(rescue).orElseThrow().createdTick());

        // One tick short of the deadline: nothing settles, no world involved.
        for (long tick = 0; tick < 3L * OptionalMissionPolicy.TICKS_PER_DAY - 1; tick++) {
            data.tick();
            if (data.activeMission() != null) {
                data.clearMission();
            }
        }
        assertEquals(0, data.settleOptionalTimeouts());
        assertFalse(data.optionalMissions().isEmpty());

        // The deadline tick settles the mission as failed.
        data.tick();
        if (data.activeMission() != null) {
            data.clearMission();
        }
        assertEquals(1, data.settleOptionalTimeouts());
        assertTrue(data.optionalMissions().isEmpty());
        assertEquals(
                MissionPoolPolicy.Outcome.FAILED,
                data.missionHistory().get(data.missionHistory().size() - 1).outcome());
    }

    @Test
    void proposedMissionsAlsoExpireByTicks() {
        CampaignSavedData data = started();
        assertTrue(data.proposeOptionalMission(MissionType.SALVAGE_CAR));
        for (long tick = 0; tick < 7L * OptionalMissionPolicy.TICKS_PER_DAY + 1; tick++) {
            data.tick();
            if (data.activeMission() != null) {
                data.clearMission();
            }
        }
        assertEquals(1, data.settleOptionalTimeouts());
        assertNull(data.proposedMission());
        assertEquals(
                MissionPoolPolicy.Outcome.SKIPPED,
                data.missionHistory().get(data.missionHistory().size() - 1).outcome());
    }

    @Test
    void dayNinetyNineProposalsSettleOnDayOneHundred() {
        CampaignSavedData data = runningAtDay(99);

        assertTrue(data.proposeOptionalMission(MissionType.RESCUE_SURVIVOR));
        UUID rescue = data.proposedMission().id();
        data.acceptProposal(rescue, null);
        assertTrue(data.proposeOptionalMission(MissionType.SALVAGE_CAR));
        UUID salvage = data.proposedMission().id();

        data.advanceDays(1);
        assertEquals(CampaignSavedData.FINAL_DAY, data.day());
        assertEquals(CampaignSavedData.TickOutcome.FINALE_MISSION_STARTED, data.tick());

        assertNull(data.proposedMission());
        assertTrue(data.optionalMissions().isEmpty());
        assertTrue(data.isFinaleMission(data.activeMission()));
        assertTrue(data.missionHistory().stream().anyMatch(entry ->
                entry.type() == MissionType.SALVAGE_CAR
                        && entry.outcome() == MissionPoolPolicy.Outcome.SKIPPED));
        assertTrue(data.missionHistory().stream().anyMatch(entry ->
                entry.type() == MissionType.RESCUE_SURVIVOR
                        && entry.outcome() == MissionPoolPolicy.Outcome.FAILED));
    }

    @Test
    void finaleNeverStartsWhileAnUnansweredProposalBlocksTheDay() {
        CampaignSavedData data = runningAtDay(99);
        assertTrue(data.proposeOptionalMission(MissionType.SALVAGE_CAR));
        data.advanceDays(1);

        assertEquals(CampaignSavedData.TickOutcome.FINALE_MISSION_STARTED, data.tick());
        assertNull(data.proposedMission());
        assertNotNull(data.activeMission());
        assertTrue(data.isFinaleMission(data.activeMission()));
    }

    @Test
    void rewardPendingMissionsSurviveFinaleSettlement() {
        CampaignSavedData data = runningAtDay(99);
        assertTrue(data.proposeOptionalMission(MissionType.RESCUE_SURVIVOR));
        UUID id = data.proposedMission().id();
        data.acceptProposal(id, null);
        data.recordSurvivorRescued(id);
        assertTrue(data.completeOptionalMission(id));
        assertEquals(MissionStage.REWARD_PENDING, data.optionalMission(id).orElseThrow().stage());

        data.advanceDays(1);
        assertEquals(CampaignSavedData.TickOutcome.FINALE_MISSION_STARTED, data.tick());

        assertEquals(MissionStage.REWARD_PENDING, data.optionalMission(id).orElseThrow().stage());
        assertEquals(
                RewardOutboxPolicy.ReceiptState.PENDING,
                data.rewardReceipt(id).orElseThrow().state());
    }

    @Test
    void terminalMissionsQueueBoundedIdempotentSiteCleanups() {
        CampaignSavedData data = started();
        assertTrue(data.proposeOptionalMission(MissionType.SALVAGE_CAR));
        UUID id = data.proposedMission().id();
        data.acceptProposal(id, null);
        data.assignOptionalMissionSite(id, new BlockPos(10, 64, 20));

        assertEquals(CampaignSavedData.OptionalSkipResult.SKIPPED, data.skipOptionalMission(id, null));
        assertEquals(1, data.pendingSiteCleanups().size());
        CampaignSavedData.PendingSiteCleanup cleanup = data.pendingSiteCleanups().get(0);
        assertEquals(id, cleanup.missionId());
        assertEquals(new BlockPos(10, 64, 20), cleanup.site());

        assertTrue(data.completePendingCleanup(id));
        assertTrue(data.pendingSiteCleanups().isEmpty());
        assertFalse(data.completePendingCleanup(id));
    }

    @Test
    void rewardReceiptsAreBoundedAndPreferEvictingClaimedEntries() {
        CampaignSavedData data = started();
        UUID firstPending = null;
        for (int index = 0; index < RewardOutboxPolicy.MAX_RECEIPTS + 8; index++) {
            assertTrue(data.proposeOptionalMission(MissionType.RESCUE_SURVIVOR));
            UUID id = data.proposedMission().id();
            data.acceptProposal(id, null);
            data.recordSurvivorRescued(id);
            assertTrue(data.completeOptionalMission(id));
            if (index == 0) {
                firstPending = id;
            }
            assertTrue(data.markRewardClaimed(id));
        }
        assertEquals(RewardOutboxPolicy.MAX_RECEIPTS, data.rewardReceipts().size());

        // The newest completion evicts the oldest claimed receipt, never a
        // pending one.
        assertTrue(data.proposeOptionalMission(MissionType.RESCUE_SURVIVOR));
        UUID pending = data.proposedMission().id();
        data.acceptProposal(pending, null);
        data.recordSurvivorRescued(pending);
        assertTrue(data.completeOptionalMission(pending));
        assertEquals(RewardOutboxPolicy.MAX_RECEIPTS, data.rewardReceipts().size());
        assertEquals(
                RewardOutboxPolicy.ReceiptState.PENDING,
                data.rewardReceipt(pending).orElseThrow().state());
        assertTrue(data.rewardReceipt(firstPending).isEmpty());
    }

    @Test
    void loadingCapsReceiptsAndProtectsPendingEntries() {
        CompoundTag tag = new CompoundTag();
        ListTag receipts = new ListTag();
        UUID protectedPending = UUID.randomUUID();
        for (int index = 0; index < 44; index++) {
            receipts.add(receiptTag(index == 0 ? protectedPending : UUID.randomUUID(), "PENDING"));
        }
        for (int index = 44; index < 300; index++) {
            receipts.add(receiptTag(UUID.randomUUID(), "CLAIMED"));
        }
        tag.put("reward_receipts", receipts);

        CampaignSavedData data = CampaignSavedData.load(tag, null);

        assertEquals(RewardOutboxPolicy.MAX_RECEIPTS, data.rewardReceipts().size());
        assertTrue(data.rewardReceipt(protectedPending).isPresent());
        assertEquals(
                RewardOutboxPolicy.ReceiptState.PENDING,
                data.rewardReceipt(protectedPending).orElseThrow().state());
    }

    @Test
    void allPendingReceiptFloodKeepsEveryReceiptAndParksTheCampaign() {
        CompoundTag tag = new CompoundTag();
        tag.putString("campaign_id", UUID.randomUUID().toString());
        tag.putString("status", CampaignStatus.RUNNING.name());
        ListTag receipts = new ListTag();
        for (int index = 0; index < RewardOutboxPolicy.MAX_RECEIPTS + 1; index++) {
            receipts.add(receiptTag(UUID.randomUUID(), "PENDING"));
        }
        tag.put("reward_receipts", receipts);

        CampaignSavedData data = CampaignSavedData.load(tag, null);

        // A corrupted save with more undelivered PENDING receipts than the cap
        // must lose nothing: the flood is kept whole and the campaign pauses.
        assertEquals(RewardOutboxPolicy.MAX_RECEIPTS + 1, data.rewardReceipts().size());
        assertTrue(data.rewardReceipts().stream()
                .allMatch(receipt -> receipt.state() == RewardOutboxPolicy.ReceiptState.PENDING));
        assertEquals(CampaignStatus.SAFE_MODE, data.status());

        // The flood and the safe state survive a save/load round-trip.
        CampaignSavedData reloaded = CampaignSavedData.load(data.save(new CompoundTag(), null), null);
        assertEquals(RewardOutboxPolicy.MAX_RECEIPTS + 1, reloaded.rewardReceipts().size());
        assertEquals(CampaignStatus.SAFE_MODE, reloaded.status());
    }

    @Test
    void mixedReceiptOverflowEvictsOnlyTheOldestClaimedEntries() {
        CompoundTag tag = new CompoundTag();
        tag.putString("campaign_id", UUID.randomUUID().toString());
        tag.putString("status", CampaignStatus.RUNNING.name());
        ListTag receipts = new ListTag();
        UUID oldestPending = UUID.randomUUID();
        for (int index = 0; index < 40; index++) {
            receipts.add(receiptTag(index == 0 ? oldestPending : UUID.randomUUID(), "PENDING"));
        }
        for (int index = 40; index < 300; index++) {
            receipts.add(receiptTag(UUID.randomUUID(), "CLAIMED"));
        }
        tag.put("reward_receipts", receipts);

        CampaignSavedData data = CampaignSavedData.load(tag, null);

        assertEquals(RewardOutboxPolicy.MAX_RECEIPTS, data.rewardReceipts().size());
        // All 40 PENDING receipts survive; only the 44 oldest CLAIMED were evicted.
        assertEquals(
                40,
                data.rewardReceipts().stream()
                        .filter(receipt -> receipt.state() == RewardOutboxPolicy.ReceiptState.PENDING)
                        .count());
        assertEquals(
                RewardOutboxPolicy.MAX_RECEIPTS - 40,
                data.rewardReceipts().stream()
                        .filter(receipt -> receipt.state() == RewardOutboxPolicy.ReceiptState.CLAIMED)
                        .count());
        assertTrue(data.rewardReceipt(oldestPending).isPresent());
        assertEquals(CampaignStatus.RUNNING, data.status());
    }

    @Test
    void pendingFloodBeyondClaimedEntriesKeepsPendingAndParksTheCampaign() {
        // 260 PENDING + 40 CLAIMED: every CLAIMED entry is evicted, all 260
        // PENDING survive even though that exceeds the cap, and the campaign
        // parks in SAFE_MODE instead of silently dropping undelivered rewards.
        CompoundTag tag = new CompoundTag();
        tag.putString("campaign_id", UUID.randomUUID().toString());
        tag.putString("status", CampaignStatus.RUNNING.name());
        ListTag receipts = new ListTag();
        for (int index = 0; index < 260; index++) {
            receipts.add(receiptTag(UUID.randomUUID(), "PENDING"));
        }
        for (int index = 260; index < 300; index++) {
            receipts.add(receiptTag(UUID.randomUUID(), "CLAIMED"));
        }
        tag.put("reward_receipts", receipts);

        CampaignSavedData data = CampaignSavedData.load(tag, null);

        assertEquals(260, data.rewardReceipts().size());
        assertTrue(data.rewardReceipts().stream()
                .allMatch(receipt -> receipt.state() == RewardOutboxPolicy.ReceiptState.PENDING));
        assertEquals(CampaignStatus.SAFE_MODE, data.status());
    }

    @Test
    void completedCampaignsKeepTheirStatusOnARewardFlood() {
        // A corrupt flood inside a finished campaign must not revive it: the
        // receipts are kept but the status stays COMPLETED.
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema_version", CampaignSavedData.CURRENT_SCHEMA);
        tag.putString("campaign_id", UUID.randomUUID().toString());
        tag.putString("status", CampaignStatus.COMPLETED.name());
        tag.putInt("day", CampaignSavedData.FINAL_DAY);
        tag.putBoolean("final_day_elapsed", true);
        tag.putBoolean("finale_mission_completed", true);
        ListTag receipts = new ListTag();
        for (int index = 0; index < RewardOutboxPolicy.MAX_RECEIPTS + 1; index++) {
            receipts.add(receiptTag(UUID.randomUUID(), "PENDING"));
        }
        tag.put("reward_receipts", receipts);

        CampaignSavedData data = CampaignSavedData.load(tag, null);

        assertEquals(RewardOutboxPolicy.MAX_RECEIPTS + 1, data.rewardReceipts().size());
        assertEquals(CampaignStatus.COMPLETED, data.status());
    }

    @Test
    void rewardReceiptRemovalOnlyDropsClaimedEntries() {
        CampaignSavedData data = started();
        assertTrue(data.proposeOptionalMission(MissionType.RESCUE_SURVIVOR));
        UUID id = data.proposedMission().id();
        data.acceptProposal(id, null);
        data.recordSurvivorRescued(id);
        assertTrue(data.completeOptionalMission(id));

        // A PENDING receipt is never evicted, not even by explicit removal.
        assertFalse(data.removeRewardReceipt(id));
        assertTrue(data.rewardReceipt(id).isPresent());

        assertTrue(data.markRewardClaimed(id));
        assertTrue(data.removeRewardReceipt(id));
        assertTrue(data.rewardReceipt(id).isEmpty());
        assertFalse(data.removeRewardReceipt(UUID.randomUUID()));
    }

    @Test
    void schemaSixSavesLoadWithSafeOptionalDefaultsAndStayCompleted() {
        CompoundTag old = new CompoundTag();
        old.putInt("schema_version", 6);
        old.putString("campaign_id", UUID.randomUUID().toString());
        old.putString("status", CampaignStatus.COMPLETED.name());
        old.putInt("day", CampaignSavedData.FINAL_DAY);
        old.putBoolean("final_day_elapsed", true);
        old.putBoolean("finale_mission_completed", true);

        CampaignSavedData data = CampaignSavedData.load(old, null);

        assertEquals(CampaignStatus.COMPLETED, data.status());
        assertNull(data.proposedMission());
        assertTrue(data.optionalMissions().isEmpty());
        assertTrue(data.rewardReceipts().isEmpty());
        assertTrue(data.pendingSiteCleanups().isEmpty());
        assertTrue(data.missionHistory().isEmpty());

        CompoundTag migrated = data.save(new CompoundTag(), null);
        assertEquals(CampaignSavedData.CURRENT_SCHEMA, migrated.getInt("schema_version"));
    }

    @Test
    void optionalStateRoundTripsThroughTheSave() {
        CampaignSavedData data = started();
        assertTrue(data.proposeOptionalMission(MissionType.SALVAGE_CAR));
        UUID salvage = data.proposedMission().id();
        data.acceptProposal(salvage, null);
        data.recordSalvageRepair(salvage, 1);
        data.assignOptionalMissionSite(salvage, new BlockPos(4, 70, -9));
        assertTrue(data.registerTeamMember(UUID.fromString("00000000-0000-0000-0000-000000000001")));
        assertTrue(data.registerTeamMember(UUID.fromString("00000000-0000-0000-0000-000000000002")));

        CampaignSavedData loaded = CampaignSavedData.load(
                data.save(new CompoundTag(), null),
                null);

        assertEquals(salvage, loaded.optionalMission(salvage).orElseThrow().id());
        assertTrue(loaded.optionalMission(salvage).orElseThrow().hasRepairIndex(1));
        assertTrue(loaded.isTeamMember(UUID.fromString("00000000-0000-0000-0000-000000000002")));
        assertTrue(loaded.isCaptain(UUID.fromString("00000000-0000-0000-0000-000000000001")));
        assertFalse(loaded.isCaptain(UUID.fromString("00000000-0000-0000-0000-000000000002")));
    }

    @Test
    void consecutiveFailuresTiltTheMainlinePoolTowardShortOrSuppliedMissions() {
        CampaignSavedData data = started();
        assertTrue(data.createMission(MissionType.STATION_POWER));
        assertTrue(data.failMission(0));
        assertTrue(data.createMission(MissionType.ZOMBIE_BLOCKADE));
        assertTrue(data.failMission(0));
        assertEquals(2, data.consecutiveFailures());
        for (int roll = 0; roll < 40; roll++) {
            assertTrue(data.createMission(
                    MissionPoolPolicy.selectMainMissionType(
                                    data.missionHistory(),
                                    new java.util.SplittableRandom(roll))
                            .orElseThrow()));
            assertTrue(MissionPoolPolicy.isShortOrSupplied(data.activeMission().type()));
            data.clearMission();
        }
    }

    private static CompoundTag receiptTag(UUID id, String state) {
        CompoundTag tag = new CompoundTag();
        tag.putString("mission_id", id.toString());
        tag.putString("type", "rescue_survivor");
        tag.putString("state", state);
        return tag;
    }

    private static CampaignSavedData started() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        return data;
    }

    private static CampaignSavedData runningAtDay(int day) {
        CompoundTag tag = new CompoundTag();
        tag.putString("campaign_id", UUID.randomUUID().toString());
        tag.putString("status", CampaignStatus.RUNNING.name());
        tag.putInt("day", day);
        return CampaignSavedData.load(tag, null);
    }
}
