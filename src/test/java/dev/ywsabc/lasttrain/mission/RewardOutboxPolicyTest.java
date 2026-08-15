package dev.ywsabc.lasttrain.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.mission.RewardOutboxPolicy.CrateAccess;
import dev.ywsabc.lasttrain.mission.RewardOutboxPolicy.GrantDecision;
import dev.ywsabc.lasttrain.mission.RewardOutboxPolicy.ReceiptState;
import dev.ywsabc.lasttrain.mission.RewardOutboxPolicy.RewardItem;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
        crate.markOperation(operationId);
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

    private static final class FakeCrate implements CrateAccess {
        private final List<RewardItem> slots = new ArrayList<>();
        private String operationId = "";
        private boolean marked;

        @Override
        public List<RewardItem> contents() {
            return List.copyOf(slots);
        }

        @Override
        public void addStack(RewardItem stack) {
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
        public String operationId() {
            return operationId;
        }

        @Override
        public void markOperation(String operationId) {
            this.operationId = operationId;
            this.marked = true;
        }
    }
}
