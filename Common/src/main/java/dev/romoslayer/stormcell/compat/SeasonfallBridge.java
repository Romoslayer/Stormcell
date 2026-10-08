package dev.romoslayer.stormcell.compat;

import dev.romoslayer.stormcell.Stormcell;
import dev.romoslayer.stormcell.api.ClimateModifiers;
import dev.romoslayer.stormcell.api.StormcellApi;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;

/**
 * Reads the seasonal climate from Seasonfall through its public API ({@code SeasonfallApi.climate}), without Stormcell
 * depending on it: the API is looked up reflectively when Seasonfall is installed and ignored otherwise. Answers are
 * cached per biome for one tick. Stands down if Seasonfall registers a provider through {@link StormcellApi} instead,
 * and gives up quietly if the API ever changes shape.
 */
public final class SeasonfallBridge {
	private static final String MOD_ID = "seasonfall";
	private static final String API_CLASS = "dev.romoslayer.seasonfall.api.SeasonfallApi";
	private static final String MODIFIERS_CLASS = "dev.romoslayer.seasonfall.api.ClimateModifiers";

	private static boolean resolved;
	private static boolean available;
	private static MethodHandle hasSeasons;
	private static MethodHandle climate;
	private static MethodHandle temperatureOffset;
	private static MethodHandle humidityMultiplier;
	private static MethodHandle precipitationMultiplier;
	private static MethodHandle stormProbabilityMultiplier;

	private static final Map<Biome, ClimateModifiers> CACHE = new IdentityHashMap<>();
	private static final Map<ServerLevel, Boolean> SEASONAL_LEVELS = new IdentityHashMap<>();

	private SeasonfallBridge() {
	}

	/** Called once per server tick: answers are reused within a tick, never across ticks. */
	public static void tick() {
		CACHE.clear();
		SEASONAL_LEVELS.clear();
	}

	/** Called when a server starts or the config is reloaded, so a newly installed Seasonfall is picked up. */
	public static void reset() {
		resolved = false;
		available = false;
		tick();
	}

	/** Whether Seasonfall is installed and its API was found. */
	public static boolean isAvailable() {
		return resolve() && !ClimateProviderRegistry.hasProviderFrom(MOD_ID);
	}

	static ClimateModifiers modifiers(ServerLevel level, Holder<Biome> biome) {
		if (!resolve() || ClimateProviderRegistry.hasProviderFrom(MOD_ID)) {
			return ClimateModifiers.NONE;
		}
		try {
			Boolean seasonal = SEASONAL_LEVELS.get(level);
			if (seasonal == null) {
				seasonal = (boolean) hasSeasons.invoke(level);
				SEASONAL_LEVELS.put(level, seasonal);
			}
			if (!seasonal) {
				return ClimateModifiers.NONE;
			}
			ClimateModifiers cached = CACHE.get(biome.value());
			if (cached == null) {
				Object result = climate.invoke(biome);
				cached = result == null ? ClimateModifiers.NONE : new ClimateModifiers((float) temperatureOffset.invoke(result),
						(float) humidityMultiplier.invoke(result), (float) precipitationMultiplier.invoke(result),
						(float) stormProbabilityMultiplier.invoke(result));
				CACHE.put(biome.value(), cached);
			}
			return cached;
		} catch (Throwable e) {
			Stormcell.LOGGER.warn("Could not read the seasonal climate from Seasonfall; the season link is off until the next reload", e);
			available = false;
			return ClimateModifiers.NONE;
		}
	}

	private static boolean resolve() {
		if (resolved) {
			return available;
		}
		resolved = true;
		available = false;
		if (!Stormcell.platform().isModLoaded(MOD_ID)) {
			return false;
		}
		try {
			MethodHandles.Lookup lookup = MethodHandles.publicLookup();
			Class<?> api = Class.forName(API_CLASS);
			Class<?> modifiers = Class.forName(MODIFIERS_CLASS);
			hasSeasons = lookup.findStatic(api, "hasSeasons", MethodType.methodType(boolean.class, ServerLevel.class));
			climate = lookup.findStatic(api, "climate", MethodType.methodType(modifiers, Holder.class));
			temperatureOffset = lookup.findVirtual(modifiers, "temperatureOffset", MethodType.methodType(float.class));
			humidityMultiplier = lookup.findVirtual(modifiers, "humidityMultiplier", MethodType.methodType(float.class));
			precipitationMultiplier = lookup.findVirtual(modifiers, "precipitationMultiplier", MethodType.methodType(float.class));
			stormProbabilityMultiplier = lookup.findVirtual(modifiers, "stormProbabilityMultiplier", MethodType.methodType(float.class));
			available = true;
			Stormcell.LOGGER.info("Seasonfall found: the seasons will shape Stormcell's climate");
		} catch (ReflectiveOperationException | LinkageError e) {
			Stormcell.LOGGER.warn("Seasonfall is installed but its climate API could not be found; the season link is off", e);
		}
		return available;
	}
}
