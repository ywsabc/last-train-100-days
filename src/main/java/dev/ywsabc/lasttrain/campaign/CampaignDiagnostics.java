package dev.ywsabc.lasttrain.campaign;

import dev.ywsabc.lasttrain.mission.ActiveMission;
import dev.ywsabc.lasttrain.mission.MissionEntityContainer;
import dev.ywsabc.lasttrain.mission.MissionStage;
import dev.ywsabc.lasttrain.mission.MissionType;
import dev.ywsabc.lasttrain.mission.RewardOutboxPolicy;
import dev.ywsabc.lasttrain.route.RouteProgressPolicy;
import java.util.Objects;
import java.util.Optional;

/**
 * `/lasttrain status` 使用的不可变只读快照。
 *
 * <p>命令层不再逐项探入可变存档对象；所有统计在同一服务器 tick 内一次采样，
 * 因而多行详细视图不会混合两个时刻的任务、路线或奖励状态。</p>
 */
public record CampaignDiagnostics(
        CampaignMode mode,
        CampaignStatus status,
        int day,
        int activeTicksIntoDay,
        long totalActiveTicks,
        int routeSegment,
        int expectedRouteSegment,
        int generatedRouteSegment,
        int plannedRouteSegments,
        RouteProgressPolicy.Pace pace,
        int threat,
        int effectivePlayers,
        int onlinePlayers,
        int attention,
        PursuitPolicy.AttentionLevel attentionLevel,
        int pursuitDistance,
        InfectionPolicy.Stage infectionStage,
        long infectionTicks,
        Optional<MissionView> activeMission,
        int optionalMissions,
        boolean proposalPending,
        int pendingRewards,
        int claimedRewards,
        int pendingCleanups,
        int missionEntities,
        int missionEntityCapacity,
        int teamMembers,
        boolean captainAssigned,
        boolean starterTrainAssembled,
        boolean starterTrainIdentityPresent,
        int trainMissingTicks,
        int trainImmobileTicks,
        int rescueCount,
        int rescueAnchorSegment) {

    public CampaignDiagnostics {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(pace, "pace");
        Objects.requireNonNull(attentionLevel, "attentionLevel");
        Objects.requireNonNull(infectionStage, "infectionStage");
        activeMission = Objects.requireNonNull(activeMission, "activeMission");
        onlinePlayers = Math.max(0, onlinePlayers);
    }

    public static CampaignDiagnostics snapshot(CampaignSavedData data, int onlinePlayers) {
        Objects.requireNonNull(data, "data");
        InfectionPolicy.Sample infection = data.infectionSample();
        long pending = data.rewardReceipts().stream()
                .filter(receipt -> receipt.state() == RewardOutboxPolicy.ReceiptState.PENDING)
                .count();
        int pendingRewards = Math.toIntExact(pending);
        int receiptCount = data.rewardReceipts().size();
        return new CampaignDiagnostics(
                data.mode(),
                data.status(),
                data.day(),
                data.activeTicksIntoDay(),
                data.totalActiveTicks(),
                data.routeSegment(),
                data.expectedRouteSegment(),
                data.generatedRouteSegment(),
                data.plannedRouteSegments().size(),
                CampaignPacingPolicy.pace(
                        data.mode(), data.day(), data.routeSegment()).pace(),
                data.threat(),
                data.effectivePlayers(),
                onlinePlayers,
                data.attention(),
                PursuitPolicy.attentionLevel(data.attention()),
                data.pursuitDistance(),
                infection.stage(),
                infection.infectionTicks(),
                Optional.ofNullable(data.activeMission()).map(MissionView::from),
                data.optionalMissions().size(),
                data.proposedMission() != null,
                pendingRewards,
                receiptCount - pendingRewards,
                data.pendingSiteCleanups().size(),
                data.missionEntityCount(),
                MissionEntityContainer.MAX_REGISTERED_ENTITIES,
                data.teamMembers().size(),
                data.captainId() != null,
                data.starterTrainAssembled(),
                data.starterTrainSublevelId() != null,
                data.trainMissingTicks(),
                data.trainImmobileTicks(),
                data.rescueCount(),
                data.rescueAnchorSegment());
    }

    public int routeDeltaFromExpected() {
        return routeSegment - expectedRouteSegment;
    }

    public int generatedLead() {
        return generatedRouteSegment - routeSegment;
    }

    /** 当前主任务的最小不可变投影。 */
    public record MissionView(
            java.util.UUID id,
            MissionType type,
            MissionStage stage,
            int progress,
            int target,
            int routeSegment) {
        public MissionView {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(stage, "stage");
        }

        private static MissionView from(ActiveMission mission) {
            return new MissionView(
                    mission.id(),
                    mission.type(),
                    mission.stage(),
                    mission.progress(),
                    mission.target(),
                    mission.routeSegment());
        }
    }
}
