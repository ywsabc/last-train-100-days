package dev.ywsabc.lasttrain.campaign;

import dev.ywsabc.lasttrain.mission.MissionType;
import java.util.Objects;

/**
 * Pure population scaling policy for the server-authoritative campaign.
 *
 * <p>The campaign tick feeds the current number of non-spectator online
 * players into {@link #observe(Hysteresis, int)}. Effective team size rises
 * and falls only after a full confirmation window, so briefly joining or
 * leaving does not immediately re-roll mission costs. New missions freeze the
 * resulting effective size; missions already in progress keep the target that
 * was stored when they were created.</p>
 */
public final class PopulationScalingPolicy {
    public static final int MIN_PLAYERS = 1;
    public static final int MAX_PLAYERS = 6;
    public static final int CONFIRM_TICKS = 1_200;

    private static final double MATERIAL_STEP = 0.55D;
    private static final double ENEMY_STEP = 0.70D;
    private static final double REWARD_STEP = 0.45D;
    private static final double MATERIAL_CAP = 3.75D;
    private static final double ENEMY_CAP = 4.50D;
    private static final double REWARD_CAP = 3.25D;

    private PopulationScalingPolicy() {
    }

    public static int clampPlayers(int observedPlayers) {
        return Math.clamp(observedPlayers, MIN_PLAYERS, MAX_PLAYERS);
    }

    /**
     * Applies one campaign tick of hysteresis.
     *
     * <p>An observed value different from the current effective value must
     * stay stable for {@link #CONFIRM_TICKS} before it is adopted. Returning
     * to the current effective value resets the confirmation window.</p>
     */
    public static Hysteresis observe(Hysteresis state, int observedPlayers) {
        Objects.requireNonNull(state, "state");
        int observed = clampPlayers(observedPlayers);
        if (observed == state.effectivePlayers()) {
            return new Hysteresis(observed, observed, 0);
        }

        int pendingPlayers = observed == state.pendingPlayers()
                ? state.pendingPlayers()
                : observed;
        int holdTicks = observed == state.pendingPlayers()
                ? state.holdTicks() + 1
                : 1;
        if (holdTicks >= CONFIRM_TICKS) {
            return new Hysteresis(pendingPlayers, pendingPlayers, 0);
        }
        return new Hysteresis(
                state.effectivePlayers(),
                pendingPlayers,
                holdTicks);
    }

    public static double materialMultiplier(int players) {
        return Math.min(
                MATERIAL_CAP,
                1.0D + MATERIAL_STEP * (clampPlayers(players) - 1));
    }

    public static double enemyMultiplier(int players) {
        return Math.min(
                ENEMY_CAP,
                1.0D + ENEMY_STEP * (clampPlayers(players) - 1));
    }

    public static double rewardMultiplier(int players) {
        return Math.min(
                REWARD_CAP,
                1.0D + REWARD_STEP * (clampPlayers(players) - 1));
    }

    /**
     * Freezes a new mission target at creation time. Zombie blockade budgets
     * follow the enemy curve; block/container objectives follow the material
     * curve. A one-player campaign always keeps the existing baseline values.
     */
    public static int missionTarget(MissionType type, int players) {
        return missionTarget(type, players, InfectionPolicy.Stage.LATENT);
    }

    /**
     * 尸潮封锁在人数倍率之后叠加感染阶段强度；其他任务的材料目标不受感染阶段
     * 影响，避免把世界难度误传导为维修用料膨胀。阶段 0 倍率为 1，保持旧契约。
     */
    public static int missionTarget(
            MissionType type,
            int players,
            InfectionPolicy.Stage infectionStage) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(infectionStage, "infectionStage");
        double multiplier = type == MissionType.ZOMBIE_BLOCKADE
                ? enemyMultiplier(players)
                        * infectionStage.effects().siegeIntensityMultiplier()
                : materialMultiplier(players);
        return Math.max(1, (int) Math.round(type.defaultTarget() * multiplier));
    }

    /**
     * Persistent hysteresis state. All fields are normalized on construction.
     */
    public record Hysteresis(
            int effectivePlayers,
            int pendingPlayers,
            int holdTicks) {
        public Hysteresis {
            effectivePlayers = clampPlayers(effectivePlayers);
            pendingPlayers = clampPlayers(pendingPlayers);
            holdTicks = Math.max(0, holdTicks);
        }

        public static Hysteresis initial() {
            return new Hysteresis(MIN_PLAYERS, MIN_PLAYERS, 0);
        }
    }
}
