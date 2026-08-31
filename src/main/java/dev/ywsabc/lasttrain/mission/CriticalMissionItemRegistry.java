package dev.ywsabc.lasttrain.mission;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * 关键任务物品注册表。
 *
 * <p>定义同时绑定任务类型和内部阶段，不能只凭普通物品 ID 判断。这样玩家自带
 * 的绊线钩或铁镐不会被当成任务保险件，旧阶段、旧任务或旧补发代次的复制品也
 * 无法推进目标。</p>
 */
public final class CriticalMissionItemRegistry {
    public static final String MISSION_ID_TAG = "lasttrain_critical_mission_id";
    public static final String KEY_TAG = "lasttrain_critical_key";
    public static final String PHASE_TAG = "lasttrain_critical_phase";
    public static final String GENERATION_TAG = "lasttrain_critical_generation";

    private CriticalMissionItemRegistry() {
    }

    public enum Key {
        STATION_FUSE(
                "station_fuse",
                "minecraft:tripwire_hook",
                MissionType.STATION_POWER,
                MissionType.MissionPhase.RESTORE_POWER,
                false),
        SWITCH_WRENCH(
                "switch_wrench",
                "minecraft:iron_pickaxe",
                MissionType.SWITCH_SIGNAL,
                MissionType.MissionPhase.REPAIR_SWITCH_BOX,
                true);

        private final String serializedName;
        private final String itemId;
        private final MissionType missionType;
        private final MissionType.MissionPhase phase;
        private final boolean completesPhaseOnRedeem;

        Key(
                String serializedName,
                String itemId,
                MissionType missionType,
                MissionType.MissionPhase phase,
                boolean completesPhaseOnRedeem) {
            this.serializedName = serializedName;
            this.itemId = itemId;
            this.missionType = missionType;
            this.phase = phase;
            this.completesPhaseOnRedeem = completesPhaseOnRedeem;
        }

        public String serializedName() {
            return serializedName;
        }

        public String itemId() {
            return itemId;
        }

        public MissionType missionType() {
            return missionType;
        }

        public MissionType.MissionPhase phase() {
            return phase;
        }

        public boolean completesPhaseOnRedeem() {
            return completesPhaseOnRedeem;
        }

        public static Optional<Key> parse(String value) {
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Arrays.stream(values())
                    .filter(candidate -> candidate.serializedName.equals(value))
                    .findFirst();
        }
    }

    /** 当前任务阶段需要的关键物品；普通目标阶段没有注册项。 */
    public static Optional<Key> requiredBy(ActiveMission mission) {
        if (mission == null || mission.stage() != MissionStage.ACTIVE) {
            return Optional.empty();
        }
        return requiredBy(mission.type(), mission.currentPhase());
    }

    public static Optional<Key> requiredBy(
            MissionType type,
            MissionType.MissionPhase phase) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(phase, "phase");
        return Arrays.stream(Key.values())
                .filter(key -> key.missionType == type && key.phase == phase)
                .findFirst();
    }

    /** 标记必须精确属于当前任务、当前阶段、当前代次。 */
    public static boolean accepts(
            ActiveMission mission,
            java.util.UUID markedMissionId,
            Key markedKey,
            MissionType.MissionPhase markedPhase,
            long markedGeneration) {
        return mission != null
                && markedMissionId != null
                && markedMissionId.equals(mission.id())
                && requiredBy(mission).filter(key -> key == markedKey).isPresent()
                && markedPhase == mission.currentPhase()
                && markedGeneration > 0L
                && markedGeneration == mission.criticalItemGeneration()
                && !mission.criticalItemRedeemed();
    }

    /** 世界适配器还必须核对载体物品 ID，不能把复制到任意物品上的标签当真。 */
    public static boolean accepts(
            ActiveMission mission,
            java.util.UUID markedMissionId,
            Key markedKey,
            MissionType.MissionPhase markedPhase,
            long markedGeneration,
            String markedItemId) {
        return accepts(
                        mission,
                        markedMissionId,
                        markedKey,
                        markedPhase,
                        markedGeneration)
                && markedKey.itemId().equals(markedItemId);
    }
}
