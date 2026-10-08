package dev.romoslayer.stormcell.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.romoslayer.stormcell.world.RegionalWeather;
import net.minecraft.world.entity.animal.fox.Fox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Sleeping foxes wake up when a thunderstorm is overhead, not when there is one somewhere in the world. */
@Mixin(Fox.class)
abstract class FoxMixin {
	@ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isThundering()Z"))
	private boolean stormcell$thunderHere(boolean thundering) {
		Fox fox = (Fox) (Object) this;
		return RegionalWeather.mobThunderCheck(fox.level(), fox.blockPosition(), thundering);
	}
}
