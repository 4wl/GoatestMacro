package com.justingoat.goat.client.module.pathfinder;

import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.combat.CombatMacro;
import com.justingoat.goat.client.module.farming.PestCleaner;
import com.justingoat.goat.client.module.mining.MiningMacro;
import com.justingoat.goat.client.module.mining.MiningBot;
import com.justingoat.goat.client.module.mining.MiningTarget;
import com.justingoat.goat.client.module.movement.ForagingMacro;
import com.justingoat.goat.client.module.movement.PathfinderTest;
import com.justingoat.goat.client.module.render.PestESP;
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
    private static final float[] COLOR_SPRINT_JUMP = {0.8f, 0.35f, 1.0f, 0.9f};
    private static final float[] COLOR_SWIM = {0.1f, 0.65f, 1.0f, 0.9f};
    private static final float[] COLOR_CURRENT = {1.0f, 1.0f, 1.0f, 1.0f}; // white
    private static final float[] COLOR_LINE    = {0.3f, 0.85f, 1.0f, 0.6f}; // light blue
    private static final float[] COLOR_FORAGING_TARGET = {1.0f, 0.9f, 0.1f, 0.75f};
    private static final float[] COLOR_COMBAT_TARGET  = {1.0f, 0.2f, 0.2f, 0.85f};
    private static final float[] COLOR_FLY_LINE      = {0.4f, 0.7f, 1.0f, 0.7f};
    private static final float[] COLOR_FLY_NODE      = {0.3f, 0.6f, 1.0f, 0.9f};
    private static final float[] COLOR_ETHERWARP_LINE  = {0.0f, 0.67f, 1.0f, 0.7f};
    private static final float[] COLOR_ETHERWARP_NODE  = {0.3f, 1.0f, 0.55f, 0.9f};
    private static final float[] COLOR_ETHERWARP_START = {0.3f, 1.0f, 0.55f, 0.9f};
    private static final float[] COLOR_ETHERWARP_END   = {1.0f, 0.35f, 0.35f, 0.9f};
    private static final float[] COLOR_ETHERWARP_CUR   = {1.0f, 1.0f, 0.2f, 1.0f};
    private static final float[] COLOR_MINING_TARGET   = {0.0f, 1.0f, 0.9f, 1.0f};
    private static final float[] COLOR_MINING_CANDIDATE = {0.0f, 0.7f, 0.6f, 0.35f};
    private static final float[] COLOR_PEST_ESP        = {1.0f, 0.4f, 0.1f, 0.75f};
    private static final float[] COLOR_PEST_TARGET     = {1.0f, 0.1f, 0.1f, 1.0f};
    private static final float[] COLOR_PEST_TRACER     = {1.0f, 0.5f, 0.2f, 0.5f};

    private static final float NODE_SIZE = 0.15f;
    private static final float CURRENT_SIZE = 0.22f;

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(PathRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        GoatModule module = ModuleManager.findByName("Pathfinder");
        PathProcessor processor = null;
        BlockPos foragingTarget = null;
        LivingEntity combatTarget = null;
        MiningBot miningBot = null;
        List<PestCleaner.PestInfo> pests = null;
        PestCleaner.PestInfo pestTarget = null;
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

        GoatModule mining = ModuleManager.findByName("MiningBot");
        if (mining instanceof MiningMacro mm && mining.isEnabled()) {
            MiningBot bot = mm.getBot();
            if (bot.isEnabled()) {
                miningBot = bot;
                shouldRender = true;
            }
        }

        pests = PestESP.getRenderPests();
        if (pests == null) pests = PestCleaner.getRenderPests();
        pestTarget = PestCleaner.getRenderTarget();
        if (pests != null || pestTarget != null) shouldRender = true;

        List<Vec3d> flyPath = FlyPathProcessor.getRenderPath();
        List<BlockPos> ethHops = EtherwarpPathfinder.getRenderHops();
        if (flyPath != null || ethHops != null) shouldRender = true;

        if (!shouldRender) return;
        List<PathNode> path = processor == null ? null : processor.getPath();
        int curIdx = processor == null ? 0 : processor.getCurrentIndex();
        boolean hasPath = processor != null && !processor.isDone()
            && path != null && !path.isEmpty() && curIdx < path.size();
        if (!hasPath && foragingTarget == null && combatTarget == null && miningBot == null
            && flyPath == null && ethHops == null && pests == null && pestTarget == null) return;

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

        // --- Fly path rendering ---
        if (flyPath != null && flyPath.size() >= 2) {
            int flyIdx = FlyPathProcessor.getRenderIndex();
            int flyMax = Math.min(flyPath.size(), flyIdx + 60);
            for (int i = Math.max(0, flyIdx); i < flyMax - 1; i++) {
                Vec3d p1 = flyPath.get(i);
                Vec3d p2 = flyPath.get(i + 1);
                float dx = (float)(p2.x - p1.x), dy = (float)(p2.y - p1.y), dz = (float)(p2.z - p1.z);
                float len = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
                if (len < 0.001f) continue;
                float[] c = COLOR_FLY_LINE;
                lineBuffer.vertex(posMatrix, (float)p1.x, (float)p1.y, (float)p1.z)
                    .color(c[0], c[1], c[2], c[3]).normal(entry, dx/len, dy/len, dz/len).lineWidth(2.5f);
                lineBuffer.vertex(posMatrix, (float)p2.x, (float)p2.y, (float)p2.z)
                    .color(c[0], c[1], c[2], c[3]).normal(entry, dx/len, dy/len, dz/len).lineWidth(2.5f);
            }
            for (int i = Math.max(0, flyIdx); i < flyMax; i++) {
                Vec3d p = flyPath.get(i);
                float[] c = i == flyIdx ? COLOR_CURRENT : COLOR_FLY_NODE;
                float s = i == flyIdx ? CURRENT_SIZE : NODE_SIZE;
                drawWireBox(lineBuffer, posMatrix, entry,
                    (float)p.x-s, (float)p.y-s, (float)p.z-s,
                    (float)p.x+s, (float)p.y+s, (float)p.z+s, c[0], c[1], c[2], c[3]);
            }
        }

        // --- Etherwarp path rendering ---
        if (ethHops != null && !ethHops.isEmpty()) {
            int curHop = EtherwarpPathfinder.getRenderCurrentHop();
            for (int i = 0; i < ethHops.size() - 1; i++) {
                BlockPos p1 = ethHops.get(i);
                BlockPos p2 = ethHops.get(i + 1);
                float x1 = p1.getX()+0.5f, y1 = p1.getY()+1.05f, z1 = p1.getZ()+0.5f;
                float x2 = p2.getX()+0.5f, y2 = p2.getY()+1.05f, z2 = p2.getZ()+0.5f;
                float dx = x2-x1, dy = y2-y1, dz = z2-z1;
                float len = (float) Math.sqrt(dx*dx+dy*dy+dz*dz);
                if (len < 0.001f) continue;
                float[] c = COLOR_ETHERWARP_LINE;
                lineBuffer.vertex(posMatrix, x1, y1, z1).color(c[0],c[1],c[2],c[3]).normal(entry,dx/len,dy/len,dz/len).lineWidth(3.0f);
                lineBuffer.vertex(posMatrix, x2, y2, z2).color(c[0],c[1],c[2],c[3]).normal(entry,dx/len,dy/len,dz/len).lineWidth(3.0f);
            }
            for (int i = 0; i < ethHops.size(); i++) {
                BlockPos p = ethHops.get(i);
                float cx = p.getX()+0.5f, cy = p.getY()+1.05f, cz = p.getZ()+0.5f;
                float[] c;
                float s;
                if (i == curHop) { c = COLOR_ETHERWARP_CUR; s = CURRENT_SIZE; }
                else if (i == 0) { c = COLOR_ETHERWARP_START; s = NODE_SIZE; }
                else if (i == ethHops.size()-1) { c = COLOR_ETHERWARP_END; s = 0.2f; }
                else { c = COLOR_ETHERWARP_NODE; s = NODE_SIZE; }
                drawWireBox(lineBuffer, posMatrix, entry, cx-s, cy-s, cz-s, cx+s, cy+s, cz+s, c[0], c[1], c[2], c[3]);
            }
        }

        // --- Mining target ESP ---
        if (miningBot != null) {
            MiningTarget currentMiningTarget = miningBot.getCurrentTarget();
            List<MiningTarget> candidates = miningBot.getFoundLocations();

            if (candidates != null) {
                for (MiningTarget t : candidates) {
                    if (t == currentMiningTarget) continue;
                    BlockPos p = t.pos;
                    float[] c = COLOR_MINING_CANDIDATE;
                    drawWireBox(lineBuffer, posMatrix, entry,
                        p.getX(), p.getY(), p.getZ(),
                        p.getX() + 1.0f, p.getY() + 1.0f, p.getZ() + 1.0f,
                        c[0], c[1], c[2], c[3]);
                }
            }

            if (currentMiningTarget != null) {
                BlockPos p = currentMiningTarget.pos;
                float[] c = COLOR_MINING_TARGET;
                drawWireBox(lineBuffer, posMatrix, entry,
                    p.getX(), p.getY(), p.getZ(),
                    p.getX() + 1.0f, p.getY() + 1.0f, p.getZ() + 1.0f,
                    c[0], c[1], c[2], c[3]);
            }
        }

        // --- Pest ESP rendering ---
        if (pests != null) {
            MinecraftClient mc = MinecraftClient.getInstance();
            Vec3d playerEye = mc.player != null ? mc.player.getEyePos() : cam;

            for (PestCleaner.PestInfo pest : pests) {
                if (pest.nameTag.isRemoved()) continue;
                Vec3d pp = pest.pestPos;
                boolean isTarget = pestTarget != null && pest.nameTag.getId() == pestTarget.nameTag.getId();
                float[] c = isTarget ? COLOR_PEST_TARGET : COLOR_PEST_ESP;
                float s = isTarget ? 0.35f : 0.25f;

                drawWireBox(lineBuffer, posMatrix, entry,
                    (float) pp.x - s, (float) pp.y, (float) pp.z - s,
                    (float) pp.x + s, (float) pp.y + 2.0f, (float) pp.z + s,
                    c[0], c[1], c[2], c[3]);

                // Tracer line from player to pest
                float[] tc = COLOR_PEST_TRACER;
                float tx = (float) playerEye.x, ty = (float) playerEye.y, tz = (float) playerEye.z;
                float px = (float) pp.x, py = (float) pp.y + 1.0f, pz = (float) pp.z;
                float dx = px - tx, dy = py - ty, dz = pz - tz;
                float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len > 0.01f) {
                    lineBuffer.vertex(posMatrix, tx, ty, tz)
                        .color(tc[0], tc[1], tc[2], tc[3])
                        .normal(entry, dx / len, dy / len, dz / len).lineWidth(1.5f);
                    lineBuffer.vertex(posMatrix, px, py, pz)
                        .color(tc[0], tc[1], tc[2], tc[3])
                        .normal(entry, dx / len, dy / len, dz / len).lineWidth(1.5f);
                }
            }
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
            case SPRINT_JUMP -> COLOR_SPRINT_JUMP;
            case SWIM -> COLOR_SWIM;
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
