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
     */
    public static final int CURRENT_SCHEMA = 11;
    public static final int FINAL_DAY = 100;
    public static final int FINALE_HUB_START_DAY = 90;
    public static final int FINALE_HUB_WINDOW_SEGMENTS = 8;
    private static final String FINALE_ID_NAMESPACE = "lasttrain:finale:";
    private static final int FINALE_REOPEN_SCHEMA = 6;

    private FinalePolicy() {
    }

    public static Directive nextDirective(
            CampaignStatus status,
            int day,
            boolean finalDayElapsed,
            boolean finaleMissionCompleted,
            boolean hasActiveMission) {
        return nextDirective(
                CampaignMode.STORY_100_DAYS,
                status,
                day,
                finalDayElapsed,
                finaleMissionCompleted,
                hasActiveMission);
    }

    public static Directive nextDirective(
            CampaignMode mode,
            CampaignStatus status,
            int day,
            boolean finalDayElapsed,
            boolean finaleMissionCompleted,
            boolean hasActiveMission) {
        if (mode == CampaignMode.ENDLESS
                || status != CampaignStatus.RUNNING
                || day < FINAL_DAY) {
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
        int current = Math.max(0, currentRouteSegment);
        return new HubWindow(
                current + 1,
                current + FINALE_HUB_WINDOW_SEGMENTS);
    }

    public static boolean isFinaleHubInForwardWindow(
            int currentRouteSegment,
            int finaleHubRouteSegment) {
        HubWindow window = finaleHubWindow(currentRouteSegment);
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
        UUID id = java.util.Objects.requireNonNull(campaignId, "campaignId");
        HubWindow window = finaleHubWindow(currentRouteSegment);
        long mixed = id.getMostSignificantBits()
                ^ Long.rotateLeft(id.getLeastSignificantBits(), 17)
                ^ (long) Math.max(0, currentRouteSegment) * 0x9E3779B97F4A7C15L;
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        mixed *= 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        int span = window.lastSegment() - window.firstSegment() + 1;
        return window.firstSegment() + Math.floorMod(mixed, span);
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
        return migrate(
                CampaignMode.STORY_100_DAYS,
                loadedSchema,
                status,
                day,
                finalDayElapsed,
                finaleMissionCompleted,
                hasActiveMission);
    }

    public static MigratedState migrate(
            CampaignMode mode,
            int loadedSchema,
            CampaignStatus status,
            int day,
            boolean finalDayElapsed,
            boolean finaleMissionCompleted,
            boolean hasActiveMission) {
        CampaignStatus migratedStatus = status;
        boolean migratedElapsed = finalDayElapsed;
        boolean migratedMissionCompleted = finaleMissionCompleted;

        if (mode == CampaignMode.ENDLESS) {
            // Endless mode is only created from a completed story save. It no
            // longer consumes the story finale state machine after reload.
            return new MigratedState(
                    migratedStatus == CampaignStatus.COMPLETED
                            ? CampaignStatus.RUNNING
                            : migratedStatus,
                    false,
                    migratedMissionCompleted);
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

    public record HubWindow(int firstSegment, int lastSegment) {
        public HubWindow {
            if (firstSegment < 1 || lastSegment < firstSegment) {
                throw new IllegalArgumentException("Finale hub window must be forward and non-empty");
            }
        }
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
