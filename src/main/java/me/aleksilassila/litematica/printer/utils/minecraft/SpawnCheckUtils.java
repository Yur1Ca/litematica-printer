package me.aleksilassila.litematica.printer.utils.minecraft;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
//#if MC > 260100
//$$ import net.minecraft.world.entity.EntityTypes;
//#endif
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.state.BlockState;

/** Spawn checks shared by features that need to cover hostile-mob spawn spaces. */
public final class SpawnCheckUtils {
    private static final EntityType<?> SUPPORT_ENTITY = resolveEntityType("minecraft:creeper");
    private static final EntityType<?> WITHER_SKELETON_ENTITY = resolveEntityType("minecraft:wither_skeleton");

    private SpawnCheckUtils() {
    }

    /**
     * Mirrors MiniHUD's light-level spawnability test for a Wither Skeleton.
     * The position is the lower of the two entity-space blocks.
     */
    public static boolean canWitherSkeletonSpawn(Level level, BlockPos spawnPos) {
        BlockPos belowPos = spawnPos.below();
        BlockState below = level.getBlockState(belowPos);
        if (!below.isValidSpawn(level, belowPos, SUPPORT_ENTITY)) {
            return false;
        }

        BlockState state = level.getBlockState(spawnPos);
        if (!isClearForSpawn(level, spawnPos, state, WITHER_SKELETON_ENTITY)) {
            return false;
        }

        BlockPos abovePos = spawnPos.above();
        BlockState above = level.getBlockState(abovePos);
        return isClearForSpawn(level, abovePos, above, WITHER_SKELETON_ENTITY);
    }

    private static EntityType<?> resolveEntityType(String id) {
        // Use each version's entity holder directly so field references are checked at compile time.
        return switch (id) {
            //#if MC > 260100
            //$$ case "minecraft:creeper" -> EntityTypes.CREEPER;
            //$$ case "minecraft:wither_skeleton" -> EntityTypes.WITHER_SKELETON;
            //#else
            case "minecraft:creeper" -> EntityType.CREEPER;
            case "minecraft:wither_skeleton" -> EntityType.WITHER_SKELETON;
            //#endif
            default -> throw new IllegalArgumentException("Unsupported entity type " + id);
        };
    }

    private static boolean isClearForSpawn(
            Level level,
            BlockPos pos,
            BlockState state,
            EntityType<?> entityType
    ) {
        return NaturalSpawner.isValidEmptySpawnBlock(
                level,
                pos,
                state,
                state.getFluidState(),
                entityType
        );
    }
}
