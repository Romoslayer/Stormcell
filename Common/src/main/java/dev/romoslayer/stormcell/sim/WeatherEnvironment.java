package dev.romoslayer.stormcell.sim;

import dev.romoslayer.stormcell.api.ClimateModifiers;
import dev.romoslayer.stormcell.climate.BiomeClimate;
import java.util.List;

/**
 * Everything the weather simulation needs from the world it runs in. {@link LevelEnvironment} answers from a real
 * dimension; tests answer from a made-up one, which is what lets the simulation be checked and calibrated without a
 * running server.
 */
public interface WeatherEnvironment {
	long seed();

	String dimensionId();

	int seaLevel();

	/** The climate of the ground at a block column (from the world generator: never loads chunks). */
	BiomeClimate climateAt(int x, int z);

	/** Terrain height at a block column, or {@link #UNKNOWN_HEIGHT}. May be expensive. */
	int terrainHeight(int x, int z);

	/** Climate adjustments from season mods for one sample point. */
	ClimateModifiers modifiers(int x, int z, BiomeClimate climate);

	/** Where the players are. */
	List<Observer> observers();

	/** Time of day in ticks, 0 = sunrise, 6000 = noon. */
	long dayTime();

	/** The server's view distance in chunks. */
	int viewDistanceChunks();

	/** Whether the calling thread is the one that runs the simulation. */
	boolean isSimulationThread();

	int UNKNOWN_HEIGHT = Integer.MIN_VALUE;

	/** A player, for deciding where weather is simulated. */
	record Observer(double x, double z) {
	}
}
