//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.util.List;

import static me.aleksilassila.litematica.printer.handler.scan.RawPrinterBoxFixture.rawBox;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("removal")
class ThirdReviewShapeCoverageTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void aHighEyeOctahedronStillOffersItsLegalFeetBasedBottomBoundary() throws Exception {
        assertHighEyeTargetIsOffered(RadiusShapeType.OCTAHEDRON, new BlockPos(0, 59, 0));
    }

    @Test void aHighEyeSphereStillOffersItsLegalEyeBasedUpperBoundary() throws Exception {
        assertHighEyeTargetIsOffered(RadiusShapeType.SPHERE, new BlockPos(0, 72, 0));
    }

    @ParameterizedTest
    @EnumSource(value = RadiusShapeType.class, names = {"CUBE", "OCTAHEDRON"})
    void nativeReachRechecksSubBlockMovementAndAttributeChanges(RadiusShapeType shape) throws Exception {
        try (var f = new ScanIntegrationFixture(0); var reach = new NativeReachContext(f)) {
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(shape);
            Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.setBooleanValue(true);
            setPlayerField(f, "blockPosition", BlockPos.ZERO);
            var boxes = List.of(rawBox(0, 0, 0, 3, 0, 0));
            for (int tick = 1; tick <= 4; tick++) {
                setPlayerField(f, "position", new Vec3(tick <= 2 ? 0.125 : 0.25, 0, 0));
                if (tick == 4) f.player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(5.25D);
                f.tick(tick);
                ScanIntegrationFixture.drain(f.cache.iterable("mine", boxes, f.level, f.schematic,
                        f.player, 0, ScanIntent.MINE, pos -> true));
                assertEquals(tick != 2, f.cache.metricsFor("mine").scannedBlocks() > 0, "tick " + tick);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = RadiusShapeType.class, names = {"CUBE", "OCTAHEDRON", "SPHERE"})
    void sameBlockMotionOnlyRevisitsGeometryThatUsesPreciseCoordinates(RadiusShapeType shape) throws Exception {
        int previousRange = Configs.Core.WORK_RANGE.getIntegerValue();
        try (var f = new ScanIntegrationFixture(0)) {
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(shape);
            Configs.Core.WORK_RANGE.setIntegerValue(6);
            var boxes = List.of(rawBox(0, 0, 0, 5, 0, 0));
            for (int tick = 1; tick <= 4; tick++) {
                double x = tick <= 2 ? 0.125 : tick == 3 ? 0.25 : 1.25;
                setPlayerField(f, "position", new Vec3(x, 0, 0));
                setPlayerField(f, "blockPosition", BlockPos.containing(x, 0, 0));
                f.tick(tick);
                ScanIntegrationFixture.drain(f.cache.iterable("mine", boxes, f.level, f.schematic,
                        f.player, 0, ScanIntent.MINE, pos -> true));
                boolean shouldScan = tick == 1 || tick == 4 || tick == 3 && shape == RadiusShapeType.SPHERE;
                assertEquals(shouldScan, f.cache.metricsFor("mine").scannedBlocks() > 0, "tick " + tick);
            }
        } finally {
            Configs.Core.WORK_RANGE.setIntegerValue(previousRange);
        }
    }

    @ParameterizedTest
    @EnumSource(value = RadiusShapeType.class, names = {"CUBE", "OCTAHEDRON"})
    void aHighEyeBlockCenteredSourceKeepsTargetsInsideNativeReach(RadiusShapeType shape) throws Exception {
        int previousRange = Configs.Core.WORK_RANGE.getIntegerValue();
        try (var fixture = new ScanIntegrationFixture(0);
             var reach = new NativeReachContext(fixture)) {
            Configs.Core.WORK_RANGE.setIntegerValue(64);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(shape);
            Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.setBooleanValue(true);
            setPlayerField(fixture, "position", new Vec3(0.5, 64, 0.5));
            setPlayerField(fixture, "blockPosition", new BlockPos(0, 64, 0));
            setPlayerField(fixture, "eyeHeight", 26.0F);
            BlockPos nearEye = new BlockPos(0, 95, 0);
            fixture.level.world.put(nearEye, Blocks.STONE.defaultBlockState());
            fixture.tick(1);

            assertEquals(List.of(nearEye), ScanIntegrationFixture.drain(fixture.cache.iterable(
                    "mine", List.of(rawBox(0, 95, 0, 0, 95, 0)), fixture.level, fixture.schematic,
                    fixture.player, 0, ScanIntent.MINE, pos -> true)),
                    "A feet-centered envelope must not apply an eye-centered interaction-band cutoff at the feet");
        } finally {
            Configs.Core.WORK_RANGE.setIntegerValue(previousRange);
        }
    }

    private static void assertHighEyeTargetIsOffered(RadiusShapeType shape, BlockPos target) throws Exception {
        int previousRange = Configs.Core.WORK_RANGE.getIntegerValue();
        try (var fixture = new ScanIntegrationFixture(0)) {
            Configs.Core.WORK_RANGE.setIntegerValue(5);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(shape);
            setPlayerField(fixture, "position", new Vec3(0.5, 64, 0.5));
            setPlayerField(fixture, "blockPosition", new BlockPos(0, 64, 0));
            setPlayerField(fixture, "eyeHeight", 3.5F);
            fixture.level.world.put(target, Blocks.STONE.defaultBlockState());
            fixture.tick(1);

            var scope = List.of(rawBox(target.getX(), target.getY(), target.getZ(),
                    target.getX(), target.getY(), target.getZ()));
            assertEquals(List.of(target), ScanIntegrationFixture.drain(fixture.cache.iterable(
                    "mine", scope, fixture.level, fixture.schematic, fixture.player, 0,
                    ScanIntent.MINE, pos -> true)),
                    "A legal shape boundary must survive the source cursor's conservative envelope");
        } finally {
            Configs.Core.WORK_RANGE.setIntegerValue(previousRange);
        }
    }

    private static void setPlayerField(ScanIntegrationFixture fixture, String name, Object value) throws Exception {
        Field field = Entity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(fixture.player, value);
    }

    private static final class NativeReachContext implements AutoCloseable {
        private final sun.misc.Unsafe unsafe;
        private final Field clientField;
        private final Object previousClient;

        NativeReachContext(ScanIntegrationFixture fixture) throws Exception {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            this.unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            Field attributes = LivingEntity.class.getDeclaredField("attributes");
            attributes.setAccessible(true);
            attributes.set(fixture.player, new AttributeMap(Player.createAttributes().build()));
            fixture.player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(5.0D);
            Minecraft client = (Minecraft) this.unsafe.allocateInstance(Minecraft.class);
            client.player = fixture.player;
            this.clientField = PlayerUtils.class.getDeclaredField("client");
            this.clientField.setAccessible(true);
            this.previousClient = this.clientField.get(null);
            this.unsafe.putObject(this.unsafe.staticFieldBase(this.clientField),
                    this.unsafe.staticFieldOffset(this.clientField), client);
        }

        @Override public void close() {
            this.unsafe.putObject(this.unsafe.staticFieldBase(this.clientField),
                    this.unsafe.staticFieldOffset(this.clientField), this.previousClient);
        }
    }
}
//#endif
