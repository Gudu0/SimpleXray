package com.gudu0.simplexray.client.mixin;

import com.gudu0.simplexray.client.XrayBlockCache;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public class WorldSetBlockStateMixin {

    // Targets the 4-arg overload because it's the common funnel all other overloads delegate
    // to — one injection point covers every code path that changes a block state.
    @Inject(
            method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
            at = @At("RETURN") // inject at RETURN so cir.getReturnValue() is available
    )
    private void xray$onSetBlockState(BlockPos pos, BlockState state, int flags, int maxUpdateDepth, CallbackInfoReturnable<Boolean> cir) {
        // The cast via Object is required because inside a @Mixin(World.class) body, `this`
        // has type World and Java won't let you directly test it against ClientWorld — the
        // intermediate Object cast sidesteps the compiler's type-narrowing restriction.
        //noinspection ConstantValue
        if ((Object) this instanceof ClientWorld && cir.getReturnValue()) {
            // Only patch when setBlockState actually changed something (return value = true).
            // A no-op set (same state placed again) still fires the event but shouldn't
            // invalidate the cache.
            XrayBlockCache.onBlockChanged(pos, state.getBlock());
        }
    }
}