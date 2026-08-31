package dev.ywsabc.lasttrain.server;

import java.util.Locale;
import java.util.Objects;

/**
 * 起点公共补给与列车装配的纯事务决策。
 *
 * <p>SavedData 保存服务端意图，箱体操作标记和列车标签保存世界侧事实。重启时
 * 两边重新对账：世界操作已经完成就只补提交标记，装配请求已经发出但找不到
 * 源布局时则失败即停止，绝不擅自放置第二列车。</p>
 */
public final class BootstrapTransactionPolicy {
    private BootstrapTransactionPolicy() {
    }

    public static SupplyDirective supplyDirective(
            SupplyPhase phase,
            boolean matchingContainerMarker) {
        Objects.requireNonNull(phase, "phase");
        if (phase == SupplyPhase.COMMITTED) {
            return SupplyDirective.NONE;
        }
        return matchingContainerMarker
                ? SupplyDirective.COMMIT_ONLY
                : SupplyDirective.WRITE_AND_COMMIT;
    }

    public static AssemblyDirective assemblyDirective(
            AssemblyPhase phase,
            boolean completeSourceLayout,
            boolean taggedTrainPresent) {
        Objects.requireNonNull(phase, "phase");
        if (taggedTrainPresent) {
            return AssemblyDirective.RECOVER_TRAIN;
        }
        if (phase == AssemblyPhase.COMMITTED) {
            return AssemblyDirective.WAIT_FOR_RECOVERY;
        }
        if (completeSourceLayout) {
            return AssemblyDirective.RESUME_ASSEMBLY;
        }
        if (phase == AssemblyPhase.NOT_STARTED) {
            return AssemblyDirective.PLACE_LAYOUT;
        }
        return AssemblyDirective.WAIT_FOR_RECOVERY;
    }

    public enum SupplyPhase {
        NOT_STARTED,
        PREPARING,
        COMMITTED;

        public static SupplyPhase parse(String value) {
            if (value == null || value.isBlank()) {
                return NOT_STARTED;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return NOT_STARTED;
            }
        }
    }

    public enum AssemblyPhase {
        NOT_STARTED,
        LAYOUT_PREPARED,
        ASSEMBLY_REQUESTED,
        COMMITTED;

        public static AssemblyPhase parse(String value) {
            if (value == null || value.isBlank()) {
                return NOT_STARTED;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return NOT_STARTED;
            }
        }
    }

    public enum SupplyDirective {
        NONE,
        WRITE_AND_COMMIT,
        COMMIT_ONLY
    }

    public enum AssemblyDirective {
        PLACE_LAYOUT,
        RESUME_ASSEMBLY,
        RECOVER_TRAIN,
        WAIT_FOR_RECOVERY
    }
}
