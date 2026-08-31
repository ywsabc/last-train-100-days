package dev.ywsabc.lasttrain.campaign;

import dev.ywsabc.lasttrain.mission.MissionType;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 第 100 日三阶段终局的纯决策策略。
 *
 * <p>存档所有者只在逻辑服务端线程应用结果；这里集中定义阶段门槛、威胁下限、
 * 任务类型与稳定 UUID，使世界落块、任务导演和迁移测试使用同一套规则。</p>
 */
public final class FinalePolicy {
    /**
     * Schema 7 added the optional mission system (proposals, optional mission
     * list, reward receipts, pending site cleanups, mission history and team
     * membership). Schema 8 adds captain hand-off timestamps and the durable
     * in-session vote record. Schema 9 adds the persistent campaign mode; old
     * saves use STORY_100_DAYS when the mode key is absent. Schema 10 adds the
     * durable route plan state (route rules version, config salt, planner
     * cursor and pending segment plans); saves without the key keep the
     * historical route rules version 1 until an explicit migration. Schema 11
     * 增加单调感染阶段/计时；旧存档固定从阶段 0 开始，不从已封顶的 day 反算。
     * Schema 12 为活动任务与延期清理快照增加任务实体 UUID 索引，同时增加
     * 阶段链任务状态、关键物品代次和已物化道岔现场引用；并增加终局阶段、
     * 枢纽物化状态，以及起点补给/列车装配事务阶段。
     */
    public static final int CURRENT_SCHEMA = 12;
    public static final int FINAL_DAY = 100;
    public static final int FINALE_HUB_START_DAY = 90;
    public static final int FINALE_HUB_WINDOW_SEGMENTS = 8;
    private static final String FINALE_ID_NAMESPACE = "lasttrain:finale:";
    private static final int FINALE_REOPEN_SCHEMA = 6;

    private FinalePolicy() {
    }

    /** 每一阶段进入时一次性应用的压力和任务形态。 */
    public static PhaseEffect phaseEffect(FinalePhase phase) {
        return switch (java.util.Objects.requireNonNull(phase, "phase")) {
            case DORMANT, COMPLETED -> new PhaseEffect(0, null, 0);
            case ARRIVAL -> new PhaseEffect(60, null, 0);
            case RESTART -> new PhaseEffect(75, MissionType.STATION_POWER, 3);
            case HOLD_DAWN -> new PhaseEffect(90, MissionType.ZOMBIE_BLOCKADE, 0);
        };
    }

    /** 守住黎明使用不少于 24、且为同人数普通尸潮两倍的冻结目标。 */
    public static int dawnTarget(int ordinaryBlockadeTarget) {
        return Math.max(24, Math.max(1, ordinaryBlockadeTarget) * 2);
    }

    public static boolean shouldOpenArrival(
            CampaignMode mode,
            CampaignStatus status,
            int day,
            FinalePhase phase) {
        return mode == CampaignMode.STORY_100_DAYS
                && status == CampaignStatus.RUNNING
                && day >= FINAL_DAY
                && phase == FinalePhase.DORMANT;
    }

    /** 抵达门同时要求真实枢纽已经落块，且物理列车推进记录到达指定区段。 */
    public static boolean arrivalComplete(
            FinalePhase phase,
            boolean hubMaterialized,
            int routeSegment,
            int hubRouteSegment) {
        return phase == FinalePhase.ARRIVAL
                && hubMaterialized
                && hubRouteSegment > 0
                && routeSegment >= hubRouteSegment;
    }

    public static FinalePhase nextPhaseAfterTurnIn(FinalePhase phase) {
        return switch (java.util.Objects.requireNonNull(phase, "phase")) {
            case RESTART -> FinalePhase.HOLD_DAWN;
            case HOLD_DAWN -> FinalePhase.COMPLETED;
            default -> phase;
        };
    }

    public static boolean allowsOrdinaryMission(CampaignStatus status, int day) {
        return allowsOrdinaryMission(CampaignMode.STORY_100_DAYS, status, day);
    }

    public static boolean allowsOrdinaryMission(
            CampaignMode mode,
            CampaignStatus status,
            int day) {
        return status == CampaignStatus.RUNNING
                && (mode == CampaignMode.ENDLESS || day < FINAL_DAY);
    }

    /** Ordinary mainline roadblocks stop competing for the finale window on day 90. */
    public static boolean allowsOrdinaryMainlineMission(CampaignStatus status, int day) {
        return allowsOrdinaryMainlineMission(CampaignMode.STORY_100_DAYS, status, day);
    }

