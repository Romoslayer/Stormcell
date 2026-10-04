package dev.romoslayer.stormcell.api;

/**
 * A read-only snapshot of the simulated weather at one spot.
 *
 * @param temperature            air temperature on the vanilla biome scale (0.15 is freezing)
 * @param humidity               0 (bone dry) to 1 (saturated)
 * @param pressure               in hectopascals, around 1013
 * @param windDirectionDegrees   the direction the wind blows towards: 0 = south (+Z), 90 = west (-X), as Minecraft yaw
 * @param windSpeed              in blocks per second
 * @param stormIntensity         strength of the storm overhead, 0 (none) to 1 (severe)
 * @param precipitationIntensity strength of the rain or snow reaching the ground here, 0 to 1
 * @param dustIntensity          thickness of the dust in a dust storm here, 0 to 1
 */
public record LocalWeather(float temperature, float humidity, float pressure, float windDirectionDegrees, float windSpeed, float stormIntensity,
		float precipitationIntensity, float dustIntensity) {
}
