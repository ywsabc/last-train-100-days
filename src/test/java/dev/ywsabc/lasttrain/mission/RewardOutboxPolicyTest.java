package dev.ywsabc.lasttrain.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import dev.ywsabc.lasttrain.campaign.CampaignStatus;
import dev.ywsabc.lasttrain.mission.RewardOutboxPolicy.CrateAccess;
import dev.ywsabc.lasttrain.mission.RewardOutboxPolicy.GrantDecision;
import dev.ywsabc.lasttrain.mission.RewardOutboxPolicy.ReceiptState;
import dev.ywsabc.lasttrain.mission.RewardOutboxPolicy.RewardItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

class RewardOutboxPolicyTest {
    @Test
    void rewardItemsRejectBlankIdentityAtThePureBoundary() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RewardItem.item("  ", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RewardItem("minecraft:paper", 1, Optional.of("")));
    }

    @Test
    void reconciliationUsesTheCrateOperationAsWorldMutationAuthority() {
        assertEquals(
                GrantDecision.GRANT_AND_CLAIM,
                RewardOutboxPolicy.reconcile(ReceiptState.PENDING, false));
        assertEquals(
                GrantDecision.CLAIM_ONLY,
                RewardOutboxPolicy.reconcile(ReceiptState.PENDING, true));
        assertEquals(
                GrantDecision.GRANT_AND_CLAIM,
                RewardOutboxPolicy.reconcile(ReceiptState.CLAIMED, false));
        assertEquals(
                GrantDecision.NO_OP,
                RewardOutboxPolicy.reconcile(ReceiptState.CLAIMED, true));
    }

    @Test
    void pendingReceiptDispatchesWholePayloadEvenWhenMatchingItemsExist() {
        // Matching world contents are not delivery authority: a PENDING
        // receipt still submits its own complete payload exactly once.
        UUID missionId = UUID.randomUUID();
        FakeCrate crate = new FakeCrate();
        List<RewardItem> payload = RewardOutboxPolicy.payload(MissionType.RESCUE_SURVIVOR, missionId);
        String operationId = RewardOutboxPolicy.operationId(missionId);
        payload.forEach(crate::addStack);

        assertTrue(RewardOutboxPolicy.settle(
                GrantDecision.GRANT_AND_CLAIM, crate, operationId, payload));
        assertEquals(1, crate.addAllCalls);
        assertEquals(payload, crate.addedBatches.get(0));
        assertEquals(
                16,
                RewardOutboxPolicy.countPresent(
                        crate.contents(), RewardItem.item("minecraft:bread", 1)));
        assertTrue(RewardOutboxPolicy.containsPayload(crate.contents(), payload));
        assertTrue(crate.operationIds().contains(operationId));
    }

    @Test
    void claimedReceiptWithAppliedOperationIsNeverRegranted() {
        UUID missionId = UUID.randomUUID();
        String operationId = RewardOutboxPolicy.operationId(missionId);
        List<RewardItem> payload = RewardOutboxPolicy.payload(MissionType.RESCUE_SURVIVOR, missionId);
        FakeCrate crate = new FakeCrate();
        assertTrue(RewardOutboxPolicy.fillCrate(crate, operationId, payload));

        // Looting does not remove the marker stored on the same block entity.
        crate.slots.clear();
        crate.resetAccessCalls();

        assertEquals(
                GrantDecision.NO_OP,
                RewardOutboxPolicy.reconcile(ReceiptState.CLAIMED, true));
        assertFalse(RewardOutboxPolicy.settle(
                GrantDecision.NO_OP, crate, operationId, payload));
        assertEquals(0, crate.accessCalls, "the NO_OP path never touches the crate");
        assertTrue(crate.slots.isEmpty());
        assertEquals(List.of(operationId), crate.markers);
    }

    @Test
    void pendingAttemptAddsTheWholePayloadInsteadOfOnlyTheShortfall() {
        UUID missionId = UUID.randomUUID();
        String operationId = RewardOutboxPolicy.operationId(missionId);
        List<RewardItem> payload = RewardOutboxPolicy.payload(MissionType.RESCUE_SURVIVOR, missionId);
        FakeCrate crate = new FakeCrate();

        // An older run already landed part of the payload: bread complete,
        // potatoes only 5 of 8; no marker was written yet.
        crate.addStack(payload.get(0));
        crate.addStack(RewardItem.item("minecraft:baked_potato", 5));
        assertFalse(crate.operationIds().contains(operationId));

        assertTrue(RewardOutboxPolicy.fillCrate(crate, operationId, payload));
        assertTrue(RewardOutboxPolicy.containsPayload(crate.contents(), payload));
        assertEquals(16, RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.item("minecraft:bread", 1)));
        assertEquals(13, RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.item("minecraft:baked_potato", 1)));
        assertEquals(16, RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.item("minecraft:arrow", 1)));
        assertEquals(8, RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.item("minecraft:charcoal", 1)));
        assertEquals(4, RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.item("minecraft:iron_ingot", 1)));
        assertEquals(payload, crate.addedBatches.get(0));
    }

    @Test
    void fullCrateDefersTheWholeReceiptWithoutPartialResidue() {
        UUID missionId = UUID.randomUUID();
        String marker = RewardOutboxPolicy.operationId(missionId);
        List<RewardItem> payload = RewardOutboxPolicy.payload(MissionType.RESCUE_SURVIVOR, missionId);
        FakeCrate crate = new FakeCrate();
        crate.refuseAdds = true;
        List<RewardItem> before = crate.contents();

        // The atomic grant cannot complete and must NOT be marked: the
        // receipt stays PENDING and the crate keeps its exact contents.
        assertFalse(RewardOutboxPolicy.fillCrate(crate, marker, payload));
        assertTrue(crate.operationIds().isEmpty());
        assertEquals(before, crate.contents(), "a failed grant leaves no partial residue");

        // Space frees up: the retry delivers exactly once and marks the crate.
        crate.refuseAdds = false;
        assertTrue(RewardOutboxPolicy.fillCrate(crate, marker, payload));
        assertTrue(crate.operationIds().contains(marker));
        assertEquals(
                GrantDecision.NO_OP,
                RewardOutboxPolicy.reconcile(ReceiptState.CLAIMED, true));
        assertEquals(
                8,
                RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.item("minecraft:bread", 8)));
    }

    @Test
    void crashBeforeWorldWriteGrantsOnRetry() {
        UUID missionId = UUID.randomUUID();
        FakeCrate crate = new FakeCrate();
        List<RewardItem> payload = RewardOutboxPolicy.payload(MissionType.SALVAGE_CAR, missionId);

        assertTrue(RewardOutboxPolicy.fillCrate(
                crate,
                RewardOutboxPolicy.operationId(missionId),
                payload));
        assertTrue(RewardOutboxPolicy.containsPayload(crate.contents(), payload));
        assertTrue(crate.marked);
    }

    @Test
    void savedDataAheadOfCrateReplaysClaimedReceiptExactlyOnce() {
        UUID missionId = UUID.randomUUID();
        String operationId = RewardOutboxPolicy.operationId(missionId);
        List<RewardItem> payload = RewardOutboxPolicy.payload(MissionType.RESCUE_SURVIVOR, missionId);

        // SavedData persisted CLAIMED while the crate block entity rolled
        // back. The missing marker proves the world mutation did not persist.
        FakeCrate repaired = new FakeCrate();
        GrantDecision replay = RewardOutboxPolicy.reconcile(ReceiptState.CLAIMED, false);
        assertEquals(GrantDecision.GRANT_AND_CLAIM, replay);
        assertTrue(RewardOutboxPolicy.settle(replay, repaired, operationId, payload));
        assertTrue(RewardOutboxPolicy.containsPayload(repaired.contents(), payload));
        assertEquals(List.of(operationId), repaired.operationIds());

        // A repeated tick sees the marker and cannot append the payload again.
        assertEquals(
                GrantDecision.NO_OP,
                RewardOutboxPolicy.reconcile(ReceiptState.CLAIMED, true));
        assertEquals(1, repaired.addAllCalls);
    }

    @Test
    void savedCompletionSuppressesReplayAfterCrateMarkerIsLost() {
        UUID missionId = UUID.randomUUID();
        String operationId = RewardOutboxPolicy.operationId(missionId);
        CampaignSavedData data = completedReceiptData(missionId);
        FakeCrate replacement = new FakeCrate();

        boolean operationApplied = data.hasCompletedRewardOperation(operationId)
                || replacement.operationIds().contains(operationId);
        GrantDecision decision = RewardOutboxPolicy.reconcile(
                ReceiptState.CLAIMED,
                operationApplied);

        assertEquals(GrantDecision.NO_OP, decision);
        replacement.resetAccessCalls();
        assertFalse(RewardOutboxPolicy.settle(
                decision,
                replacement,
                operationId,
                RewardOutboxPolicy.payload(MissionType.RESCUE_SURVIVOR, missionId)));
        assertEquals(0, replacement.accessCalls);
        assertTrue(replacement.contents().isEmpty());
    }

    @Test
    void savedCompletionAndCrateMarkerTogetherRemainIdempotent() {
        UUID missionId = UUID.randomUUID();
        String operationId = RewardOutboxPolicy.operationId(missionId);
        CampaignSavedData data = completedReceiptData(missionId);
        FakeCrate crate = new FakeCrate();
        crate.addOperationMarker(operationId);

        boolean operationApplied = data.hasCompletedRewardOperation(operationId)
                || crate.operationIds().contains(operationId);
        assertEquals(
                GrantDecision.NO_OP,
                RewardOutboxPolicy.reconcile(ReceiptState.CLAIMED, operationApplied));
        assertEquals(0, crate.addAllCalls);
        assertEquals(List.of(operationId), crate.operationIds());
    }

    @Test
    void payloadsRespectTheSanityWindowAndNeverPackIllegalStacks() {
        UUID missionId = UUID.randomUUID();
        List<RewardItem> rescue = RewardOutboxPolicy.payload(MissionType.RESCUE_SURVIVOR, missionId);
        List<RewardItem> salvage = RewardOutboxPolicy.payload(MissionType.SALVAGE_CAR, missionId);

        assertTrue(RewardOutboxPolicy.isLegalPayload(rescue));
        assertTrue(RewardOutboxPolicy.isLegalPayload(salvage));
        // No stack-limit-1 vehicle items of any kind, and every count is
        // within the sanity window; the world adapter re-checks the live
        // item stack limit before placing anything.
        for (RewardItem item : rescue) {
            assertTrue(item.count() >= 1 && item.count() <= 64);
            assertFalse(item.itemId().contains("minecart"));
        }
        for (RewardItem item : salvage) {
            assertTrue(item.count() >= 1 && item.count() <= 64);
            assertFalse(item.itemId().contains("minecart"));
        }
    }

    @Test
    void salvageCredentialIsUniqueAndVerifiable() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        RewardItem firstCredential = RewardItem.credential(first);

        assertEquals(first.toString(), firstCredential.credentialMissionId().orElseThrow());
        assertEquals(1, firstCredential.count());
        assertEquals("minecraft:paper", firstCredential.itemId());
        assertNotEquals(firstCredential, RewardItem.credential(second));
        assertEquals(
                second.toString(),
                RewardItem.credential(second).credentialMissionId().orElseThrow());
    }

    @Test
    void credentialsNeverMergeWithEachOtherOrWithPlainPaper() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        RewardItem firstCredential = RewardItem.credential(first);
        List<RewardItem> contents = List.of(
                RewardItem.credential(second),
                RewardItem.item("minecraft:paper", 4));

        assertEquals(0, RewardOutboxPolicy.countPresent(contents, firstCredential));
        assertFalse(RewardOutboxPolicy.containsPayload(contents, List.of(firstCredential)));
    }

    @Test
    void worldAheadOfSavedDataClaimsPendingReceiptWithoutDuplicatingPayload() {
        UUID missionId = UUID.randomUUID();
        String operationId = RewardOutboxPolicy.operationId(missionId);
        List<RewardItem> payload = RewardOutboxPolicy.payload(MissionType.RESCUE_SURVIVOR, missionId);

        FakeCrate crate = new FakeCrate();
        payload.forEach(crate::addStack);
        crate.addOperationMarker(operationId);
        crate.resetAccessCalls();

        GrantDecision decision = RewardOutboxPolicy.reconcile(ReceiptState.PENDING, true);
        assertEquals(GrantDecision.CLAIM_ONLY, decision);
        assertTrue(RewardOutboxPolicy.settle(decision, crate, operationId, payload));
        assertTrue(RewardOutboxPolicy.containsPayload(crate.contents(), payload));
        assertEquals(
                8,
                crate.contents().stream()
                        .filter(item -> item.itemId().equals("minecraft:bread"))
                        .mapToInt(RewardItem::count)
                        .sum());
        assertEquals(0, crate.addAllCalls);
        assertEquals(List.of(operationId), crate.operationIds());
    }

    @Test
    void crashBetweenCrateWriteAndReceiptAckIsIdempotentAcrossRepeatedTicks() {
        UUID missionId = UUID.randomUUID();
        String operationId = RewardOutboxPolicy.operationId(missionId);
        List<RewardItem> payload = RewardOutboxPolicy.payload(MissionType.RESCUE_SURVIVOR, missionId);
        FakeCrate crate = new FakeCrate();

        // Tick 1 lands world state, then the process crashes before ACK.
        assertTrue(RewardOutboxPolicy.fillCrate(crate, operationId, payload));
        assertEquals(1, crate.addAllCalls);

        // Reloaded receipt is still PENDING. Both recovery ticks only ACK.
        for (int tick = 0; tick < 2; tick++) {
            GrantDecision decision = RewardOutboxPolicy.reconcile(
                    ReceiptState.PENDING,
                    crate.operationIds().contains(operationId));
            assertEquals(GrantDecision.CLAIM_ONLY, decision);
            assertTrue(RewardOutboxPolicy.settle(decision, crate, operationId, payload));
        }
        assertEquals(1, crate.addAllCalls);
        assertEquals(
                8,
                RewardOutboxPolicy.countPresent(
                        crate.contents(), RewardItem.item("minecraft:bread", 1)));
    }

    @Test
    void operationIdsAreStableAndDistinct() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertEquals(RewardOutboxPolicy.operationId(first), RewardOutboxPolicy.operationId(first));
        assertNotEquals(RewardOutboxPolicy.operationId(first), RewardOutboxPolicy.operationId(second));
        assertTrue(RewardOutboxPolicy.operationId(first).contains(first.toString()));
    }

    @Test
    void twoReceiptsWithSpaceForOneDeliverOneAndDeferTheOther() {
        UUID rescue = UUID.randomUUID();
        UUID salvage = UUID.randomUUID();
        // Six slots hold the five rescue kinds plus one spare: the salvage
        // credential needs a sixth slot and the redstone a seventh, so only
        // one full receipt fits at a time.
        FakeCrate crate = new FakeCrate(6);
        String rescueMarker = RewardOutboxPolicy.operationId(rescue);
        String salvageMarker = RewardOutboxPolicy.operationId(salvage);
        List<RewardItem> rescuePayload = RewardOutboxPolicy.payload(MissionType.RESCUE_SURVIVOR, rescue);
        List<RewardItem> salvagePayload = RewardOutboxPolicy.payload(MissionType.SALVAGE_CAR, salvage);

        // Round 1: the rescue receipt fills the crate and claims; the salvage
        // receipt does not fit and defers with no partial residue.
        assertTrue(RewardOutboxPolicy.fillCrate(crate, rescueMarker, rescuePayload));
        List<RewardItem> afterRescue = crate.contents();
        assertFalse(RewardOutboxPolicy.fillCrate(crate, salvageMarker, salvagePayload));
        assertEquals(afterRescue, crate.contents(), "the deferred receipt leaves no residue");
        assertFalse(crate.operationIds().contains(salvageMarker));
        assertEquals(
                4,
                RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.item("minecraft:iron_ingot", 1)),
                "the deferred receipt did not merge any part of its payload");

        // Round 2: a slot frees up and the salvage receipt delivers exactly once.
        crate.setCapacity(7);
        assertTrue(RewardOutboxPolicy.fillCrate(crate, salvageMarker, salvagePayload));
        assertTrue(crate.operationIds().contains(salvageMarker));
        assertEquals(
                1,
                RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.credential(salvage)));
        assertEquals(
                8,
                RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.item("minecraft:redstone", 8)));
        assertEquals(
                8,
                RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.item("minecraft:iron_ingot", 1)));

        // Both receipts are CLAIMED now: every later round is NO_OP and the
        // exact payload counts survive any number of rounds.
        for (int round = 0; round < 3; round++) {
            assertEquals(
                    GrantDecision.NO_OP,
                    RewardOutboxPolicy.reconcile(ReceiptState.CLAIMED, true));
            assertFalse(RewardOutboxPolicy.settle(GrantDecision.NO_OP, crate, rescueMarker, rescuePayload));
            assertFalse(RewardOutboxPolicy.settle(GrantDecision.NO_OP, crate, salvageMarker, salvagePayload));
        }
        assertEquals(
                8,
                RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.item("minecraft:bread", 8)));
        assertEquals(
                1,
                RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.credential(salvage)));
        assertEquals(
                8,
                RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.item("minecraft:iron_ingot", 1)));
        assertEquals(Set.of(rescueMarker, salvageMarker), new HashSet<>(crate.operationIds()));
    }

    @Test
    void everyReceiptDeliversExactlyOnceAcrossManyDispatchRounds() {
        UUID rescue = UUID.randomUUID();
        UUID salvage = UUID.randomUUID();
        FakeCrate crate = new FakeCrate(6);
        Map<UUID, ReceiptState> states = new HashMap<>();
        states.put(rescue, ReceiptState.PENDING);
        states.put(salvage, ReceiptState.PENDING);

        for (int round = 0; round < 3; round++) {
            for (UUID missionId : List.of(rescue, salvage)) {
                if (states.get(missionId) == ReceiptState.CLAIMED) {
                    continue;
                }
                MissionType type = missionId.equals(salvage)
                        ? MissionType.SALVAGE_CAR
                        : MissionType.RESCUE_SURVIVOR;
                if (dispatch(crate, missionId, type)) {
                    states.put(missionId, ReceiptState.CLAIMED);
                }
            }
        }
        // The rescue receipt claimed in round 1; the salvage receipt still
        // does not fit and stays PENDING without any residue.
        assertEquals(ReceiptState.CLAIMED, states.get(rescue));
        assertEquals(ReceiptState.PENDING, states.get(salvage));
        assertEquals(5, crate.contents().size());

        // Space frees up: the deferred receipt delivers on a later round.
        crate.setCapacity(7);
        for (int round = 0; round < 3; round++) {
            for (UUID missionId : List.of(rescue, salvage)) {
                if (states.get(missionId) == ReceiptState.CLAIMED) {
                    continue;
                }
                MissionType type = missionId.equals(salvage)
                        ? MissionType.SALVAGE_CAR
                        : MissionType.RESCUE_SURVIVOR;
                if (dispatch(crate, missionId, type)) {
                    states.put(missionId, ReceiptState.CLAIMED);
                }
            }
        }
        // Both receipts are CLAIMED and every kind appears exactly once — no
        // doubling, no partial residue.
        assertEquals(ReceiptState.CLAIMED, states.get(rescue));
        assertEquals(ReceiptState.CLAIMED, states.get(salvage));
        assertEquals(
                8,
                RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.item("minecraft:bread", 8)));
        assertEquals(
                1,
                RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.credential(salvage)));
        assertEquals(
                8,
                RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.item("minecraft:redstone", 8)));
        assertEquals(
                8,
                RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.item("minecraft:iron_ingot", 1)));
        assertEquals(2, crate.operationIds().size());
        assertEquals(
                GrantDecision.NO_OP,
                RewardOutboxPolicy.reconcile(ReceiptState.CLAIMED, true));
    }

    @Test
    void lootedCrateKeepsItsMarkerAndIsNotRegranted() {
        UUID missionId = UUID.randomUUID();
        String marker = RewardOutboxPolicy.operationId(missionId);
        List<RewardItem> payload = RewardOutboxPolicy.payload(MissionType.RESCUE_SURVIVOR, missionId);
        FakeCrate crate = new FakeCrate();
        assertTrue(RewardOutboxPolicy.fillCrate(crate, marker, payload));
        // The player took every item; only the marker remains.
        crate.slots.clear();

        assertEquals(
                GrantDecision.NO_OP,
                RewardOutboxPolicy.reconcile(ReceiptState.CLAIMED, true));
        assertFalse(RewardOutboxPolicy.settle(GrantDecision.NO_OP, crate, marker, payload));
        assertTrue(crate.contents().isEmpty());
        assertTrue(crate.operationIds().contains(marker));
    }

    @Test
    void markerCapEvictsClaimedMarkersAndPairsTheirReceipts() {
        UUID pending = UUID.randomUUID();
        UUID claimedFirst = UUID.randomUUID();
        UUID claimedSecond = UUID.randomUUID();
        CampaignSavedData data = receiptsData(pending, claimedFirst, claimedSecond);

        FakeCrate crate = new FakeCrate();
        // Oldest first: the PENDING-backed marker is the oldest evictable
        // candidate and must be skipped; the oldest CLAIMED marker is the victim.
        crate.addOperationMarker(RewardOutboxPolicy.operationId(pending));
        crate.addOperationMarker(RewardOutboxPolicy.operationId(claimedFirst));
        crate.addOperationMarker(RewardOutboxPolicy.operationId(claimedSecond));
        for (int index = 0; index < RewardOutboxPolicy.MAX_MARKERS - 2; index++) {
            crate.addOperationMarker("orphan/" + index);
        }
        assertEquals(RewardOutboxPolicy.MAX_MARKERS + 1, crate.operationIds().size());

        OptionalMissionDirector.capCrateMarkers(crate, data);

        assertEquals(RewardOutboxPolicy.MAX_MARKERS, crate.operationIds().size());
        assertTrue(crate.operationIds().contains(RewardOutboxPolicy.operationId(pending)));
        assertFalse(crate.operationIds().contains(RewardOutboxPolicy.operationId(claimedFirst)));
        assertTrue(crate.operationIds().contains(RewardOutboxPolicy.operationId(claimedSecond)));
        // The evicted marker's CLAIMED receipt is dropped with it, so the
        // surviving receipts can never re-open the grant loop.
        assertTrue(data.rewardReceipt(pending).isPresent());
        assertTrue(data.rewardReceipt(claimedFirst).isEmpty());
        assertTrue(data.rewardReceipt(claimedSecond).isPresent());
    }

    @Test
    void markerCapNeverEvictsPendingBackedMarkers() {
        // A corrupted save with more PENDING receipts than the cap: every
        // receipt is kept, every crate marker is PENDING-backed, and the
        // marker set is allowed to exceed the cap instead of evicting.
        CompoundTag tag = new CompoundTag();
        tag.putString("campaign_id", UUID.randomUUID().toString());
        tag.putString("status", CampaignStatus.RUNNING.name());
        ListTag receipts = new ListTag();
        List<String> markers = new ArrayList<>();
        for (int index = 0; index < RewardOutboxPolicy.MAX_RECEIPTS + 1; index++) {
            UUID missionId = UUID.randomUUID();
            receipts.add(receiptTag(missionId, "PENDING"));
            markers.add(RewardOutboxPolicy.operationId(missionId));
        }
        tag.put("reward_receipts", receipts);
        CampaignSavedData data = CampaignSavedData.load(tag, null);

        FakeCrate crate = new FakeCrate();
        markers.forEach(crate::addOperationMarker);

        OptionalMissionDirector.capCrateMarkers(crate, data);

        assertEquals(RewardOutboxPolicy.MAX_RECEIPTS + 1, data.rewardReceipts().size());
        assertEquals(RewardOutboxPolicy.MAX_RECEIPTS + 1, crate.operationIds().size());
        assertEquals(new HashSet<>(markers), new HashSet<>(crate.operationIds()));
        assertEquals(CampaignStatus.SAFE_MODE, data.status());
    }

    /** One dispatch decision + settle round, mirroring the director loop. */
    private static boolean dispatch(FakeCrate crate, UUID missionId, MissionType type) {
        return RewardOutboxPolicy.settle(
                RewardOutboxPolicy.reconcile(
                        ReceiptState.PENDING,
                        crate.operationIds().contains(RewardOutboxPolicy.operationId(missionId))),
                crate,
                RewardOutboxPolicy.operationId(missionId),
                RewardOutboxPolicy.payload(type, missionId));
    }

    private static CompoundTag receiptTag(UUID id, String state) {
        CompoundTag tag = new CompoundTag();
        tag.putString("mission_id", id.toString());
        tag.putString("type", "rescue_survivor");
        tag.putString("state", state);
        return tag;
    }

    private static CampaignSavedData receiptsData(UUID pending, UUID claimedFirst, UUID claimedSecond) {
        CompoundTag tag = new CompoundTag();
        tag.putString("campaign_id", UUID.randomUUID().toString());
        tag.putString("status", CampaignStatus.RUNNING.name());
        ListTag receipts = new ListTag();
        receipts.add(receiptTag(pending, "PENDING"));
        receipts.add(receiptTag(claimedFirst, "CLAIMED"));
        receipts.add(receiptTag(claimedSecond, "CLAIMED"));
        tag.put("reward_receipts", receipts);
        return CampaignSavedData.load(tag, null);
    }

    private static CampaignSavedData completedReceiptData(UUID missionId) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema_version", CampaignSavedData.CURRENT_SCHEMA);
        tag.putString("campaign_id", UUID.randomUUID().toString());
        tag.putString("status", CampaignStatus.RUNNING.name());
        ListTag receipts = new ListTag();
        receipts.add(receiptTag(missionId, "CLAIMED"));
        tag.put("reward_receipts", receipts);
        ListTag operations = new ListTag();
        operations.add(net.minecraft.nbt.StringTag.valueOf(
                RewardOutboxPolicy.operationId(missionId)));
        tag.put("completed_reward_operations", operations);
        return CampaignSavedData.load(tag, null);
    }

    /**
     * In-memory crate fake with an explicit slot capacity, so tests can
     * arrange exactly how much payload fits. {@link #addAll} simulates the
     * adapter's plan-then-apply: everything fits or nothing is written.
     */
    private static final class FakeCrate implements CrateAccess {
        private final List<RewardItem> slots = new ArrayList<>();
        private final List<String> markers = new ArrayList<>();
        private final List<List<RewardItem>> addedBatches = new ArrayList<>();
        private int capacity;
        private boolean refuseAdds;
        private boolean marked;
        private int addAllCalls;
        private int accessCalls;

        private FakeCrate() {
            this(27);
        }

        private FakeCrate(int capacity) {
            this.capacity = capacity;
        }

        private void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        private void resetAccessCalls() {
            accessCalls = 0;
            addAllCalls = 0;
        }

        @Override
        public List<RewardItem> contents() {
            accessCalls++;
            return List.copyOf(slots);
        }

        @Override
        public boolean addAll(List<RewardItem> stacks) {
            accessCalls++;
            addAllCalls++;
            if (refuseAdds) {
                return false;
            }
            List<RewardItem> planned = new ArrayList<>(slots);
            for (RewardItem stack : stacks) {
                int remaining = stack.count();
                for (int index = 0; index < planned.size() && remaining > 0; index++) {
                    RewardItem existing = planned.get(index);
                    if (sameKind(stack, existing) && existing.count() < 64) {
                        int merged = Math.min(64 - existing.count(), remaining);
                        planned.set(
                                index,
                                new RewardItem(
                                        existing.itemId(),
                                        existing.count() + merged,
                                        existing.credentialMissionId()));
                        remaining -= merged;
                    }
                }
                while (remaining > 0) {
                    if (planned.size() >= capacity) {
                        return false;
                    }
                    int placed = Math.min(64, remaining);
                    planned.add(new RewardItem(stack.itemId(), placed, stack.credentialMissionId()));
                    remaining -= placed;
                }
            }
            addedBatches.add(List.copyOf(stacks));
            slots.clear();
            slots.addAll(planned);
            return true;
        }

        /** Test-setup helper: merges one stack directly, without capacity checks. */
        private void addStack(RewardItem stack) {
            int remaining = stack.count();
            for (int index = 0; index < slots.size() && remaining > 0; index++) {
                RewardItem existing = slots.get(index);
                if (sameKind(stack, existing) && existing.count() < 64) {
                    int merged = Math.min(64 - existing.count(), remaining);
                    slots.set(
                            index,
                            new RewardItem(
                                    existing.itemId(),
                                    existing.count() + merged,
                                    existing.credentialMissionId()));
                    remaining -= merged;
                }
            }
            while (remaining > 0) {
                int placed = Math.min(64, remaining);
                slots.add(new RewardItem(stack.itemId(), placed, stack.credentialMissionId()));
                remaining -= placed;
            }
        }

        private static boolean sameKind(RewardItem left, RewardItem right) {
            return left.credentialMissionId().isPresent()
                    ? left.credentialMissionId().equals(right.credentialMissionId())
                    : left.itemId().equals(right.itemId());
        }

        @Override
        public List<String> operationIds() {
            accessCalls++;
            return List.copyOf(markers);
        }

        @Override
        public void addOperationMarker(String operationId) {
            accessCalls++;
            if (!markers.contains(operationId)) {
                markers.add(operationId);
            }
            marked = true;
        }

        @Override
        public void removeOperationMarker(String operationId) {
            accessCalls++;
            markers.remove(operationId);
        }
    }
}
