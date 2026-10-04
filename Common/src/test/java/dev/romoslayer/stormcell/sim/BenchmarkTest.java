package dev.romoslayer.stormcell.sim;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.romoslayer.stormcell.config.StormcellConfig;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Load measurements of the simulation alone (no world generation behind the biome lookups, so real cell creation is
 * slower than here). Run with {@code gradlew :Common:benchmark}. The assertions are loose regression guards; the printed
 * numbers are the point.
 */
@Tag("benchmark")
class BenchmarkTest {
	@TempDir
	Path configDir;

	@BeforeEach
	void defaults() {
		StormcellConfig.load(this.configDir);
	}

	private record Measurement(double averageStepMs, double maxStepMs, int cells, int storms, double queryNanos) {
		@Override
		public String toString() {
			return String.format(Locale.ROOT, "step avg %6.2f ms, max %6.2f ms | cells %5d | storms %4d | query %5.0f ns", this.averageStepMs, this.maxStepMs,
					this.cells, this.storms, this.queryNanos);
		}
	}

	/** Warms the world up for two in-game days, then measures one more day of steps and a burst of queries. */
	private static Measurement measure(int players, double spacing) {
		FakeEnvironment environment = new FakeEnvironment(CalibrationTest.Biome.FOREST.climate);
		for (int i = 0; i < players; i++) {
			environment.player((i % 8) * spacing, (i / 8) * spacing);
		}
		LevelWeather weather = new LevelWeather(environment, null, 99L);
		environment.run(weather, 48000, true);
		long steps = weather.stats().steps;
		long total = 0;
		long max = 0;
		for (int tick = 0; tick < 24000; tick++) {
			long start = System.nanoTime();
			weather.tick(true);
			long elapsed = System.nanoTime() - start;
			if (weather.stats().steps > steps) {
				steps = weather.stats().steps;
				total += elapsed;
				max = Math.max(max, elapsed);
			}
		}
		int stepCount = 24000 / StormcellConfig.get().general.simulationIntervalTicks;
		// Entity-style queries around the players: many lookups in the same few cells
		int queries = 2_000_000;
		float sink = 0;
		long queryStart = System.nanoTime();
		for (int i = 0; i < queries; i++) {
			int player = i % players;
			sink += weather.precipitationAt((player % 8) * spacing + (i % 97) * 1.7, (player / 8) * spacing + (i % 89) * 1.3);
		}
		double queryNanos = (System.nanoTime() - queryStart) / (double) queries;
		assertTrue(sink >= 0);
		return new Measurement(total / 1.0E6 / stepCount, max / 1.0E6, weather.cellCount(), weather.storms().size(), queryNanos);
	}

	@Test
	void typicalAndHeavyServers() {
		StringBuilder table = new StringBuilder("Simulation load (forest climate, defaults):\n");
		Measurement one = measure(1, 0);
		table.append(String.format(Locale.ROOT, "  1 player              %s%n", one));
		Measurement grouped = measure(20, 200);
		table.append(String.format(Locale.ROOT, " 20 players together    %s%n", grouped));
		Measurement spread = measure(50, 6000);
		table.append(String.format(Locale.ROOT, " 50 players spread out  %s%n", spread));
		System.out.println(table);
		assertTrue(spread.averageStepMs() < 50.0, "a step with 50 spread-out players should stay well under a tick's budget");
		assertTrue(spread.queryNanos() < 5000.0, "gameplay lookups should stay cheap");
	}

	@Test
	void extremeRadiusIsBounded() {
		StormcellConfig config = StormcellConfig.get();
		config.performance.activeRadiusChunks = 1024;
		config.general.weatherCellSizeChunks = 4;
		Measurement extreme = measure(4, 50_000);
		System.out.println("Extreme settings (1024-chunk radius, 4-chunk cells, 4 players): " + extreme);
		assertTrue(extreme.cells() <= config.general.maxActiveWeatherCells);
		assertTrue(extreme.averageStepMs() < 100.0);
	}
}
