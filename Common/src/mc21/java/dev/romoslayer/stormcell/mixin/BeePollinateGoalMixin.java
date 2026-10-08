package dev.romoslayer.stormcell.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.romoslayer.stormcell.world.RegionalWeather;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Bees stop pollinating when it rains where they are, not when it rains somewhere in the world. The goal reaches its
 * bee through a synthetic outer-class field that has no stable name in obfuscated versions, so the bee is caught from
 * the Bee.level() call that comes just before the rain check.
 */
@Mixin(targets = "net.minecraft.world.entity.animal.Bee$BeePollinateGoal")
abstract class BeePollinateGoalMixin {
	@WrapOperation(method = {"canBeeUse", "canBeeContinueToUse"}, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/animal/Bee;level()Lnet/minecraft/world/level/Level;"))
	private Level stormcell$rememberBee(Bee bee, Operation<Level> original, @Share("bee") LocalRef<Bee> shared) {
		shared.set(bee);
		return original.call(bee);
	}

	@ModifyExpressionValue(method = {"canBeeUse", "canBeeContinueToUse"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isRaining()Z"))
	private boolean stormcell$rainingHere(boolean raining, @Share("bee") LocalRef<Bee> shared) {
		Bee bee = shared.get();
		return bee == null ? raining : RegionalWeather.mobRainCheck(bee.level(), bee.blockPosition(), raining);
	}
}
