package dev.ywsabc.lasttrain.server;

import dev.ywsabc.lasttrain.campaign.CampaignStatus;
import dev.ywsabc.lasttrain.campaign.PursuitPolicy;

/**
 * Pure policy for recovering the physical train after loss, derailment or a
 * missing vehicle mod stack.
 *
 * <p>The campaign keeps the logical train identity and the verified route
 * progress as its source of truth. When the physics body stops moving or can
 * no longer be located, this policy confirms the condition over a grace
 * period, refuses to relocate the train while a player is in the danger
 * volume, charges attention and threat for every rescue, and never offers an
 * anchor past an unfinished mission checkpoint. A missing vehicle stack parks
 * the campaign in {@link CampaignStatus#SAFE_MODE} instead of mutating the
 * world without a backend.</p>
 */
public final class TrainRecoveryPolicy {
    public static final int MISSING_GRACE_TICKS = 100;
    public static final int IMMOBILE_GRACE_TICKS = 200;
    public static final int RESCUE_COOLDOWN_DAYS = 3;
    public static final int RESCUE_COUNT_LIMIT = 12;
    public static final int RESCUE_ATTENTION_COST = 30;
    public static final int RESCUE_THREAT_COST = 4;
    public static final int MAX_TRACKED_TICKS = 1_000_000;
    public static final double PLAYER_DANGER_RADIUS = 8.0D;
    public static final double PLAYER_DANGER_RADIUS_SQUARED =
            PLAYER_DANGER_RADIUS * PLAYER_DANGER_RADIUS;
    public static final double MOVEMENT_EPSILON_SQUARED = 1.0E-2D;

    private TrainRecoveryPolicy() {
    }

    public static Directive assess(TrainSituation situation) {
        if (situation.status() != CampaignStatus.RUNNING
                && situation.status() != CampaignStatus.SAFE_MODE) {
            return Directive.NONE;
        }
        if (!situation.vehicleStackLoaded()) {
            return Directive.SAFE_MODE;
        }
        if (!situation.trainReferenceKnown()) {
            return Directive.NONE;
        }
        if (situation.trainLocatable() && situation.trainMoving()) {
            return Directive.NONE;
        }

        boolean pastGrace = situation.trainLocatable()
                ? situation.immobileTicks() >= IMMOBILE_GRACE_TICKS
                : situation.missingTicks() >= MISSING_GRACE_TICKS;
        if (!pastGrace) {
            return Directive.OBSERVING;
        }
        if (situation.playerInDangerCollision()) {
            return Directive.RESCUE_BLOCKED;
        }
        if (!canRescue(
                situation.rescueCount(),
                situation.lastRescueDay(),
                situation.currentDay())) {
            return Directive.COOLDOWN;
        }
        return Directive.RESCUE_READY;
    }

    /**
     * The rescue anchor is the farthest verified route segment, never past an
     * unfinished mission checkpoint. Rescue is repair, not a teleport that
     * skips a mainline blocker.
     */
    public static int anchorSegment(int routeSegment, Integer missionCheckpoint) {
        int anchor = Math.max(0, routeSegment);
        if (missionCheckpoint != null) {
            anchor = Math.min(anchor, Math.max(0, missionCheckpoint));
        }
        return anchor;
    }

    public static boolean canRescue(int rescueCount, int lastRescueDay, int currentDay) {
        if (rescueCount < 0 || rescueCount >= RESCUE_COUNT_LIMIT) {
            return false;
        }
        if (lastRescueDay <= 0) {
            return true;
        }
        return currentDay - lastRescueDay >= RESCUE_COOLDOWN_DAYS;
    }

    public static RescueCosts applyCosts(int attention, int threat) {
        return new RescueCosts(
                Math.clamp(
                        attention + RESCUE_ATTENTION_COST,
                        0,
                        PursuitPolicy.MAX_ATTENTION),
                Math.clamp(threat + RESCUE_THREAT_COST, 0, 100));
    }

    /** A squared per-tick displacement at or below the epsilon counts as parked. */
    public static boolean isMoving(double movedSquared) {
        return movedSquared > MOVEMENT_EPSILON_SQUARED;
    }

    public static boolean playerInDanger(double distanceSquared) {
        return distanceSquared <= PLAYER_DANGER_RADIUS_SQUARED;
    }

    public enum Directive {
        NONE,
        OBSERVING,
        RESCUE_READY,
        RESCUE_BLOCKED,
        COOLDOWN,
        SAFE_MODE
    }

    public record TrainSituation(
            boolean vehicleStackLoaded,
            boolean trainReferenceKnown,
            boolean trainLocatable,
            boolean trainMoving,
            int missingTicks,
            int immobileTicks,
            boolean playerInDangerCollision,
            int rescueCount,
            int lastRescueDay,
            int currentDay,
            CampaignStatus status) {
    }

    public record RescueCosts(int attention, int threat) {
    }
}
