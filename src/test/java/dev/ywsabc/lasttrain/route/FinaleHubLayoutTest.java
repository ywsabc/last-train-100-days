package dev.ywsabc.lasttrain.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class FinaleHubLayoutTest {
    @Test
    void finalHubIsALargeDedicatedPlatformWithThreeSeparatedControls() {
        FinaleHubLayout hub = FinaleHubLayout.compute(new BlockPos(10, 64, -20), 7);

        assertTrue(hub.platformFloor().size() > 300);
        assertEquals(4, hub.perimeterPosts().size());
        assertFalse(hub.radioMast().equals(hub.powerControl()));
        assertFalse(hub.radioMast().equals(hub.defenseControl()));
        assertFalse(hub.powerControl().equals(hub.defenseControl()));
        assertEquals(
                10 + RouteGeometry.missionCenterOffset(7),
                hub.platformCenter().getX());
    }
}
