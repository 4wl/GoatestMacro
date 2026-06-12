package com.justingoat.goat.client.module.pathfinder;

import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.combat.CombatMacro;
import com.justingoat.goat.client.module.movement.ForagingMacro;
import com.justingoat.goat.client.module.movement.PathfinderTest;
import com.justingoat.goat.client.module.GoatModule;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Draws the A* path in-world using proper line and box rendering
 * instead of particle effects. Color-coded by move type:
 *   WALK        → green
 *   STEP_UP     → cyan
 *   DROP        → yellow
 *   JUMP_ACROSS → magenta
 *   Current target → white (bright highlight)
 */
public class PathRenderer {

    // Colors (RGBA floats)
    private static final float[][] COLORS = {
        {0.15f, 1.0f, 0.55f, 0.9f},  // WALK — green
        {0.2f,  0.9f, 1.0f,  0.9f},  // STEP_UP — cyan
        {1.0f,  0.95f, 0.2f, 0.9f},  // DROP — yellow
        {1.0f,  0.3f, 0.9f,  0.9f},  // JUMP_ACROSS — magenta
        {1.0f,  0.6f, 0.2f,  0.9f},  // CLIMB — orange
    };
    private static final float[] COLOR_CURRENT = {1.0f, 1.0f, 1.0f, 1.0f}; // white
    private static final float[] COLOR_LINE    = {0.3f, 0.85f, 1.0f, 0.6f}; // light blue
    private static final float[] COLOR_FORAGING_TARGET = {1.0f, 0.9f, 0.1f, 0.75f};
    private static final float[] COLOR_COMBAT_TARGET  = {1.0f, 0.2f, 0.2f, 0.85f};

    private static final float NODE_SIZE = 0.15f; // half-width of node marker box
    private static final float CURRENT_SIZE = 0.22f;

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(PathRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        GoatModule module = ModuleManager.findByName("Pathfinder");
        PathProcessor processor = null;
        BlockPos foragingTarget = null;
        LivingEntity combatTarget = null;
        boolean shouldRender = false;

        if (module instanceof PathfinderTest pt && module.isEnabled() && pt.shouldRenderPath()) {
            processor = pt.getPathProcessor();
            shouldRender = true;
        }

        GoatModule combat = ModuleManager.findByName("CombatMacro");
        if (combat instanceof CombatMacro cm && combat.isEnabled() && cm.shouldRenderPath()) {
            processor = cm.getPathProcessor();
            combatTarget = cm.getCurrentTarget();
            shouldRender = true;
        }

        GoatModule foraging = ModuleManager.findByName("ForagingMacro");
        if (foraging instanceof ForagingMacro fm && foraging.isEnabled() && fm.shouldRenderPath()) {
            processor = fm.getPathProcessor();
            foragingTarget = fm.getRenderTarget();
            shouldRender = true;
        }

        if (!shouldRender) return;
        List<PathNode> path = processor == null ? null : processor.getPath();
        int curIdx = processor == null ? 0 : processor.getCurrentIndex();
        boolean hasPath = processor != null && !processor.isDone()
            && path != null && !path.isEmpty() && curIdx < path.size();
        if (!hasPath && foragingTarget == null && combatTarget == null) return;

        MatrixStack matrices = context.matrices();
        VertexConsumerProvider consumers = context.consumers();
        Vec3d cam = context.worldState().cameraRenderState.pos;

        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);

        Matrix4f posMatrix = matrices.peek().getPositionMatrix();
        MatrixStack.Entry entry = matrices.peek();

        VertexConsumer lineBuffer = consumers.getBuffer(RenderLayers.lines());

