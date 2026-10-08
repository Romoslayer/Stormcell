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
	@ModifyExpressionValue(method = "releaseOccupant", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/attribute/EnvironmentAttributeSystem;getValue(Lnet/minecraft/world/attribute/EnvironmentAttribute;Lnet/minecraft/core/BlockPos;)Ljava/lang/Object;"))
	private static Object stormcell$keepInWhileRaining(Object stayInHive, @Local(argsOnly = true) Level level, @Local(argsOnly = true, ordinal = 0) BlockPos pos) {
		if (Boolean.TRUE.equals(stayInHive)) {
			return stayInHive;
		}
		return RegionalWeather.mobRainCheck(level, pos, false) ? Boolean.TRUE : stayInHive;
	}
}
