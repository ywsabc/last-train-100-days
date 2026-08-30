package dev.ywsabc.lasttrain.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SableTrainTrackerCoordinateTest {
    @Test
    void plotAbsoluteCoordinatesAreConvertedForTheEmbeddedAccessor() {
        BlockPos center = new BlockPos(-1_024, 0, 2_048);
        BlockPos plotFeet = new BlockPos(-1_027, -4, 2_050);

        assertEquals(
                new BlockPos(-3, -4, 2),
                SableTrainTracker.embeddedPlotPosition(center, plotFeet));
    }

    @Test
    void negativeIntegralYRoundoffStaysInTheIntendedFeetBlock() {
        assertEquals(
                new BlockPos(-3, -4, -8),
                SableTrainTracker.gatheringFeetBlock(
                        new Vec3(-2.5D, -4.00000005D, -7.5D)));
        assertEquals(
                new BlockPos(-3, -5, -8),
                SableTrainTracker.gatheringFeetBlock(
                        new Vec3(-2.5D, -4.0002D, -7.5D)));
        assertEquals(
                new BlockPos(2, 12, 7),
                SableTrainTracker.gatheringFeetBlock(
                        new Vec3(2.5D, 11.99999995D, 7.5D)));
    }

    @Test
    void safeSlotRequiresOakFloorAndBothHeadroomBlocks() {
        assertTrue(SableTrainTracker.safeGatheringStates(true, true, true));
        assertFalse(SableTrainTracker.safeGatheringStates(false, true, true));
        assertFalse(SableTrainTracker.safeGatheringStates(true, false, true));
        assertFalse(SableTrainTracker.safeGatheringStates(true, true, false));
    }

    @Test
    void localToGlobalTransformDelegatesTheSamePlotCoordinateSemantics() {
        FakeSubLevel subLevel = new FakeSubLevel(
                new FakePose(new Vec3(120.0D, 8.0D, -40.0D)));
        Vec3 plotPosition = new Vec3(-12.5D, 4.0D, 6.5D);

        assertEquals(
                new Vec3(107.5D, 12.0D, -33.5D),
                SableTrainTracker.transformPosition(subLevel, plotPosition, false).orElseThrow());
        assertEquals(
                plotPosition,
                SableTrainTracker.transformPosition(
                        subLevel,
                        new Vec3(107.5D, 12.0D, -33.5D),
                        true).orElseThrow());
    }

    public static final class FakeSubLevel {
        private final FakePose pose;

        FakeSubLevel(FakePose pose) {
            this.pose = pose;
        }

        public FakePose logicalPose() {
            return pose;
        }
    }

    public static final class FakePose {
        private final Vec3 translation;

        FakePose(Vec3 translation) {
            this.translation = translation;
        }

        public Vec3 transformPosition(Vec3 position) {
            return position.add(translation);
        }

        public Vec3 transformPositionInverse(Vec3 position) {
            return position.subtract(translation);
        }
    }
}