        if (hasPath) {
            // --- Lines between nodes ---
            int maxRender = Math.min(path.size(), curIdx + 40);

            for (int i = curIdx; i < maxRender - 1; i++) {
                BlockPos p1 = path.get(i).getPos();
                BlockPos p2 = path.get(i + 1).getPos();

                float x1 = p1.getX() + 0.5f;
                float y1 = p1.getY() + 1.0f;
                float z1 = p1.getZ() + 0.5f;
                float x2 = p2.getX() + 0.5f;
                float y2 = p2.getY() + 1.0f;
                float z2 = p2.getZ() + 0.5f;

                float dx = x2 - x1;
                float dy = y2 - y1;
                float dz = z2 - z1;
                float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 0.001f) continue;
                float nx = dx / len;
                float ny = dy / len;
                float nz = dz / len;

                float[] c = COLOR_LINE;
                lineBuffer.vertex(posMatrix, x1, y1, z1)
                    .color(c[0], c[1], c[2], c[3])
                    .normal(entry, nx, ny, nz)
                    .lineWidth(2.5f);
                lineBuffer.vertex(posMatrix, x2, y2, z2)
                    .color(c[0], c[1], c[2], c[3])
                    .normal(entry, nx, ny, nz)
                    .lineWidth(2.5f);
            }

