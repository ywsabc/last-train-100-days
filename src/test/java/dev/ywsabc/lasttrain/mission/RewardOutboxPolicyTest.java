package dev.ywsabc.lasttrain.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import dev.ywsabc.lasttrain.campaign.CampaignStatus;
import dev.ywsabc.lasttrain.mission.RewardOutboxPolicy.CrateAccess;
import dev.ywsabc.lasttrain.mission.RewardOutboxPolicy.GrantDecision;
import dev.ywsabc.lasttrain.mission.RewardOutboxPolicy.ReceiptState;
import dev.ywsabc.lasttrain.mission.RewardOutboxPolicy.RewardItem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

class RewardOutboxPolicyTest {
    @Test
    void reconcileCoversEverySavedDataAndChunkSaveOrder() {
        // PENDING receipt saved, world mutation lost → grant on retry.
        assertEquals(GrantDecision.GRANT_AND_CLAIM, RewardOutboxPolicy.reconcile(ReceiptState.PENDING, false));
        // World mutation saved, receipt update lost → claim without re-grant.
        assertEquals(GrantDecision.CLAIM_ONLY, RewardOutboxPolicy.reconcile(ReceiptState.PENDING, true));
        // Receipt claimed, chunk save lost → re-grant; the absent marker proves no crate exists.
        assertEquals(GrantDecision.GRANT_AND_CLAIM, RewardOutboxPolicy.reconcile(ReceiptState.CLAIMED, false));
        // Both sides consistent → nothing.
        assertEquals(GrantDecision.NO_OP, RewardOutboxPolicy.reconcile(ReceiptState.CLAIMED, true));
    }

    @Test
    void crashBetweenWorldWriteAndClaimClaimsWithoutReGranting() {
        UUID missionId = UUID.randomUUID();
        FakeCrate crate = new FakeCrate();
        List<RewardItem> payload = RewardOutboxPolicy.payload(MissionType.RESCUE_SURVIVOR, missionId);
        String operationId = RewardOutboxPolicy.operationId(missionId);

        // Simulate: grant ran (marker + items in the world), crash before the
        // CLAIMED save. The marker persisted, so the reload reconciles to
        // CLAIM_ONLY — the receipt flips without any second crate fill.
        assertTrue(RewardOutboxPolicy.fillCrate(crate, operationId, payload));
        int filledSlots = crate.contents().size();
        crate.addCalls = 0;

        assertEquals(
                GrantDecision.CLAIM_ONLY,
                RewardOutboxPolicy.reconcile(ReceiptState.PENDING, true));
        assertTrue(RewardOutboxPolicy.settle(
                GrantDecision.CLAIM_ONLY, crate, operationId, payload));
        assertEquals(0, crate.addCalls, "CLAIM_ONLY must never touch the crate");
        assertEquals(filledSlots, crate.contents().size());
        assertTrue(RewardOutboxPolicy.containsPayload(crate.contents(), payload));
    }

    @Test
    void claimOnlyNeverRestocksALootedCrate() {
        UUID missionId = UUID.randomUUID();
        String operationId = RewardOutboxPolicy.operationId(missionId);
        List<RewardItem> payload = RewardOutboxPolicy.payload(MissionType.RESCUE_SURVIVOR, missionId);
        FakeCrate crate = new FakeCrate();

        // Marker persisted + CLAIMED save failed + the player took every
        // item: the reconcile must not misread the looted crate as a lost
        // chunk and re-grant.
        assertTrue(RewardOutboxPolicy.fillCrate(crate, operationId, payload));
        crate.slots.clear();

        assertTrue(RewardOutboxPolicy.settle(
                GrantDecision.CLAIM_ONLY, crate, operationId, payload));
        assertTrue(crate.contents().isEmpty());
        assertEquals(
                GrantDecision.NO_OP,
                RewardOutboxPolicy.reconcile(
                        ReceiptState.CLAIMED,
                        crate.operationIds().contains(operationId)));
        assertTrue(crate.contents().isEmpty());
    }

