package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.mission.ActiveMission;
import dev.ywsabc.lasttrain.mission.MissionType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Contract of the test-only accelerated campaign clock.
 *
 * <p>Fast-forward is off by default and every accelerated entry refuses to
 * run until the explicit opt-in happens, so a production server can never
 * trip it accidentally. The equivalence test proves that
 * {@code simulateDay()} produces exactly the same campaign state as the same
 * number of ordinary {@code tick()} calls executed one by one.</p>
 */
class FastForwardModeTest {
    private static final int SCALED_TICKS_PER_DAY = 173;
    private static final UUID CAPTAIN =
            UUID.fromString("00000000-0000-0000-0000-000000000031");

    @Test
    void productionDefaultKeepsTheTwentyMinuteDayAndRefusesAcceleration() {
        CampaignSavedData data = new CampaignSavedData();

        assertEquals(
                CampaignSavedData.DEFAULT_ACTIVE_TICKS_PER_DAY,
                FastForwardMode.ticksPerDay(data));
        assertFalse(FastForwardMode.isEnabled(data));
        assertThrows(
                IllegalStateException.class,
                () -> FastForwardMode.advanceActiveTicks(data, 1));
        assertThrows(IllegalStateException.class, () -> FastForwardMode.simulateDay(data));

        // Without the opt-in the ordinary tick clock still runs the full
        // 20-minute production day.
        data.start();
        for (int tick = 0; tick < CampaignSavedData.DEFAULT_ACTIVE_TICKS_PER_DAY - 1; tick++) {
            data.tick();
        }
        assertEquals(1, data.day());
        data.tick();
        assertEquals(2, data.day());
    }

    @Test
    void enableValidatesTheDayLengthAndOptsInExplicitly() {
        CampaignSavedData data = new CampaignSavedData();
        assertThrows(IllegalArgumentException.class, () -> FastForwardMode.enable(data, 0));
        assertThrows(IllegalArgumentException.class, () -> FastForwardMode.enable(data, -5));
        assertThrows(NullPointerException.class, () -> FastForwardMode.enable(null, 20));

        FastForwardMode.enable(data, 20);
        assertTrue(FastForwardMode.isEnabled(data));
        assertEquals(20, FastForwardMode.ticksPerDay(data));
    }

    @Test
    void disableRestoresTheProductionDayLength() {
        CampaignSavedData data = new CampaignSavedData();
        FastForwardMode.enable(data, 40);
        FastForwardMode.disable(data);

        assertFalse(FastForwardMode.isEnabled(data));
        assertEquals(
                CampaignSavedData.DEFAULT_ACTIVE_TICKS_PER_DAY,
                FastForwardMode.ticksPerDay(data));
        assertThrows(IllegalStateException.class, () -> FastForwardMode.simulateDay(data));
    }

    @Test
    void advanceActiveTicksCrossesMultipleDaysDeterministically() {
        CampaignSavedData data = new CampaignSavedData();
        data.start();
        FastForwardMode.enable(data, 100);

        FastForwardMode.AdvanceSummary summary = FastForwardMode.advanceActiveTicks(data, 250);

        assertEquals(250, summary.ticksAdvanced());
        assertEquals(2, summary.daysAdvanced());
        assertEquals(3, summary.day());
        assertEquals(3, data.day());
        assertEquals(50, data.activeTicksIntoDay());
        assertEquals(250, data.totalActiveTicks());
        // nextThreat(0, 1, day 2) = 1, then nextThreat(1, 1, day 3) = 2.
        assertEquals(2, data.threat());
    }

