package dev.romoslayer.stormcell.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.romoslayer.stormcell.world.RegionalWeather;
import net.minecraft.world.entity.animal.bee.Bee;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Bees stop pollinating when it rains where they are, not when it rains somewhere in the world. */
@Mixin(targets = "net.minecraft.world.entity.animal.bee.Bee$BeePollinateGoal")
abstract class BeePollinateGoalMixin {
	@Shadow
	@Final
	Bee this$0;

	@ModifyExpressionValue(method = {"canBeeUse", "canBeeContinueToUse"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isRaining()Z"))
	private boolean stormcell$rainingHere(boolean raining) {
		return RegionalWeather.mobRainCheck(this.this$0.level(), this.this$0.blockPosition(), raining);
	}
}
