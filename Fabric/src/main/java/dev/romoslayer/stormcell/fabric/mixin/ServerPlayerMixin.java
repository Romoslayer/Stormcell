package dev.romoslayer.stormcell.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.romoslayer.stormcell.world.BedWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A bed that may be used "when dark" may be used during a thunderstorm overhead, as in a vanilla thunderstorm.
 * Fabric only: NeoForge moves this check into a lambda and offers CanPlayerSleepEvent instead (see StormcellNeoForge).
 */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
	@WrapOperation(method = "startSleepInBed", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/attribute/BedRule;canSleep(Lnet/minecraft/world/level/Level;)Z"))
	private boolean stormcell$sleepThroughStorm(BedRule rule, Level level, Operation<Boolean> original, @Local(argsOnly = true) BlockPos pos) {
		return original.call(rule, level) || BedWeather.stormLetsSleep(rule, level, pos);
	}
}