    @Test
    void simulateDayRunsExactlyOneDayOfTicksInOrder() {
        CampaignSavedData data = new CampaignSavedData();
        data.start();
        FastForwardMode.enable(data, SCALED_TICKS_PER_DAY);

        FastForwardMode.AdvanceSummary first = FastForwardMode.simulateDay(data);
        assertEquals(SCALED_TICKS_PER_DAY, first.ticksAdvanced());
        assertEquals(1, first.daysAdvanced());
        assertEquals(2, first.day());
        assertEquals(2, data.day());
        assertEquals(0, data.activeTicksIntoDay());
        assertEquals(SCALED_TICKS_PER_DAY, data.totalActiveTicks());

        FastForwardMode.AdvanceSummary second = FastForwardMode.simulateDay(data);
        assertEquals(1, second.daysAdvanced());
        assertEquals(3, data.day());
        assertEquals(2L * SCALED_TICKS_PER_DAY, data.totalActiveTicks());
    }

    @Test
    void simulateDayIsEquivalentToTickByTickProgression() {
        CampaignSavedData accelerated = twin();
        CampaignSavedData tickByTick = twin();
        FastForwardMode.enable(accelerated, SCALED_TICKS_PER_DAY);
        FastForwardMode.enable(tickByTick, SCALED_TICKS_PER_DAY);

        // 40 simulated days stay clear of the day-90 finale hub window, whose
        // per-campaign UUID choice legitimately varies between campaigns.
        for (int day = 0; day < 40; day++) {
            FastForwardMode.AdvanceSummary summary = FastForwardMode.simulateDay(accelerated);
            CampaignSavedData.TickOutcome lastOutcome = CampaignSavedData.TickOutcome.NONE;
            for (int tick = 0; tick < SCALED_TICKS_PER_DAY; tick++) {
                lastOutcome = tickByTick.tick();
            }

            assertEquals(SCALED_TICKS_PER_DAY, summary.ticksAdvanced());
            assertEquals(1, summary.daysAdvanced());
            assertEquals(lastOutcome, summary.lastOutcome());
            assertEquals(tickByTick.day(), accelerated.day());
            assertEquals(tickByTick.activeTicksIntoDay(), accelerated.activeTicksIntoDay());
            assertEquals(tickByTick.totalActiveTicks(), accelerated.totalActiveTicks());
            assertEquals(tickByTick.threat(), accelerated.threat());
            assertEquals(tickByTick.attention(), accelerated.attention());
            assertEquals(tickByTick.pursuitDistance(), accelerated.pursuitDistance());
            assertEquals(tickByTick.routeSegment(), accelerated.routeSegment());
            assertEquals(tickByTick.generatedRouteSegment(), accelerated.generatedRouteSegment());
            assertEquals(tickByTick.status(), accelerated.status());
            assertEquals(tickByTick.mode(), accelerated.mode());
            assertMissionEquivalent(tickByTick.activeMission(), accelerated.activeMission());
            assertEquals(
                    tickByTick.optionalMissions().size(),
                    accelerated.optionalMissions().size());
            assertEquals(proposedType(tickByTick), proposedType(accelerated));
        }
        assertEquals(41, accelerated.day());
        assertEquals(accelerated.day(), tickByTick.day());
    }

    /** Two campaigns with identical seed, captain and start sequence. */
    private static CampaignSavedData twin() {
        CampaignSavedData data = new CampaignSavedData();
        data.initialize(0x5154554BL);
        data.start(CAPTAIN);
        return data;
    }

    /** Mission UUIDs are random, so equivalence compares only pacing state. */
    private static void assertMissionEquivalent(ActiveMission expected, ActiveMission actual) {
        if (expected == null) {
            assertNull(actual);
            return;
        }
        assertNotNull(actual);
        assertEquals(expected.type(), actual.type());
        assertEquals(expected.stage(), actual.stage());
        assertEquals(expected.progress(), actual.progress());
        assertEquals(expected.target(), actual.target());
        assertEquals(expected.routeSegment(), actual.routeSegment());
        assertEquals(expected.createdDay(), actual.createdDay());
        assertEquals(expected.worldPrepared(), actual.worldPrepared());
    }

    private static MissionType proposedType(CampaignSavedData data) {
        ActiveMission proposal = data.proposedMission();
        return proposal == null ? null : proposal.type();
    }
}
