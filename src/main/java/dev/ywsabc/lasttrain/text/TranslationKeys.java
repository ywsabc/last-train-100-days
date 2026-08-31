package dev.ywsabc.lasttrain.text;

import dev.ywsabc.lasttrain.campaign.CampaignMode;
import dev.ywsabc.lasttrain.campaign.CampaignIntegrityPolicy;
import dev.ywsabc.lasttrain.campaign.CampaignStatus;
import dev.ywsabc.lasttrain.campaign.InfectionPolicy;
import dev.ywsabc.lasttrain.campaign.PursuitPolicy;
import dev.ywsabc.lasttrain.mission.MissionBriefing;
import dev.ywsabc.lasttrain.mission.MissionStage;
import dev.ywsabc.lasttrain.mission.MissionType;
import dev.ywsabc.lasttrain.route.RouteProgressPolicy;
import dev.ywsabc.lasttrain.server.TrainRecoveryPolicy;
import java.util.Locale;
import java.util.Objects;

/**
 * 领域枚举到语言键的唯一映射入口。
 *
 * <p>事件、命令和玩家生命周期只负责选择要展示的领域值，不再各自拼接字符串。
 * 这样新增任务或状态时，语言键命名规则只需在此处审查一次。</p>
 */
public final class TranslationKeys {
    private static final String CAMPAIGN_PREFIX = "campaign.lasttrain.";
    private static final String MISSION_PREFIX = "mission.lasttrain.";
    private static final String BRIEFING_PREFIX = "briefing.lasttrain.";

    private TranslationKeys() {
    }

    public static String campaignMode(CampaignMode mode) {
        return CAMPAIGN_PREFIX + "mode."
                + Objects.requireNonNull(mode, "mode").serializedName();
    }

    public static String campaignStatus(CampaignStatus status) {
        return CAMPAIGN_PREFIX + "status."
                + lower(Objects.requireNonNull(status, "status"));
    }

    public static String mission(MissionType type) {
        return MISSION_PREFIX + Objects.requireNonNull(type, "type").serializedName();
    }

    public static String missionStage(MissionStage stage) {
        return MISSION_PREFIX + "stage."
                + lower(Objects.requireNonNull(stage, "stage"));
    }

    public static String attention(PursuitPolicy.AttentionLevel level) {
        return "attention.lasttrain."
                + lower(Objects.requireNonNull(level, "level"));
    }

    public static String infectionStage(InfectionPolicy.Stage stage) {
        return "infection.lasttrain.stage."
                + Objects.requireNonNull(stage, "stage").serializedName();
    }

    public static String briefingRisk(MissionBriefing.Risk risk) {
        return BRIEFING_PREFIX + "risk."
                + lower(Objects.requireNonNull(risk, "risk"));
    }

    public static String briefingReward(MissionBriefing.RewardCategory reward) {
        return BRIEFING_PREFIX + "reward."
                + lower(Objects.requireNonNull(reward, "reward"));
    }

    public static String pace(RouteProgressPolicy.Pace pace) {
        return "pace.lasttrain." + lower(Objects.requireNonNull(pace, "pace"));
    }

    public static String integrity(CampaignIntegrityPolicy.Code code) {
        return "integrity.lasttrain." + lower(Objects.requireNonNull(code, "code"));
    }

    public static String integritySeverity(CampaignIntegrityPolicy.Severity severity) {
        return "integrity.lasttrain.severity."
                + lower(Objects.requireNonNull(severity, "severity"));
    }

    public static String booleanValue(boolean value) {
        return "common.lasttrain.boolean." + value;
    }

    public static String rescuePhase(TrainRecoveryPolicy.RescuePhase phase) {
        return "recovery.lasttrain.phase."
                + Objects.requireNonNull(phase, "phase").serializedName();
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
