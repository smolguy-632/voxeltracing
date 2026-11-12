package realdiplad.voxeltracing.client;

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
 * Draws the baked overlay meshes. Per frame this is just a frustum test + one draw call
 * per visible, non-empty section: no CPU geometry work at all.
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
		RenderSystem.defaultBlendFunc();
		RenderSystem.enableDepthTest();
		RenderSystem.depthFunc(GL11.GL_LEQUAL);
		RenderSystem.depthMask(false);
		RenderSystem.enableCull();
		RenderSystem.enablePolygonOffset();
		RenderSystem.polygonOffset(-1.0f, -2.0f);
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

		boolean drewAny = false;
		for (VoxelSection s : vl.sections.values()) {
			if (s.vb == null || s.quadCount == 0) continue;
			double x0 = s.sx << 4, y0 = s.sy << 4, z0 = s.sz << 4;
			if (frustum != null && !frustum.isVisible(new AABB(x0, y0, z0, x0 + 16, y0 + 16, z0 + 16))) continue;

			MV.set(base).translate((float) (x0 - cam.x), (float) (y0 - cam.y), (float) (z0 - cam.z));
			s.vb.bind();
			s.vb.drawWithShader(MV, proj, shader);
			drewAny = true;
		}
		if (drewAny) VertexBuffer.unbind();

		RenderSystem.disablePolygonOffset();
		RenderSystem.depthMask(true);
		RenderSystem.disableBlend();
	}
}
