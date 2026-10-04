package dev.romoslayer.stormcell.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.romoslayer.stormcell.world.BedWeather;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps a player who went to bed during a thunderstorm from being woken straight back up by the daylight check. */
@Mixin(Player.class)
abstract class PlayerMixin {
	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/attribute/BedRule;canSleep(Lnet/minecraft/world/level/Level;)Z"))
	private boolean stormcell$stayAsleepInStorm(BedRule rule, Level level, Operation<Boolean> original) {
		return original.call(rule, level) || BedWeather.stormLetsSleep(rule, level, ((Player) (Object) this).blockPosition());
	}
}
