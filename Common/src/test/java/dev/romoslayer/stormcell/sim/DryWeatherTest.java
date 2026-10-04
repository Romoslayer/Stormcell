package dev.romoslayer.stormcell.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.romoslayer.stormcell.climate.BiomeClimate;
import dev.romoslayer.stormcell.config.StormcellConfig;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DryWeatherTest {
	/** Desert as Stormcell sees it with the default config: hot, very dry, never rains in vanilla, dust storm country. */
	static final BiomeClimate DESERT = FakeEnvironment.climate(2.0F, 0.05F, false, 0.05F, 0.1F, true);
	static final BiomeClimate SAVANNA = FakeEnvironment.climate(2.0F, 0.2F, false, 0.5F, 1.6F, false);
	static final BiomeClimate FOREST = FakeEnvironment.climate(0.7F, 0.8F, true, 1.0F, 1.0F);

	@TempDir
	Path configDir;

	@BeforeEach
	void defaults() {
		StormcellConfig.load(this.configDir);
	}

	@Test
	void dustIsNotRain() {
		StormcellConfig.get().generation.formationChancePerMinute = 0.0;
		FakeEnvironment environment = new FakeEnvironment(DESERT).player(0, 0);
		LevelWeather weather = new LevelWeather(environment, null, 1L);
		weather.spawnStorm(StormSystem.Kind.DUST_STORM, 0, 0, 0.8, 100000, LevelWeather.Overflow.REJECT);
		environment.run(weather, 200, true);
		assertTrue(weather.dustAt(0, 0) > 0.6F, "dust " + weather.dustAt(0, 0));
		assertEquals(0.0F, weather.stormAt(0, 0), "a dust storm is not a rain storm");
		assertEquals(0.0F, weather.precipitationAt(0, 0));
	}

	@Test
	void dustSettlesOverGreenLandButNotOverSand() {
		StormcellConfig.get().generation.formationChancePerMinute = 0.0;
		for (BiomeClimate ground : new BiomeClimate[] {FOREST, DESERT}) {
			FakeEnvironment environment = new FakeEnvironment(ground).player(0, 0);
			LevelWeather weather = new LevelWeather(environment, null, 2L);
			StormSystem storm = weather.spawnStorm(StormSystem.Kind.DUST_STORM, 0, 0, 0.8, 20 * 1200, LevelWeather.Overflow.REJECT);
			storm.anchored = false;
			environment.run(weather, 3 * 1200, true);
			boolean survives = weather.storms().contains(storm) && storm.intensity > 0.5;
			assertEquals(ground == DESERT, survives, (ground == DESERT ? "desert" : "forest") + " intensity " + storm.intensity);
		}
	}

	@Test
	void heatBuildsDryThunderstormsOverDeserts() {
		StormcellConfig.get().generation.formationChancePerMinute = 0.02;
		FakeEnvironment environment = new FakeEnvironment(DESERT).player(0, 0);
		LevelWeather weather = new LevelWeather(environment, null, 3L);
		Set<Integer> dry = new HashSet<>();
		double strongestDry = 0.0;
		for (int step = 0; step < 24 * 4; step++) {
			environment.run(weather, 500, true);
			for (StormSystem storm : weather.storms()) {
				if (storm.dry) {
					dry.add(storm.id);
					strongestDry = Math.max(strongestDry, storm.intensity);
				}
			}
			// Whatever the sky does, no rain reaches desert ground
			assertTrue(weather.precipitationAt(0, 0) < 0.05F, "rain reached the sand: " + weather.precipitationAt(0, 0));
		}
		assertFalse(dry.isEmpty(), "no dry thunderstorm formed in two days of desert heat");
		assertTrue(strongestDry >= StormcellConfig.get().thresholds.thunderstorm, "dry storms should reach thunder strength: " + strongestDry);
	}

	@Test
	void dustStormsOnlyFormOverDustStormBiomes() {
		StormcellConfig.get().generation.formationChancePerMinute = 0.05;
		StormcellConfig.get().dryWeather.dryThunderstorms = false;
		for (BiomeClimate ground : new BiomeClimate[] {DESERT, SAVANNA}) {
			FakeEnvironment environment = new FakeEnvironment(ground).player(0, 0);
			LevelWeather weather = new LevelWeather(environment, null, 4L);
			boolean dust = false;
			for (int step = 0; step < 100 && !dust; step++) {
				environment.run(weather, 500, true);
				dust = weather.storms().stream().anyMatch(storm -> storm.kind == StormSystem.Kind.DUST_STORM);
			}
			assertEquals(ground == DESERT, dust, ground == DESERT ? "desert should get dust storms" : "savanna is not in dustStormBiomes");
		}
	}

	@Test
	void aDryStormReachingMoistLandStartsRaining() {
		StormcellConfig.get().generation.formationChancePerMinute = 0.0;
		FakeEnvironment environment = new FakeEnvironment(FOREST).player(0, 0);
		LevelWeather weather = new LevelWeather(environment, null, 5L);
		environment.run(weather, 2, true);
		StormSystem storm = weather.spawnStorm(StormSystem.Kind.THUNDERSTORM, 0, 0, 0.9, 100000, LevelWeather.Overflow.REJECT);
		storm.dry = true;
		environment.run(weather, 200, true);
		assertFalse(storm.dry);
		assertTrue(weather.precipitationAt(0, 0) > 0.8F);
	}
}
