package dev.romoslayer.stormcell.api;

/**
 * Broad climate adjustments supplied through a {@link ClimateModifierProvider}.
 *
 * @param temperatureOffset          added to the biome's normal temperature, on the vanilla scale (0.15 is freezing,
 *                                   plains are 0.8). Stormcell always starts from each biome's normal, data-pack
 *                                   temperature, so a mod that also shifts biome temperatures in the game should report
 *                                   the same shift here; it is not counted twice.
 * @param humidityMultiplier         multiplies how moist the air is; moist air forms and feeds storms
 * @param precipitationMultiplier    multiplies how often rain forms
 * @param stormProbabilityMultiplier multiplies how often convective storms (potential thunderstorms) form
 */
public record ClimateModifiers(float temperatureOffset, float humidityMultiplier, float precipitationMultiplier, float stormProbabilityMultiplier) {
	public static final ClimateModifiers NONE = new ClimateModifiers(0.0F, 1.0F, 1.0F, 1.0F);

	/** Both sets of modifiers at once: offsets add up, multipliers multiply. */
	public ClimateModifiers combine(ClimateModifiers other) {
		if (other == NONE) {
			return this;
		}
		if (this == NONE) {
			return other;
		}
		return new ClimateModifiers(this.temperatureOffset + other.temperatureOffset, this.humidityMultiplier * other.humidityMultiplier,
				this.precipitationMultiplier * other.precipitationMultiplier, this.stormProbabilityMultiplier * other.stormProbabilityMultiplier);
	}

	/** Weakens (strength below 1) or exaggerates (above 1) how far these modifiers move the climate from normal. */
	public ClimateModifiers scaled(float strength) {
		if (strength == 1.0F || this == NONE) {
			return this;
		}
		return new ClimateModifiers(this.temperatureOffset * strength, scale(this.humidityMultiplier, strength), scale(this.precipitationMultiplier, strength),
				scale(this.stormProbabilityMultiplier, strength));
	}

	private static float scale(float multiplier, float strength) {
		return Math.max(0.0F, 1.0F + (multiplier - 1.0F) * strength);
	}
}
