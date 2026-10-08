package dev.romoslayer.stormcell.compat;

import dev.romoslayer.stormcell.Stormcell;
import dev.romoslayer.stormcell.api.ClimateModifierProvider;
import dev.romoslayer.stormcell.api.ClimateModifiers;
import dev.romoslayer.stormcell.config.StormcellConfig;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;

/** Collects the climate modifiers from every registered provider and the built-in Seasonfall bridge. */
public final class ClimateHooks {
	private static boolean warned;

	private ClimateHooks() {
	}

	public static ClimateModifiers modifiers(ServerLevel level, BlockPos pos, Holder<Biome> biome) {
		StormcellConfig.Seasons config = StormcellConfig.get().seasons;
		if (!config.integrationEnabled) {
			return ClimateModifiers.NONE;
		}
		ClimateModifiers result = ClimateModifiers.NONE;
		List<ClimateModifierProvider> providers = ClimateProviderRegistry.providers();
		for (ClimateModifierProvider provider : providers) {
			try {
				ClimateModifiers modifiers = provider.getModifiers(level, pos, biome);
				if (modifiers != null) {
					result = result.combine(sanitize(modifiers));
				}
			} catch (RuntimeException e) {
				if (!warned) {
					warned = true;
					Stormcell.LOGGER.error("A Stormcell climate provider threw an exception; its answer is ignored", e);
				}
			}
		}
		if (config.seasonfallBridge) {
			result = result.combine(sanitize(SeasonfallBridge.modifiers(level, biome)));
		}
		// Each answer was checked, but several large ones multiplied together can still overflow
		return clamp(result.scaled((float) config.seasonalStrength));
	}

	/** Whether anything could change the climate at all, so the common case skips building positions. */
	public static boolean anyActive() {
		StormcellConfig.Seasons config = StormcellConfig.get().seasons;
		return config.integrationEnabled && (!ClimateProviderRegistry.providers().isEmpty() || config.seasonfallBridge && SeasonfallBridge.isAvailable());
	}

	/** Keeps combined modifiers within sane limits (offset within 5, multipliers within 0 to 20). */
	public static ClimateModifiers clamp(ClimateModifiers modifiers) {
		if (modifiers == ClimateModifiers.NONE) {
			return modifiers;
		}
		return new ClimateModifiers(Mth.clamp(orIfNaN(modifiers.temperatureOffset(), 0.0F), -5.0F, 5.0F),
				Mth.clamp(orIfNaN(modifiers.humidityMultiplier(), 1.0F), 0.0F, 20.0F), Mth.clamp(orIfNaN(modifiers.precipitationMultiplier(), 1.0F), 0.0F, 20.0F),
				Mth.clamp(orIfNaN(modifiers.stormProbabilityMultiplier(), 1.0F), 0.0F, 20.0F));
	}

	private static ClimateModifiers sanitize(ClimateModifiers modifiers) {
		if (modifiers == ClimateModifiers.NONE) {
			return modifiers;
		}
		return new ClimateModifiers(finite(modifiers.temperatureOffset(), 0.0F), Math.max(0.0F, finite(modifiers.humidityMultiplier(), 1.0F)),
				Math.max(0.0F, finite(modifiers.precipitationMultiplier(), 1.0F)), Math.max(0.0F, finite(modifiers.stormProbabilityMultiplier(), 1.0F)));
	}

	/** Infinities are fine here (they clamp to the limits); only NaN has no sensible limit. */
	private static float orIfNaN(float value, float fallback) {
		return Float.isNaN(value) ? fallback : value;
	}

	private static float finite(float value, float fallback) {
		return Float.isFinite(value) ? value : fallback;
	}
}
