package dev.ywsabc.lasttrain.route;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;

/** 最终避难枢纽的确定性几何清单，不读取世界也不替换已探索区块。 */
public record FinaleHubLayout(
        BlockPos platformCenter,
        List<BlockPos> platformFloor,
        List<BlockPos> perimeterPosts,
        BlockPos radioMast,
        BlockPos powerControl,
        BlockPos defenseControl) {
    public static final int PLATFORM_HALF_LENGTH = 12;
    public static final int PLATFORM_HALF_WIDTH = 8;

    public FinaleHubLayout {
        Objects.requireNonNull(platformCenter, "platformCenter");
        platformFloor = List.copyOf(Objects.requireNonNull(platformFloor, "platformFloor"));
        perimeterPosts = List.copyOf(Objects.requireNonNull(perimeterPosts, "perimeterPosts"));
        Objects.requireNonNull(radioMast, "radioMast");
        Objects.requireNonNull(powerControl, "powerControl");
        Objects.requireNonNull(defenseControl, "defenseControl");
    }

    public static FinaleHubLayout compute(BlockPos starterStationAnchor, int segment) {
        Objects.requireNonNull(starterStationAnchor, "starterStationAnchor");
        if (segment < 1) {
            throw new IllegalArgumentException("终局枢纽必须位于前方有效区段");
        }
        BlockPos center = starterStationAnchor.offset(
                RouteGeometry.missionCenterOffset(segment),
                0,
                0);
        List<BlockPos> floor = new ArrayList<>();
        for (int x = -PLATFORM_HALF_LENGTH; x <= PLATFORM_HALF_LENGTH; x++) {
            for (int z = -PLATFORM_HALF_WIDTH; z <= PLATFORM_HALF_WIDTH; z++) {
                if (Math.abs(z) > 1) {
                    floor.add(center.offset(x, 0, z));
                }
            }
        }
        List<BlockPos> posts = new ArrayList<>();
        for (int x : new int[]{-PLATFORM_HALF_LENGTH, PLATFORM_HALF_LENGTH}) {
            for (int z : new int[]{-PLATFORM_HALF_WIDTH, PLATFORM_HALF_WIDTH}) {
                posts.add(center.offset(x, 1, z));
            }
        }
        return new FinaleHubLayout(
                center,
                floor,
                posts,
                center.offset(0, 1, PLATFORM_HALF_WIDTH - 1),
                center.offset(-6, 1, PLATFORM_HALF_WIDTH - 2),
                center.offset(6, 1, PLATFORM_HALF_WIDTH - 2));
    }
}
