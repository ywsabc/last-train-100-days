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
    void crashBetweenWorldWriteAndClaimDeliversExactlyOnce() {
        UUID missionId = UUID.randomUUID();
        FakeCrate crate = new FakeCrate();
        List<RewardItem> payload = RewardOutboxPolicy.payload(MissionType.RESCUE_SURVIVOR, missionId);
        String operationId = RewardOutboxPolicy.operationId(missionId);

        // Simulate: grant ran (marker in world), crash before CLAIMED save.
        assertTrue(RewardOutboxPolicy.fillCrate(crate, operationId, payload));
        int filledSlots = crate.contents().size();

        // Reload world state; receipt is still PENDING; dispatcher reconciles.
        assertTrue(RewardOutboxPolicy.fillCrate(crate, operationId, payload));
        assertEquals(filledSlots, crate.contents().size());
        assertTrue(RewardOutboxPolicy.containsPayload(crate.contents(), payload));
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
            // Exact payload counts: no doubling from marker overwrites. The
            // shared crate legitimately merges the 4+4 iron ingots of the two
            // payloads, so seven slots hold exactly one payload of each kind.
            assertEquals(
                    8,
                    RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.item("minecraft:bread", 8)));
            assertEquals(
                    1,
                    RewardOutboxPolicy.countPresent(crate.contents(), RewardItem.credential(second)));
            assertEquals(
                    8,
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
        private boolean refuseAdds;
        private boolean marked;

        @Override
        public List<RewardItem> contents() {
            return List.copyOf(slots);
        }

        @Override
        public void addStack(RewardItem stack) {
            if (refuseAdds) {
                return;
            }
            for (int index = 0; index < slots.size(); index++) {
                RewardItem existing = slots.get(index);
                boolean sameKind = stack.credentialMissionId().isPresent()
                        ? stack.credentialMissionId().equals(existing.credentialMissionId())
                        : stack.itemId().equals(existing.itemId());
                if (sameKind && existing.count() + stack.count() <= 64) {
                    slots.set(
                            index,
                            new RewardItem(
                                    existing.itemId(),
                                    existing.count() + stack.count(),
                                    existing.credentialMissionId()));
                    return;
                }
            }
            slots.add(stack);
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
