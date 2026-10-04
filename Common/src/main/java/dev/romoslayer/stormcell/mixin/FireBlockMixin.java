package dev.romoslayer.stormcell.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.romoslayer.stormcell.world.RegionalWeather;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.FireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Fire checks "is it raining anywhere" before looking for rain right next to it. In a managed dimension the second,
 * local check (which already follows Stormcell's weather) is all that matters, so rain puts out fires only where it
 * actually falls.
 */
@Mixin(FireBlock.class)
abstract class FireBlockMixin {
	@ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;isRaining()Z"))
	private boolean stormcell$checkLocally(boolean raining, @Local(argsOnly = true) ServerLevel level) {
		return RegionalWeather.weather(level) != null || raining;
	}
}
