package dev.ywsabc.lasttrain.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A10：关键物品识别、补发代次和反复制。 */
class CriticalItemRecoveryPolicyTest {
    @Test
    void registryBindsItemsToTheExactMissionAndPhase() {
        ActiveMission mission = ActiveMission.create(MissionType.STATION_POWER, 8, 2);
        CriticalMissionItemRegistry.Key key = CriticalMissionItemRegistry.requiredBy(mission)
                .orElseThrow();
        assertEquals(CriticalMissionItemRegistry.Key.STATION_FUSE, key);

        long generation = mission.issueCriticalItem();
        assertTrue(CriticalMissionItemRegistry.accepts(
                mission,
                mission.id(),
                key,
                MissionType.MissionPhase.RESTORE_POWER,
                generation));
        assertFalse(CriticalMissionItemRegistry.accepts(
                mission,
                mission.id(),
                key,
                MissionType.MissionPhase.RESTORE_POWER,
                generation - 1));
        assertFalse(CriticalMissionItemRegistry.accepts(
                mission,
                java.util.UUID.randomUUID(),
                key,
                MissionType.MissionPhase.RESTORE_POWER,
                generation));
        assertFalse(CriticalMissionItemRegistry.accepts(
                mission,
                mission.id(),
                key,
                MissionType.MissionPhase.RESTORE_POWER,
                generation,
                "minecraft:dirt"));
    }

    @Test
    void missingCopyIssuesANewerGenerationAndInvalidatesOldCopies() {
        CriticalItemRecoveryPolicy.Plan missing = CriticalItemRecoveryPolicy.reconcile(
                3L,
                false,
                List.of(new CriticalItemRecoveryPolicy.ObservedCopy("old-chest", 2L)));

        assertTrue(missing.issueReplacement());
        assertEquals(4L, missing.replacementGeneration());
        assertEquals(Set.of("old-chest"), missing.invalidateLocations());
        assertTrue(missing.keepLocation().isEmpty());
    }

    @Test
    void duplicateCurrentCopiesKeepOneDeterministically() {
        CriticalItemRecoveryPolicy.Plan plan = CriticalItemRecoveryPolicy.reconcile(
                7L,
                false,
                List.of(
                        new CriticalItemRecoveryPolicy.ObservedCopy("player:z", 7L),
                        new CriticalItemRecoveryPolicy.ObservedCopy("crate:0", 7L),
                        new CriticalItemRecoveryPolicy.ObservedCopy("entity:a", 6L)));

        assertFalse(plan.issueReplacement());
        assertEquals("crate:0", plan.keepLocation().orElseThrow());
        assertEquals(Set.of("player:z", "entity:a"), plan.invalidateLocations());
    }

    @Test
    void oneGenerationCanBeRedeemedOnlyOnceAndPersistsAcrossReload() {
        ActiveMission mission = ActiveMission.create(MissionType.STATION_POWER, 8, 2);
        long generation = mission.issueCriticalItem();
        assertTrue(mission.redeemCriticalItem(generation));
        assertFalse(mission.redeemCriticalItem(generation));

        ActiveMission loaded = ActiveMission.load(mission.save(null), null);
        assertEquals(generation, loaded.criticalItemGeneration());
        assertTrue(loaded.criticalItemRedeemed());
        assertFalse(CriticalMissionItemRegistry.accepts(
                loaded,
                loaded.id(),
                CriticalMissionItemRegistry.Key.STATION_FUSE,
                loaded.currentPhase(),
                generation));

        CriticalItemRecoveryPolicy.Plan afterRedeem = CriticalItemRecoveryPolicy.reconcile(
                generation,
                true,
                List.of(new CriticalItemRecoveryPolicy.ObservedCopy("copied", generation)));
        assertFalse(afterRedeem.issueReplacement());
        assertEquals(Set.of("copied"), afterRedeem.invalidateLocations());
    }

    @Test
    void replacementInvalidatesTheOldGenerationAtTheSavedDataBoundary() {
        CampaignSavedData data = new CampaignSavedData();
        data.start();
        assertTrue(data.createMission(MissionType.STATION_POWER));
        long first = data.issueMissionCriticalItem();
        long replacement = data.issueMissionCriticalItem();

        assertEquals(first + 1L, replacement);
        assertFalse(data.redeemMissionCriticalItem(
                CriticalMissionItemRegistry.Key.STATION_FUSE,
                first));
        assertTrue(data.redeemMissionCriticalItem(
                CriticalMissionItemRegistry.Key.STATION_FUSE,
                replacement));
        assertFalse(data.redeemMissionCriticalItem(
                CriticalMissionItemRegistry.Key.STATION_FUSE,
                replacement));
    }

}
