package dev.ywsabc.lasttrain.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RouteProgressPolicyPacingTest {
    @Test
    void defaultExpectedMileageLineIsDeterministic() {
        int first = RouteProgressPolicy.expectedRouteSegment(37);
        int second = RouteProgressPolicy.expectedRouteSegment(37);

        assertEquals(first, second);
        assertEquals(36, first);
        assertEquals(
                first,
                RouteProgressPolicy.DEFAULT_MILEAGE_LINE.expectedRouteSegment(37));
    }

    @Test
    void configuredFractionalSlopeProducesStableFlooredMileage() {
        RouteProgressPolicy.MileageLine line =
                new RouteProgressPolicy.MileageLine(1, 0, 3, 2);

        assertEquals(0, line.expectedRouteSegment(1));
        assertEquals(6, line.expectedRouteSegment(5));
        assertEquals(15, line.expectedRouteSegment(11));
        assertEquals(
                line.expectedRouteSegment(11),
                RouteProgressPolicy.expectedRouteSegment(11, line));
    }

    @Test
    void paceAssessmentDistinguishesBehindAndClearlyAhead() {
        int day = 30;
        int expected = RouteProgressPolicy.expectedRouteSegment(day);

        assertEquals(
                RouteProgressPolicy.Pace.BEHIND,
                RouteProgressPolicy.assess(day, expected - 1).pace());
        assertEquals(
                RouteProgressPolicy.Pace.ON_TRACK,
                RouteProgressPolicy.assess(day, expected).pace());
        assertEquals(
                RouteProgressPolicy.Pace.AHEAD,
                RouteProgressPolicy.assess(day, expected + 5).pace());
        assertTrue(RouteProgressPolicy.assess(day, expected + 5).delta() > 0);
    }
}
