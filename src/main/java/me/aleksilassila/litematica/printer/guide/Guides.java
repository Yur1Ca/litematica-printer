package me.aleksilassila.litematica.printer.guide;

import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.guides.*;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public class Guides {
    private final List<GuideRegistration> registrations = new ArrayList<>();
    private final Map<Class<?>, List<GuideRegistration>> registrationsByBlockClass = new IdentityHashMap<>();

    public Guides() {
        // ============================================================
        // 水源/含水方块兜底规则（跨 tick 破冰放水流程由 PrintWorkflowScheduler 处理）
        // ============================================================
        register(WaterGuide::new);

        // ============================================================
        // 跳过指南（对无需放置的方块直接跳过，优先级低于 WaterGuide）
        // ============================================================
        register(SkipGuide::new,
                LiquidBlock.class,
                BubbleColumnBlock.class,
                LilyPadBlock.class
        );

        // 火把
        register(TorchGuide::new,
                //#if MC > 12002
                BaseTorchBlock.class
                //#else
                //$$ TorchBlock.class
                //#endif
        );

        // 紫水晶芽
        register(AmethystGuide::new, AmethystClusterBlock.class);

        // 花
        register(FlowerGuide::new, FlowerBlock.class);

        // 台阶
        register(SlabGuide::new, SlabBlock.class);

        // 楼梯
        register(StairGuide::new, StairBlock.class);

        // 活板门
        register(TrapDoorGuide::new, TrapDoorBlock.class);

        // 门
        register(DoorGuide::new, DoorBlock.class);

        // 栅栏门
        register(FenceGateGuide::new, FenceGateBlock.class);

        // 床
        register(BedGuide::new, BedBlock.class);

        // 钟
        register(BellGuide::new, BellBlock.class);

        // 侦测器
        register(ObserverGuide::new, ObserverBlock.class);

        // 活塞
        register(PistonGuide::new, PistonBaseBlock.class);

        // 箱子
        register(ChestGuide::new, ChestBlock.class, TrappedChestBlock.class);

        // 告示牌
        register(SignGuide::new,
                StandingSignBlock.class,
                WallSignBlock.class
                //#if MC >= 12002
                , WallHangingSignBlock.class
                , CeilingHangingSignBlock.class
                //#endif
        );

        // 旗帜
        register(BannerGuide::new, AbstractBannerBlock.class);

        // 头颅
        register(SkullGuide::new, SkullBlock.class, WallSkullBlock.class);

        // 下界传送门
        register(NetherPortalGuide::new, NetherPortalBlock.class);

        // 梯子
        register(LadderGuide::new, LadderBlock.class);

        // 灯笼
        register(LanternGuide::new, LanternBlock.class);

        // 末地烛/避雷针
        register(RodGuide::new, RodBlock.class);

        // 漏斗
        register(HopperGuide::new, HopperBlock.class);

        // 铁砧
        register(AnvilGuide::new, AnvilBlock.class);

        // 去皮原木
        register(StripLogGuide::new, RotatedPillarBlock.class);

        // 可可豆
        register(CocoaGuide::new, CocoaBlock.class);

        // 绊线钩
        register(TripWireHookGuide::new, TripWireHookBlock.class);

        // 铁轨
        register(RailGuide::new, BaseRailBlock.class);

        // 合成器（MC 1.21+）
        //#if MC >= 12003
        register(CrafterGuide::new, CrafterBlock.class);
        //#endif

        // ============================================================
        // 交互指南（WRONG_STATE 处理为主）
        // ============================================================

        // 蜡烛（添加/点燃/熄灭）
        register(CandleGuide::new, CandleBlock.class);

        // 海泡菜
        register(SeaPickleGuide::new, SeaPickleBlock.class);

        // 海龟蛋（叠加放置）
        register(TurtleEggGuide::new, TurtleEggBlock.class);

        // 红石中继器（延迟调整）
        register(RepeaterGuide::new, RepeaterBlock.class);

        // 红石比较器（模式切换）
        register(ComparatorGuide::new, net.minecraft.world.level.block.ComparatorBlock.class);

        // 红石线（点状/十字形）
        register(RedstoneWireGuide::new,
                //#if MC >= 260300
                //$$ RedstoneWireBlock.class
                //#else
                RedStoneWireBlock.class
                //#endif
        );

        // 拉杆
        register(LeverGuide::new, LeverBlock.class);

        // 篝火
        register(CampfireGuide::new, CampfireBlock.class);

        // 农作物（骨粉催熟）
        register(CropsGuide::new,
                AttachedStemBlock.class, StemBlock.class, CropBlock.class, BeetrootBlock.class);

        // 音符盒（调音）
        register(NoteBlockGuide::new, NoteBlock.class);

        // 雪层（叠加）
        register(SnowGuide::new, SnowLayerBlock.class);

        // 末地传送门框架（嵌入末影之眼）
        register(EndPortalFrameGuide::new, EndPortalFrameBlock.class);

        // 阳光探测器（反转切换）
        register(DaylightDetectorGuide::new, DaylightDetectorBlock.class);

        // 花簇（MC 1.19.4+）
        //#if MC >= 11904
        register(FlowerBedGuide::new,
                //#if MC >= 12105
                FlowerBedBlock.class
                //#else
                //$$ PinkPetalsBlock.class
                //#endif
        );
        //#endif

        // 藤蔓/发光地衣
        register(VineGuide::new, VineBlock.class, GlowLichenBlock.class);

        // 火/灵魂火
        register(FireGuide::new, FireBlock.class, SoulFireBlock.class);

        // 炼药锅
        register(CauldronGuide::new,
                CauldronBlock.class, LavaCauldronBlock.class, LayeredCauldronBlock.class);

        // 堆肥桶
        register(ComposterGuide::new, ComposterBlock.class);

        // ============================================================
        // 混合指南（放置 + 交互 + 破坏）
        // ============================================================

        // 耕地/土径
        register(SoilGuide::new, FarmlandBlock.class,
                //#if MC >= 260300
                //$$ PathBlock.class
                //#else
                DirtPathBlock.class
                //#endif
        );

        // 花盆
        register(FlowerPotGuide::new, FlowerPotBlock.class);

        // 攀爬植物（洞穴藤蔓/垂泪藤/缠怨藤/大垂叶茎）
        register(ClimbingPlantGuide::new,
                BigDripleafStemBlock.class,
                CaveVinesBlock.class, CaveVinesPlantBlock.class,
                WeepingVinesBlock.class, WeepingVinesPlantBlock.class,
                TwistingVinesBlock.class, TwistingVinesPlantBlock.class);

        // 死珊瑚（需过滤非珊瑚方块）
        register(CoralGuide::new);

        // ============================================================
        // 默认指南（最低优先级，兜底所有未被上面接管的方块）
        // ============================================================
        register(DefaultGuide::new);
    }

    @SafeVarargs
    public final void register(
            Function<SchematicBlockContext, ? extends Guide> factory,
            Class<? extends Block>... supportedBlocks
    ) {
        this.registrations.add(new GuideRegistration(factory, supportedBlocks));
        this.registrationsByBlockClass.clear();
    }

    public final Optional<Action> buildAction(SchematicBlockContext context) {
        BlockMatchResult blockMatchResult = BlockMatchResult.compare(context);
        Block requiredBlock = context.requiredState.getBlock();
        List<GuideRegistration> matching = this.registrationsByBlockClass.computeIfAbsent(
                requiredBlock.getClass(),
                ignored -> this.registrations.stream()
                        .filter(registration -> registration.matches(requiredBlock))
                        .toList()
        );
        for (GuideRegistration registration : matching) {
            Guide guide = registration.create(context);
            if (!guide.canExecute()) {
                continue;
            }
            Result result = guide.buildAction(blockMatchResult);
            if (result.hasAction()) {
                return result.toOptional();
            }
            if (result.skipOtherGuide()) {
                break;
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("ClassCanBeRecord")
    private static class GuideRegistration {
        private final Function<SchematicBlockContext, ? extends Guide> factory;
        public final Class<? extends Block>[] blockClass;

        public GuideRegistration(
                Function<SchematicBlockContext, ? extends Guide> factory,
                Class<? extends Block>[] blockClass
        ) {
            this.factory = factory;
            this.blockClass = blockClass;
        }

        public Guide create(SchematicBlockContext context) {
            return this.factory.apply(context);
        }

        public boolean matches(Block block) {
            if (this.blockClass.length == 0) {
                return true;
            }
            for (Class<? extends Block> clazz : this.blockClass) {
                if (clazz.isInstance(block)) {
                    return true;
                }
            }
            return false;
        }

    }
}
