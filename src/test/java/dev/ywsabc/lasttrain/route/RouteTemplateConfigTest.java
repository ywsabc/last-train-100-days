package dev.ywsabc.lasttrain.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RouteTemplateConfigTest {
    @Test
    void defaultConfigMixesAllFourTemplates() {
        RouteTemplateConfig config = RouteTemplateConfig.DEFAULT;
        assertTrue(config.straightWeight() > 0);
        assertTrue(config.stationWeight() > 0);
        assertTrue(config.cityBypassWeight() > 0);
        assertTrue(config.bridgeTunnelWeight() > 0);
    }

    @Test
    void negativeWeightsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withWeights(-1, 20, 20, 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withWeights(50, -1, 20, 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withWeights(50, 20, -1, 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withWeights(50, 20, 20, -1));
    }

    @Test
    void poiAnchorRangeMustFitInsideTheSegment() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withPoiAnchorRange(0, 48));
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withPoiAnchorRange(
                        16,
                        RouteGeometry.SEGMENT_LENGTH));
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withPoiAnchorRange(40, 16));
    }

    @Test
    void bridgeBudgetRequiresPositiveWindowAndCapInsideWindow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withBridgeTunnelBudget(1, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withBridgeTunnelBudget(13, 12));
    }

    @Test
    void poiSlotCeilingIsEnforced() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withMaxPoiSlots(
                        RouteTemplateConfig.MAX_POI_SLOTS + 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withMaxPoiSlots(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withMaxPoiSlots(-1));
    }

    @Test
    void negativeMinimumGapsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withCityBypassMinGap(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteTemplateConfig.DEFAULT.withStationMinGap(-1));
    }

    @Test
    void seedSaltIsDeterministicAndReactsToEveryConfigField() {
        RouteTemplateConfig config = RouteTemplateConfig.DEFAULT;
        assertEquals(config.seedSalt(), RouteTemplateConfig.DEFAULT.seedSalt());
        assertNotEquals(config.seedSalt(), config.withWeights(51, 20, 20, 10).seedSalt());
        assertNotEquals(config.seedSalt(), config.withWeights(50, 21, 20, 10).seedSalt());
        assertNotEquals(config.seedSalt(), config.withWeights(50, 20, 21, 10).seedSalt());
        assertNotEquals(config.seedSalt(), config.withWeights(50, 20, 20, 11).seedSalt());
        assertNotEquals(config.seedSalt(), config.withCityBypassMinGap(2).seedSalt());
        assertNotEquals(config.seedSalt(), config.withStationMinGap(3).seedSalt());
        assertNotEquals(config.seedSalt(), config.withBridgeTunnelBudget(2, 12).seedSalt());
        assertNotEquals(config.seedSalt(), config.withBridgeTunnelBudget(1, 13).seedSalt());
        assertNotEquals(config.seedSalt(), config.withMaxPoiSlots(2).seedSalt());
        assertNotEquals(config.seedSalt(), config.withPoiAnchorRange(17, 48).seedSalt());
        assertNotEquals(config.seedSalt(), config.withPoiAnchorRange(16, 47).seedSalt());
    }

    @Test
    void legacySingleLongFoldCollisionPairGetsDistinctSeedSalts() {
        // The legacy fingerprint folded all eleven fields into one long with
        // an XOR + odd-multiply step. Every step is invertible, so the old
        // "different configs never share a fingerprint" claim cannot hold:
        // 2^93+ configs cannot inject into 2^64 longs. These two configs
        // differ only in straightWeight and stationWeight and share the
        // legacy fold result -519009049888367159 (0xf8cc1c04da30ddc9), which
        // made a config swap silently keep the old route. The SHA-256 salt
        // keeps the full field sequence, so the pair now derives distinct
        // seed inputs.
        RouteTemplateConfig configA = RouteTemplateConfig.DEFAULT.withWeights(2135587861, 0, 20, 10);
        RouteTemplateConfig configB =
                RouteTemplateConfig.DEFAULT.withWeights(1321049485, 1682898648, 20, 10);
        assertEquals(-519009049888367159L, legacyFold(configA));
        assertEquals(-519009049888367159L, legacyFold(configB));
        assertNotEquals(configA.seedSalt(), configB.seedSalt());
    }

    /** The removed XOR + odd-multiply fold, kept only to pin the collision class. */
    private static long legacyFold(RouteTemplateConfig config) {
        long fold = 0xBF58476D1CE4E5B9L;
        long mixed = 0x9E3779B97F4A7C15L;
        mixed = (mixed ^ config.straightWeight()) * fold;
        mixed = (mixed ^ config.stationWeight()) * fold;
        mixed = (mixed ^ config.cityBypassWeight()) * fold;
        mixed = (mixed ^ config.bridgeTunnelWeight()) * fold;
        mixed = (mixed ^ config.cityBypassMinGap()) * fold;
        mixed = (mixed ^ config.stationMinGap()) * fold;
        mixed = (mixed ^ config.bridgeTunnelMaxPerWindow()) * fold;
        mixed = (mixed ^ config.bridgeTunnelWindow()) * fold;
        mixed = (mixed ^ config.maxPoiSlots()) * fold;
        mixed = (mixed ^ config.poiAnchorMin()) * fold;
        mixed = (mixed ^ config.poiAnchorMax()) * fold;
        return mixed;
    }
}
