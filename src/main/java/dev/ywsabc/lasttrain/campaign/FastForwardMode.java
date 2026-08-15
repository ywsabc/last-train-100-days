package dev.ywsabc.lasttrain.campaign;

import dev.ywsabc.lasttrain.testing.DevToolsGate;
import java.util.Objects;

/**
 * Test-only accelerated campaign clock (TECH_ARCH 17.3).
 *
 * <p>The fast-forward mode shortens the in-memory day length so a full
 * campaign can be simulated deterministically without wall-clock time, and
 * offers the batch entry points {@link #advanceActiveTicks} and
 * {@link #simulateDay}. It is strictly an explicit opt-in:</p>
 * <ul>
 * <li>it defaults to disabled, keeps the production 20-minute day, and every
 * accelerated entry throws {@link IllegalStateException} until
 * {@link #enable(CampaignSavedData, int)} has been called;</li>
 * <li>{@link #enable} additionally requires an explicit development
 * environment ({@code -Dlasttrain.devTools=true} startup parameter) and
 * refuses outside one, so a production launch can never trip the accelerated
 * clock;</li>
 * <li>the day length is memory-only and is never persisted, so a reload
 * always returns to the production day length;</li>
 * <li>the production server tick loop never calls any entry of this class —
 * it only runs {@link CampaignSavedData#tick()}.</li>
 * </ul>
 *
 * <p>{@code simulateDay()} runs exactly one day's worth of ticks in order;
 * its equivalence with an explicit tick-by-tick loop over the same campaign
 * state is enforced by {@code FastForwardModeTest}.</p>
 */
public final class FastForwardMode {
    /** Smallest legal accelerated day length, in active ticks. */
    public static final int MIN_TICKS_PER_DAY = 1;

    private FastForwardMode() {
    }

    /**
     * Explicitly opts {@code data} into the accelerated clock with the given
     * day length. {@code 24_000} restores the standard day while keeping the
     * batch entries available. Requires a development environment: outside
     * one this entry throws before touching the campaign.
     */
    public static void enable(CampaignSavedData data, int ticksPerDay) {
        DevToolsGate.requireEnabled("FastForwardMode.enable");
        Objects.requireNonNull(data, "data").enableFastForward(ticksPerDay);
    }

    /** Disables the accelerated clock and restores the production day length. */
    public static void disable(CampaignSavedData data) {
        Objects.requireNonNull(data, "data").disableFastForward();
    }

    /** True after {@link #enable} and before {@link #disable}. */
    public static boolean isEnabled(CampaignSavedData data) {
        return Objects.requireNonNull(data, "data").fastForwardEnabled();
    }

    /** The current in-memory day length of {@code data}, in active ticks. */
    public static int ticksPerDay(CampaignSavedData data) {
        return Objects.requireNonNull(data, "data").activeTicksPerDay();
    }

    /**
     * Runs {@code ticks} ordinary campaign ticks back to back. Each tick is
     * the same {@link CampaignSavedData#tick()} the server runs, so the
     * resulting state equals that of the same number of real server ticks.
     */
    public static AdvanceSummary advanceActiveTicks(CampaignSavedData data, long ticks) {
        return Objects.requireNonNull(data, "data").advanceActiveTicks(ticks);
    }

    /**
     * Executes all ticks of one campaign day in order: exactly
     * {@link #ticksPerDay} {@code tick()} calls, crossing exactly one day
     * boundary (or completing the day-100 timer in story mode).
     */
    public static AdvanceSummary simulateDay(CampaignSavedData data) {
        return Objects.requireNonNull(data, "data").simulateDay();
    }

    /**
     * Aggregate result of an accelerated tick batch.
     *
     * @param ticksAdvanced how many ordinary ticks the batch ran
     * @param daysAdvanced how many day boundaries the batch crossed
     * @param day the campaign day after the batch
     * @param lastOutcome the outcome of the last tick of the batch
     */
    public record AdvanceSummary(
            long ticksAdvanced,
            long daysAdvanced,
            int day,
            CampaignSavedData.TickOutcome lastOutcome) {
        public AdvanceSummary {
            ticksAdvanced = Math.max(0L, ticksAdvanced);
            daysAdvanced = Math.max(0L, daysAdvanced);
            day = Math.max(1, day);
            lastOutcome = Objects.requireNonNull(lastOutcome, "lastOutcome");
        }
    }
}
