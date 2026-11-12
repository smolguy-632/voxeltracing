package realdiplad.voxeltracing.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class VoxeltracingClient implements ClientModInitializer {
	private static KeyMapping toggleKey;
	private static KeyMapping qualityKey;

	@Override
	public void onInitializeClient() {
		VtConfig.load();

		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.voxeltracing.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, "key.categories.voxeltracing"));
		qualityKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.voxeltracing.quality", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "key.categories.voxeltracing"));

		// Bake (time-budgeted) before the world draws, then draw the overlay right after entities
		// so translucent water/glass still composite on top of it.
		WorldRenderEvents.START.register(ctx ->
			VoxelLighting.INSTANCE.beginFrame(ctx.world(), ctx.camera(), ctx.tickDelta()));
		WorldRenderEvents.AFTER_ENTITIES.register(VoxelRenderer::render);

		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) ->
			VoxelLighting.INSTANCE.onChunkLoad(level, chunk.getPos().x, chunk.getPos().z));
		ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) ->
			VoxelLighting.INSTANCE.onChunkUnload(level, chunk.getPos().x, chunk.getPos().z));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> VoxelLighting.INSTANCE.reset());

		ClientTickEvents.END_CLIENT_TICK.register(VoxeltracingClient::onTick);
	}

	private static void onTick(Minecraft mc) {
		VtConfig cfg = VtConfig.get();
		while (toggleKey.consumeClick()) {
			cfg.enabled = !cfg.enabled;
			cfg.save();
			if (!cfg.enabled) VoxelLighting.INSTANCE.reset();
			else VoxelLighting.INSTANCE.invalidateAll();
			message(mc, "Voxel Tracing: " + (cfg.enabled ? "ON" : "OFF"));
		}
		while (qualityKey.consumeClick()) {
			cfg.quality = cfg.quality.next();
			cfg.save();
			VoxelLighting.INSTANCE.invalidateAll();
			message(mc, "Voxel Tracing quality: " + cfg.quality.name());
		}
	}

	private static void message(Minecraft mc, String text) {
		if (mc.player != null) mc.player.displayClientMessage(Component.literal(text), true);
	}
}
