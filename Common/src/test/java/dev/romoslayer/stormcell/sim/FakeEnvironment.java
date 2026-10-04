package dev.romoslayer.stormcell.sim;

import dev.romoslayer.stormcell.api.ClimateModifiers;
import dev.romoslayer.stormcell.climate.BiomeClimate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/** A made-up world for driving the simulation in tests: chosen climate, players placed by hand, counted lookups. */
final class FakeEnvironment implements WeatherEnvironment {
	final List<Observer> observers = new ArrayList<>();
	BiFunction<Integer, Integer, BiomeClimate> climate;
	ClimateModifiers modifiers = ClimateModifiers.NONE;
	long dayTime = 6000;
	int viewDistance = 10;
	int climateLookups;
	int terrainLookups;
	private final Thread owner = Thread.currentThread();

	FakeEnvironment(BiomeClimate everywhere) {
		this.climate = (x, z) -> everywhere;
	}

	static BiomeClimate climate(float temperature, float humidity, boolean precipitation, float rain, float storm) {
		return climate(temperature, humidity, precipitation, rain, storm, false);
	}

	static BiomeClimate climate(float temperature, float humidity, boolean precipitation, float rain, float storm, boolean dusty) {
		return new BiomeClimate(null, temperature, humidity, precipitation, false, rain, storm, dusty);
	}

	static BiomeClimate plains() {
		return climate(0.8F, 0.4F, true, 1.0F, 1.0F);
	}

	FakeEnvironment player(double x, double z) {
		this.observers.add(new Observer(x, z));
		return this;
	}

	@Override
	public long seed() {
		return 12345L;
	}

	@Override
	public String dimensionId() {
		return "test:world";
	}

	@Override
	public int seaLevel() {
		return 63;
	}

	@Override
	public BiomeClimate climateAt(int x, int z) {
		this.climateLookups++;
		return this.climate.apply(x, z);
	}

	@Override
	public int terrainHeight(int x, int z) {
		this.terrainLookups++;
		return 70;
	}

	@Override
	public ClimateModifiers modifiers(int x, int z, BiomeClimate climate) {
		return this.modifiers;
	}

	@Override
	public List<Observer> observers() {
		return this.observers;
	}

	@Override
	public long dayTime() {
		return this.dayTime;
	}

	@Override
	public int viewDistanceChunks() {
		return this.viewDistance;
	}

	@Override
	public boolean isSimulationThread() {
		return Thread.currentThread() == this.owner;
	}

	/** Runs the simulation for a number of ticks, advancing the time of day with it. */
	void run(LevelWeather weather, int ticks, boolean advance) {
		for (int i = 0; i < ticks; i++) {
			weather.tick(advance);
			if (advance) {
				this.dayTime++;
			}
		}
	}
}
