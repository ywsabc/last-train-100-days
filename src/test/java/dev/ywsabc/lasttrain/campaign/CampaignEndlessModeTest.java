package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.mission.MissionType;
import dev.ywsabc.lasttrain.mission.RewardOutboxPolicy;
import dev.ywsabc.lasttrain.route.RouteProgressPolicy;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

class CampaignEndlessModeTest {
    private static final UUID CAPTAIN =
            UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID MEMBER =
            UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final UUID RECEIPT =
            UUID.fromString("00000000-0000-0000-0000-000000000013");

    @Test
    void completedCampaignEntersEndlessAndPreservesWorldTeamAndReceipts() {
        CampaignSavedData data = completedSave(123, 126);

        assertEquals(CampaignMode.STORY_100_DAYS, data.mode());
        assertTrue(data.enableEndlessMode());
        assertEquals(CampaignMode.ENDLESS, data.mode());
        assertEquals(CampaignStatus.RUNNING, data.status());
        assertEquals(100, data.day());
        assertEquals(123, data.routeSegment());
        assertEquals(126, data.generatedRouteSegment());
        assertEquals(Set.of(CAPTAIN, MEMBER), data.teamMembers());
        assertEquals(
                RewardOutboxPolicy.ReceiptState.PENDING,
                data.rewardReceipt(RECEIPT).orElseThrow().state());

        CompoundTag saved = data.save(new CompoundTag(), null);
        assertEquals(CampaignMode.ENDLESS.serializedName(), saved.getString("mode"));
        assertEquals(CampaignSavedData.CURRENT_SCHEMA, saved.getInt("schema_version"));
        CampaignSavedData loaded = CampaignSavedData.load(saved, null);
        assertEquals(CampaignMode.ENDLESS, loaded.mode());
        assertEquals(CampaignStatus.RUNNING, loaded.status());
        assertEquals(data.routeSegment(), loaded.routeSegment());
        assertEquals(data.generatedRouteSegment(), loaded.generatedRouteSegment());
    }

    @Test
    void missingModeInAnOldSaveDefaultsToStoryAndIsWrittenOnMigration() {
        CompoundTag old = new CompoundTag();
        old.putInt("schema_version", 8);
        old.putString("status", CampaignStatus.RUNNING.name());
        old.putInt("day", 42);

        CampaignSavedData data = CampaignSavedData.load(old, null);

        assertEquals(CampaignMode.STORY_100_DAYS, data.mode());
        CompoundTag migrated = data.save(new CompoundTag(), null);
        assertEquals(
                CampaignMode.STORY_100_DAYS.serializedName(),
                migrated.getString("mode"));
        assertEquals(CampaignSavedData.CURRENT_SCHEMA, migrated.getInt("schema_version"));
    }

    @Test
    void endlessSwitchIsOnlyAvailableAfterCompletionAndIsIrreversible() {
        CampaignSavedData running = new CampaignSavedData();
        assertTrue(running.start());
        assertFalse(running.enableEndlessMode());

        CampaignSavedData completed = completedSave(0, 0);
        assertTrue(completed.enableEndlessMode());
        assertFalse(completed.enableEndlessMode());
        assertTrue(CampaignMode.ENDLESS.isIrreversible());
    }

    @Test
    void endlessDayAndThreatContinueBeyondTheStoryCap() {
        CampaignSavedData data = completedSave(0, 0);
        assertTrue(data.enableEndlessMode());

        int threatBefore = data.threat();
        data.advanceDays(6);

        assertEquals(CampaignSavedData.FINAL_DAY + 6, data.day());
        assertTrue(data.threat() > threatBefore);
    }

    @Test
    void endlessActiveClockCrossesDay100WithoutReopeningTheFinale() {
        CampaignSavedData data = completedSave(0, 0);
        assertTrue(data.enableEndlessMode());

        for (int tick = 0; tick < CampaignSavedData.DEFAULT_ACTIVE_TICKS_PER_DAY; tick++) {
            data.tick();
        }

        assertEquals(CampaignSavedData.FINAL_DAY + 1, data.day());
        assertFalse(data.finalDayElapsed());
        assertFalse(data.isFinaleMission(data.activeMission()));
    }

