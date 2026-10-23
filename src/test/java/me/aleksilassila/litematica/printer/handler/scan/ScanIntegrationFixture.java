//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.scan;

import fi.dy.masa.litematica.world.ChunkSchematic;
import fi.dy.masa.litematica.world.WorldSchematic;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

/** Headless level/player shells around the real index, snapshots, sessions and candidate source. */
final class ScanIntegrationFixture implements AutoCloseable {
    final ConcurrentHashMap<Long, ChunkSchematic> chunks = new ConcurrentHashMap<>();
    final ScanCache cache = new ScanCache();
    final TestPlayer player;
    final List<PrinterBox> boxes = List.of(new PrinterBox(0, 0, 0, 15, 3, 15));
    WorldSchematic schematic;
    TestLevel level;
    RuntimeEpoch epoch = RuntimeEpoch.INITIAL;
    private final boolean previousExtra = Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue();
    private final boolean previousRange = Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.getBooleanValue();
    private final Object previousShape = Configs.Core.ITERATOR_SHAPE.getOptionListValue();
    private final int previousThroughput = Configs.Placement.PLACE_BLOCKS_PER_TICK.getIntegerValue();

    ScanIntegrationFixture(int count) throws Exception {
        Configs.Print.BREAK_EXTRA_BLOCK.setBooleanValue(false);
        Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.setBooleanValue(false);
        Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.CUBE);
        Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(1);
        this.chunks.put(0L, newChunk(count));
        this.schematic = SchematicIndexFixture.world(this.chunks);
        this.level = newLevel();
        this.player = allocate(TestPlayer.class);
        set(this.player, "position", Vec3.ZERO);
        // Make admission deterministic; this fixture measures correctness, never wall-clock speed.
        Field budget = ScanCache.class.getDeclaredField("budget");
        budget.setAccessible(true);
        set(budget.get(this.cache), "nanoClock", (LongSupplier) () -> 0L);
    }

    TestChunk newChunk(int count) throws Exception {
        LevelChunkSection[] sections = SchematicIndexFixture.chunk(0, count).getSections();
        return SchematicIndexFixture.chunk(TestChunk.class, new ChunkPos(0, 0), sections);
    }

    void replaceTarget(BlockState state) throws Exception {
        TestChunk replacement = newChunk(1);
        replacement.getSections()[4].setBlockState(0, 0, 0, state);
        this.chunks.put(0L, replacement);
    }

    void tick(long tick) {
        this.level.tick = tick;
        this.cache.beginTick(this.level, this.schematic, tick, this.epoch);
    }

    ScanEngine engine() {
        return new ScanEngine(() -> this.epoch, () -> this.level, this.cache);
    }

    Iterable<BlockPos> scan(String owner, List<PrinterBox> sourceBoxes, int guard, ScanIntent intent) {
        return this.cache.iterable(owner, sourceBoxes, this.level, this.schematic, null, guard, intent, pos -> true);
    }

    List<BlockPos> print(String owner) {
        return drain(scan(owner, this.boxes, 0, ScanIntent.PRINT));
    }

    static List<BlockPos> positions(int count) {
        List<BlockPos> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(new BlockPos(i & 15, i >>> 8, (i >>> 4) & 15));
        return result;
    }

    static List<BlockPos> drain(Iterable<BlockPos> source) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : source) {
            result.add(pos);
            if (result.size() > 4096) throw new AssertionError("Candidate source did not stop");
        }
        return result;
    }

    static TestLevel newLevel() throws Exception {
        TestLevel level = allocate(TestLevel.class);
        level.world = new HashMap<>();
        level.reads = new HashMap<>();
        level.defaultState = Blocks.AIR.defaultBlockState();
        return level;
    }

    @Override public void close() {
        this.cache.clear();
        Configs.Print.BREAK_EXTRA_BLOCK.setBooleanValue(this.previousExtra);
        Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.setBooleanValue(this.previousRange);
        Configs.Core.ITERATOR_SHAPE.setOptionListValue((RadiusShapeType) this.previousShape);
        Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(this.previousThroughput);
    }

    static final class TestLevel extends ClientLevel {
        long tick;
        BlockState defaultState;
        Map<BlockPos, BlockState> world;
        Map<BlockPos, Integer> reads;
        private TestLevel() { super(null, null, null, null, 0, 0, null, false, 0L, 0); }
        @Override public long getGameTime() { return this.tick; }
        @Override public boolean hasChunk(int x, int z) { return x == 0 && z == 0; }
        @Override public BlockState getBlockState(BlockPos pos) {
            this.reads.merge(pos.immutable(), 1, Integer::sum);
            return this.world.getOrDefault(pos, this.defaultState);
        }
        int readsAt(BlockPos pos) { return this.reads.getOrDefault(pos, 0); }
    }

    static final class TestPlayer extends LocalPlayer {
        private TestPlayer() { super(null, null, null, null, null, null, false, null); }
        @Override public ItemStack getMainHandItem() { return ItemStack.EMPTY; }
        @Override public Vec3 getLookAngle() { return new Vec3(0, 0, 1); }
    }

    static final class TestChunk extends ChunkSchematic {
        private TestChunk() { super(null, null); }
        @Override public BlockState getBlockState(BlockPos pos) {
            int section = (pos.getY() + 64) >> 4;
            LevelChunkSection[] sections = getSections();
            return section < 0 || section >= sections.length || sections[section] == null
                    ? Blocks.AIR.defaultBlockState()
                    : sections[section].getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // Minecraft keeps entity position and chunk fields on a superclass.
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }
}
//#endif
