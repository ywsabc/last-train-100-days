package dev.ywsabc.lasttrain.mission;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 关键物品补发与反复制的纯策略。
 *
 * <p>扫描不到当前代次时签发下一代，所有旧代次立即失效；扫描到多个当前代次
 * 时只保留位置键最小的一份，其余副本移除。核销后不再补发，并清理所有残留。
 * 策略只处理位置标识和代次，世界容器、玩家背包及掉落实体由适配器枚举。</p>
 */
public final class CriticalItemRecoveryPolicy {
    private CriticalItemRecoveryPolicy() {
    }

    public record ObservedCopy(String location, long generation) {
        public ObservedCopy {
            if (location == null || location.isBlank()) {
                throw new IllegalArgumentException("关键物品位置不能为空");
            }
            generation = Math.max(0L, generation);
        }
    }

    public record Plan(
            boolean issueReplacement,
            long replacementGeneration,
            Optional<String> keepLocation,
            Set<String> invalidateLocations) {
        public Plan {
            keepLocation = Objects.requireNonNull(keepLocation, "keepLocation");
            invalidateLocations = Set.copyOf(Objects.requireNonNull(
                    invalidateLocations,
                    "invalidateLocations"));
            if (replacementGeneration < 0L) {
                throw new IllegalArgumentException("补发代次不能为负数");
            }
        }
    }

    public static Plan reconcile(
            long currentGeneration,
            boolean redeemed,
            List<ObservedCopy> observedCopies) {
        long current = Math.max(0L, currentGeneration);
        List<ObservedCopy> copies = new ArrayList<>(Objects.requireNonNull(
                observedCopies,
                "observedCopies"));
        copies.sort(Comparator.comparing(ObservedCopy::location));

        LinkedHashSet<String> invalid = new LinkedHashSet<>();
        ObservedCopy kept = null;
        for (ObservedCopy copy : copies) {
            if (!redeemed && copy.generation() == current && current > 0L && kept == null) {
                kept = copy;
            } else {
                invalid.add(copy.location());
            }
        }
        if (redeemed) {
            return new Plan(false, current, Optional.empty(), invalid);
        }
        if (kept != null) {
            return new Plan(false, current, Optional.of(kept.location()), invalid);
        }
        if (current == Long.MAX_VALUE) {
            return new Plan(false, current, Optional.empty(), invalid);
        }
        return new Plan(true, current + 1L, Optional.empty(), invalid);
    }
}
