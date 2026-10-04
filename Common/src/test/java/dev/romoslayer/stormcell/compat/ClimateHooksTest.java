package dev.romoslayer.stormcell.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.romoslayer.seasonfall.api.SeasonfallApi;
import dev.romoslayer.stormcell.Stormcell;
import dev.romoslayer.stormcell.api.ClimateModifiers;
import dev.romoslayer.stormcell.api.StormcellApi;
import dev.romoslayer.stormcell.platform.Platform;
import java.nio.file.Path;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClimateHooksTest {
	private static final Identifier SEASONFALL_PROVIDER = Identifier.fromNamespaceAndPath("seasonfall", "climate");
	private static final Identifier OTHER_PROVIDER = Identifier.fromNamespaceAndPath("othermod", "climate");
	private static final Holder<Biome> BIOME = Holder.direct(null);

	@TempDir
	Path dir;

	@BeforeEach
	void setUp() {
		Stormcell.init(new Platform() {
			@Override
			public Path configDir() {
				return ClimateHooksTest.this.dir;
			}

			@Override
			public boolean isModLoaded(String modId) {
				return modId.equals("seasonfall");
			}
		});
		SeasonfallBridge.reset();
		SeasonfallApi.seasons = true;
		SeasonfallApi.answer = new dev.romoslayer.seasonfall.api.ClimateModifiers(0.3F, 1.2F, 0.8F, 1.5F);
		SeasonfallApi.calls = 0;
	}

	@AfterEach
	void tearDown() {
		StormcellApi.unregisterClimateProvider(SEASONFALL_PROVIDER);
		StormcellApi.unregisterClimateProvider(OTHER_PROVIDER);
		SeasonfallBridge.reset();
	}

	@Test
	void seasonfallIsReadThroughItsApi() {
		assertTrue(SeasonfallBridge.isAvailable());
		ClimateModifiers modifiers = ClimateHooks.modifiers(null, null, BIOME);
		assertEquals(new ClimateModifiers(0.3F, 1.2F, 0.8F, 1.5F), modifiers);
		ClimateHooks.modifiers(null, null, BIOME);
		assertEquals(1, SeasonfallApi.calls, "answers are cached within a tick");
		SeasonfallBridge.tick();
		ClimateHooks.modifiers(null, null, BIOME);
		assertEquals(2, SeasonfallApi.calls, "and asked again next tick");
	}

	@Test
	void dimensionsWithoutSeasonsAreNeutral() {
		SeasonfallApi.seasons = false;
		assertEquals(ClimateModifiers.NONE, ClimateHooks.modifiers(null, null, BIOME));
	}

	@Test
	void aRegisteredSeasonfallProviderReplacesTheBridge() {
		StormcellApi.registerClimateProvider(SEASONFALL_PROVIDER, (level, pos, biome) -> new ClimateModifiers(0.1F, 1.0F, 1.0F, 1.0F));
		ClimateModifiers modifiers = ClimateHooks.modifiers(null, null, BIOME);
		assertEquals(0.1F, modifiers.temperatureOffset(), 1.0E-6F, "applied once, not twice");
		assertEquals(0, SeasonfallApi.calls);
	}

	@Test
	void hugeCombinedValuesAreClamped() {
		StormcellApi.registerClimateProvider(OTHER_PROVIDER, (level, pos, biome) -> new ClimateModifiers(1.0E30F, 3.0E38F, Float.MAX_VALUE, Float.NaN));
		SeasonfallApi.answer = new dev.romoslayer.seasonfall.api.ClimateModifiers(1.0E30F, 3.0E38F, Float.MAX_VALUE, 2.0F);
		ClimateModifiers modifiers = ClimateHooks.modifiers(null, null, BIOME);
		assertTrue(Float.isFinite(modifiers.temperatureOffset()) && Math.abs(modifiers.temperatureOffset()) <= 5.0F);
		assertTrue(modifiers.humidityMultiplier() <= 20.0F);
		assertTrue(modifiers.precipitationMultiplier() <= 20.0F);
		assertTrue(Float.isFinite(modifiers.stormProbabilityMultiplier()));
	}
}
