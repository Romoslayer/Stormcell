package dev.romoslayer.stormcell.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.romoslayer.stormcell.world.RegionalWeather;
import net.minecraft.world.entity.animal.fox.Fox;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Foxes run for shelter from a thunderstorm that is actually over them. */
@Mixin(targets = "net.minecraft.world.entity.animal.fox.Fox$SeekShelterGoal")
abstract class FoxSeekShelterGoalMixin {
	@Shadow
	@Final
	Fox this$0;

	@ModifyExpressionValue(method = "canUse", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isThundering()Z"))
	private boolean stormcell$thunderHere(boolean thundering) {
		return RegionalWeather.mobThunderCheck(this.this$0.level(), this.this$0.blockPosition(), thundering);
	}
}
