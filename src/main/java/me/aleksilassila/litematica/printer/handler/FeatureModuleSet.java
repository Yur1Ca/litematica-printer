package me.aleksilassila.litematica.printer.handler;

import me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent;
import me.aleksilassila.litematica.printer.core.runtime.FeatureLifecycle;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import com.google.common.collect.ImmutableList;
import me.aleksilassila.litematica.printer.handler.handlers.BedrockHandler;
import me.aleksilassila.litematica.printer.handler.handlers.CoverHandler;
import me.aleksilassila.litematica.printer.handler.handlers.FillHandler;
import me.aleksilassila.litematica.printer.handler.handlers.FluidHandler;
import me.aleksilassila.litematica.printer.handler.handlers.GuiHandler;
import me.aleksilassila.litematica.printer.handler.handlers.MineHandler;
import me.aleksilassila.litematica.printer.handler.handlers.PrintHandler;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.config.ConfiguredPlacementSettings;

/** Runtime-owned feature graph and its stable execution order. */
public final class FeatureModuleSet implements RuntimeComponent {
    private final PrinterRuntime runtime;
    private final FeatureLifecycle lifecycle =
            new FeatureLifecycle();
    private final GuiHandler gui;
    private final PrintHandler print;
    private final FillHandler fill;
    private final MineHandler mine;
    private final FluidHandler fluid;
    private final BedrockHandler bedrock;
    private final CoverHandler cover;
    private final ImmutableList<FeatureModuleBase> values;
    private final TickScheduler scheduler;

    public FeatureModuleSet(PrinterRuntime runtime) {
        this.runtime = runtime;
        runtime.register(this);
        this.gui = new GuiHandler(runtime);
        this.print = new PrintHandler(runtime);
        var placementSettings = new ConfiguredPlacementSettings();
        this.fill = new FillHandler(runtime, placementSettings);
        this.mine = new MineHandler(runtime);
        this.fluid = new FluidHandler(runtime, placementSettings);
        this.bedrock = new BedrockHandler(runtime);
        this.cover = new CoverHandler(runtime);
        this.values = ImmutableList.of(this.gui, this.mine, this.fluid, this.print, this.fill, this.cover, this.bedrock);
        for (FeatureModuleBase module : this.values) {
            runtime.register(module);
        }
        this.scheduler = new TickScheduler(this.values, runtime);
        runtime.register(this.scheduler);
    }

    public GuiHandler gui() { return this.gui; }
    public PrintHandler print() { return this.print; }
    public FillHandler fill() { return this.fill; }
    public MineHandler mine() { return this.mine; }
    public FluidHandler fluid() { return this.fluid; }
    public BedrockHandler bedrock() { return this.bedrock; }
    public CoverHandler cover() { return this.cover; }
    public ImmutableList<FeatureModuleBase> values() { return this.values; }

    public FeatureLifecycle lifecycle() { return this.lifecycle; }

    /** Runs before providers and queued interactions, including on the tick that work is disabled. */
    public void synchronizeActivation() {
        for (FeatureModuleBase module : this.values) {
            boolean enabled = ModuleEnablePolicy.allows(module);
            if (!this.lifecycle.observe(module.getId(), enabled)) continue;
            this.runtime.actionBroker().cancelOwnerQueue(module.getId());
            this.runtime.cancelMaterialsFor(module.getId());
            if (module == this.print) this.runtime.interactionUtils().cancelPlannedWork();
            module.resetRuntimeState();
        }
    }

    @Override
    public void onEpochChanged(RuntimeEvent.EpochChanged event) {
        this.lifecycle.reset();
    }

    public void tick() { this.scheduler.tick(); }
    public int packetTick() { return this.scheduler.getPacketTick(); }
    public void setPacketTick(int packetTick) { this.scheduler.setPacketTick(packetTick); }
    public void recordInboundPacket() { this.scheduler.recordInboundPacket(); }
    public String lastPauseReason() { return this.scheduler.getLastPauseReason(); }
}