    @Test
    void endlessPacingSkipsChaptersAndFinaleButKeepsOrdinaryMissionsAvailable() {
        CampaignSavedData data = completedSave(0, 0);
        assertTrue(data.enableEndlessMode());
        data.advanceDays(1);
        data.clearMission();

        assertTrue(
                CampaignPacingPolicy.expectedRouteSegment(CampaignMode.ENDLESS, 150)
                        > CampaignPacingPolicy.expectedRouteSegment(100));
        assertTrue(
                CampaignPacingPolicy.nextKeyMission(
                                CampaignMode.ENDLESS,
                                150,
                                150,
                                Set.of())
                        .isEmpty());
        assertTrue(CampaignPacingPolicy.allowsMainlineMission(CampaignMode.ENDLESS, 150));
        assertTrue(FinalePolicy.allowsOrdinaryMission(CampaignMode.ENDLESS, data.status(), 150));
        assertTrue(data.createMission(MissionType.RAIL_BREAK));
        assertFalse(data.isFinaleMission(data.activeMission()));
        data.clearMission();
        assertTrue(data.proposeOptionalMission(MissionType.SALVAGE_CAR));
        assertEquals(
                FinalePolicy.Directive.NONE,
                FinalePolicy.nextDirective(
                        CampaignMode.ENDLESS,
                        CampaignStatus.RUNNING,
                        150,
                        false,
                        true,
                        false));
    }

    @Test
    void endlessAttentionBaselineRisesSlowlyAndSiegesRemainOrdinaryMissions() {
        assertTrue(
                PursuitPolicy.minAttention(CampaignMode.ENDLESS, 150)
                        > PursuitPolicy.minAttention(CampaignMode.ENDLESS, 100));
        assertTrue(
                PursuitPolicy.shouldTriggerSiege(
                        CampaignMode.ENDLESS,
                        CampaignStatus.RUNNING,
                        150,
                        0,
                        false));
    }

    @Test
    void endlessRouteContinuesUntilTheHardSafetyLimitWithExplicitAvailability() {
        CampaignSavedData data = completedSave(CampaignSavedData.MAX_ROUTE_SEGMENT - 1,
                CampaignSavedData.MAX_ROUTE_SEGMENT - 1);
        assertTrue(data.enableEndlessMode());

        assertTrue(data.advanceRoute(1));
        assertEquals(CampaignSavedData.MAX_ROUTE_SEGMENT, data.routeSegment());
        assertFalse(data.advanceRoute(1));
        assertTrue(data.routeSafetyLimitReached());
        assertTrue(
                RouteProgressPolicy.isAtSafetyLimit(
                        data.routeSegment(), CampaignSavedData.MAX_ROUTE_SEGMENT));
        assertTrue(
                RouteProgressPolicy.allowsRouteGeneration(
                        CampaignMode.ENDLESS, CampaignStatus.RUNNING));
        assertFalse(
                RouteProgressPolicy.allowsRouteGeneration(
                        CampaignMode.STORY_100_DAYS, CampaignStatus.COMPLETED));
    }

    @Test
    void ordinaryMemberIsDeniedTheEndlessModePermissionGate() {
        TeamPermissionPolicy.TeamState team =
                new TeamPermissionPolicy.TeamState(CAPTAIN, Set.of(CAPTAIN, MEMBER), true);
        assertEquals(
                TeamPermissionPolicy.Decision.DENY,
                TeamPermissionPolicy.decide(
                        TeamPermissionPolicy.Operation.ENABLE_ENDLESS_MODE,
                        new TeamPermissionPolicy.Requester(MEMBER, true, false, 0),
                        team,
                        TeamPermissionPolicy.Config.defaults()));
    }

    private static CampaignSavedData completedSave(int routeSegment, int generatedSegment) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema_version", CampaignSavedData.CURRENT_SCHEMA);
        tag.putString("campaign_id", UUID.randomUUID().toString());
        tag.putString("status", CampaignStatus.COMPLETED.name());
        tag.putInt("day", CampaignSavedData.FINAL_DAY);
        tag.putBoolean("final_day_elapsed", true);
        tag.putBoolean("finale_mission_completed", true);
        tag.putInt("route_segment", routeSegment);
        tag.putInt("generated_route_segment", generatedSegment);
        tag.putInt("threat", 10);

        ListTag members = new ListTag();
        members.add(StringTag.valueOf(CAPTAIN.toString()));
        members.add(StringTag.valueOf(MEMBER.toString()));
        tag.put("team_members", members);
        tag.putString("captain_id", CAPTAIN.toString());

        CompoundTag receipt = new CompoundTag();
        receipt.putString("mission_id", RECEIPT.toString());
        receipt.putString("type", MissionType.RESCUE_SURVIVOR.serializedName());
        receipt.putString("state", RewardOutboxPolicy.ReceiptState.PENDING.name());
        ListTag receipts = new ListTag();
        receipts.add(receipt);
        tag.put("reward_receipts", receipts);
        return CampaignSavedData.load(tag, null);
    }
}
