package dev.romoslayer.stormcell.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.romoslayer.stormcell.world.RegionalWeather;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Foxes run for shelter from a thunderstorm that is actually over them. The goal reaches its fox through a synthetic
 * outer-class field that has no stable name in obfuscated versions, so the fox is caught from the Fox.level() call
 * that comes just before the thunder check.
 */
@Mixin(targets = "net.minecraft.world.entity.animal.Fox$SeekShelterGoal")
abstract class FoxSeekShelterGoalMixin {
	@WrapOperation(method = "canUse", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/Fox;level()Lnet/minecraft/world/level/Level;"))
	private Level stormcell$rememberFox(Fox fox, Operation<Level> original, @Share("fox") LocalRef<Fox> shared) {
		shared.set(fox);
		return original.call(fox);
	}

	@ModifyExpressionValue(method = "canUse", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isThundering()Z"))
	private boolean stormcell$thunderHere(boolean thundering, @Share("fox") LocalRef<Fox> shared) {
		Fox fox = shared.get();
		return fox == null ? thundering : RegionalWeather.mobThunderCheck(fox.level(), fox.blockPosition(), thundering);
	}
}
