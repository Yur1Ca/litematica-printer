//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.scan;

import java.util.List;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static me.aleksilassila.litematica.printer.handler.scan.RawPrinterBoxFixture.rawBox;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Releasing a workflow delivery must not make its unchanged ICE wait for section repair. */
class ReleasedIceSourceReturnTest {
    private static final List<PrinterBox> SCOPE = List.of(rawBox(0, 0, 0, 0, 0, 0));

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest
    @ValueSource(strings = {"print", "print_sorted"})
    void aReleasedIceReofferSurvivesAnEmptyScopeUntilTheSameScopeReturns(String owner) throws Exception {
        try (var fixture = iceFixture()) {
            var engine = fixture.engine();
            fixture.tick(1);
            var initial = source(engine, fixture, owner, SCOPE);
            assertEquals(List.of(BlockPos.ZERO), ScanIntegrationFixture.drain(initial));
            assertEquals(ScanAvailability.COMPLETE, initial.availability());
            int reads = fixture.level.readsAt(BlockPos.ZERO);
            long dirty = engine.dirtyVersion();
            assertTrue(reads > 0, "The initial positive ICE fact must come from a real world observation");

            fixture.tick(2);
            assertEquals(List.of(), ScanIntegrationFixture.drain(engine.iterable(owner, List.of(),
                    fixture.level, fixture.schematic, fixture.player, 0, ScanIntent.PRINT, pos -> true)));
            // This is the owner-specific bridge used when the out-of-scope idle task is released.
            engine.reoffer(owner, BlockPos.ZERO);
            assertEquals(reads, fixture.level.readsAt(BlockPos.ZERO));
            assertEquals(dirty, engine.dirtyVersion());

            fixture.tick(3);
            assertEquals(List.of(BlockPos.ZERO), ScanIntegrationFixture.drain(source(engine, fixture, owner, SCOPE)),
                    "Returning before the 20-tick repair deadline must deliver the released ICE again");
            assertEquals(reads, fixture.level.readsAt(BlockPos.ZERO),
                    "Releasing delivery ownership must reuse the positive world fact");
            assertEquals(dirty, engine.dirtyVersion(), "Reoffer must not fabricate a world update");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"print", "print_sorted"})
    void anEmptyScopeAloneDoesNotWakeACompletedIceSourceBeforeRepair(String owner) throws Exception {
        try (var fixture = iceFixture()) {
            var engine = fixture.engine();
            fixture.tick(1);
            assertEquals(List.of(BlockPos.ZERO), ScanIntegrationFixture.drain(source(engine, fixture, owner, SCOPE)));
            int reads = fixture.level.readsAt(BlockPos.ZERO);
            long dirty = engine.dirtyVersion();

            fixture.tick(2);
            assertEquals(List.of(), ScanIntegrationFixture.drain(engine.iterable(owner, List.of(),
                    fixture.level, fixture.schematic, fixture.player, 0, ScanIntent.PRINT, pos -> true)));
            fixture.tick(3);
            var returned = source(engine, fixture, owner, SCOPE);
            assertEquals(List.of(), ScanIntegrationFixture.drain(returned),
                    "The same scope and player position leave the consumed source asleep without a release reoffer");
            assertEquals(ScanAvailability.COMPLETE, returned.availability());
            assertEquals(reads, fixture.level.readsAt(BlockPos.ZERO));
            assertEquals(dirty, engine.dirtyVersion());

            fixture.tick(21);
            assertEquals(List.of(BlockPos.ZERO), ScanIntegrationFixture.drain(source(engine, fixture, owner, SCOPE)),
                    "The control recovers only when its section repair becomes due");
            assertTrue(fixture.level.readsAt(BlockPos.ZERO) > reads);
            assertEquals(dirty, engine.dirtyVersion());
        }
    }

    private static ScanIntegrationFixture iceFixture() throws Exception {
        var fixture = new ScanIntegrationFixture(1);
        fixture.chunks.get(0L).getSections()[4].setBlockState(0, 0, 0, Blocks.WATER.defaultBlockState());
        fixture.level.world.put(BlockPos.ZERO, Blocks.ICE.defaultBlockState());
        return fixture;
    }

    private static ScanCandidateIterable source(ScanEngine engine, ScanIntegrationFixture fixture,
                                                String owner, List<PrinterBox> scope) {
        return (ScanCandidateIterable) engine.iterable(owner, scope, fixture.level, fixture.schematic,
                fixture.player, 0, ScanIntent.PRINT, pos -> true);
    }
}
//#endif
