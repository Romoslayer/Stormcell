package dev.romoslayer.stormcell.mixin;

import dev.romoslayer.stormcell.Stormcell;
import dev.romoslayer.stormcell.sim.LevelWeather;
import dev.romoslayer.stormcell.world.Lightning;
import dev.romoslayer.stormcell.world.RegionalWeather;
import dev.romoslayer.stormcell.world.StormcellLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
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
 *
 * <p>1.20.x does lightning, snow and cauldron filling inline in tickChunk, all behind the world-wide rain flag read once
 * at its start. That flag stays off in a managed dimension, so vanilla never does them there; the local versions run at
 * the start of tickChunk instead, precipitation at vanilla's rate (one random column in 16 chunk ticks) and as vanilla
 * does it, only gated on the weather over that column.
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

	@Inject(method = "tickChunk", at = @At("HEAD"))
	private void stormcell$localWeather(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
		LevelWeather weather = this.stormcell$weather;
		if (weather == null) {
			return;
		}
		ServerLevel level = (ServerLevel) (Object) this;
		Lightning.tickChunk(level, weather, chunk);
		RandomSource random = level.getRandom();
		if (random.nextInt(16) == 0) {
			ChunkPos chunkPos = chunk.getPos();
			stormcell$precipitate(level, weather, level.getBlockRandomPos(chunkPos.getMinBlockX(), 0, chunkPos.getMinBlockZ(), 15), random);
		}
	}

	/** Vanilla 1.20.1's snow and cauldron step for one column, gated on the local precipitation over it. */
	@Unique
	private static void stormcell$precipitate(ServerLevel level, LevelWeather weather, BlockPos column, RandomSource random) {
		if (!RegionalWeather.isPrecipitating(weather, column) || !RegionalWeather.rollPrecipitationEffect(weather, column, random)) {
			return;
		}
		BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, column);
		BlockPos below = top.below();
		Biome biome = level.getBiome(top).value();
		int seaLevel = level.getSeaLevel();
		int maxLayers = level.getGameRules().getInt(GameRules.RULE_SNOW_ACCUMULATION_HEIGHT);
		boolean snow = biome.shouldSnow(level, top)
				|| RegionalWeather.extraCoolingMakesSnow(biome, top, seaLevel) && RegionalWeather.canPlaceSnow(level, top);
		if (maxLayers > 0 && snow) {
			BlockState state = level.getBlockState(top);
			if (state.is(Blocks.SNOW)) {
				int layers = state.getValue(SnowLayerBlock.LAYERS);
				if (layers < Math.min(maxLayers, 8)) {
					BlockState thicker = state.setValue(SnowLayerBlock.LAYERS, layers + 1);
					Block.pushEntitiesUp(state, thicker, level, top);
					level.setBlockAndUpdate(top, thicker);
				}
			} else {
				level.setBlockAndUpdate(top, Blocks.SNOW.defaultBlockState());
			}
		}
		Biome.Precipitation precipitation = RegionalWeather.precipitationType(biome, below, seaLevel);
		if (precipitation != Biome.Precipitation.NONE) {
			BlockState state = level.getBlockState(below);
			state.getBlock().handlePrecipitation(state, level, below, precipitation);
		}
	}
}
