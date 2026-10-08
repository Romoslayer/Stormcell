package dev.romoslayer.stormcell.world;

import dev.romoslayer.stormcell.config.StormcellConfig;
import dev.romoslayer.stormcell.mc.Versioned;
import dev.romoslayer.stormcell.sim.LevelWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The answers the game's own code gets once Stormcell runs a dimension's weather. The mixins route vanilla's
 * world-wide "is it raining / thundering" checks through here with the position that matters.
 */
public final class RegionalWeather {
	private RegionalWeather() {
	}

	/** The weather of a level, or null if Stormcell does not run it (any client level, or an unmanaged dimension). */
	public static @Nullable LevelWeather weather(Level level) {
		return level instanceof StormcellLevel managed ? managed.stormcell$weather() : null;
	}

	/** Whether rain or snow is falling (reaching the ground) at a block column. */
	public static boolean isPrecipitating(LevelWeather weather, BlockPos pos) {
		return weather.precipitationAt(pos.getX() + 0.5, pos.getZ() + 0.5) >= StormcellConfig.get().thresholds.drizzle;
	}

	/** Whether a thunderstorm is overhead at a block column. */
	public static boolean isThundering(LevelWeather weather, BlockPos pos) {
		return weather.stormAt(pos.getX() + 0.5, pos.getZ() + 0.5) >= StormcellConfig.get().thresholds.thunderstorm;
	}

	/** Vanilla's world-wide isRaining(), answered for one position instead. Unmanaged levels keep the vanilla value. */
	public static boolean isRainingAt(Level level, BlockPos pos, boolean vanilla) {
		LevelWeather weather = weather(level);
		return weather == null ? vanilla : isPrecipitating(weather, pos);
	}

	/** Vanilla's world-wide isThundering(), answered for one position instead. */
	public static boolean isThunderingAt(Level level, BlockPos pos, boolean vanilla) {
		LevelWeather weather = weather(level);
		return weather == null ? vanilla : isThundering(weather, pos);
	}

	/** For the mob and bed tweaks, which can be switched off on their own. */
	public static boolean mobRainCheck(Level level, BlockPos pos, boolean vanilla) {
		return StormcellConfig.get().gameplay.regionalMobBehaviour ? isRainingAt(level, pos, vanilla) : vanilla;
	}

	public static boolean mobThunderCheck(Level level, BlockPos pos, boolean vanilla) {
		return StormcellConfig.get().gameplay.regionalMobBehaviour ? isThunderingAt(level, pos, vanilla) : vanilla;
	}

	/**
	 * Rain or snow at a spot, taking the optional extra height cooling into account (see
	 * elevation.extraTemperatureLossPerBlock). With the default of 0 this is exactly vanilla's answer, which is also
	 * what clients draw.
	 */
	public static Biome.Precipitation precipitationType(Biome biome, BlockPos pos, int seaLevel) {
		return Versioned.precipitationAt(biome, coolerPosition(pos, seaLevel), seaLevel);
	}

	/** True where extra height cooling (and only that) turns vanilla rain into snow. */
	public static boolean extraCoolingMakesSnow(Biome biome, BlockPos pos, int seaLevel) {
		BlockPos adjusted = coolerPosition(pos, seaLevel);
		return adjusted != pos && biome.hasPrecipitation() && Versioned.warmEnoughToRain(biome, pos, seaLevel) && Versioned.coldEnoughToSnow(biome, adjusted, seaLevel);
	}

	/** A snow layer could form on this spot (the placement half of vanilla's Biome.shouldSnow). */
	public static boolean canPlaceSnow(LevelReader level, BlockPos pos) {
		if (pos.getY() < Versioned.minY(level) || pos.getY() > Versioned.maxY(level) || level.getBrightness(LightLayer.BLOCK, pos) >= 10) {
			return false;
		}
		BlockState state = level.getBlockState(pos);
		return (state.isAir() || state.is(Blocks.SNOW)) && Blocks.SNOW.defaultBlockState().canSurvive(level, pos);
	}

	/**
	 * Vanilla cools the air by 0.05 per 40 blocks above sea level + 17. Extra cooling per block is the same as asking
	 * about a spot proportionally higher up, which keeps vanilla's own temperature noise and modded biome temperature
	 * modifiers in play.
	 */
	private static BlockPos coolerPosition(BlockPos pos, int seaLevel) {
		double extra = StormcellConfig.get().elevation.extraTemperatureLossPerBlock;
		int above = pos.getY() - seaLevel;
		if (extra <= 0.0 || above <= 0) {
			return pos;
		}
		int lift = (int) Math.min(4096, above * extra / (0.05 / 40.0));
		return lift == 0 ? pos : pos.above(lift);
	}

	/**
	 * Whether a precipitation tick (snow layer, cauldron) does anything, so a downpour builds snow faster than a
	 * drizzle. Always true when the option is off.
	 */
	public static boolean rollPrecipitationEffect(LevelWeather weather, BlockPos pos, RandomSource random) {
		StormcellConfig config = StormcellConfig.get();
		if (!config.gameplay.precipitationScalesBlockEffects) {
			return true;
		}
		double precipitation = weather.precipitationAt(pos.getX() + 0.5, pos.getZ() + 0.5);
		double span = Math.max(0.01, config.thresholds.heavyRain - config.thresholds.drizzle);
		double chance = Mth.clamp((precipitation - config.thresholds.drizzle) / span, 0.25, 1.0);
		return random.nextDouble() < chance;
	}
}
