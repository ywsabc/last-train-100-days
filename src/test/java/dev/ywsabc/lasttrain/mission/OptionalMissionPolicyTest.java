package dev.ywsabc.lasttrain.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OptionalMissionPolicyTest {
    @Test
    void rescueHasThreeDaysAndSalvageHasSeven() {
        assertEquals(3, OptionalMissionPolicy.graceDays(MissionType.RESCUE_SURVIVOR));
        assertEquals(7, OptionalMissionPolicy.graceDays(MissionType.SALVAGE_CAR));
    }

    @Test
    void deadlinesAreAbsoluteMonotonicTicksNotCappedDayDifferences() {
        long createdTick = 99L * OptionalMissionPolicy.TICKS_PER_DAY - 1_000L;
        long deadline = OptionalMissionPolicy.deadlineTick(
                createdTick,
                MissionType.RESCUE_SURVIVOR);

        // The campaign day field is clamped at 100, so a day-difference
        // deadline could never fire for a day-99 mission. The absolute tick
        // deadline keeps advancing and passes long before any day arithmetic.
        assertEquals(createdTick + 3L * OptionalMissionPolicy.TICKS_PER_DAY, deadline);
        assertFalse(OptionalMissionPolicy.shouldTimeout(deadline, createdTick));
        assertFalse(OptionalMissionPolicy.shouldTimeout(
                deadline,
                100L * OptionalMissionPolicy.TICKS_PER_DAY));
        assertTrue(OptionalMissionPolicy.shouldTimeout(deadline, deadline));
        assertTrue(OptionalMissionPolicy.shouldTimeout(deadline, deadline + 1));
    }

    @Test
    void deadlineArithmeticSaturatesInsteadOfOverflowing() {
        assertEquals(
                Long.MAX_VALUE,
                OptionalMissionPolicy.deadlineTick(
                        Long.MAX_VALUE,
                        MissionType.SALVAGE_CAR));
    }

    @Test
    void onlyOptionalTypesHaveTickDeadlines() {
        for (MissionType type : MissionType.values()) {
            if (type.category() == MissionType.Category.OPTIONAL) {
                assertTrue(OptionalMissionPolicy.isOptional(type));
            } else {
                assertFalse(OptionalMissionPolicy.isOptional(type));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> OptionalMissionPolicy.graceDays(type));
            }
        }
    }

    @Test
    void concurrentOptionalMissionBudgetIsPositiveAndBounded() {
        assertTrue(OptionalMissionPolicy.MAX_ACTIVE_OPTIONAL_MISSIONS >= 1);
        assertTrue(OptionalMissionPolicy.MAX_ACTIVE_OPTIONAL_MISSIONS <= 8);
    }
}
