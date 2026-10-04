package dev.romoslayer.stormcell.api;

import dev.romoslayer.stormcell.sim.LevelWeather;
import dev.romoslayer.stormcell.world.RegionalWeather;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

/**
 * The supported way for other mods to work with Stormcell. Everything here must be called on the server thread.
 *
 * <p>To stay independent of Stormcell, guard calls with your loader's "is mod loaded" check (mod id
 * {@code stormcell}) and keep them in a class that is only loaded when it is present.
 */
public final class StormcellApi {
	private static final Map<Identifier, ClimateModifierProvider> PROVIDERS = new LinkedHashMap<>();
	private static volatile List<ClimateModifierProvider> providerList = List.of();

	private StormcellApi() {
	}

	/** Adds (or replaces) a climate provider under the given id. */
	public static synchronized void registerClimateProvider(Identifier id, ClimateModifierProvider provider) {
		PROVIDERS.put(id, provider);
		providerList = List.copyOf(PROVIDERS.values());
	}

	public static synchronized void unregisterClimateProvider(Identifier id) {
		PROVIDERS.remove(id);
		providerList = List.copyOf(PROVIDERS.values());
	}

	/** Whether a provider from the given mod (by id namespace) is registered. */
	public static synchronized boolean hasClimateProviderFrom(String namespace) {
		return PROVIDERS.keySet().stream().anyMatch(id -> id.getNamespace().equals(namespace));
	}

	/** Every registered provider, in registration order. */
	public static List<ClimateModifierProvider> climateProviders() {
		return providerList;
	}

	/** Whether Stormcell is running the weather of this dimension (otherwise it has vanilla weather). */
	public static boolean isManaged(ServerLevel level) {
		return RegionalWeather.weather(level) != null;
	}

	/** The simulated weather at a spot, or empty for a dimension Stormcell does not manage. */
	public static Optional<LocalWeather> getLocalWeather(ServerLevel level, BlockPos pos) {
		LevelWeather weather = RegionalWeather.weather(level);
		return weather == null ? Optional.empty() : Optional.of(weather.localWeather(pos.getX() + 0.5, pos.getZ() + 0.5));
	}

	/** Strength of the rain or snow reaching the ground at a spot, 0 to 1 (0 for unmanaged dimensions). */
	public static float getPrecipitationIntensity(ServerLevel level, BlockPos pos) {
		LevelWeather weather = RegionalWeather.weather(level);
		return weather == null ? 0.0F : weather.precipitationAt(pos.getX() + 0.5, pos.getZ() + 0.5);
	}

	/** Thickness of the dust in a dust storm at a spot, 0 to 1 (0 for unmanaged dimensions). */
	public static float getDustIntensity(ServerLevel level, BlockPos pos) {
		LevelWeather weather = RegionalWeather.weather(level);
		return weather == null ? 0.0F : weather.dustAt(pos.getX() + 0.5, pos.getZ() + 0.5);
	}

	/** Strength of the storm overhead at a spot, 0 to 1 (0 for unmanaged dimensions). */
	public static float getStormIntensity(ServerLevel level, BlockPos pos) {
		LevelWeather weather = RegionalWeather.weather(level);
		return weather == null ? 0.0F : weather.stormAt(pos.getX() + 0.5, pos.getZ() + 0.5);
	}
}
