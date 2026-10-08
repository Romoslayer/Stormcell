package dev.romoslayer.stormcell.climate;

import com.google.gson.JsonObject;
import dev.romoslayer.stormcell.Stormcell;
import dev.romoslayer.stormcell.config.StormcellConfig;
import dev.romoslayer.stormcell.mc.Versioned;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;

/**
 * What the weather simulation needs to know about a biome, worked out once per biome from its vanilla climate
 * settings plus any override from the config. Modded biomes get sensible weather from their own climate values with
 * no extra setup. The temperature is the biome's normal one from its data, never a value a seasons mod has shifted
 * in the game, so seasonal changes arrive only once, through the climate modifiers.
 *
 * @param holder             the biome (null only in tests, where there is no registry)
 * @param baseTemperature    the biome's normal temperature plus the override offset (vanilla scale)
 * @param humidity           0 to 1, from the biome's downfall
 * @param hasPrecipitation   whether vanilla ever rains or snows here (clients draw nothing where it does not)
 * @param water              oceans and rivers, which moisten the air
 * @param rainMultiplier     how much more (or less) often rain forms here
 * @param stormMultiplier    how much more (or less) often convective storms form here
 * @param dusty              dust storms form here (dryWeather.dustStormBiomes)
 */
public record BiomeClimate(Holder<Biome> holder, float baseTemperature, float humidity, boolean hasPrecipitation, boolean water, float rainMultiplier,
		float stormMultiplier, boolean dusty) {
	private static final float NEUTRAL_HUMIDITY = 0.5F;
	private static final float NEUTRAL_TEMPERATURE = 0.8F;
	private static final Map<Biome, BiomeClimate> CACHE = new IdentityHashMap<>();
	private static final Map<Biome, float[]> NORMAL_CLIMATE = new IdentityHashMap<>();

	/** Forgets everything worked out so far, after a config reload or when a new server starts. */
	public static synchronized void clearCache() {
		CACHE.clear();
		NORMAL_CLIMATE.clear();
	}

	public static synchronized BiomeClimate of(Holder<Biome> holder, RegistryAccess registries) {
		BiomeClimate climate = CACHE.get(holder.value());
		if (climate == null) {
			climate = compute(holder, registries);
			CACHE.put(holder.value(), climate);
		}
		return climate;
	}

	/** The temperature the simulation uses for this biome (a temperate value when biome temperatures are ignored). */
	public float temperature() {
		StormcellConfig.Biomes config = StormcellConfig.get().biomes;
		return config.biomeWeatherInfluence && config.biomeTemperatureInfluence ? this.baseTemperature : NEUTRAL_TEMPERATURE;
	}

	private static BiomeClimate compute(Holder<Biome> holder, RegistryAccess registries) {
		StormcellConfig config = StormcellConfig.get();
		Biome biome = holder.value();
		boolean water = holder.is(BiomeTags.IS_OCEAN) || holder.is(BiomeTags.IS_DEEP_OCEAN) || holder.is(BiomeTags.IS_RIVER);
		StormcellConfig.BiomeOverride override = findOverride(holder, config);

		float[] normal = normalClimate(biome, registries);
		float humidity;
		if (!config.biomes.biomeWeatherInfluence || !config.biomes.biomeHumidityInfluence) {
			humidity = NEUTRAL_HUMIDITY;
		} else {
			humidity = normal[1];
			if (!biome.hasPrecipitation()) {
				humidity = Math.max(humidity, (float) config.biomes.dryBiomeHumidity);
			}
			if (water) {
				humidity += (float) config.biomes.waterHumidityBonus;
			}
		}
		float rain = 1.0F;
		float storm = 1.0F;
		float offset = 0.0F;
		if (override != null && config.biomes.biomeWeatherInfluence) {
			humidity *= (float) override.humidityMultiplier;
			rain = (float) override.rainMultiplier;
			storm = (float) override.stormMultiplier;
			offset = (float) override.temperatureOffset;
		}
		boolean dusty = matchesAny(holder, config.dryWeather.dustStormBiomes);
		return new BiomeClimate(holder, normal[0] + offset, Mth.clamp(humidity, 0.0F, 1.0F), biome.hasPrecipitation(), water, rain, storm, dusty);
	}

	/** Whether a biome matches any of a list of biome ids and #tags. */
	private static boolean matchesAny(Holder<Biome> holder, java.util.List<String> entries) {
		Optional<ResourceKey<Biome>> key = holder.unwrapKey();
		for (String entry : entries) {
			if (entry.startsWith("#")) {
				TagKey<Biome> tag = Versioned.biomeTag(entry.substring(1));
				if (tag != null && holder.is(tag)) {
					return true;
				}
			} else if (key.isPresent() && Versioned.id(key.get()).equals(entry)) {
				return true;
			}
		}
		return false;
	}

	private static StormcellConfig.BiomeOverride findOverride(Holder<Biome> holder, StormcellConfig config) {
		Optional<ResourceKey<Biome>> key = holder.unwrapKey();
		if (key.isPresent()) {
			StormcellConfig.BiomeOverride exact = config.biomeOverrides.get(Versioned.id(key.get()));
			if (exact != null) {
				return exact;
			}
		}
		for (Map.Entry<String, StormcellConfig.BiomeOverride> entry : config.biomeOverrides.entrySet()) {
			if (entry.getKey().startsWith("#")) {
				TagKey<Biome> tag = Versioned.biomeTag(entry.getKey().substring(1));
				if (tag != null && holder.is(tag)) {
					return entry.getValue();
				}
			}
		}
		return null;
	}

	/**
	 * A biome's normal temperature and downfall (0 = arid, 1 = very wet), as {temperature, downfall}. The game keeps
	 * them private, so they are read from the biome's network encoding, which carries its climate settings exactly as
	 * the data pack defines them. Falls back to estimates if that fails.
	 */
	private static synchronized float[] normalClimate(Biome biome, RegistryAccess registries) {
		float[] known = NORMAL_CLIMATE.get(biome);
		if (known != null) {
			return known;
		}
		float temperature = biome.getBaseTemperature();
		float downfall = estimateDownfall(biome);
		try {
			JsonObject object = Versioned.encodeForNetwork(biome, registries);
			if (object.has("temperature")) {
				temperature = object.get("temperature").getAsFloat();
			}
			if (object.has("downfall")) {
				downfall = object.get("downfall").getAsFloat();
			}
		} catch (RuntimeException e) {
			Stormcell.LOGGER.debug("Could not read the climate of a biome; estimating it", e);
		}
		float[] climate = {temperature, downfall};
		NORMAL_CLIMATE.put(biome, climate);
		return climate;
	}

	private static float estimateDownfall(Biome biome) {
		if (!biome.hasPrecipitation()) {
			return 0.0F;
		}
		return biome.getBaseTemperature() > 1.0F ? 0.3F : 0.5F;
	}
}
