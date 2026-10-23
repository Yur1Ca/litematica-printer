//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.restrictions.UsageRestriction;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ExcavateListMode;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import me.aleksilassila.litematica.printer.handler.handlers.MineHandler;
import me.aleksilassila.litematica.printer.integration.tweakeroo.TweakerooAdapter;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityFluidInteraction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.phys.Vec3;

/** Real candidate qualification, analysis and sorting over a real vanilla section palette. */
final class MineCandidateRefreshFixture implements AutoCloseable {
    private static final String HANDLERS = "me.aleksilassila.litematica.printer.handler.handlers.";
    private static final Class<?> ANALYZER = type("MineBreakExecutor");
    private static final Class<?> TARGET = type("MineBreakExecutor$Target");
    private static final Class<?> QUEUE = type("MineCandidateQueue");
    private static final Method ANALYZE = method(ANALYZER, "analyze", BlockPos.class);
    private static final Method BEGIN_TICK = method(ANALYZER, "beginTick");
    private static final Method ADD = method(QUEUE, "add", TARGET);
    private static final Method CLEAR = method(QUEUE, "clear");
    private static final Method REFRESH = method(MineHandler.class, "refreshCandidateTargets");
    private static final Method POS = method(TARGET, "pos");
    private static final Method STATE = method(TARGET, "state");
    private static final Method CURRENT = method(TARGET, "currentProgress");
    private static final Method PROGRESS = method(TARGET, "progress");
    private static final Method TOOL = method(TARGET, "bestToolItem");

    private final Object oldConfigClient = MiningInteractionRegressionTest.get(null, ConfigUtils.class, "client");
    private final Object oldPlayerClient = MiningInteractionRegressionTest.get(null, PlayerUtils.class, "client");
    final MiningInteractionRegressionTest.Fixture mining = new MiningInteractionRegressionTest.Fixture(1.0F, false);
    final CountingLevel level = MiningInteractionRegressionTest.alloc(CountingLevel.class);
    private final boolean oldCheckRange = Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.getBooleanValue();
    private final boolean oldHardness = Configs.Break.BREAK_CHECK_HARDNESS.getBooleanValue();
    private final int oldRange = Configs.Core.WORK_RANGE.getIntegerValue();
    private final IConfigOptionListEntry oldShape = Configs.Core.ITERATOR_SHAPE.getOptionListValue();
    private final IConfigOptionListEntry oldBreakLimiter = Configs.Break.BREAK_LIMITER.getOptionListValue();
    private final IConfigOptionListEntry oldBreakLimit = Configs.Break.BREAK_LIMIT.getOptionListValue();
    private final IConfigOptionListEntry oldMineLimiter = Configs.Mine.EXCAVATE_LIMITER.getOptionListValue();
    private final IConfigOptionListEntry oldMineLimit = Configs.Mine.EXCAVATE_LIMIT.getOptionListValue();
    private final List<String> oldMineBlacklist = List.copyOf(Configs.Mine.EXCAVATE_BLACKLIST.getStrings());
    private final List<TagKey<Block>> oldStoneTags = stoneHolder().tags().toList();
    private final Object analyzer;
    private final Object queue;
    final Map<BlockPos, BlockState> observedStates = new HashMap<>();