            // --- Node markers (small filled quads rendered as debug boxes) ---
            for (int i = curIdx; i < maxRender; i++) {
                PathNode node = path.get(i);
                BlockPos p = node.getPos();
                float cx = p.getX() + 0.5f;
                float cy = p.getY() + 1.0f;
                float cz = p.getZ() + 0.5f;

                boolean isCurrent = (i == curIdx);
                float[] c = isCurrent ? COLOR_CURRENT : getColor(node.getMoveType());
                float s = isCurrent ? CURRENT_SIZE : NODE_SIZE;

                drawWireBox(lineBuffer, posMatrix, entry, cx - s, cy - s, cz - s,
                        cx + s, cy + s, cz + s, c[0], c[1], c[2], c[3]);
            }
        }

        if (foragingTarget != null) {
            float[] c = COLOR_FORAGING_TARGET;
            float x = foragingTarget.getX() + 0.5f;
            float y = foragingTarget.getY() + 0.5f;
            float z = foragingTarget.getZ() + 0.5f;
            float s = 0.32f;
            drawWireBox(lineBuffer, posMatrix, entry, x - s, y - s, z - s, x + s, y + s, z + s, c[0], c[1], c[2], c[3]);
        }

        if (combatTarget != null && combatTarget.isAlive()) {
            float[] c = COLOR_COMBAT_TARGET;
            float tickDelta = MinecraftClient.getInstance().getRenderTickCounter().getTickProgress(false);
            double ex = combatTarget.lastRenderX + (combatTarget.getX() - combatTarget.lastRenderX) * tickDelta;
            double ey = combatTarget.lastRenderY + (combatTarget.getY() - combatTarget.lastRenderY) * tickDelta;
            double ez = combatTarget.lastRenderZ + (combatTarget.getZ() - combatTarget.lastRenderZ) * tickDelta;
            Box bb = combatTarget.getBoundingBox();
            float hw = (float) ((bb.maxX - bb.minX) * 0.5);
            float hh = (float) (bb.maxY - bb.minY);
            float hd = (float) ((bb.maxZ - bb.minZ) * 0.5);
            drawWireBox(lineBuffer, posMatrix, entry,
                (float) ex - hw, (float) ey, (float) ez - hd,
                (float) ex + hw, (float) ey + hh, (float) ez + hd,
                c[0], c[1], c[2], c[3]);
        }

        matrices.pop();
    }

    private static float[] getColor(PathNode.MoveType type) {
        return switch (type) {
            case WALK -> COLORS[0];
            case STEP_UP -> COLORS[1];
            case DROP -> COLORS[2];
            case JUMP_ACROSS -> COLORS[3];
            case CLIMB -> COLORS[4];
        };
    }

    private static void drawWireBox(VertexConsumer buf, Matrix4f mat, MatrixStack.Entry entry,
                                    float x1, float y1, float z1,
                                    float x2, float y2, float z2,
                                    float r, float g, float b, float a) {
        line(buf, mat, entry, x1, y1, z1, x2, y1, z1, r, g, b, a);
        line(buf, mat, entry, x2, y1, z1, x2, y1, z2, r, g, b, a);
        line(buf, mat, entry, x2, y1, z2, x1, y1, z2, r, g, b, a);
        line(buf, mat, entry, x1, y1, z2, x1, y1, z1, r, g, b, a);
        line(buf, mat, entry, x1, y2, z1, x2, y2, z1, r, g, b, a);
        line(buf, mat, entry, x2, y2, z1, x2, y2, z2, r, g, b, a);
        line(buf, mat, entry, x2, y2, z2, x1, y2, z2, r, g, b, a);
        line(buf, mat, entry, x1, y2, z2, x1, y2, z1, r, g, b, a);
        line(buf, mat, entry, x1, y1, z1, x1, y2, z1, r, g, b, a);
        line(buf, mat, entry, x2, y1, z1, x2, y2, z1, r, g, b, a);
        line(buf, mat, entry, x2, y1, z2, x2, y2, z2, r, g, b, a);
        line(buf, mat, entry, x1, y1, z2, x1, y2, z2, r, g, b, a);
    }

    private static void line(VertexConsumer buf, Matrix4f mat, MatrixStack.Entry entry,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float r, float g, float b, float a) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.001f) return;
        float nx = dx / len;
        float ny = dy / len;
        float nz = dz / len;
        buf.vertex(mat, x1, y1, z1).color(r, g, b, a).normal(entry, nx, ny, nz).lineWidth(2.0f);
        buf.vertex(mat, x2, y2, z2).color(r, g, b, a).normal(entry, nx, ny, nz).lineWidth(2.0f);
    }

    /**
     * Emit 24 vertices (6 faces × 4 verts) for a filled box.
     * debugFilledBox uses QUADS draw mode with POSITION_COLOR format.
     */
    private static void drawBox(VertexConsumer buf, Matrix4f mat,
                                 float x1, float y1, float z1,
                                 float x2, float y2, float z2,
                                 float r, float g, float b, float a) {
        // Bottom (y1)
        buf.vertex(mat, x1, y1, z1).color(r, g, b, a);
        buf.vertex(mat, x2, y1, z1).color(r, g, b, a);
        buf.vertex(mat, x2, y1, z2).color(r, g, b, a);
        buf.vertex(mat, x1, y1, z2).color(r, g, b, a);
        // Top (y2)
        buf.vertex(mat, x1, y2, z1).color(r, g, b, a);
        buf.vertex(mat, x1, y2, z2).color(r, g, b, a);
        buf.vertex(mat, x2, y2, z2).color(r, g, b, a);
        buf.vertex(mat, x2, y2, z1).color(r, g, b, a);
        // North (z1)
        buf.vertex(mat, x1, y1, z1).color(r, g, b, a);
        buf.vertex(mat, x1, y2, z1).color(r, g, b, a);
        buf.vertex(mat, x2, y2, z1).color(r, g, b, a);
        buf.vertex(mat, x2, y1, z1).color(r, g, b, a);
        // South (z2)
        buf.vertex(mat, x1, y1, z2).color(r, g, b, a);
        buf.vertex(mat, x2, y1, z2).color(r, g, b, a);
        buf.vertex(mat, x2, y2, z2).color(r, g, b, a);
        buf.vertex(mat, x1, y2, z2).color(r, g, b, a);
        // West (x1)
        buf.vertex(mat, x1, y1, z1).color(r, g, b, a);
        buf.vertex(mat, x1, y1, z2).color(r, g, b, a);
        buf.vertex(mat, x1, y2, z2).color(r, g, b, a);
        buf.vertex(mat, x1, y2, z1).color(r, g, b, a);
        // East (x2)
        buf.vertex(mat, x2, y1, z1).color(r, g, b, a);
        buf.vertex(mat, x2, y2, z1).color(r, g, b, a);
        buf.vertex(mat, x2, y2, z2).color(r, g, b, a);
        buf.vertex(mat, x2, y1, z2).color(r, g, b, a);
    }
}
