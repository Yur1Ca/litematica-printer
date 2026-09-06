package me.aleksilassila.litematica.printer.handler;

import com.google.common.collect.ImmutableList;
import me.aleksilassila.litematica.printer.handler.handlers.BedrockHandler;
import me.aleksilassila.litematica.printer.handler.handlers.CoverHandler;
import me.aleksilassila.litematica.printer.handler.handlers.FillHandler;
import me.aleksilassila.litematica.printer.handler.handlers.FluidHandler;
import me.aleksilassila.litematica.printer.handler.handlers.GuiHandler;
import me.aleksilassila.litematica.printer.handler.handlers.MineHandler;
import me.aleksilassila.litematica.printer.handler.handlers.PrintHandler;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;

/** Runtime-owned feature graph and its stable execution order. */
public final class FeatureModuleSet {
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
        this.gui = new GuiHandler(runtime);
        this.print = new PrintHandler(runtime);
        this.fill = new FillHandler(runtime);
        this.mine = new MineHandler(runtime);
        this.fluid = new FluidHandler(runtime);
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

    public void tick() { this.scheduler.tick(); }
    public int packetTick() { return this.scheduler.getPacketTick(); }
    public void setPacketTick(int packetTick) { this.scheduler.setPacketTick(packetTick); }
    public void recordInboundPacket() { this.scheduler.recordInboundPacket(); }
    public String lastPauseReason() { return this.scheduler.getLastPauseReason(); }
}