    MineCandidateRefreshFixture(boolean creative) throws Exception {
        MiningInteractionRegressionTest.set(null, ConfigUtils.class, "client", mining.client);
        MiningInteractionRegressionTest.set(null, PlayerUtils.class, "client", mining.client);
        Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.setBooleanValue(false);
        Configs.Core.WORK_RANGE.setIntegerValue(64);
        Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.SPHERE);
        Configs.Break.BREAK_CHECK_HARDNESS.setBooleanValue(true);
        Configs.Break.BREAK_AUTO_TOOL.setBooleanValue(true);
        Configs.Break.BREAK_LIMITER.setOptionListValue(ExcavateListMode.CUSTOM);
        Configs.Break.BREAK_LIMIT.setOptionListValue(UsageRestriction.ListType.NONE);
        Configs.Mine.EXCAVATE_LIMITER.setOptionListValue(ExcavateListMode.CUSTOM);
        Configs.Mine.EXCAVATE_LIMIT.setOptionListValue(UsageRestriction.ListType.NONE);
        bindStoneTags(List.of(BlockTags.MINEABLE_WITH_PICKAXE));
        level.section = new LevelChunkSection(new PalettedContainer<>(Blocks.AIR.defaultBlockState(),
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)), null);
        level.border = mining.level.border;
        level.player = mining.player;
        mining.client.level = level;
        mining.player.abilities.instabuild = creative;
        MiningInteractionRegressionTest.set(mining.mineHandler, FeatureModuleBase.class, "level", level);
        MiningInteractionRegressionTest.set(mining.player, LivingEntity.class, "activeEffects", new HashMap<>());
        MiningInteractionRegressionTest.set(mining.player, LivingEntity.class, "attributes",
                new AttributeMap(Player.createAttributes().build()));
        MiningInteractionRegressionTest.set(mining.player, Entity.class, "fluidInteraction",
                new EntityFluidInteraction(Set.of(FluidTags.WATER, FluidTags.LAVA)));
        MiningInteractionRegressionTest.set(mining.player, Entity.class, "onGround", true);
        setSlot(0, new ItemStack(Items.WOODEN_PICKAXE));
        setSlot(1, new ItemStack(Items.IRON_PICKAXE));
        selectSlot(0);
        var tweakeroo = new TweakerooAdapter();
        analyzer = construct(ANALYZER, new Class<?>[]{Minecraft.class, TweakerooAdapter.class}, mining.client, tweakeroo);
        queue = construct(QUEUE, new Class<?>[0]);
        Object session = construct(type("MineToolSession"), new Class<?>[]{TweakerooAdapter.class}, tweakeroo);
        MiningInteractionRegressionTest.set(mining.mineHandler, MineHandler.class, "analyzer", analyzer);
        MiningInteractionRegressionTest.set(mining.mineHandler, MineHandler.class, "tweakeroo", tweakeroo);
        MiningInteractionRegressionTest.set(mining.mineHandler, MineHandler.class, "toolSession", session);
        MiningInteractionRegressionTest.set(mining.mineHandler, MineHandler.class, "candidates", queue);
        MiningInteractionRegressionTest.set(mining.mineHandler, MineHandler.class, "candidateStates", observedStates);
    }

    static final class CountingLevel extends MiningInteractionRegressionTest.Level {
        LevelChunkSection section;
        long reads;
        @Override public BlockState getBlockState(BlockPos pos) {
            reads++;
            return section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        }
    }

    void state(BlockPos pos, BlockState state) {
        level.section.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state);
    }

    void setSlot(int slot, ItemStack stack) {
        InventoryUtils.getMainStacks(mining.player.getInventory()).set(slot, stack);
    }

    void selectSlot(int slot) {
        InventoryUtils.setSelectedSlot(mining.player.getInventory(), slot);
        mining.player.held = InventoryUtils.getMainStacks(mining.player.getInventory()).get(slot);
    }

    void movePlayer(Vec3 position) throws Exception {
        MiningInteractionRegressionTest.set(mining.player, Entity.class, "position", position);
    }

    void beginTick() throws Exception { BEGIN_TICK.invoke(analyzer); }

    void seed(int count, boolean mixed) throws Exception {
        CLEAR.invoke(queue);
        beginTick();
        for (int i = 0; i < count; i++) {
            BlockPos pos = position(i);
            state(pos, (mixed && i % 3 == 0 ? Blocks.DIRT : Blocks.STONE).defaultBlockState());
            enqueue(pos);
        }
        level.reads = 0;
    }

    void enqueue(BlockPos pos) throws Exception { ADD.invoke(queue, ANALYZE.invoke(analyzer, pos)); }

    @SuppressWarnings("unchecked")
    List<?> refresh() throws Exception { return (List<?>) REFRESH.invoke(mining.mineHandler); }

    List<TargetView> views(List<?> targets) throws Exception {
        var result = new ArrayList<TargetView>(targets.size());
        for (Object target : targets) {
            result.add(new TargetView((BlockPos) POS.invoke(target), (BlockState) STATE.invoke(target),
                    (float) CURRENT.invoke(target), (float) PROGRESS.invoke(target), (Item) TOOL.invoke(target)));
        }
        return result;
    }

    static BlockPos position(int index) { return new BlockPos(index & 15, index >>> 8, (index >>> 4) & 15); }
    record TargetView(BlockPos pos, BlockState state, float currentProgress, float progress, Item tool) {}

    @Override public void close() throws Exception {
        MiningInteractionRegressionTest.set(null, ConfigUtils.class, "client", oldConfigClient);
        MiningInteractionRegressionTest.set(null, PlayerUtils.class, "client", oldPlayerClient);
        Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.setBooleanValue(oldCheckRange);
        Configs.Core.WORK_RANGE.setIntegerValue(oldRange);
        Configs.Core.ITERATOR_SHAPE.setOptionListValue(oldShape);
        Configs.Break.BREAK_CHECK_HARDNESS.setBooleanValue(oldHardness);
        Configs.Break.BREAK_LIMITER.setOptionListValue(oldBreakLimiter);
        Configs.Break.BREAK_LIMIT.setOptionListValue(oldBreakLimit);
        Configs.Mine.EXCAVATE_LIMITER.setOptionListValue(oldMineLimiter);
        Configs.Mine.EXCAVATE_LIMIT.setOptionListValue(oldMineLimit);
        Configs.Mine.EXCAVATE_BLACKLIST.setStrings(oldMineBlacklist);
        bindStoneTags(oldStoneTags);
        mining.close();
    }

    private static void bindStoneTags(Collection<TagKey<Block>> tags) throws Exception {
        method(Holder.Reference.class, "bindTags", Collection.class).invoke(stoneHolder(), tags);
    }

    private static Holder.Reference<Block> stoneHolder() {
        return BuiltInRegistries.BLOCK.get(BuiltInRegistries.BLOCK.getId(Blocks.STONE)).orElseThrow();
    }

    private static Class<?> type(String name) {
        try { return Class.forName(HANDLERS + name); }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        try {
            Method method = type.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }

    private static Object construct(Class<?> type, Class<?>[] parameters, Object... values) throws Exception {
        Constructor<?> constructor = type.getDeclaredConstructor(parameters);
        constructor.setAccessible(true);
        return constructor.newInstance(values);
    }
}
//#endif
