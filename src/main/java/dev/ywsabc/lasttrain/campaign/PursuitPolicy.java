package dev.ywsabc.lasttrain.campaign;

import java.util.Objects;

/**
 * Pure policy for the abstract rear horde.
 *
 * <p>The campaign never keeps thousands of chase entities behind the train.
 * Instead it persists two numbers: attention (0–100) and pursuit distance
 * (0–10,000 abstract meters). Parking raises attention and lets the horde
 * close in; verified forward route progress cools attention and opens the
 * distance. Reaching zero distance triggers an ordinary zombie-blockade
 * mission, never an instant world reset.</p>
 */
public final class PursuitPolicy {
    public static final int MAX_ATTENTION = 100;
    public static final int MAX_PURSUIT_DISTANCE = 10_000;
    public static final int INITIAL_ATTENTION = 12;
    public static final int INITIAL_PURSUIT_DISTANCE = 8_000;

    public static final int SAMPLE_INTERVAL_TICKS = 20;
    public static final int MAX_SEGMENTS_PER_SAMPLE = 8;
    public static final int PARKED_ATTENTION_GROWTH_PER_SAMPLE = 1;
    public static final int MOVING_ATTENTION_COOLING_PER_SEGMENT = 3;
    public static final int PARKED_PURSUIT_DRAIN_PER_SAMPLE = 10;
    public static final int PURSUIT_GAIN_PER_SEGMENT = 640;

    public static final int GUNFIRE_ATTENTION = 16;
    public static final int GUNFIRE_PURSUIT_COST = 180;
    public static final int EXPLOSION_ATTENTION = 22;
    public static final int EXPLOSION_PURSUIT_COST = 240;

    public static final int PURSUIT_AFTER_SIEGE = 3_600;
    public static final int PURSUIT_AFTER_SIEGE_FALLBACK = 2_400;

    private PursuitPolicy() {
    }

    /**
     * Applies one periodic pressure sample. Route movement is capped per
     * sample so a teleport or an old save cannot jump from zero to maximum
     * distance in a single tick.
     */
    public static Sample sample(
            int attention,
            int pursuitDistance,
            int currentRouteSegment,
            int previousRouteSegment,
            int day) {
        int movedSegments = Math.clamp(
                currentRouteSegment - previousRouteSegment,
                0,
                MAX_SEGMENTS_PER_SAMPLE);

        if (movedSegments > 0) {
            attention = Math.max(
                    minAttention(day),
                    attention - movedSegments * MOVING_ATTENTION_COOLING_PER_SEGMENT);
            pursuitDistance = Math.min(
                    MAX_PURSUIT_DISTANCE,
                    pursuitDistance + movedSegments * PURSUIT_GAIN_PER_SEGMENT);
        } else {
            attention = Math.min(
                    MAX_ATTENTION,
                    attention + PARKED_ATTENTION_GROWTH_PER_SAMPLE);
            pursuitDistance = Math.max(
                    0,
                    pursuitDistance - PARKED_PURSUIT_DRAIN_PER_SAMPLE);
        }
        return new Sample(attention, pursuitDistance, currentRouteSegment);
    }

    public static int minAttention(int day) {
        if (day <= 10) {
            return 5;
        }
        if (day <= 30) {
            return 12;
        }
        if (day <= 55) {
            return 20;
        }
        if (day <= 80) {
            return 32;
        }
        if (day <= 99) {
            return 45;
        }
        return 60;
    }

    public static AttentionLevel attentionLevel(int attention) {
        if (attention < 20) {
            return AttentionLevel.QUIET;
        }
        if (attention < 45) {
            return AttentionLevel.WATCHED;
        }
        if (attention < 70) {
            return AttentionLevel.GATHERING;
        }
        if (attention < 85) {
            return AttentionLevel.SIEGE;
        }
        return AttentionLevel.OUT_OF_CONTROL;
    }

    public static int afterGunfireAttention(int attention) {
        return Math.min(MAX_ATTENTION, attention + GUNFIRE_ATTENTION);
    }

    public static int afterGunfirePursuit(int pursuitDistance) {
        return Math.max(0, pursuitDistance - GUNFIRE_PURSUIT_COST);
    }

    public static int afterExplosionAttention(int attention) {
        return Math.min(MAX_ATTENTION, attention + EXPLOSION_ATTENTION);
    }

    public static int afterExplosionPursuit(int pursuitDistance) {
        return Math.max(0, pursuitDistance - EXPLOSION_PURSUIT_COST);
    }

    public static boolean shouldTriggerSiege(
            CampaignStatus status,
            int day,
            int pursuitDistance,
            boolean hasActiveMission) {
        return status == CampaignStatus.RUNNING
                && day < FinalePolicy.FINAL_DAY
                && pursuitDistance <= 0
                && !hasActiveMission;
    }

    public enum AttentionLevel {
        QUIET,
        WATCHED,
        GATHERING,
        SIEGE,
        OUT_OF_CONTROL
    }

    public record Sample(
            int attention,
            int pursuitDistance,
            int routeSegment) {
        public Sample {
            attention = Math.clamp(attention, 0, MAX_ATTENTION);
            pursuitDistance = Math.clamp(pursuitDistance, 0, MAX_PURSUIT_DISTANCE);
            routeSegment = Math.max(0, routeSegment);
        }

        public static Sample initial() {
            return new Sample(
                    INITIAL_ATTENTION,
                    INITIAL_PURSUIT_DISTANCE,
                    0);
        }
    }
}
