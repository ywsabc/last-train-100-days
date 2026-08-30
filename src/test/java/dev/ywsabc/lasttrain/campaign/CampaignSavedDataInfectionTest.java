package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.mission.MissionType;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class CampaignSavedDataInfectionTest {
    @Test
    void activeTickPersistsMonotonicInfectionState() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());

        data.tick(1);
        assertEquals(1L, data.infectionTicks());
        assertEquals(InfectionPolicy.Stage.LATENT.index(), data.infectionStage());

        CompoundTag saved = data.save(new CompoundTag(), null);
        assertEquals(11, saved.getInt("schema_version"));
        assertEquals(data.infectionStage(), saved.getInt("infection_stage"));
        assertEquals(data.infectionTicks(), saved.getLong("infection_ticks"));

        CampaignSavedData loaded = CampaignSavedData.load(saved, null);
        assertEquals(data.infectionStage(), loaded.infectionStage());
        assertEquals(data.infectionTicks(), loaded.infectionTicks());
        assertEquals(data.infectionSample(), loaded.infectionSample());
    }

    @Test
    void savedCounterCrossesAStageThresholdOnTheNextActiveTick() {
        CompoundTag tag = runningTag(CampaignSavedData.CURRENT_SCHEMA);
        tag.putInt("infection_stage", InfectionPolicy.Stage.LATENT.index());
        tag.putLong(
                "infection_ticks",
                InfectionPolicy.SCARCITY_THRESHOLD_TICKS - 1L);
        tag.putInt("pursuit_distance", PursuitPolicy.MAX_PURSUIT_DISTANCE);

        CampaignSavedData data = CampaignSavedData.load(tag, null);
        data.tick(1);

        assertEquals(InfectionPolicy.SCARCITY_THRESHOLD_TICKS, data.infectionTicks());
        assertEquals(InfectionPolicy.Stage.SCARCITY.index(), data.infectionStage());
        assertEquals(InfectionPolicy.Stage.SCARCITY, data.infectionSample().stage());

        CampaignSavedData reloaded = CampaignSavedData.load(
                data.save(new CompoundTag(), null),
                null);
        assertEquals(data.infectionStage(), reloaded.infectionStage());
        assertEquals(data.infectionTicks(), reloaded.infectionTicks());
    }

    @Test
    void gunfireExplosionAndHordeFeedTheSavedCounter() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());

        data.registerGunfire();
        data.registerExplosion();
        data.registerHorde();

        assertEquals(
                InfectionPolicy.GUNFIRE_PROGRESS_TICKS
                        + InfectionPolicy.EXPLOSION_PROGRESS_TICKS
                        + InfectionPolicy.HORDE_PROGRESS_TICKS,
                data.infectionTicks());
    }

    @Test
    void pursuitCatchUpFeedsOneHordeEventIntoInfection() {
        CompoundTag tag = runningTag(CampaignSavedData.CURRENT_SCHEMA);
        tag.putInt("pursuit_distance", 1);
        tag.putInt("attention", PursuitPolicy.INITIAL_ATTENTION);
        tag.putInt("infection_stage", InfectionPolicy.Stage.LATENT.index());
        tag.putLong(
                "infection_ticks",
                InfectionPolicy.SCARCITY_THRESHOLD_TICKS
                        - InfectionPolicy.HORDE_PROGRESS_TICKS
                        - PursuitPolicy.SAMPLE_INTERVAL_TICKS * 5L);
        CampaignSavedData data = CampaignSavedData.load(tag, null);
        CampaignSavedData.TickOutcome outcome = CampaignSavedData.TickOutcome.NONE;

        for (int tick = 0; tick < PursuitPolicy.SAMPLE_INTERVAL_TICKS; tick++) {
            outcome = data.tick(1);
        }

        assertEquals(CampaignSavedData.TickOutcome.SIEGE_TRIGGERED, outcome);
        assertEquals(MissionType.ZOMBIE_BLOCKADE, data.activeMission().type());
        assertEquals(InfectionPolicy.SCARCITY_THRESHOLD_TICKS, data.infectionTicks());
        assertEquals(InfectionPolicy.Stage.SCARCITY.index(), data.infectionStage());
        assertEquals(
                PopulationScalingPolicy.missionTarget(
                        MissionType.ZOMBIE_BLOCKADE,
                        1,
                        InfectionPolicy.Stage.SCARCITY),
                data.activeMission().target());
    }

    @Test
    void noPlayersPauseTicksAndWorldEventsAtTheSavedDataBoundary() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        long ticksBefore = data.infectionTicks();
        int attentionBefore = data.attention();
        int pursuitBefore = data.pursuitDistance();

        assertEquals(CampaignSavedData.TickOutcome.NONE, data.tick(0));
        data.registerGunfire(false);
        data.registerExplosion(false);
        data.registerHorde(false);

        assertEquals(ticksBefore, data.infectionTicks());
        assertEquals(0L, data.totalActiveTicks());
        assertEquals(attentionBefore, data.attention());
        assertEquals(pursuitBefore, data.pursuitDistance());
    }

    @Test
    void schemaTenSaveDefaultsToStageZeroWithoutDayRecalculation() {
        CompoundTag old = runningTag(10);
        old.putInt("day", CampaignSavedData.FINAL_DAY);
        old.putLong("total_active_ticks", Long.MAX_VALUE / 2L);
        old.putBoolean("final_day_elapsed", true);

        CampaignSavedData loaded = CampaignSavedData.load(old, null);

        assertEquals(InfectionPolicy.Stage.LATENT.index(), loaded.infectionStage());
        assertEquals(0L, loaded.infectionTicks());
        assertEquals(InfectionPolicy.Stage.LATENT, loaded.infectionSample().stage());

        CompoundTag migrated = loaded.save(new CompoundTag(), null);
        assertEquals(CampaignSavedData.CURRENT_SCHEMA, migrated.getInt("schema_version"));
        assertEquals(0, migrated.getInt("infection_stage"));
        assertEquals(0L, migrated.getLong("infection_ticks"));
    }

    @Test
    void finalDayCapDoesNotFreezeTheIndependentInfectionClock() {
        CompoundTag old = runningTag(10);
        old.putInt("day", CampaignSavedData.FINAL_DAY);
        old.putBoolean("final_day_elapsed", true);
        CampaignSavedData data = CampaignSavedData.load(old, null);

        data.tick(1);

        assertEquals(1L, data.infectionTicks());
        assertEquals(CampaignSavedData.FINAL_DAY, data.day());
    }

    @Test
    void persistedStageControlsZombieBlockadeIntensity() {
        CompoundTag tag = runningTag(CampaignSavedData.CURRENT_SCHEMA);
        tag.putInt("infection_stage", InfectionPolicy.Stage.FINALE.index());
        tag.putLong("infection_ticks", InfectionPolicy.FINALE_THRESHOLD_TICKS);
        CampaignSavedData data = CampaignSavedData.load(tag, null);

        assertTrue(data.createMission(MissionType.ZOMBIE_BLOCKADE, 1));

        assertEquals(
                PopulationScalingPolicy.missionTarget(
                        MissionType.ZOMBIE_BLOCKADE,
                        1,
                        InfectionPolicy.Stage.FINALE),
                data.activeMission().target());
    }

    private static CompoundTag runningTag(int schema) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema_version", schema);
        tag.putString("campaign_id", "00000000-0000-0000-0000-000000000051");
        tag.putString("status", CampaignStatus.RUNNING.name());
        tag.putInt("day", 1);
        return tag;
    }
}
