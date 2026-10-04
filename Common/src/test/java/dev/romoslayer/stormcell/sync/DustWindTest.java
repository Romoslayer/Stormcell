package dev.romoslayer.stormcell.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DustWindTest {
	@Test
	void eachGustStartsShortlyBeforeThePreviousEnds() {
		// The 10.22 s clip at pitch 0.6 lasts 17.03 s; the next gust starts 2 s before that
		assertEquals(Math.round((10.22 / 0.6F - 2.0) * 20.0), DustWind.gustTicks(0.6F));
		// Lower pitch, longer clip, longer gap
		assertEquals(true, DustWind.gustTicks(0.55F) > DustWind.gustTicks(0.65F));
	}
}
