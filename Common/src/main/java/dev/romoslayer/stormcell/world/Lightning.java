package dev.romoslayer.stormcell.world;

import dev.romoslayer.stormcell.config.StormcellConfig;
import dev.romoslayer.stormcell.mc.Versioned;
import dev.romoslayer.stormcell.mixin.ServerLevelInvoker;
import dev.romoslayer.stormcell.sim.LevelWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Lightning under thunderstorms only. Replaces vanilla's per-chunk thunder tick: the same lightning rods, the same
 * skeleton horse traps, but the chance comes from the storm over that chunk instead of a world-wide flag. In biomes
 * where it never rains (deserts, savannas, badlands) a storm strikes without rain: dry lightning, which by default only
 * flashes and thunders (dryWeather.dryLightningStartsFires).
 */
public final class Lightning {
	/** Vanilla strikes a thundering chunk with a 1 in 100,000 chance per tick. */
	private static final double VANILLA_CHANCE = 1.0 / 100000.0;

	private Lightning() {
	}

	public static void tickChunk(ServerLevel level, LevelWeather weather, LevelChunk chunk) {
		StormcellConfig.Lightning config = StormcellConfig.get().lightning;
		if (!config.localizedLightning || config.lightningFrequencyMultiplier <= 0.0) {
			return;
		}
		ChunkPos chunkPos = chunk.getPos();
		int minX = chunkPos.getMinBlockX();
		int minZ = chunkPos.getMinBlockZ();
		double storm = weather.stormAt(minX + 8.0, minZ + 8.0);
		double threshold = config.minimumLightningStormIntensity;
		if (storm < threshold) {
			return;
		}
		double strength = threshold >= 1.0 ? 1.0 : Mth.clamp((storm - threshold) / (1.0 - threshold), 0.0, 1.0);
		double rate = config.lightningRateAtMinimum + (config.lightningRateAtMaximum - config.lightningRateAtMinimum) * strength;
		if (level.getRandom().nextDouble() >= VANILLA_CHANCE * rate * config.lightningFrequencyMultiplier) {
			return;
		}
		BlockPos pos = ((ServerLevelInvoker) level).stormcell$findLightningTargetAround(level.getBlockRandomPos(minX, 0, minZ, 15));
		boolean dry = false;
		if (!level.isRainingAt(pos)) {
			// Dry lightning: where clients never draw rain (deserts, savannas, badlands) a storm can still strike
			StormcellConfig.DryWeather dryWeather = StormcellConfig.get().dryWeather;
			if (!dryWeather.dryThunderstorms || level.getBiome(pos).value().hasPrecipitation() || !level.canSeeSky(pos)) {
				return;
			}
			dry = true;
		}
		DifficultyInstance difficulty = level.getCurrentDifficultyAt(pos);
		boolean isTrap = config.skeletonTraps && !dry
				&& Versioned.spawnsMobs(level)
				&& level.getRandom().nextDouble() < difficulty.getEffectiveDifficulty() * 0.01
				&& !Versioned.isLightningRod(level.getBlockState(pos.below()));
		if (isTrap) {
			Versioned.spawnSkeletonTrap(level, pos);
		}
		// Dry lightning only flashes and thunders unless fires are allowed (they would burn savannas down)
		Versioned.strikeLightning(level, pos, isTrap || dry && !StormcellConfig.get().dryWeather.dryLightningStartsFires);
	}
}
