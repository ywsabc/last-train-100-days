package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.route.RouteSegmentLayout;
import dev.ywsabc.lasttrain.route.RouteSegmentPlan;
import dev.ywsabc.lasttrain.route.RouteSegmentPlanner;
import dev.ywsabc.lasttrain.route.SegmentTemplate;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/**
 * Durable route plan state (schema 10): rules version defaulting, plan
 * persistence across restarts, forward planning, trimming of realized plans
 * and the explicit version migration.
 */
class CampaignSavedDataRoutePlanTest {
    private static final long CAMPAIGN_SEED = 0x4C415354L;

    private static CampaignSavedData started() {
        CampaignSavedData data = new CampaignSavedData();
        data.initialize(CAMPAIGN_SEED);
        data.start();
        data.markStarterStationBuilt(new BlockPos(0, 64, 0));
        return data;
    }

    private static CompoundTag legacyTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema_version", 9);
        tag.putString("campaign_id", UUID.randomUUID().toString());
        tag.putLong("campaign_seed", CAMPAIGN_SEED);
        tag.putString("status", CampaignStatus.RUNNING.name());
        return tag;
    }

    @Test
    void newCampaignsCommitTheCurrentRouteRulesVersion() {
        assertEquals(RouteSegmentPlanner.DEFAULT_ROUTE_RULES_VERSION, started().routeRulesVersion());
        assertEquals(2, started().routeRulesVersion());
    }

    @Test
    void legacySavesDefaultToRouteRulesVersionOneAndNeverAutoUpgrade() {
        CampaignSavedData data = CampaignSavedData.load(legacyTag(), null);
        assertEquals(1, data.routeRulesVersion());
        assertTrue(data.legacyLinearRoute());

        // A legacy world without any committed plan state stays on the
        // historical linear production and never auto-migrates.
        CampaignSavedData reloaded =
                CampaignSavedData.load(data.save(new CompoundTag(), null), null);
        assertEquals(1, reloaded.routeRulesVersion());
        assertTrue(reloaded.legacyLinearRoute());
    }

    @Test
    void legacyLinearProductionKeepsTheFixedStraightSegmentsAndWaypointPlatforms() {
        CampaignSavedData data = CampaignSavedData.load(legacyTag(), null);
        assertTrue(data.legacyLinearRoute());

        // No multi-template planning: every future segment is the fixed
        // 64-block eastbound straight line.
        for (int segment = 2; segment <= 8; segment++) {
            assertEquals(SegmentTemplate.STRAIGHT, data.routePlan(segment).template());
        }
        // The fixed every-fourth-segment waypoint platform still appears.
        RouteSegmentLayout third = layoutFor(data, 3);
        assertFalse(third.hasPlatform());
        assertTrue(third.branchTrackSections().isEmpty());
        RouteSegmentLayout fourth = layoutFor(data, 4);
        assertTrue(fourth.hasPlatform());
        assertEquals(RouteSegmentLayout.LEGACY_WAYPOINT_PLATFORM_HALF_LENGTH, fourth.platformHalfLength());
        // Legacy production commits no pending plans.
        assertEquals(0, data.plannedRouteSegments().size());
    }

    private static RouteSegmentLayout layoutFor(CampaignSavedData data, int segment) {
        return RouteSegmentLayout.compute(
                data.starterStationAnchor(),
                segment,
                data.routePlan(segment));
    }

    @Test
    void explicitAdoptionExitsLegacyLinearAndPlansFutureSegmentsWithMultiTemplates() {
        CampaignSavedData data = CampaignSavedData.load(legacyTag(), null);
        assertEquals(SegmentTemplate.STRAIGHT, data.routePlan(6).template());

        assertTrue(data.adoptRouteRulesVersion(2));
        assertFalse(data.legacyLinearRoute());
        assertEquals(2, data.routeRulesVersion());
        // Future segments now follow the v2 planner, identical to a fresh
        // planner over the same seed.
        assertEquals(
                new RouteSegmentPlanner(CAMPAIGN_SEED, 0, 2).plan(6),
                data.routePlan(6));
    }

    @Test
    void legacyAndPlannerModesNeverPolluteEachOtherAcrossRestarts() {
        CampaignSavedData legacy = CampaignSavedData.load(legacyTag(), null);
        RouteSegmentPlan straight = legacy.routePlan(4);
        assertEquals(SegmentTemplate.STRAIGHT, straight.template());

        // Legacy production must not persist committed plans that would flip
        // the world into planner mode on reload.
        CampaignSavedData reloaded =
                CampaignSavedData.load(legacy.save(new CompoundTag(), null), null);
        assertTrue(reloaded.legacyLinearRoute());
        assertEquals(straight, reloaded.routePlan(4));

        // After the explicit migration the v2 plans persist and the reload
        // stays in planner mode with the same committed plans.
        assertTrue(reloaded.adoptRouteRulesVersion(2));
        RouteSegmentPlan planned = reloaded.routePlan(4);
        CampaignSavedData migrated =
                CampaignSavedData.load(reloaded.save(new CompoundTag(), null), null);
        assertFalse(migrated.legacyLinearRoute());
        assertEquals(2, migrated.routeRulesVersion());
        assertEquals(planned, migrated.routePlan(4));
    }

    @Test
    void routePlansArePersistedAndRestoredAcrossRestarts() {
        CampaignSavedData data = started();
        RouteSegmentPlan plan = data.routePlan(5);
        assertEquals(plan, data.routePlan(5));

        CampaignSavedData reloaded =
                CampaignSavedData.load(data.save(new CompoundTag(), null), null);
        assertEquals(2, reloaded.routeRulesVersion());
        assertEquals(plan, reloaded.routePlan(5));
        assertEquals(
                new RouteSegmentPlanner(data.campaignSeed(), 0, 2).plan(9),
                reloaded.routePlan(9));
    }

    @Test
    void committedPlansNeverReRollWhenTheExtensionContinuesAfterRestart() {
        CampaignSavedData data = started();
        RouteSegmentPlan plannedEight = data.routePlan(8);
        RouteSegmentPlan plannedFifteen = data.routePlan(15);

        CampaignSavedData reloaded =
                CampaignSavedData.load(data.save(new CompoundTag(), null), null);
        assertEquals(plannedEight, reloaded.routePlan(8));
        assertEquals(plannedFifteen, reloaded.routePlan(15));
        RouteSegmentPlanner fresh = new RouteSegmentPlanner(data.campaignSeed(), 0, 2);
        assertEquals(fresh.plan(8), reloaded.routePlan(8));
        assertEquals(fresh.plan(15), reloaded.routePlan(15));
    }

    @Test
    void realizedPlansAreTrimmedFromThePersistedPendingList() {
        CampaignSavedData data = started();
        data.routePlan(6);
        assertEquals(8, data.plannedRouteSegments().size());

        assertTrue(data.markRouteSegmentGenerated(1));
        data.dropRoutePlanThrough(1);
        assertTrue(
                data.plannedRouteSegments().stream()
                        .noneMatch(plan -> plan.segmentIndex() <= 1));

        CampaignSavedData reloaded =
                CampaignSavedData.load(data.save(new CompoundTag(), null), null);
        assertEquals(data.routePlan(7), reloaded.routePlan(7));
    }

    @Test
    void realizedSegmentsCannotBeRePlanned() {
        CampaignSavedData data = started();
        data.routePlan(3);
        assertTrue(data.markRouteSegmentGenerated(1));
        assertTrue(data.markRouteSegmentGenerated(2));
        assertThrows(IllegalArgumentException.class, () -> data.routePlan(2));
    }

    @Test
    void explicitAdoptionKeepsCommittedPlansButReRollsFutureSegments() {
        // A version-1 world WITH already committed v1 plans stays on the
        // planner (only the empty-plan legacy world keeps LEGACY_LINEAR).
        CampaignSavedData data = CampaignSavedData.load(legacyTag(), null);
        RouteSegmentPlanner v1 = new RouteSegmentPlanner(CAMPAIGN_SEED, 0, 1);
        data.commitRoutePlans(List.of(v1.plan(1), v1.plan(2), v1.plan(3)));
        assertFalse(data.legacyLinearRoute());
        assertEquals(v1.plan(5), data.routePlan(5));

        assertTrue(data.adoptRouteRulesVersion(2));
        assertEquals(2, data.routeRulesVersion());
        assertEquals(v1.plan(3), data.routePlan(3), "committed plans must never re-roll");

        RouteSegmentPlan future = data.routePlan(9);
        assertNotEquals(
                v1.plan(9),
                future,
                "future segments must use the adopted version");

        CampaignSavedData reloaded =
                CampaignSavedData.load(data.save(new CompoundTag(), null), null);
        assertEquals(2, reloaded.routeRulesVersion());
        assertEquals(v1.plan(3), reloaded.routePlan(3));
        assertEquals(future, reloaded.routePlan(9));
    }

    @Test
    void adoptingAnInvalidOrUnchangedVersionIsRejected() {
        CampaignSavedData data = started();
        assertFalse(data.adoptRouteRulesVersion(0));
        assertFalse(data.adoptRouteRulesVersion(-3));
        assertFalse(data.adoptRouteRulesVersion(data.routeRulesVersion()));
    }

    @Test
    void invalidPersistedPlanStateHealsByDeterministicReDerivation() {
        CampaignSavedData data = started();
        data.routePlan(4);
        CompoundTag tag = data.save(new CompoundTag(), null);
        // Corrupt the pending plan list: drop one entry so it is no longer
        // contiguous with the persisted cursor.
        tag.getCompound("route_plan_state").getList("pending_plans", 10).remove(2);

        CampaignSavedData healed = CampaignSavedData.load(tag, null);
        assertEquals(new RouteSegmentPlanner(data.campaignSeed(), 0, 2).plan(4), healed.routePlan(4));
    }

    @Test
    void malformedPlanEntriesAreDroppedOnLoad() {
        CampaignSavedData data = started();
        data.routePlan(4);
        CompoundTag tag = data.save(new CompoundTag(), null);
        CompoundTag entry =
                tag.getCompound("route_plan_state").getList("pending_plans", 10).getCompound(1);
        entry.putString("template", "NOT_A_TEMPLATE");

        CampaignSavedData loaded = CampaignSavedData.load(tag, null);
        assertEquals(new RouteSegmentPlanner(data.campaignSeed(), 0, 2).plan(4), loaded.routePlan(4));
    }

    @Test
    void structurallyCorruptPlanEntriesAreDroppedAndTheSegmentHeals() {
        CampaignSavedData data = started();
        data.routePlan(4);
        CompoundTag tag = data.save(new CompoundTag(), null);
        // Rewrite one pending entry into a STATION plan without its required
        // station interest point: the entry must be dropped on load, and the
        // healed re-derivation must feed a valid plan into the layout.
        CompoundTag entry =
                tag.getCompound("route_plan_state").getList("pending_plans", 10).getCompound(1);
        entry.putString("template", "STATION");
        entry.put("pois", new net.minecraft.nbt.ListTag());

        CampaignSavedData loaded = CampaignSavedData.load(tag, null);
        assertEquals(new RouteSegmentPlanner(data.campaignSeed(), 0, 2).plan(4), loaded.routePlan(4));
        // The layout realizes the healed segment without crashing.
        assertEquals(4, layoutFor(loaded, 4).plan().segmentIndex());
    }
}
