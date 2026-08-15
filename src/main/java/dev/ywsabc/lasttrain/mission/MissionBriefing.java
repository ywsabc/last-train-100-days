package dev.ywsabc.lasttrain.mission;

import java.util.Objects;
import java.util.Optional;

/**
 * Pre-acceptance summary of an optional mission proposal.
 *
 * <p>Shown by the mission status command so the team can weigh risk, reward
 * category and the time limit before answering. Mainline missions are never
 * proposals and have no briefing.</p>
 */
public record MissionBriefing(
        MissionType type,
        Risk risk,
        RewardCategory reward,
        int timedDays) {
    public MissionBriefing {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(risk, "risk");
        Objects.requireNonNull(reward, "reward");
        if (timedDays <= 0) {
            throw new IllegalArgumentException("timedDays must be positive");
        }
    }

    public static Optional<MissionBriefing> of(MissionType type) {
        Objects.requireNonNull(type, "type");
        return switch (type) {
            case RESCUE_SURVIVOR -> Optional.of(new MissionBriefing(
                    type,
                    Risk.MEDIUM,
                    RewardCategory.SUPPLIES,
                    OptionalMissionPolicy.RESCUE_GRACE_DAYS));
            case SALVAGE_CAR -> Optional.of(new MissionBriefing(
                    type,
                    Risk.HIGH,
                    RewardCategory.UPGRADE,
                    OptionalMissionPolicy.SALVAGE_GRACE_DAYS));
            default -> Optional.empty();
        };
    }

    public enum Risk {
        LOW,
        MEDIUM,
        HIGH
    }

    public enum RewardCategory {
        SUPPLIES,
        UPGRADE
    }
}
