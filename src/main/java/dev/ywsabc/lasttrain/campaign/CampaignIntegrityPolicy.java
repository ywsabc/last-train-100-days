package dev.ywsabc.lasttrain.campaign;

import dev.ywsabc.lasttrain.mission.ActiveMission;
import dev.ywsabc.lasttrain.mission.MissionStage;
import dev.ywsabc.lasttrain.mission.OptionalMissionPolicy;
import dev.ywsabc.lasttrain.mission.RewardOutboxPolicy;
import dev.ywsabc.lasttrain.route.RouteSegmentPlan;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 不修改存档的低成本完整性审计。
 *
 * <p>加载器继续负责限幅和兼容迁移；本策略只检查跨字段不变量，并返回稳定代码，
 * 供命令、日志和后续恢复工具共同使用。警告表示可继续但值得检查，错误表示状态
 * 之间已经互相矛盾。本策略不尝试自动删除任务、列车或玩家内容。</p>
 */
public final class CampaignIntegrityPolicy {
    private CampaignIntegrityPolicy() {
    }

    public static Report audit(CampaignSavedData data) {
        Objects.requireNonNull(data, "data");
        List<Issue> issues = new ArrayList<>();
        issues.addAll(data.integrityEvents());

        ActiveMission active = data.activeMission();
        if (active != null && !active.type().occupiesMainlineSlot()) {
            issues.add(error(Code.OPTIONAL_IN_MAINLINE_SLOT, active.id().toString()));
        }

        Set<UUID> liveMissionIds = new HashSet<>();
        addMissionId(issues, liveMissionIds, active);
        ActiveMission proposal = data.proposedMission();
        if (proposal != null) {
            if (!OptionalMissionPolicy.isOptional(proposal.type())
                    || proposal.stage() != MissionStage.PROPOSED) {
                issues.add(error(Code.INVALID_PROPOSAL, proposal.id().toString()));
            }
            addMissionId(issues, liveMissionIds, proposal);
        }
        for (ActiveMission optional : data.optionalMissions()) {
            if (!OptionalMissionPolicy.isOptional(optional.type())
                    || optional.stage() == MissionStage.PROPOSED
                    || optional.stage().terminal()) {
                issues.add(error(Code.INVALID_OPTIONAL_SLOT, optional.id().toString()));
            }
            addMissionId(issues, liveMissionIds, optional);
        }

        for (CampaignSavedData.RewardReceipt receipt : data.rewardReceipts()) {
            if (receipt.state() != RewardOutboxPolicy.ReceiptState.PENDING
                    || !OptionalMissionPolicy.isOptional(receipt.type())) {
                continue;
            }
            boolean matchingRewardMission = data.optionalMission(receipt.missionId())
                    .map(mission -> mission.stage() == MissionStage.REWARD_PENDING
                            && mission.type() == receipt.type())
                    .orElse(false);
            if (!matchingRewardMission) {
                issues.add(warning(
                        Code.ORPHAN_OPTIONAL_REWARD,
                        receipt.missionId().toString()));
            }
        }

        if (data.captainId() != null && !data.teamMembers().contains(data.captainId())) {
            issues.add(error(Code.CAPTAIN_NOT_IN_TEAM, data.captainId().toString()));
        }
        if (data.status() == CampaignStatus.COMPLETED
                && (!data.finalDayElapsed()
                        || !data.finaleMissionCompleted()
                        || active != null)) {
            issues.add(error(Code.INVALID_COMPLETED_FINALE, data.status().name()));
        }
        if (data.mode() == CampaignMode.ENDLESS
                && (data.finalDayElapsed() || data.finaleHubRouteSegment() != 0)) {
            issues.add(error(Code.STORY_STATE_IN_ENDLESS, String.valueOf(data.finaleHubRouteSegment())));
        }
        if (data.routeSegment() > data.generatedRouteSegment()) {
            issues.add(warning(
                    Code.ROUTE_AHEAD_OF_GENERATION,
                    data.routeSegment() + "/" + data.generatedRouteSegment()));
        }
        auditRoutePlans(data, issues);

        Set<UUID> cleanupIds = new HashSet<>();
        data.pendingSiteCleanups().forEach(cleanup -> {
            if (!cleanupIds.add(cleanup.missionId())) {
                issues.add(warning(Code.DUPLICATE_CLEANUP, cleanup.missionId().toString()));
            }
        });
        if (data.starterTrainAssembled() && data.starterTrainSublevelId() == null) {
            issues.add(error(Code.ASSEMBLED_TRAIN_WITHOUT_ID, "missing"));
        }
        return new Report(issues);
    }

    private static void addMissionId(
            List<Issue> issues,
            Set<UUID> liveMissionIds,
            ActiveMission mission) {
        if (mission != null && !liveMissionIds.add(mission.id())) {
            issues.add(error(Code.DUPLICATE_LIVE_MISSION_ID, mission.id().toString()));
        }
    }

    private static void auditRoutePlans(CampaignSavedData data, List<Issue> issues) {
        int previous = data.generatedRouteSegment();
        for (RouteSegmentPlan plan : data.plannedRouteSegments()) {
            if (plan.segmentIndex() != previous + 1) {
                issues.add(error(
                        Code.NON_CONTIGUOUS_ROUTE_PLAN,
                        previous + "->" + plan.segmentIndex()));
                return;
            }
            previous = plan.segmentIndex();
        }
    }

    private static Issue warning(Code code, String detail) {
        return new Issue(Severity.WARNING, code, detail);
    }

    private static Issue error(Code code, String detail) {
        return new Issue(Severity.ERROR, code, detail);
    }

    public enum Severity {
        WARNING,
        ERROR
    }

    public enum Code {
        OPTIONAL_IN_MAINLINE_SLOT,
        INVALID_PROPOSAL,
        INVALID_OPTIONAL_SLOT,
        DUPLICATE_LIVE_MISSION_ID,
        ORPHAN_OPTIONAL_REWARD,
        CAPTAIN_NOT_IN_TEAM,
        INVALID_COMPLETED_FINALE,
        STORY_STATE_IN_ENDLESS,
        ROUTE_AHEAD_OF_GENERATION,
        NON_CONTIGUOUS_ROUTE_PLAN,
        DUPLICATE_CLEANUP,
        ASSEMBLED_TRAIN_WITHOUT_ID,
        CORRUPT_SAVE_DATA,
        TICK_EVALUATION_FAILURE,
        TICK_EVALUATION_BACKOFF,
        SAFE_MODE_WORLD_WRITE_SKIPPED,
        ENTITY_PERFORMANCE_GUARD
    }

    public record Issue(Severity severity, Code code, String detail) {
        private static final int MAX_DETAIL_LENGTH = 256;

        public Issue {
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(code, "code");
            detail = Objects.requireNonNullElse(detail, "");
            if (detail.length() > MAX_DETAIL_LENGTH) {
                detail = detail.substring(0, MAX_DETAIL_LENGTH);
            }
        }

    }

    public record Report(List<Issue> issues) {
        public Report {
            issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        }

        public long errors() {
            return issues.stream().filter(issue -> issue.severity() == Severity.ERROR).count();
        }

        public long warnings() {
            return issues.stream().filter(issue -> issue.severity() == Severity.WARNING).count();
        }

        public boolean healthy() {
            return errors() == 0L;
        }
    }
}
