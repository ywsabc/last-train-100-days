package dev.ywsabc.lasttrain.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import dev.ywsabc.lasttrain.testing.FaultInjection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Route segment generation under injected materialization failures.
 *
 * <p>The pure {@link RouteDirector#segmentGenerationAllowed()} gate is
 * consulted before the world-bound generator touches any block. A failed
 * attempt never commits segment progress, the commit API keeps enforcing
 * in-order generation, and the next tick retries the same segment.</p>
 */
class RouteDirectorFaultInjectionTest {
    @AfterEach
    void clearInjections() {
        FaultInjection.clear();
    }

    @Test
    void segmentGenerationGateFailsWhileInjectedAndRetriesAfter() {
        assertTrue(RouteDirector.segmentGenerationAllowed());

        FaultInjection.register(FaultInjection.FailurePoint.ROUTE_SEGMENT_GENERATION, 1);
        assertFalse(RouteDirector.segmentGenerationAllowed());
        assertTrue(RouteDirector.segmentGenerationAllowed());

        FaultInjection.clear();
        assertTrue(RouteDirector.segmentGenerationAllowed());
    }

    @Test
    void failedSegmentGenerationNeverCommitsProgressAndTheRetryCommitsInOrder() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());

        FaultInjection.register(FaultInjection.FailurePoint.ROUTE_SEGMENT_GENERATION, 1);
        assertFalse(RouteDirector.segmentGenerationAllowed());
        assertEquals(0, data.generatedRouteSegment());
        // Progress stays ordered even while generation is down: jumping to
        // segment 2 without committing segment 1 is rejected.
        assertThrows(
                IllegalArgumentException.class,
                () -> data.markRouteSegmentGenerated(2));

        // Recovery: the next attempt commits exactly the next segment.
        assertTrue(RouteDirector.segmentGenerationAllowed());
        assertTrue(data.markRouteSegmentGenerated(1));
        assertTrue(data.markRouteSegmentGenerated(2));
        assertEquals(2, data.generatedRouteSegment());
    }
}
