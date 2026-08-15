package dev.ywsabc.lasttrain.route;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.ywsabc.lasttrain.campaign.CampaignMode;
import dev.ywsabc.lasttrain.campaign.CampaignStatus;
import org.junit.jupiter.api.Test;

class RouteProgressPolicyEndlessTest {
    @Test
    void endlessMileageLineDoesNotClampAtTheStoryDay() {
        assertTrue(
                RouteProgressPolicy.expectedRouteSegment(CampaignMode.ENDLESS, 150)
                        > RouteProgressPolicy.expectedRouteSegment(CampaignMode.ENDLESS, 100));
    }

    @Test
    void completedStoryStopsGenerationUntilTheModeIsSwitched() {
        assertFalse(
                RouteProgressPolicy.allowsRouteGeneration(
                        CampaignMode.STORY_100_DAYS, CampaignStatus.COMPLETED));
        assertTrue(
                RouteProgressPolicy.allowsRouteGeneration(
                        CampaignMode.ENDLESS, CampaignStatus.RUNNING));
    }
}
