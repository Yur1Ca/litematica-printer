package me.aleksilassila.litematica.printer.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import fi.dy.masa.malilib.render.RenderContext;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.HighlightStyleType;
import me.aleksilassila.litematica.printer.enums.HighlightType;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.phys.Vec3;
//#if MC >= 260100
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
//#if MC >= 260300
//$$ import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
//#else
import com.mojang.blaze3d.buffers.GpuBufferSlice;
//#endif
//#else
//$$ import org.joml.Matrix4f;
//#endif
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Geometry and styles adapted from BiliXWhite/litematica-printer@6cef6d31 (AGPL-3.0).
 * The renderer reads bounded snapshots only; it never visits the world or advances an iterator.
 */
public final class BlockHighlightRenderer implements IRenderer {
    private boolean renderFailureLogged;
    @Override
    //#if MC >= 260100
    //#if MC >= 260300
    //$$ public void onRenderWorldLast(RenderTarget target, CameraRenderState camera, Frustum frustum,
    //#else
    public void onRenderWorldLast(RenderTarget target, Matrix4fc matrices, CameraRenderState camera, Frustum frustum,
    //#endif
                                  RenderBuffers buffers, GpuBufferSlice fog, Vector4f fogColor, ProfilerFiller profiler) {
    //#else
    //$$ public void onRenderWorldLast(Matrix4f matrices, Matrix4f projection) {
    //#endif
        if (!Configs.Highlight.ENABLED.getBooleanValue() && !Configs.Highlight.NEXT_ENABLED.getBooleanValue()) return;
        var runtime = RuntimeAccess.currentOrNull();
        if (runtime == null || runtime.client().level == null || runtime.operationHighlights() == null) return;
        //#if MC >= 260100
        Vec3 cameraPos = camera.pos;
        //#else
        //$$ Vec3 cameraPos = fi.dy.masa.malilib.render.RenderUtils.camPos();
        //#endif
        var trace = runtime.operationHighlights();
        long now = trace.now();
        var entries = frameEntries(trace, cameraPos, now);
        if (!entries.isEmpty()) {
            float previewFill = 0.13f + 0.04f * (float) Math.sin(now % 1800L * (Math.PI * 2.0 / 1800.0));
            this.draw(entries, cameraPos, previewFill);
        }
    }

    static List<HighlightEntry> frameEntries(OperationHighlights trace, Vec3 cameraPos, long now) {
        List<HighlightEntry> entries = new ArrayList<>();
        var previewPositions = new HashSet<BlockPos>();
        if (Configs.Highlight.NEXT_ENABLED.getBooleanValue()) {
            for (var preview : trace.previews()) {
                if (previewPositions.add(preview.pos())) entries.add(entry(preview.pos(), Configs.Highlight.NEXT.getIntegerValue(), 1f,
                        HighlightStyleType.BOTH, true, cameraPos));
            }
        }
        if (!Configs.Highlight.ENABLED.getBooleanValue()) return entries;

        long duration = Configs.Highlight.FADE_DURATION.getIntegerValue() * 100L;
        var style = (HighlightStyleType) Configs.Highlight.STYLE.getOptionListValue();
        for (var operation : trace.snapshot(duration)) {
            if (previewPositions.contains(operation.pos())) continue;
            float fade = 1f - Math.clamp((float)(now - operation.time()) / duration, 0f, 1f);
            entries.add(entry(operation.pos(), color(operation.type()), fade, style, false, cameraPos));
        }
        return entries;
    }

    private static int color(HighlightType type) {
        return switch (type) {
            case PLACE -> Configs.Highlight.PLACE.getIntegerValue();
            case ADJUST -> Configs.Highlight.ADJUST.getIntegerValue();
            case BREAK -> Configs.Highlight.BREAK.getIntegerValue();
            case FAILED -> Configs.Highlight.FAILED.getIntegerValue();
        };
    }