    @Test
    void partialWriteRetryAddsOnlyThePerKindShortfall() {
        UUID missionId = UUID.randomUUID();
        String operationId = RewardOutboxPolicy.operationId(missionId);
        List<RewardItem> payload = RewardOutboxPolicy.payload(MissionType.RESCUE_SURVIVOR, missionId);
        FakeCrate crate = new FakeCrate();

        // Crash mid-fill: bread complete, potatoes only 5 of 8, the remaining
        // kinds never reached the crate; no marker was written yet.
        crate.addStack(payload.get(0));
        crate.addStack(RewardItem.item("minecraft:baked_potato", 5));
        assertFalse(crate.operationIds().contains(operationId));
        crate.addedStacks.clear();

        assertTrue(RewardOutboxPolicy.fillCrate(crate, operationId, payload));
        assertTrue(RewardOutboxPolicy.containsPayload(crate.contents(), payload));
        assertEquals(8, RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.item("minecraft:bread", 1)));
        assertEquals(8, RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.item("minecraft:baked_potato", 1)));
        assertEquals(16, RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.item("minecraft:arrow", 1)));
        assertEquals(8, RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.item("minecraft:charcoal", 1)));
        assertEquals(4, RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.item("minecraft:iron_ingot", 1)));
        // The retry requested exactly the per-kind shortfall, never the full
        // payload stack again.
        assertEquals(
                List.of(
                        RewardItem.item("minecraft:baked_potato", 3),
                        RewardItem.item("minecraft:arrow", 16),
                        RewardItem.item("minecraft:charcoal", 8),
                        RewardItem.item("minecraft:iron_ingot", 4)),
                crate.addedStacks);
    }

    @Test
    void crateAlreadyHoldingThePayloadIsNeverClaimedWithoutNetAddition() {
        UUID missionId = UUID.randomUUID();
        String operationId = RewardOutboxPolicy.operationId(missionId);
        List<RewardItem> payload = RewardOutboxPolicy.payload(MissionType.RESCUE_SURVIVOR, missionId);
        FakeCrate crate = new FakeCrate();
        // The crate happens to hold every payload kind (for example
        // player-deposited items), but the grant itself never ran: no marker.
        payload.forEach(crate::addStack);
        crate.addCalls = 0;
        crate.addedStacks.clear();

        // Zero net addition → no marker, no claim: the receipt stays PENDING
        // and the dispatcher retries until it can actually add something.
        assertFalse(RewardOutboxPolicy.settle(
                GrantDecision.GRANT_AND_CLAIM, crate, operationId, payload));
        assertEquals(0, crate.addCalls);
        assertTrue(crate.operationIds().isEmpty());
        assertEquals(
                GrantDecision.GRANT_AND_CLAIM,
                RewardOutboxPolicy.reconcile(ReceiptState.PENDING, false));
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
    void chunkLostAfterClaimRegrantsWithoutDuplication() {
        UUID missionId = UUID.randomUUID();
        String operationId = RewardOutboxPolicy.operationId(missionId);
        List<RewardItem> payload = RewardOutboxPolicy.payload(MissionType.RESCUE_SURVIVOR, missionId);

        FakeCrate first = new FakeCrate();
        RewardOutboxPolicy.fillCrate(first, operationId, payload);
        int expectedSlots = first.contents().size();

        // Chunk rolled back: fresh empty crate, receipt already CLAIMED.
        FakeCrate repaired = new FakeCrate();
        assertTrue(RewardOutboxPolicy.fillCrate(repaired, operationId, payload));
        assertEquals(expectedSlots, repaired.contents().size());
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
    void markedCrateToppedUpOnlyForMissingPayloadAfterPartialFill() {
        UUID missionId = UUID.randomUUID();
        String operationId = RewardOutboxPolicy.operationId(missionId);
        List<RewardItem> payload = RewardOutboxPolicy.payload(MissionType.RESCUE_SURVIVOR, missionId);

        FakeCrate crate = new FakeCrate();
        crate.addOperationMarker(operationId);
        // Crash mid-fill: only the first entry reached the crate.
        crate.addStack(payload.get(0));

        assertTrue(RewardOutboxPolicy.fillCrate(crate, operationId, payload));
        assertTrue(RewardOutboxPolicy.containsPayload(crate.contents(), payload));
        // The partial bread stack was already complete: no duplicate added.
        assertEquals(
                8,
                crate.contents().stream()
                        .filter(item -> item.itemId().equals("minecraft:bread"))
                        .mapToInt(RewardItem::count)
                        .sum());
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
    void twoClaimedReceiptsDeliverExactlyOnceAcrossManyRounds() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        FakeCrate crate = new FakeCrate();
        String firstMarker = RewardOutboxPolicy.operationId(first);
        String secondMarker = RewardOutboxPolicy.operationId(second);
        List<RewardItem> firstPayload = RewardOutboxPolicy.payload(MissionType.RESCUE_SURVIVOR, first);
        List<RewardItem> secondPayload = RewardOutboxPolicy.payload(MissionType.SALVAGE_CAR, second);

        // Two CLAIMED receipts in one shared crate. A single overwritten
        // marker used to flip-flop: every round one of the two missions was
        // misjudged as "marker lost" and re-granted forever. Per-mission
        // markers must survive any dispatch order and any round count.
        for (int round = 0; round < 5; round++) {
            for (int order = 0; order < 2; order++) {
                boolean reversed = (round + order) % 2 == 1;
                UUID missionId = reversed ? second : first;
                String marker = reversed ? secondMarker : firstMarker;
                List<RewardItem> payload = reversed ? secondPayload : firstPayload;
                GrantDecision decision = RewardOutboxPolicy.reconcile(
                        ReceiptState.CLAIMED,
                        crate.operationIds().contains(marker));
                if (decision != GrantDecision.NO_OP) {
                    assertTrue(RewardOutboxPolicy.fillCrate(crate, marker, payload));
                }
            }
            // Exact payload counts: no doubling from marker overwrites or
            // partial-fill retries. The salvage payload's iron shortfall is
            // already satisfied by the rescue grant's four ingots, so the
            // shared crate holds one payload of each kind: iron stays 4.
            assertEquals(
                    8,
                    RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.item("minecraft:bread", 8)));
            assertEquals(
                    1,
                    RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.credential(second)));
            assertEquals(
                    4,
                    RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.item("minecraft:iron_ingot", 4)));
            assertEquals(
                    8,
                    RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.item("minecraft:redstone", 8)));
            assertEquals(7, crate.contents().size());
            assertEquals(Set.of(firstMarker, secondMarker), new HashSet<>(crate.operationIds()));
        }
        // Both receipts stay CLAIMED with their own markers present → NO_OP forever.
        assertEquals(
                GrantDecision.NO_OP,
                RewardOutboxPolicy.reconcile(ReceiptState.CLAIMED, crate.operationIds().contains(firstMarker)));
        assertEquals(
                GrantDecision.NO_OP,
                RewardOutboxPolicy.reconcile(ReceiptState.CLAIMED, crate.operationIds().contains(secondMarker)));
    }

    @Test
    void fullCrateIsNotMarkedAndRetriesOnceSpaceFrees() {
        UUID missionId = UUID.randomUUID();
        String marker = RewardOutboxPolicy.operationId(missionId);
        List<RewardItem> payload = RewardOutboxPolicy.payload(MissionType.RESCUE_SURVIVOR, missionId);
        FakeCrate crate = new FakeCrate();
        crate.refuseAdds = true;

        // CLAIMED receipt, marker absent, crate full of unrelated content:
        // the grant cannot complete and the crate must NOT be marked, or the
        // receipt would misjudge itself as delivered and stall as NO_OP.
        assertEquals(
                GrantDecision.GRANT_AND_CLAIM,
                RewardOutboxPolicy.reconcile(ReceiptState.CLAIMED, false));
        assertFalse(RewardOutboxPolicy.fillCrate(crate, marker, payload));
        assertTrue(crate.operationIds().isEmpty());

        // Space frees up: the retry delivers exactly once and marks the crate.
        crate.refuseAdds = false;
        assertTrue(RewardOutboxPolicy.fillCrate(crate, marker, payload));
        assertTrue(crate.operationIds().contains(marker));
        assertEquals(
                GrantDecision.NO_OP,
                RewardOutboxPolicy.reconcile(ReceiptState.CLAIMED, crate.operationIds().contains(marker)));
        assertEquals(
                8,
                RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.item("minecraft:bread", 8)));
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

        // CLAIMED + marker present → NO_OP: a looted crate is not misjudged
        // as "marker lost" and never refilled.
        assertEquals(
                GrantDecision.NO_OP,
                RewardOutboxPolicy.reconcile(ReceiptState.CLAIMED, crate.operationIds().contains(marker)));
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

    private static final class FakeCrate implements CrateAccess {
        private final List<RewardItem> slots = new ArrayList<>();
        private final List<String> markers = new ArrayList<>();
        private final List<RewardItem> addedStacks = new ArrayList<>();
        private boolean refuseAdds;
        private boolean marked;
        private int addCalls;

        @Override
        public List<RewardItem> contents() {
            return List.copyOf(slots);
        }

        @Override
        public int addStack(RewardItem stack) {
            addCalls++;
            addedStacks.add(stack);
            if (refuseAdds) {
                return 0;
            }
            int remaining = stack.count();
            for (int index = 0; index < slots.size() && remaining > 0; index++) {
                RewardItem existing = slots.get(index);
                boolean sameKind = stack.credentialMissionId().isPresent()
                        ? stack.credentialMissionId().equals(existing.credentialMissionId())
                        : stack.itemId().equals(existing.itemId());
                if (sameKind && existing.count() < 64) {
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
            return stack.count() - remaining;
        }

        @Override
        public List<String> operationIds() {
            return List.copyOf(markers);
        }

        @Override
        public void addOperationMarker(String operationId) {
            if (!markers.contains(operationId)) {
                markers.add(operationId);
            }
            marked = true;
        }

        @Override
        public void removeOperationMarker(String operationId) {
            markers.remove(operationId);
        }
    }
}
