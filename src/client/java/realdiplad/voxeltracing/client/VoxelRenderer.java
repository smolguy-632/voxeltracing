package realdiplad.voxeltracing.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * Draws the baked meshes as a lighting correction on top of the vanilla image:
 *   pass 1 (multiply): colour = src * dst            darkens / tints (shadows, occlusion, glass tint)
 *   pass 2 (boost)   : colour = dst * (1 + src)      brightens up to 2x (bounced light)
 * No CPU geometry work per frame: a frustum test and one draw per visible buffer.
 */
final class VoxelRenderer {
	private static final Matrix4f MV = new Matrix4f();

	private VoxelRenderer() {
	}

	static void render(WorldRenderContext ctx) {
		VtConfig cfg = VtConfig.get();
		VoxelLighting vl = VoxelLighting.INSTANCE;
		if (!cfg.enabled || vl.level == null || vl.level != ctx.world() || vl.sections.isEmpty()) return;

		ShaderInstance shader = GameRenderer.getPositionColorShader();
		if (shader == null) return;

		Vec3 cam = ctx.camera().getPosition();
		Frustum frustum = ctx.frustum();
		Matrix4f base = ctx.matrixStack().last().pose();
		Matrix4f proj = ctx.projectionMatrix();

		RenderSystem.enableBlend();
		RenderSystem.enableDepthTest();
		RenderSystem.depthFunc(GL11.GL_LEQUAL);
		RenderSystem.depthMask(false);
		RenderSystem.disableCull();
		RenderSystem.enablePolygonOffset();
		RenderSystem.polygonOffset(-1.0f, -2.0f);
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

		RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ZERO);
		pass(vl, false, cam, frustum, base, proj, shader);
		RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ONE);
		pass(vl, true, cam, frustum, base, proj, shader);

		RenderSystem.defaultBlendFunc();
		RenderSystem.disablePolygonOffset();
		RenderSystem.enableCull();
		RenderSystem.depthMask(true);
		RenderSystem.disableBlend();
	}

	private static void pass(VoxelLighting vl, boolean boost, Vec3 cam, Frustum frustum, Matrix4f base,
							 Matrix4f proj, ShaderInstance shader) {
		boolean drew = false;
		for (Sect s : vl.sections.values()) {
			VertexBuffer vb = boost ? s.vbBoost : s.vbMul;
			if (vb == null || (boost ? s.cBoost : s.cMul) == 0) continue;
			double x0 = s.sx << 4, y0 = s.sy << 4, z0 = s.sz << 4;
			if (frustum != null && !frustum.isVisible(new AABB(x0, y0, z0, x0 + 16, y0 + 16, z0 + 16))) continue;
			MV.set(base).translate((float) (x0 - cam.x), (float) (y0 - cam.y), (float) (z0 - cam.z));
			vb.bind();
			vb.drawWithShader(MV, proj, shader);
			drew = true;
		}
		if (drew) VertexBuffer.unbind();
	}
}
