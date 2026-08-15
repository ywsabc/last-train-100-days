package dev.ywsabc.lasttrain.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class MissionPoolPolicyTest {
    private static final List<MissionPoolPolicy.Entry> EMPTY = List.of();

    @Test
    void aFreshPoolAllowsAnyMainlineMission() {
        for (MissionType type : MissionPoolPolicy.MAINLINE_TYPES) {
            assertTrue(MissionPoolPolicy.mayCreateMainline(EMPTY, type));
        }
    }

    @Test
    void theSameMainlineTypeCannotFollowItself() {
        List<MissionPoolPolicy.Entry> history = List.of(
                MissionPoolPolicy.entry(MissionType.RAIL_BREAK, MissionPoolPolicy.Outcome.COMPLETED));

        assertFalse(MissionPoolPolicy.mayCreateMainline(history, MissionType.RAIL_BREAK));
        assertTrue(MissionPoolPolicy.mayCreateMainline(history, MissionType.STATION_POWER));
    }

    @Test
    void zombieBlockadesAreHardBlockedBackToBackEvenAsPressureEntries() {
        List<MissionPoolPolicy.Entry> history = List.of(
                MissionPoolPolicy.entry(MissionType.ZOMBIE_BLOCKADE, MissionPoolPolicy.Outcome.COMPLETED));

        assertFalse(MissionPoolPolicy.mayCreateMainline(history, MissionType.ZOMBIE_BLOCKADE));
        // The pressure siege must be postponed, not lost: another mainline
        // mission between two blockades opens the door again.
        List<MissionPoolPolicy.Entry> afterRail = List.of(
                MissionPoolPolicy.entry(MissionType.ZOMBIE_BLOCKADE, MissionPoolPolicy.Outcome.COMPLETED),
                MissionPoolPolicy.entry(MissionType.RAIL_BREAK, MissionPoolPolicy.Outcome.COMPLETED));
        assertTrue(MissionPoolPolicy.mayCreateMainline(afterRail, MissionType.ZOMBIE_BLOCKADE));
    }

    @Test
    void onlyTheMostRecentMainlineEntryConstrainsSelection() {
        List<MissionPoolPolicy.Entry> history = List.of(
                MissionPoolPolicy.entry(MissionType.RAIL_BREAK, MissionPoolPolicy.Outcome.COMPLETED),
                MissionPoolPolicy.entry(MissionType.RESCUE_SURVIVOR, MissionPoolPolicy.Outcome.SKIPPED),
                MissionPoolPolicy.entry(MissionType.STATION_GATE, MissionPoolPolicy.Outcome.FAILED));

        assertEquals(MissionType.STATION_GATE, MissionPoolPolicy.lastMainlineType(history).orElseThrow());
        assertFalse(MissionPoolPolicy.mayCreateMainline(history, MissionType.STATION_GATE));
        assertTrue(MissionPoolPolicy.mayCreateMainline(history, MissionType.RAIL_BREAK));
    }

    @Test
    void optionalOutcomesNeverCountAsMainline() {
        List<MissionPoolPolicy.Entry> history = List.of(
                MissionPoolPolicy.entry(MissionType.SALVAGE_CAR, MissionPoolPolicy.Outcome.FAILED));

        assertTrue(MissionPoolPolicy.lastMainlineType(history).isEmpty());
    }

    @Test
    void twoConsecutiveFailuresCountAcrossMissionKinds() {
        assertEquals(0, MissionPoolPolicy.consecutiveFailures(EMPTY));
        assertEquals(
                2,
                MissionPoolPolicy.consecutiveFailures(List.of(
                        MissionPoolPolicy.entry(MissionType.RAIL_BREAK, MissionPoolPolicy.Outcome.FAILED),
                        MissionPoolPolicy.entry(MissionType.RESCUE_SURVIVOR, MissionPoolPolicy.Outcome.FAILED))));
        assertEquals(
                1,
                MissionPoolPolicy.consecutiveFailures(List.of(
                        MissionPoolPolicy.entry(MissionType.RAIL_BREAK, MissionPoolPolicy.Outcome.FAILED),
                        MissionPoolPolicy.entry(MissionType.RAIL_BREAK, MissionPoolPolicy.Outcome.COMPLETED),
                        MissionPoolPolicy.entry(MissionType.STATION_GATE, MissionPoolPolicy.Outcome.FAILED))));
    }

    @Test
    void afterTwoFailuresOnlyShortOrSuppliedMissionsAreDrawn() {
        List<MissionPoolPolicy.Entry> history = List.of(
                MissionPoolPolicy.entry(MissionType.STATION_POWER, MissionPoolPolicy.Outcome.FAILED),
                MissionPoolPolicy.entry(MissionType.ZOMBIE_BLOCKADE, MissionPoolPolicy.Outcome.FAILED));

        assertTrue(MissionPoolPolicy.consecutiveFailures(history) >= 2);
        SplittableRandom random = new SplittableRandom(42L);
        for (int roll = 0; roll < 100; roll++) {
            MissionType type =
                    MissionPoolPolicy.selectMainMissionType(history, random).orElseThrow();
            assertTrue(MissionPoolPolicy.isShortOrSupplied(type), type.name());
        }
    }

    @Test
    void shortMeansAtMostFourGraceDaysAndSuppliedMeansGuaranteedMaterials() {
        assertTrue(MissionPoolPolicy.isShortOrSupplied(MissionType.RAIL_BREAK));
        assertTrue(MissionPoolPolicy.isShortOrSupplied(MissionType.STATION_GATE));
        assertTrue(MissionPoolPolicy.isShortOrSupplied(MissionType.SUPPLY_RECOVERY));
        assertFalse(MissionPoolPolicy.isShortOrSupplied(MissionType.STATION_POWER));
        assertFalse(MissionPoolPolicy.isShortOrSupplied(MissionType.ZOMBIE_BLOCKADE));
        // Rescue (3 days) is short; salvage (7 days) is not a recovery pick.
        assertTrue(MissionPoolPolicy.isShortOrSupplied(MissionType.RESCUE_SURVIVOR));
        assertFalse(MissionPoolPolicy.isShortOrSupplied(MissionType.SALVAGE_CAR));
    }

    @Test
    void historyEntriesAreBoundedToNewestEntriesOnLoad() {
        List<MissionPoolPolicy.Entry> longHistory = new ArrayList<>();
        for (int index = 0; index < MissionPoolPolicy.HISTORY_LIMIT + 8; index++) {
            longHistory.add(MissionPoolPolicy.entry(
                    MissionType.RAIL_BREAK,
                    MissionPoolPolicy.Outcome.COMPLETED));
        }
        List<MissionPoolPolicy.Entry> bounded = MissionPoolPolicy.bounded(longHistory);

        assertEquals(MissionPoolPolicy.HISTORY_LIMIT, bounded.size());
        assertEquals(longHistory.get(longHistory.size() - 1), bounded.get(bounded.size() - 1));
        assertEquals(
                longHistory.get(longHistory.size() - MissionPoolPolicy.HISTORY_LIMIT),
                bounded.get(0));
    }

    @Test
    void optionalDrawsOnlyReturnOptionalTypes() {
        SplittableRandom random = new SplittableRandom(7L);
        for (int roll = 0; roll < 100; roll++) {
            MissionType type = MissionPoolPolicy.selectOptionalType(random).orElseThrow();
            assertEquals(MissionType.Category.OPTIONAL, type.category());
        }
    }
}