    public static boolean allowsOrdinaryMainlineMission(
            CampaignMode mode,
            CampaignStatus status,
            int day) {
        return status == CampaignStatus.RUNNING
                && (mode == CampaignMode.ENDLESS || day < FINALE_HUB_START_DAY);
    }

    public static boolean finaleHubWindowOpen(CampaignStatus status, int day) {
        return finaleHubWindowOpen(CampaignMode.STORY_100_DAYS, status, day);
    }

    public static boolean finaleHubWindowOpen(
            CampaignMode mode,
            CampaignStatus status,
            int day) {
        return mode != CampaignMode.ENDLESS
                && status == CampaignStatus.RUNNING
                && day >= FINALE_HUB_START_DAY;
    }

    /** Returns the finite, strictly-forward hub reservation window. */
    public static HubWindow finaleHubWindow(int currentRouteSegment) {
        return finaleHubWindow(currentRouteSegment, currentRouteSegment);
    }

    /**
     * 枢纽必须位于已验证生成前沿之外，确保不会把玩家已经探索的普通区段替换掉。
     */
    public static HubWindow finaleHubWindow(
            int currentRouteSegment,
            int generatedRouteSegment) {
        int current = Math.max(0, Math.max(currentRouteSegment, generatedRouteSegment));
        return new HubWindow(
                current + 1,
                current + FINALE_HUB_WINDOW_SEGMENTS);
    }

    public static boolean isFinaleHubInForwardWindow(
            int currentRouteSegment,
            int finaleHubRouteSegment) {
        return isFinaleHubInForwardWindow(
                currentRouteSegment,
                currentRouteSegment,
                finaleHubRouteSegment);
    }

    public static boolean isFinaleHubInForwardWindow(
            int currentRouteSegment,
            int generatedRouteSegment,
            int finaleHubRouteSegment) {
        HubWindow window = finaleHubWindow(currentRouteSegment, generatedRouteSegment);
        return finaleHubRouteSegment >= window.firstSegment()
                && finaleHubRouteSegment <= window.lastSegment();
    }

    /**
     * Picks a stable location in the current forward window. The campaign UUID
     * makes the choice stable across restarts while the current route keeps a
     * stale/explored segment from being reused.
     */
    public static int chooseFinaleHubSegment(
            UUID campaignId,
            int currentRouteSegment) {
        return chooseFinaleHubSegment(campaignId, currentRouteSegment, currentRouteSegment);
    }

    public static int chooseFinaleHubSegment(
            UUID campaignId,
            int currentRouteSegment,
            int generatedRouteSegment) {
        UUID id = java.util.Objects.requireNonNull(campaignId, "campaignId");
        int frontier = Math.max(currentRouteSegment, generatedRouteSegment);
        HubWindow window = finaleHubWindow(currentRouteSegment, generatedRouteSegment);
        long mixed = id.getMostSignificantBits()
                ^ Long.rotateLeft(id.getLeastSignificantBits(), 17)
                ^ (long) Math.max(0, frontier) * 0x9E3779B97F4A7C15L;
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        mixed *= 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        int span = window.lastSegment() - window.firstSegment() + 1;
        return window.firstSegment() + Math.floorMod(mixed, span);
    }

    public static UUID missionId(UUID campaignId) {
        return missionId(campaignId, FinalePhase.HOLD_DAWN);
    }

    /** 每阶段任务 UUID 稳定且互不复用，清除/重启后可以安全重建当前任务。 */
    public static UUID missionId(UUID campaignId, FinalePhase phase) {
        return UUID.nameUUIDFromBytes(
                (FINALE_ID_NAMESPACE + campaignId + ":" + phase.serializedName())
                        .getBytes(StandardCharsets.UTF_8));
    }

    public static MigratedState migrate(
            int loadedSchema,
            CampaignStatus status,
            int day,
            boolean finalDayElapsed,
            boolean finaleMissionCompleted,
            boolean hasActiveMission) {
        return migrate(
                CampaignMode.STORY_100_DAYS,
                loadedSchema,
                status,
                day,
                finalDayElapsed,
                finaleMissionCompleted,
                hasActiveMission,
                false,
                FinalePhase.DORMANT,
                false);
    }

    public static MigratedState migrate(
            CampaignMode mode,
            int loadedSchema,
            CampaignStatus status,
            int day,
            boolean finalDayElapsed,
            boolean finaleMissionCompleted,
            boolean hasActiveMission) {
        return migrate(
                mode,
                loadedSchema,
                status,
                day,
                finalDayElapsed,
                finaleMissionCompleted,
                hasActiveMission,
                false,
                FinalePhase.DORMANT,
                false);
    }

