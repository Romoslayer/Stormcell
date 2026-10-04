package dev.romoslayer.seasonfall.api;

/** Test stand-in matching Seasonfall's ClimateModifiers record. */
public record ClimateModifiers(float temperatureOffset, float humidityMultiplier, float precipitationMultiplier, float stormProbabilityMultiplier) {
	public static final ClimateModifiers NEUTRAL = new ClimateModifiers(0.0F, 1.0F, 1.0F, 1.0F);
}
