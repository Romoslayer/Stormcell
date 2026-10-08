package dev.romoslayer.stormcell.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.romoslayer.stormcell.world.RegionalWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Hives keep their bees in while it rains at the hive. */
@Mixin(BeehiveBlockEntity.class)
abstract class BeehiveBlockEntityMixin {
	@ModifyExpressionValue(method = "releaseOccupant", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isRaining()Z"))
	private static boolean stormcell$keepInWhileRaining(boolean raining, @Local(argsOnly = true) Level level, @Local(argsOnly = true, ordinal = 0) BlockPos pos) {
		return RegionalWeather.mobRainCheck(level, pos, raining);
	}
}
