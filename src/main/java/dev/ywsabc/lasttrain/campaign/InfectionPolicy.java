package dev.ywsabc.lasttrain.campaign;

import java.util.Objects;

/**
 * 五阶段感染进度的纯策略。
 *
 * <p>设计文档只量化了百日战役的五段边界，因此基础阈值按生产环境每游戏日
 * 24,000 个有效 tick 映射为第 10、30、55、80 日结束。枪声、爆炸、尸潮和
 * 后方追击逼近使用保守的“等效感染 tick”默认值加速推进；这些默认值没有写进
 * 设计文档，集中放在本类常量中，后续可依据试玩数据调整。</p>
 *
 * <p>策略只接受单调累计 tick，不读取 day，也不回退阶段。这样第 100 天的 day
 * 封顶、睡眠或管理员改日数都不会锁死或反算感染进度。</p>
 */
public final class InfectionPolicy {
    public static final int MIN_STAGE = 0;
    public static final int MAX_STAGE = 4;
    public static final long TICKS_PER_DESIGN_DAY = 24_000L;

    public static final long SCARCITY_THRESHOLD_TICKS = 10L * TICKS_PER_DESIGN_DAY;
    public static final long SPREAD_THRESHOLD_TICKS = 30L * TICKS_PER_DESIGN_DAY;
    public static final long COLLAPSE_THRESHOLD_TICKS = 55L * TICKS_PER_DESIGN_DAY;
    public static final long FINALE_THRESHOLD_TICKS = 80L * TICKS_PER_DESIGN_DAY;

    /** 单次枪声按 10 秒自然推进计，TaCZ 桥自身另有脉冲冷却。 */
    public static final long GUNFIRE_PROGRESS_TICKS = 200L;
    /** 爆炸比枪声更醒目，默认按 30 秒自然推进计。 */
    public static final long EXPLOSION_PROGRESS_TICKS = 600L;
    /** 一次真正追上列车的尸潮按半个游戏日计。 */
    public static final long HORDE_PROGRESS_TICKS = 12_000L;

    public static final int NEAR_PURSUIT_DISTANCE = 7_500;
    public static final int DANGEROUS_PURSUIT_DISTANCE = 5_000;
    public static final int CRITICAL_PURSUIT_DISTANCE = 2_500;
    public static final int CAUGHT_PURSUIT_DISTANCE = 0;

    private InfectionPolicy() {
    }

    /**
     * 采样一个在线有效 tick。距离越近，每个有效 tick 追加的等效进度越多；
     * 无有效玩家时原样返回，确保离线期间完全暂停。
     */
    public static Sample sample(
            int currentStage,
            long infectionTicks,
            int pursuitDistance,
            boolean hasActivePlayers) {
        return sample(
                new Sample(Stage.fromIndex(currentStage), infectionTicks),
                pursuitDistance,
                hasActivePlayers);
    }

    public static Sample sample(
            Sample current,
            int pursuitDistance,
            boolean hasActivePlayers) {
        Objects.requireNonNull(current, "current");
        if (!hasActivePlayers || current.stage() == Stage.FINALE) {
            return current;
        }

        long increment = 1L + pursuitAcceleration(pursuitDistance);
        return advance(current, increment);
    }

