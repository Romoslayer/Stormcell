package dev.romoslayer.stormcell.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.romoslayer.stormcell.Stormcell;
import dev.romoslayer.stormcell.sim.LevelWeather;
import dev.romoslayer.stormcell.world.Lightning;
import dev.romoslayer.stormcell.world.RegionalWeather;
import dev.romoslayer.stormcell.world.StormcellLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Takes the weather of a managed dimension away from vanilla: the world-wide weather cycle is switched off (vanilla
 * would otherwise tell every player it is raining), lightning follows thunderstorms, and snow and cauldron filling
 * follow the local precipitation.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin implements StormcellLevel {
	@Unique
	private @Nullable LevelWeather stormcell$weather;

	@Override
	public @Nullable LevelWeather stormcell$weather() {
		return this.stormcell$weather;
	}

	@Override
	public void stormcell$setWeather(@Nullable LevelWeather weather) {
		this.stormcell$weather = weather;
	}

	@Inject(method = "advanceWeatherCycle", at = @At("HEAD"), cancellable = true)
	private void stormcell$replaceWeatherCycle(CallbackInfo ci) {
		if (this.stormcell$weather != null) {
			ServerLevel self = (ServerLevel) (Object) this;
			// Keep the world-wide levels at zero even if something else sets them; players get their local weather
			if (self.getRainLevel(1.0F) != 0.0F) {
				self.setRainLevel(0.0F);
			}
			if (self.getThunderLevel(1.0F) != 0.0F) {
				self.setThunderLevel(0.0F);
			}
			ci.cancel();
		}
	}

	/** Players slept through the night: vanilla would end its world-wide rain here (called only from that path). */
	@Inject(method = "wakeUpAllPlayers", at = @At("HEAD"))
	private void stormcell$sleptThroughNight(CallbackInfo ci) {
		if (this.stormcell$weather != null) {
			Stormcell.onNightSkipped((ServerLevel) (Object) this);
		}
	}

	@Inject(method = "tickThunder", at = @At("HEAD"), cancellable = true)
	private void stormcell$localLightning(LevelChunk chunk, CallbackInfo ci) {
		if (this.stormcell$weather != null) {
			Lightning.tickChunk((ServerLevel) (Object) this, this.stormcell$weather, chunk);
			ci.cancel();
		}
	}

	@ModifyExpressionValue(method = "tickPrecipitation", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;isRaining()Z"))
	private boolean stormcell$precipitationHere(boolean raining, @Local(argsOnly = true) BlockPos pos) {
		LevelWeather weather = this.stormcell$weather;
		if (weather == null) {
			return raining;
		}
		return RegionalWeather.isPrecipitating(weather, pos)
				&& RegionalWeather.rollPrecipitationEffect(weather, pos, ((ServerLevel) (Object) this).getRandom());
	}

	@WrapOperation(method = "tickPrecipitation", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/biome/Biome;shouldSnow(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"))
	private boolean stormcell$snowWithHeight(Biome biome, LevelReader level, BlockPos pos, Operation<Boolean> original) {
		boolean snow = original.call(biome, level, pos);
		if (snow || this.stormcell$weather == null) {
			return snow;
		}
		int seaLevel = ((ServerLevel) (Object) this).getSeaLevel();
		return RegionalWeather.extraCoolingMakesSnow(biome, pos, seaLevel) && RegionalWeather.canPlaceSnow(level, pos);
	}

	@WrapOperation(method = "tickPrecipitation", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/biome/Biome;getPrecipitationAt(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/biome/Biome$Precipitation;"))
	private Biome.Precipitation stormcell$precipitationType(Biome biome, BlockPos pos, int seaLevel, Operation<Biome.Precipitation> original) {
		if (this.stormcell$weather == null) {
			return original.call(biome, pos, seaLevel);
		}
		return RegionalWeather.precipitationType(biome, pos, seaLevel);
	}
}
