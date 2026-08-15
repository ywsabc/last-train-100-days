package dev.ywsabc.lasttrain.route;

import java.util.Optional;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Canonical Create track states for the guaranteed eastbound route. */
public final class RouteTrackStates {
    private RouteTrackStates() {
    }

    public static BlockState eastbound(Block trackBlock) {
        BlockState state = trackBlock.defaultBlockState();
        state = apply(state, trackBlock, "shape", "xo");
        state = apply(state, trackBlock, "turn", "false");
        return apply(state, trackBlock, "waterlogged", "false");
    }

    private static BlockState apply(
            BlockState state,
            Block block,
            String propertyName,
            String serializedValue) {
        Property<?> property = state.getProperties().stream()
                .filter(candidate -> candidate.getName().equals(propertyName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        block + " has no block-state property " + propertyName));
        return applyProperty(state, property, serializedValue);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState applyProperty(
            BlockState state,
            Property property,
            String serializedValue) {
        Optional<? extends Comparable> parsed = property.getValue(serializedValue);
        return parsed.map(value -> state.setValue(property, value))
                .orElseThrow(() -> new IllegalArgumentException(
                        property.getName() + " rejects block-state value " + serializedValue));
    }
}
