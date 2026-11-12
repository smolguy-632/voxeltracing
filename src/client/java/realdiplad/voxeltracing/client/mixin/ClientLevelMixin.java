package realdiplad.voxeltracing.client.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import realdiplad.voxeltracing.client.VoxelLighting;

/**
 * The single hook the lighting system needs: "a block changed here".
 * ClientLevel.sendBlockUpdated fires for both local edits and server block-update packets,
 * and (unlike LevelRenderer) isn't replaced by rendering-optimisation mods.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void voxeltracing$onBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
		VoxelLighting.INSTANCE.onBlockChanged((ClientLevel) (Object) this, pos, oldState, newState);
	}
}
