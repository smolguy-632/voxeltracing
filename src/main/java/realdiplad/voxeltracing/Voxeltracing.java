package realdiplad.voxeltracing;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.ResourceLocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. Voxel Tracing is purely client-side, so all real work lives in
 * the client source set ({@code realdiplad.voxeltracing.client}).
 */
public class Voxeltracing implements ModInitializer {
	public static final String MOD_ID = "voxeltracing";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Voxel Tracing loaded (client-side voxel ray-traced lighting).");
	}

	public static ResourceLocation id(String path) {
		return new ResourceLocation(MOD_ID, path);
	}
}
