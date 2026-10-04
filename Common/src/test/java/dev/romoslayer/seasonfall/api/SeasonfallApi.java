package dev.romoslayer.seasonfall.api;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;

/**
 * Test stand-in with the same signatures as Seasonfall's real API (checked with javap against
 * seasonfall-fabric-1.0.0+26.3.jar): lets the bridge's reflection be exercised without Seasonfall on the classpath.
 */
public final class SeasonfallApi {
	public static boolean seasons = true;
	public static ClimateModifiers answer = ClimateModifiers.NEUTRAL;
	public static int calls;

	private SeasonfallApi() {
	}

	public static boolean hasSeasons(ServerLevel level) {
		return seasons;
	}

	public static ClimateModifiers climate(Holder<Biome> biome) {
		calls++;
		return answer;
	}
}
