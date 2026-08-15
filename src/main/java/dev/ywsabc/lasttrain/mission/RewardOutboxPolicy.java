package dev.ywsabc.lasttrain.mission;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Durable reward outbox policy for optional missions.
 *
 * <p>Ordering contract: the saved data persists a PENDING receipt before the
 * world mutation runs, and the shared supply crate persists one marker per
 * mission — a mission-keyed marker set, never a single overwritten operation
 * id. On restart the dispatcher reconciles instead of assuming either side
 * won:</p>
 * <ul>
 * <li>PENDING receipt, marker absent → grant, then claim (normal run, or a
 * crash before the world mutation);</li>
 * <li>PENDING receipt, marker present → verify/repair crate contents, claim
 * without granting again (crash between world write and receipt update);</li>
 * <li>CLAIMED receipt, marker absent → the chunk save was lost: grant again —
 * the absent marker proves no crate exists, so this cannot duplicate;</li>
 * <li>CLAIMED receipt, marker present → nothing to do.</li>
 * </ul>
 * Every combination of the two crash orders therefore delivers the reward
 * exactly once: no loss and no duplication. Because markers are kept per
 * mission, any number of receipts can share the crate: a grant for mission A
 * never overwrites the marker of mission B, so two CLAIMED receipts deliver
 * exactly once no matter how many dispatch rounds run.</p>
 *
 * <p>Receipts and crate markers are capped at {@link #MAX_RECEIPTS} /
 * {@link #MAX_MARKERS}. Eviction only ever drops CLAIMED history first, and a
 * crate marker eviction always pairs with its CLAIMED receipt so neither side
 * can re-open the grant loop.</p>
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
        CLAIM_ONLY,
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
            if (count <= 0 || count > 64) {
                throw new IllegalArgumentException(
                        "Reward counts must be within 1..64: " + count);
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
     * wraps the chest block entity; {@link #addStack} merges into matching
     * stacks or places into a free slot, and {@link #contents()} maps real
     * item stacks back to registry-free {@link RewardItem} entries.
     * Operation markers are kept per mission, oldest first, so several
     * missions can share one crate without overwriting each other's markers.
     */
    public interface CrateAccess {
        List<RewardItem> contents();

        void addStack(RewardItem stack);

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

    public static GrantDecision reconcile(ReceiptState state, boolean worldMarkerPresent) {
        Objects.requireNonNull(state, "state");
        return switch (state) {
            case PENDING -> worldMarkerPresent ? GrantDecision.CLAIM_ONLY : GrantDecision.GRANT_AND_CLAIM;
            case CLAIMED -> worldMarkerPresent ? GrantDecision.NO_OP : GrantDecision.GRANT_AND_CLAIM;
        };
    }

    /**
     * The reward payload of an optional mission type. Every entry respects
     * the 1..64 sanity window; the world adapter additionally rejects any
     * stack above its live item stack limit, so an illegal stack such as a
     * count-10 minecart can never reach a container.
     */
    public static List<RewardItem> payload(MissionType type, UUID missionId) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(missionId, "missionId");
        return switch (type) {
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
            default -> throw new IllegalArgumentException(type + " has no optional reward");
        };
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
     * Idempotent crate fill: an unmarked crate receives the payload and the
     * mission's own marker; a crate already carrying that mission's marker is
     * only topped up with missing payload items (crash mid-fill), never
     * duplicated beyond the payload counts. The marker is written only once
     * the crate actually holds the payload: a full crate stays unmarked so
     * the next dispatch round retries instead of stalling as delivered.
     */
    public static boolean fillCrate(CrateAccess crate, String operationId, List<RewardItem> payload) {
        Objects.requireNonNull(crate, "crate");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(payload, "payload");
        if (!crate.operationIds().contains(operationId)) {
            for (RewardItem stack : payload) {
                crate.addStack(stack);
            }
        }
        for (RewardItem stack : payload) {
            if (countPresent(crate.contents(), stack) < stack.count()) {
                crate.addStack(stack);
            }
        }
        if (!containsPayload(crate.contents(), payload)) {
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
