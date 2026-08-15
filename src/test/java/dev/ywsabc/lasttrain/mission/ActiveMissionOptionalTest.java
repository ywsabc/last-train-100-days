package dev.ywsabc.lasttrain.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class ActiveMissionOptionalTest {
    @Test
    void proposalsStartProposedWithAnAbsoluteTickDeadline() {
        long createdTick = 99L * OptionalMissionPolicy.TICKS_PER_DAY - 500L;
        ActiveMission proposal = ActiveMission.createProposal(
                MissionType.RESCUE_SURVIVOR,
                99,
                40,
                createdTick);

        assertEquals(MissionStage.PROPOSED, proposal.stage());
        assertEquals(0, proposal.revision());
        assertEquals(createdTick, proposal.createdTick());
        assertEquals(
                createdTick + 3L * OptionalMissionPolicy.TICKS_PER_DAY,
                proposal.deadlineTick());
        assertTrue(proposal.isOptional());
        assertFalse(proposal.type().blocksRoute());
    }

    @Test
    void mainlineTypesCannotBeProposed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ActiveMission.createProposal(
                        MissionType.RAIL_BREAK,
                        10,
                        1,
                        1_000L));
    }

    @Test
    void eachTransitionBumpsTheRevision() {
        ActiveMission proposal = ActiveMission.createProposal(
                MissionType.SALVAGE_CAR,
                10,
                2,
                2_000L);

        assertTrue(proposal.transitionTo(MissionStage.ACTIVE));
        assertEquals(1, proposal.revision());
        assertFalse(proposal.transitionTo(MissionStage.ACTIVE));
        assertEquals(1, proposal.revision());

        assertTrue(proposal.transitionTo(MissionStage.SKIPPED));
        assertEquals(2, proposal.revision());
    }

    @Test
    void salvageRepairsDeduplicateByIndexAndCapAtTarget() {
        ActiveMission mission = ActiveMission.createProposal(
                MissionType.SALVAGE_CAR,
                5,
                3,
                5_000L);
        mission.transitionTo(MissionStage.ACTIVE);
        int target = mission.target();
        assertEquals(MissionType.SALVAGE_CAR.defaultTarget(), target);

        assertTrue(mission.recordRepairIndex(0));
        assertFalse(mission.recordRepairIndex(0));
        assertEquals(1, mission.progress());
        assertEquals(MissionStage.ACTIVE, mission.stage());

        for (int index = 1; index < target; index++) {
            assertTrue(mission.recordRepairIndex(index));
        }
        assertFalse(mission.recordRepairIndex(target - 1));
        assertEquals(target, mission.progress());
        assertEquals(MissionStage.READY_TO_TURN_IN, mission.stage());
        assertFalse(mission.recordRepairIndex(0));
    }

    @Test
    void salvageRejectsOutOfRangeRepairsAndGenericProgressWrites() {
        ActiveMission mission = ActiveMission.createProposal(
                MissionType.SALVAGE_CAR,
                5,
                3,
                5_000L);
        mission.transitionTo(MissionStage.ACTIVE);

        assertFalse(mission.recordRepairIndex(-1));
        assertFalse(mission.recordRepairIndex(mission.target()));
        assertFalse(mission.addProgress(3));
        assertFalse(mission.setObservedProgress(2));
        assertEquals(0, mission.progress());
    }

    @Test
    void terminalOptionalMissionsRefuseFurtherProgress() {
        ActiveMission mission = ActiveMission.createProposal(
                MissionType.RESCUE_SURVIVOR,
                5,
                3,
                5_000L);
        mission.transitionTo(MissionStage.ACTIVE);
        mission.transitionTo(MissionStage.FAILED);

        assertFalse(mission.recordRepairIndex(0));
        assertFalse(mission.addProgress(1));
        assertFalse(mission.setObservedProgress(1));
    }

    @Test
    void optionalStateRoundTripsThroughNbt() {
        ActiveMission mission = ActiveMission.createProposal(
                UUID.randomUUID(),
                MissionType.SALVAGE_CAR,
                12,
                7,
                8,
                12_345L);
        mission.transitionTo(MissionStage.ACTIVE);
        mission.recordRepairIndex(2);
        mission.recordRepairIndex(5);

        ActiveMission loaded = ActiveMission.load(mission.save(null), null);

        assertEquals(mission.id(), loaded.id());
        assertEquals(MissionStage.ACTIVE, loaded.stage());
        assertEquals(mission.revision(), loaded.revision());
        assertEquals(mission.createdTick(), loaded.createdTick());
        assertEquals(mission.deadlineTick(), loaded.deadlineTick());
        assertEquals(2, loaded.progress());
        assertTrue(loaded.hasRepairIndex(2));
        assertTrue(loaded.hasRepairIndex(5));
        assertFalse(loaded.hasRepairIndex(0));
    }

    @Test
    void loadingCapsTargetsIndicesAndMissingDeadlines() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", UUID.randomUUID().toString());
        tag.putString("type", "salvage_car");
        tag.putString("stage", "active");
        tag.putInt("target", 10);
        tag.putIntArray(
                "repair_indices",
                new int[]{3, 3, 7, 200, -5});

        ActiveMission loaded = ActiveMission.load(tag, null);

        assertEquals(Long.MAX_VALUE, loaded.deadlineTick());
        assertTrue(loaded.hasRepairIndex(3));
        assertTrue(loaded.hasRepairIndex(7));
        assertEquals(2, loaded.progress());
        assertFalse(loaded.hasRepairIndex(200));
        assertFalse(loaded.hasRepairIndex(-5));

        CompoundTag huge = new CompoundTag();
        huge.putString("id", UUID.randomUUID().toString());
        huge.putString("type", "salvage_car");
        huge.putInt("target", 1_000_000);
        ActiveMission capped = ActiveMission.load(huge, null);
        assertEquals(ActiveMission.MAX_TARGET, capped.target());
    }

    @Test
    void repairIndexSetCapsAtSixtyFourEntries() {
        ActiveMission mission = ActiveMission.createProposal(
                UUID.randomUUID(),
                MissionType.SALVAGE_CAR,
                5,
                1,
                ActiveMission.MAX_REPAIR_INDICES + 32,
                5_000L);
        mission.transitionTo(MissionStage.ACTIVE);

        for (int index = 0; index < ActiveMission.MAX_REPAIR_INDICES + 10; index++) {
            mission.recordRepairIndex(index);
        }
        assertEquals(ActiveMission.MAX_REPAIR_INDICES, mission.progress());
        assertFalse(mission.recordRepairIndex(999));
    }
}
