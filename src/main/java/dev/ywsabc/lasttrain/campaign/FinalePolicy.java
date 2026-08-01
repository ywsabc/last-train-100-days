package dev.ywsabc.lasttrain.campaign;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Pure decision policy for the day-100 finale.
 *
 * <p>The saved-data owner applies the returned directive on the logical
 * server thread. Keeping the decision independent from world state makes the
 * two completion gates explicit and straightforward to regression test.</p>
 */
public final class FinalePolicy {
    public static final int CURRENT_SCHEMA = 6;
    public static final int FINAL_DAY = 100;
    private static final String FINALE_ID_NAMESPACE = "lasttrain:finale:";

    private FinalePolicy() {
    }

    public static Directive nextDirective(
            CampaignStatus status,
            int day,
            boolean finalDayElapsed,
            boolean finaleMissionCompleted,
            boolean hasActiveMission) {
        if (status != CampaignStatus.RUNNING || day < FINAL_DAY) {
            return Directive.NONE;
        }
        if (finalDayElapsed && finaleMissionCompleted && !hasActiveMission) {
            return Directive.COMPLETE_CAMPAIGN;
        }
        if (!finaleMissionCompleted && !hasActiveMission) {
            return Directive.CREATE_FINALE_MISSION;
        }
        return Directive.NONE;
    }

    public static boolean allowsOrdinaryMission(CampaignStatus status, int day) {
        return status == CampaignStatus.RUNNING && day < FINAL_DAY;
    }

    public static UUID missionId(UUID campaignId) {
        return UUID.nameUUIDFromBytes(
                (FINALE_ID_NAMESPACE + campaignId)
                        .getBytes(StandardCharsets.UTF_8));
    }

    public static MigratedState migrate(
            int loadedSchema,
            CampaignStatus status,
            int day,
            boolean finalDayElapsed,
            boolean finaleMissionCompleted,
            boolean hasActiveMission) {
        CampaignStatus migratedStatus = status;
        boolean migratedElapsed = finalDayElapsed;
        boolean migratedMissionCompleted = finaleMissionCompleted;

        if (loadedSchema < CURRENT_SCHEMA) {
            // Schema 5 only reached COMPLETED after its final-day timer path.
            // Preserve that elapsed-time achievement but require the new
            // finale mission before considering the campaign complete again.
            if (status == CampaignStatus.COMPLETED && day >= FINAL_DAY) {
                migratedElapsed = true;
                migratedStatus = CampaignStatus.RUNNING;
            }
            migratedMissionCompleted = false;
        }

        if (day < FINAL_DAY) {
            migratedElapsed = false;
            migratedMissionCompleted = false;
        }
        if (migratedStatus == CampaignStatus.COMPLETED
                && (!migratedElapsed || !migratedMissionCompleted || hasActiveMission)) {
            migratedStatus = CampaignStatus.RUNNING;
        }
        return new MigratedState(
                migratedStatus,
                migratedElapsed,
                migratedMissionCompleted);
    }

    public enum Directive {
        NONE,
        CREATE_FINALE_MISSION,
        COMPLETE_CAMPAIGN
    }

    public record MigratedState(
            CampaignStatus status,
            boolean finalDayElapsed,
            boolean finaleMissionCompleted) {
    }
}
