package dev.romoslayer.stormcell.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.romoslayer.stormcell.world.BedWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A bed refused in daylight may be used during a thunderstorm overhead, as in a vanilla thunderstorm. Vanilla's
 * monster check still follows. Fabric only: NeoForge and Forge offer an event for this instead.
 */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
	@ModifyExpressionValue(method = "startSleepInBed", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isDay()Z"))
	private boolean stormcell$sleepThroughStorm(boolean day, @Local(argsOnly = true) BlockPos pos) {
		return day && !BedWeather.stormLetsSleep(((ServerPlayer) (Object) this).level(), pos);
	}
}
