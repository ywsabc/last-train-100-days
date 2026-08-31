package dev.ywsabc.lasttrain.mission;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Mission definitions for the campaign director.
 *
 * <p>{@link Category#MAIN} missions occupy the single mission slot, act as
 * progression checkpoints and block the route. {@link Category#SUPPORT}
 * missions occupy the same single slot but never block route progress (the
 * fuel/supply guarantee). {@link Category#OPTIONAL} missions run beside the
 * main line, are offered as proposals first and never occupy the slot.</p>
 */
public enum MissionType {
    RAIL_BREAK("rail_break", 3, Category.MAIN, MissionPhase.OBJECTIVE),
    /** 供电、开门和防守属于同一任务实例，任何阶段都不能越过前一阶段。 */
    STATION_POWER(
            "station_power",
            4,
            Category.MAIN,
            MissionPhase.RESTORE_POWER,
            MissionPhase.OPEN_GATE,
            MissionPhase.DEFEND_GATE),
    /**
     * 仅用于读取旧存档和兼容管理命令；新的任务池不再单独生成开门任务。
     */
    STATION_GATE("station_gate", 2, Category.MAIN, MissionPhase.OPEN_GATE),
    /** 隧道坍塌或废车形成的真实净空障碍，可用工具或爆炸清除。 */
    TRACK_CLEARANCE("track_clearance", 5, Category.MAIN, MissionPhase.OBJECTIVE),
    /** 道岔箱修复后，才允许在两个信号位置确认线路。 */
    SWITCH_SIGNAL(
            "switch_signal",
            1,
            Category.MAIN,
            MissionPhase.REPAIR_SWITCH_BOX,
            MissionPhase.CONFIRM_SIGNALS),
    SUPPLY_RECOVERY("supply_recovery", 5, Category.SUPPORT, MissionPhase.OBJECTIVE),
    ZOMBIE_BLOCKADE("zombie_blockade", 12, Category.MAIN, MissionPhase.OBJECTIVE),
    RESCUE_SURVIVOR("rescue_survivor", 1, Category.OPTIONAL, MissionPhase.OBJECTIVE),
    SALVAGE_CAR("salvage_car", 6, Category.OPTIONAL, MissionPhase.OBJECTIVE);

    private final String serializedName;
    private final int defaultTarget;
    private final Category category;
    private final List<MissionPhase> phaseChain;

    MissionType(
            String serializedName,
            int defaultTarget,
            Category category,
            MissionPhase... phaseChain) {
        this.serializedName = serializedName;
        this.defaultTarget = defaultTarget;
        this.category = category;
        if (phaseChain.length == 0) {
            throw new IllegalArgumentException("任务至少需要一个阶段");
        }
        this.phaseChain = List.of(phaseChain);
    }

    public String serializedName() {
        return serializedName;
    }

    public int defaultTarget() {
        return defaultTarget;
    }

    public Category category() {
        return category;
    }

    /** 按存档稳定顺序返回任务阶段；列表不可变。 */
    public List<MissionPhase> phaseChain() {
        return phaseChain;
    }

    public boolean sequential() {
        return phaseChain.size() > 1;
    }

    /**
     * 返回某阶段冻结的目标量。首阶段沿用创建任务时的人数缩放目标，后续阶段
     * 使用稳定规则，避免版本更新或重连后重新抽取目标。
     */
    public int phaseTarget(int phaseIndex, int frozenPrimaryTarget) {
        if (phaseIndex < 0 || phaseIndex >= phaseChain.size()) {
            throw new IllegalArgumentException("阶段索引越界：" + phaseIndex);
        }
        int primary = Math.max(1, frozenPrimaryTarget);
        return switch (phaseChain.get(phaseIndex)) {
            case OPEN_GATE, CONFIRM_SIGNALS -> 2;
            case DEFEND_GATE -> Math.max(6, primary * 2);
            case OBJECTIVE, RESTORE_POWER, REPAIR_SWITCH_BOX -> primary;
        };
    }

    /**
     * The single authoritative mainline criterion: only MAIN missions hold
     * the route checkpoint. SUPPORT, OPTIONAL and unaccepted proposal
     * missions must never stop the train from progressing.
     */
    public boolean blocksRoute() {
        return category == Category.MAIN;
    }

    /** True for every mission that occupies the single mainline mission slot. */
    public boolean occupiesMainlineSlot() {
        return category != Category.OPTIONAL;
    }

    /** Missions whose own reward loop guarantees a material safety net. */
    public boolean guaranteedSupplies() {
        return this == SUPPLY_RECOVERY;
    }

    public static Optional<MissionType> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.serializedName.equals(normalized))
                .findFirst();
    }

    public enum Category {
        /** Route-blocking progression checkpoint. */
        MAIN,
        /** Occupies the mainline slot but never blocks the route. */
        SUPPORT,
        /** Side mission offered as a proposal; never occupies the mainline slot. */
        OPTIONAL
    }

    /**
     * 任务内部阶段。枚举名和顺序写入任务存档；新增任务应组合这些阶段，而不是
     * 再创建互相独立、可能乱序出现的半个任务。
     */
    public enum MissionPhase {
        OBJECTIVE,
        RESTORE_POWER,
        OPEN_GATE,
        DEFEND_GATE,
        REPAIR_SWITCH_BOX,
        CONFIRM_SIGNALS
    }
}
