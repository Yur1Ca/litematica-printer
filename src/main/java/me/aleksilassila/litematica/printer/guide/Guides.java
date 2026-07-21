package me.aleksilassila.litematica.printer.guide;

<<<<<<< HEAD
import me.aleksilassila.litematica.printer.Reference;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.guides.*;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Guides {
    public static final Guides INSTANCE = new Guides();
    private final List<GuideRegistration> registrations = new ArrayList<>();

    private Guides() {
        // ============================================================
        // 水源/含水方块兜底规则（跨 tick 破冰放水流程由 PrintTaskController 处理）
        // ============================================================
        register(WaterGuide.class);

        // ============================================================
        // 跳过指南（对无需放置的方块直接跳过，优先级低于 WaterGuide）
        // ============================================================
        register(SkipGuide.class,
                LiquidBlock.class,
                BubbleColumnBlock.class,
                LilyPadBlock.class
        );

        // 火把
        register(TorchGuide.class,
                //#if MC > 12002
                BaseTorchBlock.class
                //#else
                //$$ TorchBlock.class
                //#endif
        );

        // 紫水晶芽
        register(AmethystGuide.class, AmethystClusterBlock.class);

        // 花
        register(FlowerGuide.class, FlowerBlock.class);

        // 台阶
        register(SlabGuide.class, SlabBlock.class);

        // 楼梯
        register(StairGuide.class, StairBlock.class);

        // 活板门
        register(TrapDoorGuide.class, TrapDoorBlock.class);

        // 门
        register(DoorGuide.class, DoorBlock.class);

        // 栅栏门
        register(FenceGateGuide.class, FenceGateBlock.class);

        // 床
        register(BedGuide.class, BedBlock.class);

        // 钟
        register(BellGuide.class, BellBlock.class);

        // 侦测器
        register(ObserverGuide.class, ObserverBlock.class);

        // 活塞
        register(PistonGuide.class, PistonBaseBlock.class);

        // 箱子
        register(ChestGuide.class, ChestBlock.class, TrappedChestBlock.class);

        // 告示牌
        register(SignGuide.class,
                StandingSignBlock.class,
                WallSignBlock.class
                //#if MC >= 12002
                , WallHangingSignBlock.class
                , CeilingHangingSignBlock.class
                //#endif
        );

        // 旗帜
        register(BannerGuide.class, AbstractBannerBlock.class);

        // 头颅
        register(SkullGuide.class, SkullBlock.class, WallSkullBlock.class);

        // 下界传送门
        register(NetherPortalGuide.class, NetherPortalBlock.class);

        // 梯子
        register(LadderGuide.class, LadderBlock.class);

        // 灯笼
        register(LanternGuide.class, LanternBlock.class);

        // 末地烛/避雷针
        register(RodGuide.class, RodBlock.class);

        // 漏斗
        register(HopperGuide.class, HopperBlock.class);

        // 铁砧
        register(AnvilGuide.class, AnvilBlock.class);

        // 去皮原木
        register(StripLogGuide.class, RotatedPillarBlock.class);

        // 可可豆
        register(CocoaGuide.class, CocoaBlock.class);

        // 绊线钩
        register(TripWireHookGuide.class, TripWireHookBlock.class);

        // 铁轨
        register(RailGuide.class, BaseRailBlock.class);

        // 合成器（MC 1.21+）
        //#if MC >= 12003
        register(CrafterGuide.class, CrafterBlock.class);
        //#endif

        // ============================================================
        // 交互指南（WRONG_STATE 处理为主）
        // ============================================================

        // 蜡烛（添加/点燃/熄灭）
        register(CandleGuide.class, CandleBlock.class);

        // 海泡菜
        register(SeaPickleGuide.class, SeaPickleBlock.class);

        // 海龟蛋（叠加放置）
        register(TurtleEggGuide.class, TurtleEggBlock.class);

        // 红石中继器（延迟调整）
        register(RepeaterGuide.class, RepeaterBlock.class);

        // 红石比较器（模式切换）
        register(ComparatorGuide.class, net.minecraft.world.level.block.ComparatorBlock.class);

        // 红石线（点状/十字形）
        register(RedstoneWireGuide.class, RedStoneWireBlock.class);

        // 拉杆
        register(LeverGuide.class, LeverBlock.class);

        // 篝火
        register(CampfireGuide.class, CampfireBlock.class);

        // 农作物（骨粉催熟）
        register(CropsGuide.class,
                AttachedStemBlock.class, StemBlock.class, CropBlock.class, BeetrootBlock.class);

        // 音符盒（调音）
        register(NoteBlockGuide.class, NoteBlock.class);

        // 雪层（叠加）
        register(SnowGuide.class, SnowLayerBlock.class);

        // 末地传送门框架（嵌入末影之眼）
        register(EndPortalFrameGuide.class, EndPortalFrameBlock.class);

        // 阳光探测器（反转切换）
        register(DaylightDetectorGuide.class, DaylightDetectorBlock.class);

        // 花簇（MC 1.19.4+）
        //#if MC >= 11904
        register(FlowerBedGuide.class,
                //#if MC >= 12105
                FlowerBedBlock.class
                //#else
                //$$ PinkPetalsBlock.class
                //#endif
        );
        //#endif

        // 藤蔓/发光地衣
        register(VineGuide.class, VineBlock.class, GlowLichenBlock.class);

        // 火/灵魂火
        register(FireGuide.class, FireBlock.class, SoulFireBlock.class);

        // 炼药锅
        register(CauldronGuide.class,
                CauldronBlock.class, LavaCauldronBlock.class, LayeredCauldronBlock.class);

        // 堆肥桶
        register(ComposterGuide.class, ComposterBlock.class);

        // ============================================================
        // 混合指南（放置 + 交互 + 破坏）
        // ============================================================

        // 耕地/土径
        register(SoilGuide.class, FarmlandBlock.class, DirtPathBlock.class);

        // 花盆
        register(FlowerPotGuide.class, FlowerPotBlock.class);

        // 攀爬植物（洞穴藤蔓/垂泪藤/缠怨藤/大垂叶茎）
        register(ClimbingPlantGuide.class,
                BigDripleafStemBlock.class,
                CaveVinesBlock.class, CaveVinesPlantBlock.class,
                WeepingVinesBlock.class, WeepingVinesPlantBlock.class,
                TwistingVinesBlock.class, TwistingVinesPlantBlock.class);

        // 死珊瑚（需过滤非珊瑚方块）
        register(CoralGuide.class);

        // ============================================================
        // 默认指南（最低优先级，兜底所有未被上面接管的方块）
        // ============================================================
        register(DefaultGuide.class);
    }

    @SafeVarargs
    public final void register(Class<? extends Guide> guideClass, Class<? extends Block>... supportedBlocks) {
        registrations.add(new GuideRegistration(guideClass, supportedBlocks));
    }

<<<<<<< HEAD
    public final Optional<Action> buildAction(SchematicBlockContext context) {
        BlockMatchResult blockMatchResult = BlockMatchResult.compare(context);
        for (GuideRegistration registration : this.registrations) {
            if (!registration.matches(context.requiredState.getBlock())) {
                continue;
            }
            Guide guide;
            try {
                guide = registration.create(context);
            } catch (ReflectiveOperationException exception) {
                Reference.LOGGER.error("Failed to create printer guide {}", registration.guideName(), exception);
                continue;
            }
            if (!guide.canExecute()) {
                continue;
            }
=======
    @SuppressWarnings("CallToPrintStackTrace")
    private List<Guide> getGuides(SchematicBlockContext context) {
        List<Guide> guides = new ArrayList<>();
        for (GuideRegistration reg : registrations) {
            boolean matches = reg.blockClass.length == 0;
            if (!matches) {
                for (Class<? extends Block> clazz : reg.blockClass) {
                    if (clazz.isInstance(context.requiredState.getBlock())) {
                        matches = true;
                        break;
                    }
                }
            }
            if (matches) {
                try {
                    Guide guide = reg.create(context);
                    if (guide.canExecute()) {
                        guides.add(guide);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return guides;
    }

    public final Optional<Action> buildAction(SchematicBlockContext context) {
        BlockMatchResult blockMatchResult = BlockMatchResult.compare(context);
        List<Guide> guides = this.getGuides(context);
        for (Guide guide : guides) {
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
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
        private final Constructor<? extends Guide> constructor;
        public final Class<? extends Block>[] blockClass;

        public GuideRegistration(Class<? extends Guide> guideClass, Class<? extends Block>[] blockClass) {
            try {
                this.constructor = guideClass.getConstructor(SchematicBlockContext.class);
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException("Guide must expose a SchematicBlockContext constructor: "
                        + guideClass.getName(), e);
            }
            this.blockClass = blockClass;
        }

        public Guide create(SchematicBlockContext context) throws ReflectiveOperationException {
            return this.constructor.newInstance(context);
        }
<<<<<<< HEAD

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

        public String guideName() {
            return this.constructor.getDeclaringClass().getName();
        }
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
    }
}
