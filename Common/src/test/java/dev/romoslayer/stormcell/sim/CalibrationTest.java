package dev.romoslayer.stormcell.sim;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.romoslayer.stormcell.climate.BiomeClimate;
import dev.romoslayer.stormcell.config.StormcellConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Long, seeded runs of the simulation over uniform worlds, measuring how often it rains where a player stands. Run with
 * {@code gradlew :Common:calibrate}; prints a table. The assertions only check the broad shape (wet biomes wetter than
 * dry ones, results not swinging with the simulation interval), so retuning defaults does not break the build.
 *
 * <p>Vanilla reference: rain about 15.8% of the time (clear spells average 96,000 ticks, rain spells 18,000), and a
 * thunderstorm about 1.4% of the time (thunder is a separate timer that only shows while it also rains).
 */
@Tag("calibration")
class CalibrationTest {
	private static final int DAYS = 30;
	private static final int TICKS_PER_DAY = 24000;

	@TempDir
	Path configDir;

	@BeforeEach
	void defaults() {
		StormcellConfig.load(this.configDir);
	}

	/** The climate Stormcell derives for these vanilla biomes with the default config (see BiomeClimate). */
	enum Biome {
		PLAINS(0.8F, 0.4F, true, 1.0F, 1.0F),
		FOREST(0.7F, 0.8F, true, 1.0F, 1.0F),
		JUNGLE(0.95F, 0.99F, true, 1.5F, 1.5F),
		SWAMP(0.8F, 0.99F, true, 1.3F, 1.1F),
		SNOWY_PLAINS(0.0F, 0.5F, true, 1.0F, 1.0F),
		OCEAN(0.5F, 0.65F, true, 1.0F, 1.0F),
		SAVANNA(2.0F, 0.2F, false, 0.5F, 1.6F),
		BADLANDS(2.0F, 0.1F, false, 0.15F, 0.4F, true),
		DESERT(2.0F, 0.05F, false, 0.05F, 0.1F, true);

		final BiomeClimate climate;

		Biome(float temperature, float humidity, boolean precipitation, float rain, float storm) {
			this.climate = FakeEnvironment.climate(temperature, humidity, precipitation, rain, storm);
		}

		Biome(float temperature, float humidity, boolean precipitation, float rain, float storm, boolean dusty) {
			this.climate = FakeEnvironment.climate(temperature, humidity, precipitation, rain, storm, dusty);
		}
	}

	record Result(double rain, double thunder, double storms, double meanRainSpellMinutes, double dryThunder, double dust, double meanDustSpellMinutes) {
		@Override
		public String toString() {
			return String.format(Locale.ROOT, "rain %5.1f%%  thunder %5.1f%%  storms %5.1f  rain spell %5.1f min | dry thunder %4.1f%%  dust %4.1f%%  dust spell %4.1f min",
					this.rain * 100, this.thunder * 100, this.storms, this.meanRainSpellMinutes, this.dryThunder * 100, this.dust * 100, this.meanDustSpellMinutes);
		}
	}

	/** Runs one world for {@link #DAYS} days and measures the weather at the first player. */
	static Result run(Biome biome, long seed, int players, int interval) {
		StormcellConfig.get().general.simulationIntervalTicks = interval;
		FakeEnvironment environment = new FakeEnvironment(biome.climate);
		for (int i = 0; i < players; i++) {
			environment.player(i * 20_000.0, 0.0);
		}
		LevelWeather weather = new LevelWeather(environment, null, seed);
		StormcellConfig config = StormcellConfig.get();
		// Let the weather settle in for a day first
		environment.run(weather, TICKS_PER_DAY, true);
		int samples = 0;
		int rainy = 0;
		int thundery = 0;
		long storms = 0;
		int spells = 0;
		boolean wasRaining = false;
		int dryThundery = 0;
		int dusty = 0;
		int dustSpells = 0;
		boolean wasDusty = false;
		for (int tick = 0; tick < DAYS * TICKS_PER_DAY; tick += 100) {
			environment.run(weather, 100, true);
			samples++;
			boolean raining = weather.precipitationAt(0.0, 0.0) >= config.thresholds.drizzle;
			rainy += raining ? 1 : 0;
			thundery += weather.stormAt(0.0, 0.0) >= config.thresholds.thunderstorm ? 1 : 0;
			dryThundery += weather.stormAt(0.0, 0.0) >= config.thresholds.thunderstorm && !raining ? 1 : 0;
			boolean dust = weather.dustAt(0.0, 0.0) >= config.dryWeather.dustThreshold;
			dusty += dust ? 1 : 0;
			dustSpells += dust && !wasDusty ? 1 : 0;
			wasDusty = dust;
			storms += weather.storms().size();
			if (raining && !wasRaining) {
				spells++;
			}
			wasRaining = raining;
		}
		double rainMinutes = rainy * 100.0 / 1200.0;
		return new Result((double) rainy / samples, (double) thundery / samples, (double) storms / samples, spells == 0 ? 0.0 : rainMinutes / spells,
				(double) dryThundery / samples, (double) dusty / samples, dustSpells == 0 ? 0.0 : dusty * 100.0 / 1200.0 / dustSpells);
	}

