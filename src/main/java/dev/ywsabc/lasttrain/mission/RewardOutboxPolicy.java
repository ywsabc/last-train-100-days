package dev.ywsabc.lasttrain.mission;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Durable reward outbox policy for mission completion rewards.
 *
 * <p>The persisted saved data is the sole authority over reward delivery.
 * The decision table is deliberately simple:</p>
 * <ul>
 * <li>{@link ReceiptState#PENDING} → {@link GrantDecision#GRANT_AND_CLAIM}:
 * one dispatch attempt per tick, no matter what the world side looks like;</li>
 * <li>{@link ReceiptState#CLAIMED} → {@link GrantDecision#NO_OP} forever. A
 * broken, looted, rolled-back or otherwise missing crate is never restocked
 * and never re-read for a decision — the receipt alone ends the grant.</li>
 * </ul>
 * A successful dispatch writes the whole payload into the crate, persists the
 * diagnostic operation marker and flips the receipt to CLAIMED in the same
 * tick. Any failure (crate full, space insufficient, chunk unloaded) leaves
 * the receipt PENDING and the next tick retries. The only accepted crash
 * trade-off: if the crate chunk save is lost after the receipt turned
 * CLAIMED, the reward is lost but never duplicated — the saved data wins over
 * the world.
 *
 * <p>Each grant attempt is atomic per receipt: the exact payload either lands
 * in full or is deferred in full, so a failed attempt can never leave a
 * partial reward behind. Existing matching crate contents may be merge
 * targets, but never count as proof that part of this receipt was already
 * delivered. Receipts are independent: one receipt always submits its own
 * complete payload against the crate as it stands (including earlier
 * receipts' committed writes of the same round), and its success or deferral
 * never influences another receipt's decision. Because a receipt only ever
 * flips PENDING → CLAIMED once and CLAIMED never dispatches again, every
 * receipt is delivered at most once during normal dispatch.</p>
 *
 * <p>The crate marker is kept per mission for diagnostics only: it records
 * which missions touched the shared crate, and a missing marker on a CLAIMED
 * receipt explains a lost crate in the logs. Markers never participate in
 * the grant decision. Receipts and crate markers are capped at
 * {@link #MAX_RECEIPTS} / {@link #MAX_MARKERS}; eviction only ever drops
 * CLAIMED history first, and a crate marker eviction always pairs with its
 * CLAIMED receipt so neither side can outlive the other.</p>
 *
 * <p>Payloads are registry-free {@link RewardItem} entries so the policy stays
 * unit-testable without a running game. The world adapter materializes them
 * into {@code ItemStack}s and re-validates every count against the live
 * item stack limit before anything enters a container.</p>
 */
public final class RewardOutboxPolicy {
    public static final int MAX_RECEIPTS = 256;
    public static final int MAX_MARKERS = 256;
    public static final String CREDENTIAL_TAG = "lasttrain_salvage_credential";
    private static final String OPERATION_PREFIX = "mission/";
    private static final String OPERATION_SUFFIX = "/reward/main";

    private RewardOutboxPolicy() {
    }

    public enum ReceiptState {
        PENDING,
        CLAIMED
    }

    public enum GrantDecision {
        GRANT_AND_CLAIM,
        NO_OP
    }

    /**
     * One payload entry. {@code itemId} is a registry id such as
     * {@code minecraft:bread}. The salvage credential is a single paper item
     * carrying the mission id tag — the verifiable permanent-upgrade
     * credential.
     *
     * <p>TODO(P4 车厢后端): the salvage reward is delivered as this verifiable
     * upgrade credential only. Once a vehicle backend supports assembling
     * player-facing cars, replace the credential grant with the actual
     * functional railcar attachment keyed by the same mission id, keeping
     * the credential as the fallback for backends that cannot attach cars.</p>
     */
    public record RewardItem(
            String itemId,
            int count,
            Optional<String> credentialMissionId) {
        public RewardItem {
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(credentialMissionId, "credentialMissionId");
            if (itemId.isBlank()) {
                throw new IllegalArgumentException("Reward item id must not be blank");
            }
            if (count <= 0 || count > 64) {
                throw new IllegalArgumentException(
                        "Reward counts must be within 1..64: " + count);
            }
            if (credentialMissionId.filter(String::isBlank).isPresent()) {
                throw new IllegalArgumentException("Credential mission id must not be blank");
            }
        }

        public static RewardItem item(String itemId, int count) {
            return new RewardItem(itemId, count, Optional.empty());
        }

        public static RewardItem credential(UUID missionId) {
            Objects.requireNonNull(missionId, "missionId");
            return new RewardItem("minecraft:paper", 1, Optional.of(missionId.toString()));
        }
    }

    /**
     * World-side crate abstraction so the fill logic stays pure. The adapter
     * wraps the chest block entity; {@link #addAll} either places a whole
     * batch of stacks or none of them, and {@link #contents()} maps real item
     * stacks back to registry-free {@link RewardItem} entries. Operation
     * markers are kept per mission, oldest first, so several missions can
     * share one crate without overwriting each other's markers.
     */
    public interface CrateAccess {
        List<RewardItem> contents();

        /**
         * Atomically adds the whole batch: every stack lands in full, or
         * nothing is written. Reports false — leaving the crate untouched —
         * when the crate cannot hold the batch or rejects a write, so the
         * caller defers the whole receipt instead of leaving partial residue.
         */
        boolean addAll(List<RewardItem> stacks);

        /** Operation markers persisted on the crate, oldest first. */
        List<String> operationIds();

        /** Persists one per-mission marker; an already-present marker is a no-op. */
        void addOperationMarker(String operationId);

        /** Removes one persisted operation marker. */
        void removeOperationMarker(String operationId);
    }

    /** Stable one-shot operation id for the reward mutation of a mission. */
    public static String operationId(UUID missionId) {
        Objects.requireNonNull(missionId, "missionId");
        return OPERATION_PREFIX + missionId + OPERATION_SUFFIX;
    }

    /**
     * The grant decision table. The saved data is the sole authority:
     * PENDING always means "try one atomic dispatch", CLAIMED always means
     * "never dispatch again". The world side — crate, marker, items — never
     * influences the decision.
     */
    public static GrantDecision reconcile(ReceiptState state) {
        Objects.requireNonNull(state, "state");
        return switch (state) {
            case PENDING -> GrantDecision.GRANT_AND_CLAIM;
            case CLAIMED -> GrantDecision.NO_OP;
        };
    }

    /**
     * The reward payload of a mission type. Every entry respects
     * the 1..64 sanity window; the world adapter additionally rejects any
     * stack above its live item stack limit, so an illegal stack such as a
     * count-10 minecart can never reach a container.
     */
    public static List<RewardItem> payload(MissionType type, UUID missionId) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(missionId, "missionId");
        return switch (type) {
            case RAIL_BREAK -> List.of(
                    RewardItem.item("minecraft:iron_ingot", 6),
                    RewardItem.item("minecraft:rail", 8),
                    RewardItem.item("minecraft:redstone", 4));
            case STATION_POWER -> List.of(
                    RewardItem.item("minecraft:redstone", 8),
                    RewardItem.item("minecraft:charcoal", 8),
                    RewardItem.item("minecraft:iron_ingot", 4));
            case STATION_GATE -> List.of(
                    RewardItem.item("minecraft:iron_ingot", 6),
                    RewardItem.item("minecraft:bread", 6));
            case TRACK_CLEARANCE -> List.of(
                    RewardItem.item("minecraft:iron_ingot", 4),
                    RewardItem.item("minecraft:coal", 8),
                    RewardItem.item("minecraft:torch", 12));
            case ZOMBIE_BLOCKADE -> List.of(
                    RewardItem.item("minecraft:arrow", 16),
                    RewardItem.item("minecraft:bread", 8),
                    RewardItem.item("minecraft:iron_ingot", 4));
            case RESCUE_SURVIVOR -> List.of(
                    RewardItem.item("minecraft:bread", 8),
                    RewardItem.item("minecraft:baked_potato", 8),
                    RewardItem.item("minecraft:arrow", 16),
                    RewardItem.item("minecraft:charcoal", 8),
                    RewardItem.item("minecraft:iron_ingot", 4));
            case SALVAGE_CAR -> List.of(
                    RewardItem.credential(missionId),
                    RewardItem.item("minecraft:iron_ingot", 4),
                    RewardItem.item("minecraft:redstone", 8));
            case SUPPLY_RECOVERY -> throw new IllegalArgumentException(
                    "Supply recovery pays through its mission barrels, not the outbox");
        };
    }

    /** 完成时进入 outbox 的任务；补给回收的现场容器本身就是奖励。 */
    public static boolean rewardedOnTurnIn(MissionType type) {
        return Objects.requireNonNull(type, "type") != MissionType.SUPPLY_RECOVERY;
    }

    /** Sanity window check; stack-limit enforcement happens at the adapter. */
    public static boolean isLegalPayload(List<RewardItem> payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.isEmpty()) {
            return false;
        }
        for (RewardItem item : payload) {
            if (item.count() <= 0 || item.count() > 64) {
                return false;
            }
            if (item.credentialMissionId().isPresent() && item.count() != 1) {
                return false;
            }
        }
        return true;
    }

    /**
     * Applies one grant decision. {@link GrantDecision#GRANT_AND_CLAIM} fills
     * the whole payload atomically and reports claimable only after the crate
     * holds it and the diagnostic marker was written; {@link GrantDecision#NO_OP}
     * touches nothing.
     *
     * @param crate crate access, required only for GRANT_AND_CLAIM
     * @return true when the caller must flip the receipt to CLAIMED
     */
    public static boolean settle(
            GrantDecision decision,
            CrateAccess crate,
            String operationId,
            List<RewardItem> payload) {
        Objects.requireNonNull(decision, "decision");
        return switch (decision) {
            case GRANT_AND_CLAIM -> fillCrate(crate, operationId, payload);
            case NO_OP -> false;
        };
    }

    /**
     * Atomic all-or-nothing grant of one receipt: the exact payload is
     * submitted on every PENDING attempt and either lands in full or, when
     * the crate cannot hold it, leaves the crate untouched. Existing matching
     * contents and diagnostic markers never reduce or suppress the batch. On
     * success the diagnostic marker is persisted before the receipt flips.
     *
     * @return true when the crate now holds the payload and the marker was
     *     written; false leaves the crate untouched
     */
    public static boolean fillCrate(CrateAccess crate, String operationId, List<RewardItem> payload) {
        Objects.requireNonNull(crate, "crate");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(payload, "payload");
        if (!crate.addAll(payload)) {
            return false;
        }
        crate.addOperationMarker(operationId);
        return true;
    }

    /**
     * The markers to evict so a persisted marker set returns to
     * {@link #MAX_MARKERS}, oldest first. Markers backed by PENDING receipts
     * are never evicted; when more PENDING-backed markers exist than the cap,
     * the set is allowed to exceed the cap and the returned list is shorter
     * than the overflow.
     */
    public static List<String> excessMarkers(List<String> markers, Set<String> pendingMarkers) {
        Objects.requireNonNull(markers, "markers");
        Objects.requireNonNull(pendingMarkers, "pendingMarkers");
        List<String> victims = new ArrayList<>();
        List<String> remaining = new ArrayList<>(markers);
        while (remaining.size() > MAX_MARKERS) {
            String victim = null;
            for (String marker : remaining) {
                if (!pendingMarkers.contains(marker)) {
                    victim = marker;
                    break;
                }
            }
            if (victim == null) {
                break;
            }
            remaining.remove(victim);
            victims.add(victim);
        }
        return victims;
    }

    /** Sum of matching entries across all crate slots. */
    public static int countPresent(List<RewardItem> contents, RewardItem expected) {
        int count = 0;
        for (RewardItem item : contents) {
            if (matches(item, expected)) {
                count += item.count();
            }
        }
        return count;
    }

    public static boolean containsPayload(List<RewardItem> contents, List<RewardItem> payload) {
        for (RewardItem expected : payload) {
            if (countPresent(contents, expected) < expected.count()) {
                return false;
            }
        }
        return true;
    }

    /** Credentials match by mission id; supplies match by item id. */
    private static boolean matches(RewardItem actual, RewardItem expected) {
        return expected.credentialMissionId().isPresent()
                ? expected.credentialMissionId().equals(actual.credentialMissionId())
                : expected.itemId().equals(actual.itemId());
    }
}
