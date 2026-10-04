package dev.romoslayer.stormcell.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.romoslayer.stormcell.config.StormcellConfig;
import org.junit.jupiter.api.Test;

class PlayerWeatherSyncTest {
	@Test
	void fastTravelIsNotATeleport() {
		PlayerWeatherSync.State state = new PlayerWeatherSync.State();
		state.moveTo(0, 0);
		// Elytra-fast: 30 blocks a tick for 10 seconds, far more than 96 blocks in total
		for (int tick = 1; tick <= 200; tick++) {
			assertFalse(state.moveTo(tick * 30.0, 0), "tick " + tick);
		}
	}

	@Test
	void aSingleTickJumpIsATeleport() {
		PlayerWeatherSync.State state = new PlayerWeatherSync.State();
		state.moveTo(0, 0);
		assertTrue(state.moveTo(500, 0));
		assertFalse(state.moveTo(510, 0), "only the jump itself counts");
	}

	@Test
	void easingMovesAtTheConfiguredRate() {
		PlayerWeatherSync.State state = new PlayerWeatherSync.State();
		state.target(1.0F, 0.5F);
		for (int i = 0; i < 100; i++) {
			state.ease(0.005F);
		}
		assertEquals(0.5F, state.rain, 1.0E-4F);
		assertEquals(0.5F, state.thunder, 1.0E-4F);
		state.snap();
		assertEquals(1.0F, state.rain);
	}

	@Test
	void clientsShowRainExactlyWhereTheServerSaysItRains() {
		StormcellConfig config = new StormcellConfig();
		double drizzle = config.thresholds.drizzle;
		for (int i = 0; i <= 1000; i++) {
			float precipitation = i / 1000.0F;
			boolean serverRain = precipitation >= drizzle;
			boolean clientRain = PlayerWeatherSync.clientRain(config, precipitation) > 0.0F;
			assertEquals(serverRain, clientRain, "at " + precipitation);
		}
	}

	@Test
	void dryBiomesGetADarkSkyButNoRain() {
		StormcellConfig config = new StormcellConfig();
		// A thunderstorm overhead in a desert: no rain reaches the ground, the client draws none, the sky still darkens
		PlayerWeatherSync.Targets desert = PlayerWeatherSync.targets(config, 0.0F, 0.9F, 0.0F, false);
		assertTrue(desert.rain() > 0.5F && desert.rain() <= config.dryWeather.drySkyDarkness + 1.0E-6, "sky " + desert.rain());
		assertTrue(desert.thunder() > 0.0F);
		// A savanna storm the simulation thinks is raining still only darkens the sky, at the configured darkness
		assertEquals(config.dryWeather.drySkyDarkness, PlayerWeatherSync.targets(config, 0.95F, 0.95F, 0.0F, false).rain(), 1.0E-6);
		// The same storm with no rain reaching the ground in a biome where the client WOULD draw rain must show none
		assertEquals(0.0F, PlayerWeatherSync.targets(config, 0.0F, 0.9F, 0.0F, true).rain());
	}

	@Test
	void dustDarkensTheSkyAboveItsThreshold() {
		StormcellConfig config = new StormcellConfig();
		float below = PlayerWeatherSync.targets(config, 0.0F, 0.0F, (float) config.dryWeather.dustThreshold - 0.01F, false).rain();
		float thick = PlayerWeatherSync.targets(config, 0.0F, 0.0F, 1.0F, false).rain();
		assertEquals(0.0F, below);
		assertEquals(config.dryWeather.dustSkyDarkness, thick, 1.0E-6);
		config.dryWeather.dustStorms = false;
		assertEquals(0.0F, PlayerWeatherSync.targets(config, 0.0F, 0.0F, 1.0F, false).rain());
	}

	@Test
	void clientLevelsRiseWithIntensity() {
		StormcellConfig config = new StormcellConfig();
		float previousRain = 0.0F;
		float previousThunder = 0.0F;
		for (int i = 0; i <= 100; i++) {
			float value = i / 100.0F;
			float rain = PlayerWeatherSync.clientRain(config, value);
			float thunder = PlayerWeatherSync.clientThunder(config, value);
			assertTrue(rain >= previousRain && thunder >= previousThunder, "not monotonic at " + value);
			assertTrue(rain <= 1.0F && thunder <= 1.0F);
			previousRain = rain;
			previousThunder = thunder;
		}
		assertEquals(0.0F, PlayerWeatherSync.clientThunder(config, (float) config.thresholds.heavyRain));
		assertEquals(1.0F, PlayerWeatherSync.clientThunder(config, 1.0F), 1.0E-6F);
	}
}