    public static MigratedState migrate(
            CampaignMode mode,
            int loadedSchema,
            CampaignStatus status,
            int day,
            boolean finalDayElapsed,
            boolean finaleMissionCompleted,
            boolean hasActiveMission,
            boolean activeMissionIsBlockade,
            FinalePhase loadedPhase,
            boolean hubMaterialized) {
        CampaignStatus migratedStatus = status;
        boolean migratedElapsed = finalDayElapsed;
        boolean migratedMissionCompleted = finaleMissionCompleted;
        FinalePhase migratedPhase = java.util.Objects.requireNonNullElse(
                loadedPhase,
                FinalePhase.DORMANT);
        boolean migratedHubMaterialized = hubMaterialized;

        if (mode == CampaignMode.ENDLESS) {
            // 无限模式不再占用百日终局状态机，但保留历史完成收据。
            return new MigratedState(
                    migratedStatus == CampaignStatus.COMPLETED
                            ? CampaignStatus.RUNNING
                            : migratedStatus,
                    false,
                    migratedMissionCompleted,
                    FinalePhase.DORMANT,
                    false);
        }

        if (loadedSchema < FINALE_REOPEN_SCHEMA) {
            // Schema 5 only reached COMPLETED after its final-day timer path.
            // Preserve that elapsed-time achievement but require the new
            // finale mission before considering the campaign complete again.
            // Schema 6+ saves already carry the finale mission state and must
            // not be reopened by later schema bumps.
            if (status == CampaignStatus.COMPLETED && day >= FINAL_DAY) {
                migratedElapsed = true;
                migratedStatus = CampaignStatus.RUNNING;
            }
            migratedMissionCompleted = false;
        }

        if (loadedSchema < CURRENT_SCHEMA) {
            // Schema 11 的单一尸潮任务等价于已经完成“抵达、重启”，直接迁到
            // 守住黎明并保留任务进度；未开始旧终局则从抵达阶段补齐新流程。
            if (migratedMissionCompleted) {
                migratedPhase = FinalePhase.COMPLETED;
                migratedHubMaterialized = true;
            } else if (day >= FINAL_DAY && hasActiveMission && activeMissionIsBlockade) {
                migratedPhase = FinalePhase.HOLD_DAWN;
                migratedHubMaterialized = true;
            } else if (day >= FINAL_DAY) {
                migratedPhase = FinalePhase.ARRIVAL;
                migratedHubMaterialized = false;
            } else {
                migratedPhase = FinalePhase.DORMANT;
                migratedHubMaterialized = false;
            }
        }

        if (day < FINAL_DAY) {
            migratedElapsed = false;
            migratedMissionCompleted = false;
            migratedPhase = FinalePhase.DORMANT;
            migratedHubMaterialized = false;
        } else if (!migratedMissionCompleted
                && migratedPhase == FinalePhase.DORMANT) {
            migratedPhase = FinalePhase.ARRIVAL;
        }
        if (migratedPhase == FinalePhase.COMPLETED) {
            migratedMissionCompleted = true;
        } else if (migratedMissionCompleted) {
            migratedPhase = FinalePhase.COMPLETED;
        }
        if (migratedStatus == CampaignStatus.COMPLETED
                && (!migratedElapsed
                        || migratedPhase != FinalePhase.COMPLETED
                        || hasActiveMission)) {
            migratedStatus = CampaignStatus.RUNNING;
        }
        return new MigratedState(
                migratedStatus,
                migratedElapsed,
                migratedMissionCompleted,
                migratedPhase,
                migratedHubMaterialized);
    }

    public record HubWindow(int firstSegment, int lastSegment) {
        public HubWindow {
            if (firstSegment < 1 || lastSegment < firstSegment) {
                throw new IllegalArgumentException("Finale hub window must be forward and non-empty");
            }
        }
    }

    public record MigratedState(
            CampaignStatus status,
            boolean finalDayElapsed,
            boolean finaleMissionCompleted,
            FinalePhase phase,
            boolean hubMaterialized) {
    }

    public record PhaseEffect(
            int minimumThreat,
            MissionType missionType,
            int fixedTarget) {
        public PhaseEffect {
            minimumThreat = Math.clamp(minimumThreat, 0, 100);
            fixedTarget = Math.max(0, fixedTarget);
        }
    }
}
