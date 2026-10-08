package dev.romoslayer.stormcell.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.romoslayer.stormcell.config.StormcellConfig;
import dev.romoslayer.stormcell.persist.WeatherState;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LevelWeatherTest {
	@TempDir
	Path configDir;

	@BeforeEach
	void defaults() {
		StormcellConfig.load(this.configDir);
		// No natural storms unless a test wants them, so results depend only on what the test does
		StormcellConfig.get().generation.formationChancePerMinute = 0.0;
	}

	private static LevelWeather weather(FakeEnvironment environment) {
		return new LevelWeather(environment, null, 42L);
	}

	@Test
	void commandStormsRespectTheLimit() {
		StormcellConfig.get().performance.maximumStormSystems = 3;
		FakeEnvironment environment = new FakeEnvironment(FakeEnvironment.plains()).player(0, 0);
		LevelWeather weather = weather(environment);
		for (int i = 0; i < 3; i++) {
			assertNotNull(weather.spawnStorm(StormSystem.Kind.SHOWER, i * 2000, 0, 0.5, 1000, LevelWeather.Overflow.REJECT));
		}
		assertNull(weather.spawnStorm(StormSystem.Kind.SHOWER, 0, 0, 0.5, 1000, LevelWeather.Overflow.REJECT));
		assertEquals(3, weather.storms().size());

		StormSystem strong = weather.spawnStorm(StormSystem.Kind.THUNDERSTORM, 0, 0, 0.95, 1000, LevelWeather.Overflow.REPLACE_WEAKEST);
		assertNotNull(strong);
		assertEquals(3, weather.storms().size(), "replacing keeps the count at the limit");
		assertTrue(weather.storms().contains(strong));
	}

	@Test
	void reloadKeepsMoistureHistory() {
		FakeEnvironment environment = new FakeEnvironment(FakeEnvironment.plains()).player(128, 128);
		LevelWeather weather = weather(environment);
		weather.spawnStorm(StormSystem.Kind.RAIN_SYSTEM, 128, 128, 0.8, 20 * 1200, LevelWeather.Overflow.REJECT);
		environment.run(weather, 20 * 60 * 10, true);
		float before = weather.localWeather(128, 128).humidity();
		weather.clearStorms(0, 0, -1);
		weather.configReloaded();
		environment.run(weather, 2, true);
		float after = weather.localWeather(128, 128).humidity();
		// The rained-out air must still be dry after the reload (a reset would jump back to the biome's 0.4)
		assertTrue(before < 0.35F, "rain should have dried the air: " + before);
		assertEquals(before, after, 0.05F, "humidity jumped across a reload");
	}

	@Test
	void creationWorkIsBoundedPerTick() {
		StormcellConfig.get().performance.maxNewCellsPerTick = 3;
		FakeEnvironment environment = new FakeEnvironment(FakeEnvironment.plains());
		for (int i = 0; i < 40; i++) {
			environment.player(i * 5000.0, -i * 7000.0);
		}
		LevelWeather weather = weather(environment);
		weather.spawnStorm(StormSystem.Kind.RAIN_SYSTEM, 777_000, 777_000, 0.8, 100000, LevelWeather.Overflow.REJECT);
		int samples = StormcellConfig.get().general.cellResolution * StormcellConfig.get().general.cellResolution;
		for (int tick = 0; tick < 300; tick++) {
			int climateBefore = environment.climateLookups;
			int terrainBefore = environment.terrainLookups;
			weather.tick(true);
			// Gameplay asks about many far-flung spots in the same tick
			for (int q = 0; q < 50; q++) {
				weather.precipitationAt(q * 31_337.0 + tick * 1000.0, q * -12_345.0);
			}
			assertTrue(environment.terrainLookups - terrainBefore <= 3, "too many terrain lookups in one tick");
			assertTrue(environment.climateLookups - climateBefore <= 3 * samples, "too many biome lookups in one tick");
		}
		assertTrue(weather.cellCount() <= StormcellConfig.get().general.maxActiveWeatherCells);
	}

	@Test
	void spotsWithoutACellAreAnsweredFromTheStorms() {
		StormcellConfig.get().performance.maxNewCellsPerTick = 1;
		FakeEnvironment environment = new FakeEnvironment(FakeEnvironment.plains()).player(0, 0);
		LevelWeather weather = weather(environment);
		weather.tick(true);
		weather.spawnStorm(StormSystem.Kind.THUNDERSTORM, 50_000, 50_000, 0.95, 100000, LevelWeather.Overflow.REJECT);
		weather.precipitationAt(-40_000, -40_000);
		// The budget is spent: a teleport into the storm must still see rain, not a false clear sky
		assertFalse(weather.hasCellAt(50_000, 50_000));
		assertTrue(weather.precipitationAt(50_000, 50_000) > 0.8F);
	}

	@Test
	void cellLimitIsStrict() {
		StormcellConfig.get().general.maxActiveWeatherCells = 64;
		StormcellConfig.get().performance.maxNewCellsPerTick = 64;
		FakeEnvironment environment = new FakeEnvironment(FakeEnvironment.plains()).player(0, 0).player(100_000, 0);
		LevelWeather weather = weather(environment);
		for (int tick = 0; tick < 200; tick++) {
			weather.tick(true);
			weather.precipitationAt(tick * 999.0, tick * 777.0);
			assertTrue(weather.cellCount() <= 64, "cell count " + weather.cellCount());
		}
	}

	@Test
	void dryRegionMemoryIsBounded() {
		StormcellConfig config = StormcellConfig.get();
		config.general.maxActiveWeatherCells = 64;
		config.performance.activeRadiusChunks = 16;
		config.performance.passiveCellTimeoutSeconds = 5;
		config.performance.maxNewCellsPerTick = 64;
		config.stormBehavior.moistureRecoveryMinutes = 600;
		FakeEnvironment environment = new FakeEnvironment(FakeEnvironment.plains()).player(0, 0);
		LevelWeather weather = weather(environment);
		// A player travelling through rain for a long way leaves a trail of rained-out cells behind
		for (int leg = 0; leg < 60; leg++) {
			double x = leg * 1024.0;
			environment.observers.set(0, new WeatherEnvironment.Observer(x, 0));
			weather.clearStorms(0, 0, -1);
			weather.spawnStorm(StormSystem.Kind.RAIN_SYSTEM, x, 0, 0.8, 100000, LevelWeather.Overflow.REJECT);
			environment.run(weather, 1200, true);
		}
		weather.pruneDormant(config);
		assertTrue(weather.dormantCount() <= 64, "remembered " + weather.dormantCount());
		WeatherState state = weather.snapshotState();
		assertTrue(state.dryCells.size() <= 64 * 2, "saved " + state.dryCells.size());
	}

	@Test
	void publishedSamplesNeverChange() {
		FakeEnvironment environment = new FakeEnvironment(FakeEnvironment.plains()).player(0, 0);
		LevelWeather weather = weather(environment);
		weather.spawnStorm(StormSystem.Kind.THUNDERSTORM, 0, 0, 0.9, 100000, LevelWeather.Overflow.REJECT);
		environment.run(weather, 200, true);
		LevelWeather.Snapshot snapshot = weatherSnapshot(weather);
		long key = WeatherCell.key(0, 0);
		WeatherCell.Samples samples = snapshot.samples().get(key);
		assertNotNull(samples);
		float[] copy = samples.precipitation().clone();
		environment.run(weather, 2000, true);
		assertTrue(java.util.Arrays.equals(copy, samples.precipitation()), "a published sample set was changed in place");
	}

	@Test
	void otherThreadsReadConsistentlyThroughGridChanges() throws Exception {
		FakeEnvironment environment = new FakeEnvironment(FakeEnvironment.plains()).player(0, 0);
		LevelWeather weather = weather(environment);
		weather.spawnStorm(StormSystem.Kind.RAIN_SYSTEM, 0, 0, 0.8, 1_000_000, LevelWeather.Overflow.REJECT);
		environment.run(weather, 100, true);
		AtomicBoolean done = new AtomicBoolean();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread reader = new Thread(() -> {
			try {
				while (!done.get()) {
					for (int i = 0; i < 1000; i++) {
						float value = weather.precipitationAt(i * 7.3 - 3000, i * -5.1 + 2000);
						if (!(value >= 0.0F && value <= 1.0F)) {
							throw new AssertionError("bad value " + value);
						}
					}
				}
			} catch (Throwable e) {
				failure.set(e);
			}
		});
		reader.start();
		StormcellConfig config = StormcellConfig.get();
		for (int round = 0; round < 40; round++) {
			config.general.cellResolution = 1 + round % 8;
			config.general.weatherCellSizeChunks = 4 + (round % 5) * 4;
			environment.run(weather, 101, true);
		}
		done.set(true);
		reader.join();
		if (failure.get() != null) {
			throw new AssertionError("reader failed", failure.get());
		}
	}

	@Test
	void pausedWeatherHoldsStill() {
		StormcellConfig.get().generation.formationChancePerMinute = 1.0;
		FakeEnvironment environment = new FakeEnvironment(FakeEnvironment.plains()).player(0, 0);
		LevelWeather weather = weather(environment);
		StormSystem storm = weather.spawnStorm(StormSystem.Kind.SHOWER, 0, 0, 0.5, 0, LevelWeather.Overflow.REJECT);
		storm.anchored = false;
		long time = weather.simTicks();
		double x = storm.x;
		double intensity = storm.intensity;
		environment.run(weather, 5000, false);
		assertEquals(time, weather.simTicks());
		assertEquals(x, storm.x);
		assertEquals(intensity, storm.intensity);
		assertEquals(1, weather.storms().size(), "no storms may form while paused");
		assertTrue(weather.precipitationAt(0, 0) > 0.3F, "players still see the paused weather");
	}

	@Test
	void sleepingEndsOnlyTheStormsOverTheSleepers() {
		FakeEnvironment environment = new FakeEnvironment(FakeEnvironment.plains()).player(0, 0);
		LevelWeather weather = weather(environment);
		weather.spawnStorm(StormSystem.Kind.SHOWER, 0, 0, 0.6, 100000, LevelWeather.Overflow.REJECT);
		weather.spawnStorm(StormSystem.Kind.SHOWER, 5000, 0, 0.6, 100000, LevelWeather.Overflow.REJECT);
		assertEquals(1, weather.clearStormsOver(List.of(new WeatherEnvironment.Observer(10, 10))));
		assertEquals(1, weather.storms().size());
		assertEquals(5000.0, weather.storms().get(0).x, 1.0);
		assertTrue(weather.precipitationAt(10, 10) < 0.01F, "the sleeper's sky clears at once");
	}

	@Test
	void commandStormsStayPutForTheirDurationThenMoveOn() {
		// Desert air would normally starve a storm within minutes
		FakeEnvironment environment = new FakeEnvironment(FakeEnvironment.climate(2.0F, 0.05F, false, 0.05F, 0.1F)).player(0, 0);
		LevelWeather weather = weather(environment);
		StormSystem storm = weather.spawnStorm(StormSystem.Kind.RAIN_SYSTEM, 0, 0, 0.7, 6000, LevelWeather.Overflow.REJECT);
		environment.run(weather, 5900, true);
		assertEquals(0.0, storm.x, 1.0E-9);
		assertEquals(0.0, storm.z, 1.0E-9);
		assertTrue(storm.intensity > 0.6, "dry air must not wear down a command storm early: " + storm.intensity);
		environment.run(weather, 3000, true);
		assertFalse(storm.anchored);
		assertTrue(storm.intensity < 0.6 || !weather.storms().contains(storm));
		assertTrue(Math.abs(storm.x) + Math.abs(storm.z) > 1.0, "it drifts away once its time is up");
	}

	@Test
	void restartContinuesTheSameWeather() {
		FakeEnvironment environment = new FakeEnvironment(FakeEnvironment.plains()).player(0, 0);
		LevelWeather weather = weather(environment);
		weather.spawnStorm(StormSystem.Kind.RAIN_SYSTEM, 300, -200, 0.75, 100000, LevelWeather.Overflow.REJECT);
		environment.run(weather, 600, true);
		WeatherState state = weather.snapshotState();
		assertTrue(weather.isDirty());
		weather.markSaved(state);
		assertFalse(weather.isDirty());

		LevelWeather restored = new LevelWeather(environment, state, 7L);
		assertEquals(1, restored.storms().size());
		StormSystem before = weather.storms().get(0);
		StormSystem after = restored.storms().get(0);
		assertEquals(before.x, after.x, 1.0E-9);
		assertEquals(before.intensity, after.intensity, 1.0E-9);
		assertEquals(before.anchored, after.anchored);
		assertEquals(weather.simTicks(), restored.simTicks());
	}

	@Test
	void savingDuringChangesStaysDirty() {
		FakeEnvironment environment = new FakeEnvironment(FakeEnvironment.plains()).player(0, 0);
		LevelWeather weather = weather(environment);
		environment.run(weather, 100, true);
		WeatherState state = weather.snapshotState();
		environment.run(weather, 100, true);
		weather.markSaved(state);
		assertTrue(weather.isDirty(), "a save taken before later changes must not clear them");
	}

	private static LevelWeather.Snapshot weatherSnapshot(LevelWeather weather) {
		try {
			java.lang.reflect.Field field = LevelWeather.class.getDeclaredField("snapshot");
			field.setAccessible(true);
			return (LevelWeather.Snapshot) field.get(weather);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}
}
