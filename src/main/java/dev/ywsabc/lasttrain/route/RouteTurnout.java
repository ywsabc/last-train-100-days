package dev.ywsabc.lasttrain.route;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** 已物化分支线路上可供“道岔与信号修复”任务占用的稳定现场引用。 */
public record RouteTurnout(
        int segment,
        BlockPos junction,
        Direction branchDirection) {
    public RouteTurnout {
        if (segment < 1) {
            throw new IllegalArgumentException("道岔区段必须从 1 开始");
        }
        junction = Objects.requireNonNull(junction, "junction").immutable();
        branchDirection = Objects.requireNonNull(branchDirection, "branchDirection");
        if (branchDirection.getAxis() == Direction.Axis.Y) {
            throw new IllegalArgumentException("道岔分支方向必须水平");
        }
    }
}