    /** 记录一次离散事件；无有效玩家时事件不推进全局感染。 */
    public static Sample onEvent(
            Sample current,
            Event event,
            boolean hasActivePlayers) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(event, "event");
        if (!hasActivePlayers || current.stage() == Stage.FINALE) {
            return current;
        }
        return advance(current, event.progressTicks());
    }

    public static Sample afterGunfire(Sample current, boolean hasActivePlayers) {
        return onEvent(current, Event.GUNFIRE, hasActivePlayers);
    }

    public static Sample afterExplosion(Sample current, boolean hasActivePlayers) {
        return onEvent(current, Event.EXPLOSION, hasActivePlayers);
    }

    public static Sample afterHorde(Sample current, boolean hasActivePlayers) {
        return onEvent(current, Event.HORDE, hasActivePlayers);
    }

    /**
     * 只规范化存档值，不依据 tick 反算阶段。旧存档缺字段时因此可以稳定保持
     * 阶段 0，而不是因已有 day 或 totalActiveTicks 被悄悄重算。
     */
    public static Sample snapshot(int savedStage, long infectionTicks) {
        return new Sample(Stage.fromIndex(savedStage), infectionTicks);
    }

    public static Stage stageForTicks(long infectionTicks) {
        long safeTicks = Math.max(0L, infectionTicks);
        if (safeTicks >= FINALE_THRESHOLD_TICKS) {
            return Stage.FINALE;
        }
        if (safeTicks >= COLLAPSE_THRESHOLD_TICKS) {
            return Stage.COLLAPSE;
        }
        if (safeTicks >= SPREAD_THRESHOLD_TICKS) {
            return Stage.SPREAD;
        }
        if (safeTicks >= SCARCITY_THRESHOLD_TICKS) {
            return Stage.SCARCITY;
        }
        return Stage.LATENT;
    }

    /** 后方尸潮的逼近加速；数值为每个在线 tick 追加的等效 tick。 */
    public static int pursuitAcceleration(int pursuitDistance) {
        int distance = Math.clamp(
                pursuitDistance,
                CAUGHT_PURSUIT_DISTANCE,
                PursuitPolicy.MAX_PURSUIT_DISTANCE);
        if (distance <= CAUGHT_PURSUIT_DISTANCE) {
            return 8;
        }
        if (distance <= CRITICAL_PURSUIT_DISTANCE) {
            return 4;
        }
        if (distance <= DANGEROUS_PURSUIT_DISTANCE) {
            return 2;
        }
        if (distance <= NEAR_PURSUIT_DISTANCE) {
            return 1;
        }
        return 0;
    }

    /** 按当前阶段调整事件概率，同时保持合法概率范围。 */
    public static double eventChance(double baseChance, Stage stage) {
        Objects.requireNonNull(stage, "stage");
        double safeBase = Math.clamp(baseChance, 0.0D, 1.0D);
        return Math.min(1.0D, safeBase * stage.effects().eventFrequencyMultiplier());
    }

    private static Sample advance(Sample current, long increment) {
        long nextTicks = saturatingAdd(current.infectionTicks(), Math.max(0L, increment));
        Stage inferred = stageForTicks(nextTicks);
        Stage nextStage = inferred.index() > current.stage().index()
                ? inferred
                : current.stage();
        return new Sample(nextStage, nextTicks);
    }

    private static long saturatingAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    /**
     * 阶段名称严格对应玩法文档的五段节奏：序章（此处按任务定义为潜伏/未感染）、
     * 匮乏、扩散、崩溃、最后航段/终局。
     *
     * <p>文档没有量化阶段倍率；下列倍率采用单调、保守默认：围攻规模最高 2 倍，
     * 普通事件频率最高 1.7 倍，追击消耗最高 1.75 倍。关注度下限沿用文档现有
     * 章节曲线，因此不会与 {@link PursuitPolicy} 的旧数值冲突。</p>
     */
    public enum Stage {
        LATENT(0, "latent", 0L, new Effects(5, 1.00D, 1.00D, 1.00D)),
        SCARCITY(1, "scarcity", SCARCITY_THRESHOLD_TICKS, new Effects(12, 1.15D, 1.10D, 1.00D)),
        SPREAD(2, "spread", SPREAD_THRESHOLD_TICKS, new Effects(20, 1.35D, 1.25D, 1.25D)),
        COLLAPSE(3, "collapse", COLLAPSE_THRESHOLD_TICKS, new Effects(32, 1.65D, 1.45D, 1.50D)),
        FINALE(4, "finale", FINALE_THRESHOLD_TICKS, new Effects(45, 2.00D, 1.70D, 1.75D));

        private final int index;
        private final String serializedName;
        private final long thresholdTicks;
        private final Effects effects;

        Stage(int index, String serializedName, long thresholdTicks, Effects effects) {
            this.index = index;
            this.serializedName = serializedName;
            this.thresholdTicks = thresholdTicks;
            this.effects = effects;
        }

        public int index() {
            return index;
        }

        public String serializedName() {
            return serializedName;
        }

        public long thresholdTicks() {
            return thresholdTicks;
        }

        public Effects effects() {
            return effects;
        }

        public static Stage fromIndex(int index) {
            int safeIndex = Math.clamp(index, MIN_STAGE, MAX_STAGE);
            return values()[safeIndex];
        }
    }

    public enum Event {
        GUNFIRE(GUNFIRE_PROGRESS_TICKS),
        EXPLOSION(EXPLOSION_PROGRESS_TICKS),
        HORDE(HORDE_PROGRESS_TICKS);

        private final long progressTicks;

        Event(long progressTicks) {
            this.progressTicks = progressTicks;
        }

        public long progressTicks() {
            return progressTicks;
        }
    }

    public record Effects(
            int minAttention,
            double siegeIntensityMultiplier,
            double eventFrequencyMultiplier,
            double pursuitDrainMultiplier) {
        public Effects {
            minAttention = Math.clamp(minAttention, 0, PursuitPolicy.MAX_ATTENTION);
            siegeIntensityMultiplier = Math.max(1.0D, siegeIntensityMultiplier);
            eventFrequencyMultiplier = Math.max(1.0D, eventFrequencyMultiplier);
            pursuitDrainMultiplier = Math.max(1.0D, pursuitDrainMultiplier);
        }
    }

    /** 单次采样快照，同时向事件、围攻和关注度接线暴露当前阶段效果。 */
    public record Sample(Stage stage, long infectionTicks) {
        public Sample {
            stage = Objects.requireNonNull(stage, "stage");
            infectionTicks = Math.max(0L, infectionTicks);
        }

        public static Sample initial() {
            return new Sample(Stage.LATENT, 0L);
        }

        public int stageIndex() {
            return stage.index();
        }

        public Effects effects() {
            return stage.effects();
        }

        public int minAttention() {
            return effects().minAttention();
        }

        public double siegeIntensityMultiplier() {
            return effects().siegeIntensityMultiplier();
        }

        public double eventFrequencyMultiplier() {
            return effects().eventFrequencyMultiplier();
        }

        public double pursuitDrainMultiplier() {
            return effects().pursuitDrainMultiplier();
        }
    }
}
