package dev.ywsabc.lasttrain.mission;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 任务完成奖励的持久 outbox 策略。
 *
 * <p>奖励箱 block entity 上的 operation marker 与存档中的已完成 operation
 * 集合，是两份互相独立的世界写入证明。收据负责持久化投递意图与确认状态；任一
 * 完成证明命中即可阻止重发：</p>
 * <ul>
 * <li>完成证明存在 + PENDING 收据 → {@link GrantDecision#CLAIM_ONLY}；</li>
 * <li>完成证明缺失（PENDING 或 CLAIMED）→
 * {@link GrantDecision#GRANT_AND_CLAIM};</li>
 * <li>完成证明存在 + CLAIMED 收据 → {@link GrantDecision#NO_OP}。</li>
 * </ul>
 * 成功投递会先把完整 payload 与 marker 写入同一个 block entity，再把收据改为
 * CLAIMED 并写入存档侧完成证明。崩溃导致任一存储领先时，下一轮扫描都只会补齐
 * 另一侧，不会重复或丢失奖励。
 *
 * <p>每次投递以单张收据为原子边界：payload 要么完整落箱，要么完整延后，失败
 * 不会留下部分奖励。箱内恰好存在同类物品不算投递证明，只有稳定 marker 或存档
 * 完成集合才算；每个 operationId 最多改变一次奖励箱。</p>
 *
 * <p>收据和 marker 分别受 {@link #MAX_RECEIPTS} / {@link #MAX_MARKERS} 限制；
 * 淘汰只优先删除 CLAIMED 历史，marker 淘汰时会同步删除对应 CLAIMED 收据及存档
 * 完成证明，避免三份状态失配。</p>
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

    /** Crash-consistent decision across the receipt and crate stores. */
    public static GrantDecision reconcile(ReceiptState state, boolean operationApplied) {
        Objects.requireNonNull(state, "state");
        if (!operationApplied) {
            return GrantDecision.GRANT_AND_CLAIM;
        }
        return state == ReceiptState.PENDING
                ? GrantDecision.CLAIM_ONLY
                : GrantDecision.NO_OP;
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
            case SWITCH_SIGNAL -> List.of(
                    RewardItem.item("minecraft:redstone", 8),
                    RewardItem.item("minecraft:copper_ingot", 6),
                    RewardItem.item("minecraft:iron_ingot", 4));
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
     * holds it and the operation marker was written; {@link GrantDecision#NO_OP}
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
            case CLAIM_ONLY -> true;
            case NO_OP -> false;
        };
    }

    /**
     * Atomic all-or-nothing grant of one receipt: the exact payload is
     * submitted only when its marker is absent and either lands in full or,
     * when the crate cannot hold it, leaves the crate untouched. A matching
     * marker proves the operation already landed and suppresses every replay.
     * On success the marker is persisted before the receipt flips.
     *
     * @return true when the crate now holds the payload and the marker was
     *     written; false leaves the crate untouched
     */
    public static boolean fillCrate(CrateAccess crate, String operationId, List<RewardItem> payload) {
        Objects.requireNonNull(crate, "crate");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(payload, "payload");
        if (crate.operationIds().contains(operationId)) {
            return true;
        }
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
