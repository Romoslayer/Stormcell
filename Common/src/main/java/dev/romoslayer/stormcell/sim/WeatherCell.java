package dev.romoslayer.stormcell.sim;

import dev.romoslayer.stormcell.climate.BiomeClimate;

/**
 * One square of the atmosphere (256x256 blocks by default). Holds the climate of the ground beneath it, worked out
 * once from the biomes there, the atmospheric state the simulation tracks, and a small grid of {@link Samples} saying
 * how strong the storm overhead and the precipitation reaching the ground are across the cell.
 *
 * <p>Only the simulation thread touches a cell. Each refresh builds a new {@link Samples} rather than changing the old
 * one, so a reader on another thread that got hold of a set of samples always sees one consistent refresh.
 */
public final class WeatherCell {
	public final int cx;
	public final int cz;
	public final long key;
	/** Biome climate at each sample point, row by row (index = sz * resolution + sx). */
	final BiomeClimate[] climate;
	/** Terrain height above sea level at the centre, in blocks (0 when unknown or terrain sampling is off). */
	final float elevation;
	final float rainMultiplier;
	final float stormMultiplier;
	/** Share of the cell's sample points over dust-storm biomes. */
	final float dustiness;

	// Atmospheric state, refreshed every time the cell is updated
	public float temperature;
	public float humidity;
	public float pressure = 1013.0F;
	public float windX;
	public float windZ;
	/** Strongest storm overhead anywhere in the cell, and the average precipitation reaching the ground. */
	public float stormIntensity;
	public float precipitationIntensity;
	/** Share of the cell where the ground is too dry for rain to reach it. */
	public float groundDryness;
	/** Share of this cell's moisture recently rained out (0 to 0.6), which recovers over time. */
	public float depletion;
	/** Season multipliers for this cell from the last refresh (see ClimateModifiers). */
	float seasonPrecipitation = 1.0F;
	float seasonStorm = 1.0F;

	/** The latest samples. Replaced (never modified) on every refresh. */
	volatile Samples samples;

	long lastUpdateTick;
	long lastQueriedTick;
	/** Within a player's view (refreshed every step) rather than in the outer ring (refreshed less often). */
	boolean near;
	/** Within the active radius of some player as of the last step. */
	boolean active;
	final int stagger;

	WeatherCell(int cx, int cz, BiomeClimate[] climate, float elevation, long tick) {
		this.cx = cx;
		this.cz = cz;
		this.key = key(cx, cz);
		this.climate = climate;
		this.elevation = elevation;
		int count = climate.length;
		this.samples = Samples.empty(count);
		float rain = 0.0F;
		float storm = 0.0F;
		int dusty = 0;
		for (BiomeClimate sample : climate) {
			rain += sample.rainMultiplier();
			storm += sample.stormMultiplier();
			dusty += sample.dusty() ? 1 : 0;
		}
		this.rainMultiplier = rain / count;
		this.stormMultiplier = storm / count;
		this.dustiness = (float) dusty / count;
		this.lastUpdateTick = tick;
		this.lastQueriedTick = tick;
		this.stagger = Math.floorMod((int) (this.key ^ (this.key >>> 32)) * 0x9E3779B9, 1 << 16);
	}

	public static long key(int cx, int cz) {
		return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
	}

	public static int keyX(long key) {
		return (int) (key >> 32);
	}

	public static int keyZ(long key) {
		return (int) key;
	}

	/**
	 * Per-sample storm strength overhead, precipitation reaching the ground and humidity, each 0 to 1. Treated as
	 * immutable once published.
	 */
	public record Samples(float[] storm, float[] precipitation, float[] humidity, float[] dust) {
		static Samples empty(int count) {
			return new Samples(new float[count], new float[count], new float[count], new float[count]);
		}

		public int size() {
			return this.storm.length;
		}
	}
}
