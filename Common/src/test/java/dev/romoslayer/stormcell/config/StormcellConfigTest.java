package dev.romoslayer.stormcell.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StormcellConfigTest {
	@TempDir
	Path dir;

	private static String defaultsText() {
		return ConfigBinder.write(new StormcellConfig(), "test");
	}

	private static boolean mentions(List<String> problems, String text) {
		return problems.stream().anyMatch(problem -> problem.contains(text));
	}

	@Test
	void overflowingNumbersAreRejectedNotThrown() {
		for (String value : List.of("1e309", "-1e309")) {
			StormcellConfig.Parsed parsed = StormcellConfig.parse("[wind]\nmaximumWindSpeed = " + value + "\n");
			assertNull(parsed.config(), value + " should make the file unusable, not crash");
			assertTrue(mentions(parsed.problems(), "out of range"), parsed.problems().toString());
		}
		StormcellConfig.Parsed huge = StormcellConfig.parse("[general]\nsimulationIntervalTicks = 99999999999999999999\n");
		assertNull(huge.config());
	}

	@Test
	void outOfRangeValuesAreClamped() {
		StormcellConfig.Parsed parsed = StormcellConfig.parse("[wind]\nmaximumWindSpeed = 500.0\n");
		assertNotNull(parsed.config());
		assertEquals(50.0, parsed.config().wind.maximumWindSpeed);
		assertTrue(mentions(parsed.problems(), "outside"));
	}

	@Test
	void fractionalWholeNumbersKeepTheDefault() {
		StormcellConfig.Parsed parsed = StormcellConfig.parse("[general]\nsimulationIntervalTicks = 100.5\n");
		assertNotNull(parsed.config());
		assertEquals(new StormcellConfig().general.simulationIntervalTicks, parsed.config().general.simulationIntervalTicks);
		assertTrue(mentions(parsed.problems(), "whole number"));
	}

	@Test
	void malformedTextIsRejected() {
		assertNull(StormcellConfig.parse("[general\nenabled = true\n").config());
		assertNull(StormcellConfig.parse("enabled = maybe\n").config());
		assertNull(StormcellConfig.parse("[general]\nenabled = \"unterminated\n").config());
	}

	@Test
	void defaultsRoundTrip() {
		String text = defaultsText();
		StormcellConfig.Parsed parsed = StormcellConfig.parse(text);
		assertNotNull(parsed.config());
		assertEquals(List.of(), parsed.problems());
		assertEquals(text, ConfigBinder.write(parsed.config(), "test"));
	}

	@Test
	void customValuesRoundTrip() {
		String text = defaultsText().replace("simulationIntervalTicks = 100", "simulationIntervalTicks = 40")
				.replace("[biomeOverrides.\"minecraft:desert\"]", "[biomeOverrides.\"#c:is_cave\"]\nrainMultiplier = 0.0\n\n[biomeOverrides.\"minecraft:desert\"]");
		StormcellConfig.Parsed parsed = StormcellConfig.parse(text);
		assertNotNull(parsed.config());
		assertEquals(List.of(), parsed.problems());
		assertEquals(40, parsed.config().general.simulationIntervalTicks);
		assertEquals(0.0, parsed.config().biomeOverrides.get("#c:is_cave").rainMultiplier);
		assertTrue(parsed.config().biomeOverrides.containsKey("minecraft:desert"));
		StormcellConfig again = StormcellConfig.parse(ConfigBinder.write(parsed.config(), "test")).config();
		assertEquals(parsed.config().biomeOverrides.keySet(), again.biomeOverrides.keySet());
	}

	@Test
	void unknownSectionsAreReported() {
		StormcellConfig.Parsed misspelled = StormcellConfig.parse("[generaton]\nrainChanceMultiplier = 2.0\n");
		assertNotNull(misspelled.config());
		assertTrue(mentions(misspelled.problems(), "[generaton]"), misspelled.problems().toString());
		StormcellConfig.Parsed nested = StormcellConfig.parse("[stormTypes.hurricane]\nweight = 1.0\n");
		assertTrue(mentions(nested.problems(), "[stormTypes.hurricane]"), nested.problems().toString());
		StormcellConfig.Parsed key = StormcellConfig.parse("[general]\nenabeld = false\n");
		assertTrue(mentions(key.problems(), "general.enabeld"));
	}

	@Test
	void duplicatesAreErrors() {
		assertThrows(Toml.ParseException.class, () -> Toml.parse("[general]\nenabled = true\nenabled = false\n"));
		assertThrows(Toml.ParseException.class, () -> Toml.parse("[general]\nenabled = true\n[wind]\n[general]\n"));
		assertNull(StormcellConfig.parse("[general]\nenabled = true\nenabled = false\n").config());
	}

	@Test
	void anEmptyOverrideTableClearsTheDefaults() {
		String text = defaultsText();
		int start = text.indexOf("[biomeOverrides]");
		int end = text.indexOf("\n[elevation]");
		String emptied = text.substring(0, start) + "[biomeOverrides]\n" + text.substring(text.lastIndexOf('\n', end - 1), text.length());
		StormcellConfig.Parsed parsed = StormcellConfig.parse(emptied);
		assertNotNull(parsed.config());
		assertTrue(parsed.config().biomeOverrides.isEmpty(), parsed.problems().toString());
		// ...and stays empty when the file is rewritten and read again
		assertTrue(StormcellConfig.parse(ConfigBinder.write(parsed.config(), "test")).config().biomeOverrides.isEmpty());
		// A file without the section at all (an older file) keeps the defaults
		assertFalse(StormcellConfig.parse("[general]\n").config().biomeOverrides.isEmpty());
	}

	@Test
	void failedReloadKeepsTheWorkingSettingsAndTheFile() throws Exception {
		Path file = this.dir.resolve("stormcell.toml");
		Files.writeString(file, defaultsText().replace("simulationIntervalTicks = 100", "simulationIntervalTicks = 40"), StandardCharsets.UTF_8);
		StormcellConfig.load(this.dir);
		assertEquals(40, StormcellConfig.get().general.simulationIntervalTicks);

		String broken = "[general]\nsimulationIntervalTicks = 1e309\n";
		Files.writeString(file, broken, StandardCharsets.UTF_8);
		List<String> problems = StormcellConfig.reload();
		assertFalse(problems.isEmpty());
		assertEquals(40, StormcellConfig.get().general.simulationIntervalTicks, "a broken file must not replace working settings");
		assertEquals(broken, Files.readString(file), "a broken file must be left for the owner to fix");
	}

	@Test
	void correctedFilesAreBackedUpBeforeRewriting() throws Exception {
		Path file = this.dir.resolve("stormcell.toml");
		String original = "[generaton]\nrainChanceMultiplier = 2.0\n[wind]\nmaximumWindSpeed = 4.0\n";
		Files.writeString(file, original, StandardCharsets.UTF_8);
		StormcellConfig.load(this.dir);
		assertEquals(4.0, StormcellConfig.get().wind.maximumWindSpeed);
		assertEquals(original, Files.readString(this.dir.resolve("stormcell.toml.bak")));
		assertFalse(Files.readString(file).contains("generaton"));
	}
}
