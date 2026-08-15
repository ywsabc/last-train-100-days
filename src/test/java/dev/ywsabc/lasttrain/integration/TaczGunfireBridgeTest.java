package dev.ywsabc.lasttrain.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TaczGunfireBridgeTest {
    private static final TaczGunfireBridge.Settings SETTINGS =
            TaczGunfireBridge.Settings.defaults();

    @Test
    void hearingDistanceIncludesTheBoundary() {
        assertTrue(GunfireAttractionPolicy.isWithinHearingDistance(
                0.0D, SETTINGS.horizontalRadius()));
        assertTrue(GunfireAttractionPolicy.isWithinHearingDistance(
                64.0D * 64.0D, SETTINGS.horizontalRadius()));
        assertFalse(GunfireAttractionPolicy.isWithinHearingDistance(
                64.01D * 64.01D, SETTINGS.horizontalRadius()));
    }

    @Test
    void gunfireDoesNotStealACloserLiveTarget() {
        assertTrue(GunfireAttractionPolicy.shouldRetarget(false, 4096.0D, 1.0D));
        assertTrue(GunfireAttractionPolicy.shouldRetarget(true, 100.0D, 100.0D));
        assertTrue(GunfireAttractionPolicy.shouldRetarget(true, 99.0D, 100.0D));
        assertFalse(GunfireAttractionPolicy.shouldRetarget(true, 101.0D, 100.0D));
    }

    @Test
    void settingsRejectUnsafeBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TaczGunfireBridge.Settings(0.0D, 32.0D, 4, 64));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TaczGunfireBridge.Settings(64.0D, Double.NaN, 4, 64));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TaczGunfireBridge.Settings(64.0D, 32.0D, -1, 64));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TaczGunfireBridge.Settings(64.0D, 32.0D, 4, 0));
    }
}