	static Result average(Biome biome, int players, int interval, long... seeds) {
		double rain = 0;
		double thunder = 0;
		double storms = 0;
		double spell = 0;
		double dryThunder = 0;
		double dust = 0;
		double dustSpell = 0;
		for (long seed : seeds) {
			Result result = run(biome, seed, players, interval);
			rain += result.rain();
			thunder += result.thunder();
			storms += result.storms();
			spell += result.meanRainSpellMinutes();
			dryThunder += result.dryThunder();
			dust += result.dust();
			dustSpell += result.meanDustSpellMinutes();
		}
		int n = seeds.length;
		return new Result(rain / n, thunder / n, storms / n, spell / n, dryThunder / n, dust / n, dustSpell / n);
	}

	@Test
	void biomesDifferInTheRightDirection() {
		long[] seeds = {1, 2, 3, 4, 5, 6};
		List<String> table = new ArrayList<>();
		Result plains = null;
		Result jungle = null;
		Result desert = null;
		Result savanna = null;
		for (Biome biome : Biome.values()) {
			Result result = average(biome, 1, 100, seeds);
			table.add(String.format(Locale.ROOT, "%-13s %s", biome, result));
			switch (biome) {
				case PLAINS -> plains = result;
				case JUNGLE -> jungle = result;
				case DESERT -> desert = result;
				case SAVANNA -> savanna = result;
				default -> {
				}
			}
		}
		System.out.println("Calibration over " + DAYS + " days x " + seeds.length + " seeds, one player:\n" + String.join("\n", table));
		assertTrue(jungle.rain() > plains.rain(), "jungle should be wetter than plains");
		assertTrue(plains.rain() > savanna.rain(), "plains should be wetter than savanna");
		assertTrue(savanna.rain() >= desert.rain(), "savanna should be at least as wet as desert");
		assertTrue(desert.rain() < 0.03, "deserts should hardly ever get rain on the ground");
		assertTrue(savanna.thunder() > 0.0 || savanna.storms() > 0.0, "savannas should still get storms now and then");
	}

	@Test
	void resultsDoNotDependOnTheSimulationInterval() {
		long[] seeds = {11, 12, 13, 14, 15, 16};
		List<String> table = new ArrayList<>();
		List<Double> rain = new ArrayList<>();
		for (int interval : new int[] {50, 100, 200}) {
			Result result = average(Biome.PLAINS, 1, interval, seeds);
			table.add(String.format(Locale.ROOT, "interval %3d  %s", interval, result));
			rain.add(result.rain());
		}
		System.out.println("Plains, by simulation interval:\n" + String.join("\n", table));
		double min = rain.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
		double max = rain.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
		assertTrue(max - min < 0.08, "rain share should not swing with the interval: " + rain);
	}

	@Test
	void moreSpreadOutPlayersMeanMoreStormsButTheSameLocalWeather() {
		long[] seeds = {21, 22, 23, 24};
		List<String> table = new ArrayList<>();
		List<Double> rain = new ArrayList<>();
		for (int players : new int[] {1, 4, 16}) {
			Result result = average(Biome.PLAINS, players, 100, seeds);
			table.add(String.format(Locale.ROOT, "%2d players  %s", players, result));
			rain.add(result.rain());
		}
		System.out.println("Plains, by number of spread-out players:\n" + String.join("\n", table));
		double min = rain.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
		double max = rain.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
		assertTrue(max - min < 0.08, "one player's weather should not depend on how many others are online: " + rain);
	}
}