    private static HighlightEntry entry(BlockPos pos, int argb, float fade, HighlightStyleType style, boolean preview, Vec3 camera) {
        return new HighlightEntry(pos, ((argb >>> 24) & 255) / 255f * fade,
                Vec3.atCenterOf(pos).distanceToSqr(camera), (argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255, style, preview);
    }

    private void draw(List<HighlightEntry> entries, Vec3 cameraPos, float previewFill) {
        boolean through = Configs.Highlight.THROUGH_WALLS.getBooleanValue();
        var linePipeline = through ? MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_NO_DEPTH_NO_CULL
                : MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_LEQUAL_DEPTH;
        var filledPipeline = through ? MaLiLibPipelines.POSITION_COLOR_TRANSLUCENT_NO_DEPTH_NO_CULL
                : MaLiLibPipelines.POSITION_COLOR_TRANSLUCENT_LEQUAL_DEPTH_NO_CULL;
        boolean outline = entries.stream().anyMatch(e -> e.style != HighlightStyleType.FILLED);
        boolean filled = entries.stream().anyMatch(e -> e.style != HighlightStyleType.OUTLINE);
        if (filled) entries.sort(Comparator.comparingDouble(e -> -e.distSq));
        //#if MC > 260100
        //$$ try (RenderContext ctx = new RenderContext(() -> "litematica_printer:operations", linePipeline, 0)) {
        //#else
        try (RenderContext ctx = new RenderContext(() -> "litematica_printer:operations", linePipeline)) {
        //#endif
            if (outline) {
                BufferBuilder buf = ctx.getBuilder();
                for (var e : entries) if (e.style != HighlightStyleType.FILLED)
                    addOutlineBoxLines(buf, e.pos, e.r, e.g, e.b, (int)(e.alpha * 255 * (e.preview ? 0.9f : 1f)), cameraPos);
                try (MeshData mesh = buf.build()) { if (mesh != null) ctx.draw(mesh, false, true); }
                ctx.reset();
            }
            if (filled) {
                //#if MC > 260100
                //$$ BufferBuilder buf = ctx.start(() -> "operations_filled", filledPipeline, 0);
                //#else
                BufferBuilder buf = ctx.start(() -> "operations_filled", filledPipeline);
                //#endif
                for (var e : entries) if (e.style != HighlightStyleType.OUTLINE)
                    addFilledBoxModern(buf, e.pos, e.r, e.g, e.b, (int)(e.alpha * 255 * (e.preview ? previewFill : 1f)), cameraPos);
                try (MeshData mesh = buf.build()) { if (mesh != null) ctx.draw(mesh, false, false); }
            }
        } catch (Exception error) {
            if (!this.renderFailureLogged) {
                this.renderFailureLogged = true;
                me.aleksilassila.litematica.printer.Reference.LOGGER.warn("Unable to render printer operation highlights", error);
            }
        }
    }

    private void addFilledBoxModern(BufferBuilder buf, BlockPos pos,
                                    int r, int g, int b, int a, Vec3 cameraPos) {
        float x1 = (float) (pos.getX() - cameraPos.x - 0.001);
        float y1 = (float) (pos.getY() - cameraPos.y - 0.001 );
        float z1 = (float) (pos.getZ() - cameraPos.z - 0.001 );
        float x2 = (float) (pos.getX() - cameraPos.x + 1 + 0.001);
        float y2 = (float) (pos.getY() - cameraPos.y + 1 + 0.001);
        float z2 = (float) (pos.getZ() - cameraPos.z + 1 + 0.001);

        quadModern(buf, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, r, g, b, a);
        quadModern(buf, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1, r, g, b, a);
        quadModern(buf, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, r, g, b, a);
        quadModern(buf, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, r, g, b, a);
        quadModern(buf, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, r, g, b, a);
        quadModern(buf, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, r, g, b, a);
    }

    private void quadModern(BufferBuilder buf, float x1, float y1, float z1,
                            float x2, float y2, float z2, float x3, float y3, float z3,
                            float x4, float y4, float z4, int r, int g, int b, int a) {
        buf.addVertex(x1, y1, z1).setColor(r, g, b, a);
        buf.addVertex(x2, y2, z2).setColor(r, g, b, a);
        buf.addVertex(x3, y3, z3).setColor(r, g, b, a);
        buf.addVertex(x4, y4, z4).setColor(r, g, b, a);
    }

    private void addOutlineBoxLines(BufferBuilder buf, BlockPos pos,
                                    int r, int g, int b, int a, Vec3 cameraPos) {
        float x1 = (float) (pos.getX() - cameraPos.x );
        float y1 = (float) (pos.getY() - cameraPos.y );
        float z1 = (float) (pos.getZ() - cameraPos.z );
        float x2 = (float) (pos.getX() - cameraPos.x + 1 );
        float y2 = (float) (pos.getY() - cameraPos.y + 1 );
        float z2 = (float) (pos.getZ() - cameraPos.z + 1 );

        line(buf, x1, y1, z1, x2, y1, z1, r, g, b, a);
        line(buf, x2, y1, z1, x2, y1, z2, r, g, b, a);
        line(buf, x2, y1, z2, x1, y1, z2, r, g, b, a);
        line(buf, x1, y1, z2, x1, y1, z1, r, g, b, a);
        line(buf, x1, y2, z1, x2, y2, z1, r, g, b, a);
        line(buf, x2, y2, z1, x2, y2, z2, r, g, b, a);
        line(buf, x2, y2, z2, x1, y2, z2, r, g, b, a);
        line(buf, x1, y2, z2, x1, y2, z1, r, g, b, a);
        line(buf, x1, y1, z1, x1, y2, z1, r, g, b, a);
        line(buf, x2, y1, z1, x2, y2, z1, r, g, b, a);
        line(buf, x2, y1, z2, x2, y2, z2, r, g, b, a);
        line(buf, x1, y1, z2, x1, y2, z2, r, g, b, a);
    }

    private void line(BufferBuilder buf, float x1, float y1, float z1,
                      float x2, float y2, float z2, int r, int g, int b, int a) {
        buf.addVertex(x1, y1, z1).setColor(r, g, b, a).setLineWidth(1.0f);
        buf.addVertex(x2, y2, z2).setColor(r, g, b, a).setLineWidth(1.0f);
    }
    record HighlightEntry(BlockPos pos, float alpha, double distSq, int r, int g, int b, HighlightStyleType style, boolean preview) { }
}
