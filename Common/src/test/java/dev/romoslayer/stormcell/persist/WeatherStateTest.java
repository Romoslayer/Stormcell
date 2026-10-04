package dev.romoslayer.stormcell.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WeatherStateTest {
	@TempDir
	Path dir;

	private WeatherState load(String json) throws IOException {
		Path file = this.dir.resolve("overworld.json");
		Files.writeString(file, json, StandardCharsets.UTF_8);
		return WeatherState.load(file, 48, 100);
	}

	private long keptCopies() throws IOException {
		try (Stream<Path> files = Files.list(this.dir)) {
			return files.filter(path -> path.getFileName().toString().contains(".corrupt-")).count();
		}
	}

	private static String storm(String fields) {
		return "{\"kind\":\"shower\",\"phase\":\"MATURE\",\"x\":0,\"z\":0,\"radius\":100,\"length\":1,\"width\":1,\"intensity\":0.5,\"potential\":0.5"
				+ (fields.isEmpty() ? "" : "," + fields) + "}";
	}

	@Test
	void nullEntriesAndListsAreSurvived() throws IOException {
		WeatherState state = this.load("{\"storms\":[null," + storm("") + "],\"dryCells\":[null]}");
		assertNotNull(state);
		assertEquals(1, state.storms.size());
		assertEquals(0, state.dryCells.size());
		assertEquals(1, this.keptCopies(), "the original should be kept before repairs");

		WeatherState nullLists = this.load("{\"storms\":null,\"dryCells\":null}");
		assertNotNull(nullLists);
		assertTrue(nullLists.storms.isEmpty());
		assertTrue(nullLists.dryCells.isEmpty());
	}

	@Test
	void hugeAndInvalidNumbersAreDropped() throws IOException {
		WeatherState state = this.load("{\"windSpeed\":1e400,\"fieldOffsetX\":-1e400,\"storms\":["
				+ storm("\"radius\":1e400") + "," + storm("\"length\":0") + "," + storm("\"x\":1e300") + "," + storm("\"intensity\":7") + ","
				+ storm("\"decayBoost\":-1") + "," + storm("") + "]}");
		assertNotNull(state);
		assertEquals(1, state.storms.size(), "only the sound storm survives");
		assertTrue(Double.isFinite(state.windSpeed) && state.windSpeed <= 1000.0);
		assertEquals(0.0, state.fieldOffsetX);
		// What survives can be saved again (Gson refuses non-finite numbers)
		assertTrue(state.save(this.dir.resolve("again.json")));
	}

	@Test
	void dryCellTimestampsAreMadeCoherent() throws IOException {
		WeatherState state = this.load("{\"simTicks\":1000,\"dryCells\":[{\"x\":1,\"z\":2,\"depletion\":0.3,\"tick\":-50},"
				+ "{\"x\":3,\"z\":4,\"depletion\":0.3,\"tick\":999999},{\"x\":5,\"z\":6,\"depletion\":5.0,\"tick\":10},"
				+ "{\"x\":7,\"z\":8,\"depletion\":-1,\"tick\":10}]}");
		assertNotNull(state);
		assertEquals(3, state.dryCells.size());
		for (WeatherState.DryCell cell : state.dryCells) {
			assertTrue(cell.tick() >= 0 && cell.tick() <= 1000, "tick " + cell.tick());
			assertTrue(cell.depletion() > 0.0F && cell.depletion() <= 0.6F);
		}
	}

	@Test
	void listsAreCutToTheLimits() throws IOException {
		StringBuilder json = new StringBuilder("{\"storms\":[");
		for (int i = 0; i < 200; i++) {
			json.append(i == 0 ? "" : ",").append(storm(""));
		}
		json.append("],\"simTicks\":10,\"dryCells\":[");
		for (int i = 0; i < 500; i++) {
			json.append(i == 0 ? "" : ",").append("{\"x\":").append(i).append(",\"z\":0,\"depletion\":0.2,\"tick\":1}");
		}
		json.append("]}");
		WeatherState state = this.load(json.toString());
		assertEquals(48, state.storms.size());
		assertEquals(100, state.dryCells.size());
	}

	@Test
	void unreadableFilesStartFreshButAreKept() throws IOException {
		assertNull(this.load("{ this is not json"));
		assertEquals(1, this.keptCopies());
		assertNull(this.load(""));
	}

	@Test
	void failedSavesLeaveTheOldFileAlone() throws IOException {
		Path file = this.dir.resolve("overworld.json");
		WeatherState first = new WeatherState();
		first.simTicks = 5;
		assertTrue(first.save(file));
		String before = Files.readString(file);

		// A directory where the temporary file would go makes the write fail
		Files.createDirectory(this.dir.resolve("overworld.json.tmp"));
		WeatherState second = new WeatherState();
		second.simTicks = 99;
		assertFalse(second.save(file));
		assertEquals(before, Files.readString(file));
	}

	@Test
	void unsavableValuesFailCleanly() {
		WeatherState state = new WeatherState();
		state.windSpeed = Double.NaN;
		assertFalse(state.save(this.dir.resolve("nan.json")));
		assertFalse(Files.exists(this.dir.resolve("nan.json")));
	}
}
