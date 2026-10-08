package dev.romoslayer.stormcell.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.romoslayer.stormcell.sim.LevelWeather;
import dev.romoslayer.stormcell.world.RegionalWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Level.isRainingAt asks the world-wide rain flag first. In a dimension Stormcell runs, it
 * asks whether rain is falling at that spot instead. Everything that cares whether a particular block or mob is out in
 * the rain goes through here: wet mobs, endermen, fire, farmland, fishing, tridents, lightning.
 */
@Mixin(Level.class)
abstract class LevelMixin {
	@ModifyExpressionValue(method = "isRainingAt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isRaining()Z"))
	private boolean stormcell$rainingHere(boolean raining, @Local(argsOnly = true) BlockPos pos) {
		LevelWeather weather = RegionalWeather.weather((Level) (Object) this);
		return weather == null ? raining : RegionalWeather.isPrecipitating(weather, pos);
	}

	@WrapOperation(method = "isRainingAt", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/biome/Biome;getPrecipitationAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome$Precipitation;"))
	private Biome.Precipitation stormcell$precipitationType(Biome biome, BlockPos pos, Operation<Biome.Precipitation> original) {
		Level level = (Level) (Object) this;
		if (RegionalWeather.weather(level) == null) {
			return original.call(biome, pos);
		}
		return RegionalWeather.precipitationType(biome, pos, level.getSeaLevel());
	}
}
