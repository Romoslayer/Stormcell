package dev.romoslayer.stormcell.sim;

import dev.romoslayer.stormcell.Stormcell;
import dev.romoslayer.stormcell.api.ClimateModifiers;
import dev.romoslayer.stormcell.api.LocalWeather;
import dev.romoslayer.stormcell.climate.BiomeClimate;
import dev.romoslayer.stormcell.config.StormcellConfig;
import dev.romoslayer.stormcell.persist.WeatherState;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.Nullable;

/**
 * The weather of one dimension.
 *
 * <p>Two layers work together. Storm systems are a short list of moving objects, cheap to keep however far they
 * travel. The atmosphere is a grid of large cells, kept only around players (and anything else that asks about the
 * weather, such as chunk loaders): each cell knows the climate of the ground under it, tracks temperature, humidity,
 * pressure and wind, and holds a small grid of samples saying how much storm and how much rain there is across it.
 * Everything the game asks ("is it raining here?") is answered by blending the four nearest samples.
 *
 * <p>All of this runs on the simulation (server) thread. Other threads read an immutable {@link Snapshot} published
 * after every step; it carries its own grid layout, so it is never mixed up with a grid change.
 *
 * <p>Creating a cell is the expensive part (biome lookups and a terrain height). Every path that creates cells, both
 * the planned ones around players and the ones asked for on the spot, shares one budget per tick
 * (performance.maxNewCellsPerTick). A question about a spot whose cell is not ready yet is answered straight from the
 * storms overhead, so a teleporting player never sees false clear weather.
 */
public final class LevelWeather {
	private static final double TICKS_PER_MINUTE = 1200.0;
	private static final int STORM = 0;
	private static final int PRECIPITATION = 1;
	private static final int DUST = 2;
	/** Below this humidity a storm starts to dry out (deserts, badlands, dry savanna; not temperate land). */
	private static final double DRY_AIR = 0.18;

	private final WeatherEnvironment environment;
	private final RandomSource random;
	private final ValueNoise pressureNoise;
	private final ValueNoise moistureNoise;
	private final ValueNoise windNoise;
	private final ValueNoise temperatureNoise;

	private final Long2ObjectOpenHashMap<WeatherCell> cells = new Long2ObjectOpenHashMap<>();
	private volatile Snapshot snapshot;
	/** Moisture deficits of cells that were dropped, so a region that just had a storm stays drier when revisited. */
	private final Long2ObjectOpenHashMap<Dormant> dormant = new Long2ObjectOpenHashMap<>();
	private final List<StormSystem> storms = new ArrayList<>();
	/** Missing cells around players, nearest first, waiting for creation budget. */
	private final LongArrayList creationQueue = new LongArrayList();
	private int creationQueueIndex;
	private int creationBudget;
	private final Stats stats = new Stats();

	private int cellSize;
	private int resolution;
	private int nextStormId = 1;
	/** Simulated weather time in ticks. Stops while weather progression is paused. */
	private long simTicks;
	/** Ticks this object has been ticked, paused or not. Used for bookkeeping such as cell expiry. */
	private long clock;
	private int ticksUntilStep;
	private long stepCount;
	/** Prevailing wind: the direction it blows towards (Minecraft yaw, radians) and its speed in blocks per second. */
	private double windAngle;
	private double windSpeed;
	/** How far the pressure and moisture patterns have drifted with the wind. */
	private double fieldOffsetX;
	private double fieldOffsetZ;
	private long formationSuppressedUntil;
	/** Bumped on every change worth saving; compared with the value at the last successful save. */
	private long changes = 1;
	private long savedChanges;

	private final double[] windScratch = new double[2];
	private final List<StormSystem> nearbyScratch = new ArrayList<>();
	private @Nullable StormIndex stormIndex;

	public LevelWeather(WeatherEnvironment environment, @Nullable WeatherState saved) {
		this(environment, saved, environment.seed() ^ System.nanoTime());
	}

	/** With a fixed random seed, for reproducible tests. */
	public LevelWeather(WeatherEnvironment environment, @Nullable WeatherState saved, long randomSeed) {
		this.environment = environment;
		long seed = environment.seed() ^ environment.dimensionId().hashCode() * 0x5DEECE66DL;
		this.random = RandomSource.create(randomSeed);
		this.pressureNoise = new ValueNoise(seed ^ 0x51A7E5L);
		this.moistureNoise = new ValueNoise(seed ^ 0x0B5E55EDL);
		this.windNoise = new ValueNoise(seed ^ 0x3F1DL);
		this.temperatureNoise = new ValueNoise(seed ^ 0x7E3A1L);
		StormcellConfig config = StormcellConfig.get();
		this.cellSize = config.cellSizeBlocks();
		this.resolution = config.general.cellResolution;
		this.snapshot = Snapshot.empty(this.cellSize, this.resolution);
		this.windAngle = this.random.nextDouble() * Math.PI * 2.0;
		this.windSpeed = Mth.lerp(this.random.nextDouble(), config.wind.minimumWindSpeed, config.wind.maximumWindSpeed);
		if (saved != null) {
			this.restore(saved, config);
		}
	}

	public WeatherEnvironment environment() {
		return this.environment;
	}

	// ---- Ticking

	/**
	 * Called once per server tick. With {@code advance} false (the advance_weather game rule is off, or the game is
	 * frozen) the weather holds still: storms neither move, grow nor fade and none form, but cells are still created
	 * and refreshed around players so they keep seeing the weather where they are.
	 */
	public void tick(boolean advance) {
		StormcellConfig config = StormcellConfig.get();
		this.clock++;
		this.creationBudget = config.performance.maxNewCellsPerTick;
		if (--this.ticksUntilStep <= 0) {
			int interval = config.general.simulationIntervalTicks;
			this.ticksUntilStep = interval;
			long start = System.nanoTime();
			this.step(config, interval, advance);
			this.stats.recordStep(System.nanoTime() - start);
		}
		this.drainCreationQueue(config);
	}

	private void step(StormcellConfig config, int dt, boolean advance) {
		if (config.cellSizeBlocks() != this.cellSize || config.general.cellResolution != this.resolution) {
			this.resetGrid(config);
		}
		this.stepCount++;
		List<WeatherEnvironment.Observer> observers = this.environment.observers();
		if (advance) {
			this.simTicks += dt;
			this.updateWind(config, dt);
		}
		this.markCells(config, observers);
		this.planCreation(config, observers);
		if (advance) {
			this.updateStorms(config, dt, observers);
		}
		this.refreshCells(config);
		if (advance) {
			this.formStorms(config, dt);
			if (this.stepCount % 20 == 0) {
				this.pruneDormant(config);
			}
			this.changes++;
		}
		this.publishSnapshot();
	}

	/** The cell grid changed size: the old cells and their moisture history no longer line up, so start afresh. */
	private void resetGrid(StormcellConfig config) {
		this.stormsChanged();
		this.cells.clear();
		this.dormant.clear();
		this.creationQueue.clear();
		this.creationQueueIndex = 0;
		this.cellSize = config.cellSizeBlocks();
		this.resolution = config.general.cellResolution;
		this.publishSnapshot();
	}

	/**
	 * After a config reload: cached biome climates are worked out again with the new settings. On an unchanged grid the
	 * moisture history of every cell is kept (cells are retired to the dormant store and pick it up when rebuilt); a
	 * changed grid starts afresh, since the old history no longer lines up.
	 */
	public void configReloaded() {
		StormcellConfig config = StormcellConfig.get();
		if (config.cellSizeBlocks() != this.cellSize || config.general.cellResolution != this.resolution) {
			this.resetGrid(config);
		} else {
			for (WeatherCell cell : this.cells.values()) {
				this.retire(cell);
			}
			this.cells.clear();
			this.creationQueue.clear();
			this.creationQueueIndex = 0;
			this.publishSnapshot();
		}
		this.windSpeed = Mth.clamp(this.windSpeed, config.wind.minimumWindSpeed, config.wind.maximumWindSpeed);
		this.ticksUntilStep = 1;
		this.changes++;
	}

	// ---- Wind and the large atmospheric patterns

	private void updateWind(StormcellConfig config, int dt) {
		StormcellConfig.Wind wind = config.wind;
		double minutes = dt / TICKS_PER_MINUTE;
		double root = Math.sqrt(minutes);
		this.windAngle += this.random.nextGaussian() * Math.toRadians(wind.windDirectionChangeRate) * root;
		this.windAngle = Mth.wrapDegrees(Math.toDegrees(this.windAngle)) * Mth.DEG_TO_RAD;
		double mean = (wind.minimumWindSpeed + wind.maximumWindSpeed) * 0.5;
		this.windSpeed += (mean - this.windSpeed) * 0.03 * minutes + this.random.nextGaussian() * wind.windSpeedChangeRate * root;
		this.windSpeed = Mth.clamp(this.windSpeed, wind.minimumWindSpeed, wind.maximumWindSpeed);
		double speed = wind.windEnabled ? this.windSpeed : wind.calmDriftSpeed;
		// Pressure and moisture patterns drift with the prevailing wind, a little slower than the storms themselves
		this.fieldOffsetX += -Math.sin(this.windAngle) * speed * 0.5 * dt / 20.0;
		this.fieldOffsetZ += Math.cos(this.windAngle) * speed * 0.5 * dt / 20.0;
	}

	/** Wind at a point in blocks per second, as {x, z}. Varies smoothly around the prevailing wind. */
	public double[] windAt(double x, double z) {
		double[] out = new double[2];
		this.windInto(x, z, out);
		return out;
	}

	private void windInto(double x, double z, double[] out) {
		StormcellConfig.Wind wind = StormcellConfig.get().wind;
		if (!wind.windEnabled) {
			out[0] = 0.0;
			out[1] = 0.0;
			return;
		}
		double angle = this.windAngle;
		double speed = this.windSpeed;
		double variation = wind.localWindVariation;
		if (variation > 0.0) {
			double t = this.simTicks / 48000.0;
			double n1 = Mth.clamp(this.windNoise.sample(x / 3500.0, t, z / 3500.0) * 1.6, -1.0, 1.0);
			double n2 = Mth.clamp(this.windNoise.sample(x / 3500.0 + 31.7, t + 5.0, z / 3500.0 - 12.3) * 1.6, -1.0, 1.0);
			angle += n1 * variation * Math.PI / 3.0;
			speed *= 1.0 + n2 * variation * 0.5;
		}
		out[0] = -Math.sin(angle) * speed;
		out[1] = Math.cos(angle) * speed;
	}

	/** -1 (deep low, unsettled) to 1 (strong high, settled). */
	private double pressureField(StormcellConfig config, double x, double z) {
		double size = config.atmosphere.pressureSystemSizeBlocks;
		double t = this.simTicks / (config.atmosphere.pressureSystemLifetimeMinutes * TICKS_PER_MINUTE);
		return Mth.clamp(this.pressureNoise.fractal((x - this.fieldOffsetX) / size, t, (z - this.fieldOffsetZ) / size) * 1.7, -1.0, 1.0);
	}

	/** -1 (unusually dry spell) to 1 (unusually moist). */
	private double moistureField(StormcellConfig config, double x, double z) {
		double size = config.atmosphere.pressureSystemSizeBlocks * 0.7;
		double t = this.simTicks / (config.atmosphere.pressureSystemLifetimeMinutes * TICKS_PER_MINUTE) * 1.3;
		return Mth.clamp(this.moistureNoise.fractal((x - this.fieldOffsetX) / size + 40.0, t, (z - this.fieldOffsetZ) / size) * 1.7, -1.0, 1.0);
	}

	/** -1 at night to 1 in mid-afternoon. */
	private double dayCycle() {
		long time = Math.floorMod(this.environment.dayTime(), 24000L);
		return Math.cos((time - 8000.0) / 24000.0 * Math.PI * 2.0);
	}

	// ---- Cells: which exist, which are active, and creating them

	/** Flags each cell as near/active from the players' positions and drops cells nobody has needed for a while. */
	private void markCells(StormcellConfig config, List<WeatherEnvironment.Observer> observers) {
		int activeRadius = this.activeRadiusCells(config);
		int nearRadius = Mth.ceil(this.environment.viewDistanceChunks() * 16.0 / this.cellSize) + 1;
		double activeLimit = (activeRadius + 0.5) * (activeRadius + 0.5);
		int count = observers.size();
		int[] px = new int[count];
		int[] pz = new int[count];
		for (int i = 0; i < count; i++) {
			px[i] = Math.floorDiv((int) Math.floor(observers.get(i).x()), this.cellSize);
			pz[i] = Math.floorDiv((int) Math.floor(observers.get(i).z()), this.cellSize);
		}
		long timeout = config.performance.passiveCellTimeoutSeconds * 20L;
		ObjectIterator<Long2ObjectMap.Entry<WeatherCell>> iterator = this.cells.long2ObjectEntrySet().fastIterator();
		while (iterator.hasNext()) {
			WeatherCell cell = iterator.next().getValue();
			boolean active = false;
			boolean near = false;
			for (int i = 0; i < count && !near; i++) {
				int dx = cell.cx - px[i];
				int dz = cell.cz - pz[i];
				active |= dx * dx + dz * dz <= activeLimit;
				near = Math.abs(dx) <= nearRadius && Math.abs(dz) <= nearRadius;
			}
			cell.active = active || near;
			cell.near = near;
			if (!cell.active && this.clock - cell.lastQueriedTick > timeout) {
				this.retire(cell);
				iterator.remove();
			}
		}
	}

	private int activeRadiusCells(StormcellConfig config) {
		return Mth.ceil(config.performance.activeRadiusChunks * 16.0 / this.cellSize);
	}

	/**
	 * Lists the missing cells around players, ring by ring outwards and taking turns between players, so every player's
	 * immediate surroundings come first. Stops early rather than walking an enormous radius.
	 */
	private void planCreation(StormcellConfig config, List<WeatherEnvironment.Observer> observers) {
		this.creationQueue.clear();
		this.creationQueueIndex = 0;
		if (observers.isEmpty()) {
			return;
		}
		int activeRadius = this.activeRadiusCells(config);
		double limit = (activeRadius + 0.5) * (activeRadius + 0.5);
		int cap = config.general.maxActiveWeatherCells;
		int maxQueue = Math.max(16, config.performance.maxNewCellsPerTick * config.general.simulationIntervalTicks);
		int scanLimit = cap * 2 + observers.size() * 64;
		int scanned = 0;
		LongOpenHashSet queued = new LongOpenHashSet();
		int[] px = new int[observers.size()];
		int[] pz = new int[observers.size()];
		for (int i = 0; i < observers.size(); i++) {
			px[i] = Math.floorDiv((int) Math.floor(observers.get(i).x()), this.cellSize);
			pz[i] = Math.floorDiv((int) Math.floor(observers.get(i).z()), this.cellSize);
		}
		for (int ring = 0; ring <= activeRadius; ring++) {
			for (int p = 0; p < px.length; p++) {
				for (int dx = -ring; dx <= ring; dx++) {
					int step = Math.abs(dx) == ring ? 1 : 2 * ring;
					for (int dz = -ring; dz <= ring; dz += Math.max(1, step)) {
						if (dx * dx + dz * dz > limit) {
							continue;
						}
						long key = WeatherCell.key(px[p] + dx, pz[p] + dz);
						if (++scanned > scanLimit || this.creationQueue.size() >= maxQueue) {
							return;
						}
						if (!this.cells.containsKey(key) && queued.add(key)) {
							this.creationQueue.add(key);
						}
					}
				}
			}
		}
	}

	private void drainCreationQueue(StormcellConfig config) {
		while (this.creationBudget > 0 && this.creationQueueIndex < this.creationQueue.size()) {
			long key = this.creationQueue.getLong(this.creationQueueIndex++);
			if (this.cells.containsKey(key)) {
				continue;
			}
			if (!this.makeRoom(config)) {
				this.creationQueueIndex = this.creationQueue.size();
				return;
			}
			this.creationBudget--;
			this.createCell(config, WeatherCell.keyX(key), WeatherCell.keyZ(key));
		}
	}

	/** True if there is room for one more cell, dropping the longest-unneeded inactive cell if the limit is reached. */
	private boolean makeRoom(StormcellConfig config) {
		if (this.cells.size() < config.general.maxActiveWeatherCells) {
			return true;
		}
		WeatherCell oldest = null;
		for (WeatherCell cell : this.cells.values()) {
			if (!cell.active && cell.lastQueriedTick < this.clock && (oldest == null || cell.lastQueriedTick < oldest.lastQueriedTick)) {
				oldest = cell;
			}
		}
		if (oldest == null) {
			return false;
		}
		this.retire(oldest);
		this.cells.remove(oldest.key);
		this.stats.evictions++;
		return true;
	}

	/** Remembers a dropped cell's moisture deficit. */
	private void retire(WeatherCell cell) {
		if (cell.depletion > 0.01F) {
			this.dormant.put(cell.key, new Dormant(cell.depletion, this.simTicks));
		}
	}

	/**
	 * Forgets moisture deficits that have recovered, and if more are remembered than the cell limit, keeps only the
	 * largest. Dropping a small deficit early just means that region recovers a little sooner than it would have.
	 */
	void pruneDormant(StormcellConfig config) {
		double recovery = config.stormBehavior.moistureRecoveryMinutes * TICKS_PER_MINUTE;
		this.dormant.long2ObjectEntrySet().removeIf(entry -> entry.getValue().current(this.simTicks, recovery) < 0.01);
		int cap = config.general.maxActiveWeatherCells;
		if (this.dormant.size() > cap) {
			List<Long2ObjectMap.Entry<Dormant>> entries = new ArrayList<>(this.dormant.long2ObjectEntrySet());
			entries.sort(Comparator.comparingDouble(entry -> -entry.getValue().current(this.simTicks, recovery)));
			Long2ObjectOpenHashMap<Dormant> kept = new Long2ObjectOpenHashMap<>();
			for (int i = 0; i < cap; i++) {
				kept.put(entries.get(i).getLongKey(), entries.get(i).getValue());
			}
			this.dormant.clear();
			this.dormant.putAll(kept);
		}
	}

	private WeatherCell createCell(StormcellConfig config, int cx, int cz) {
		int n = this.resolution;
		double spacing = (double) this.cellSize / n;
		BiomeClimate[] climate = new BiomeClimate[n * n];
		for (int sz = 0; sz < n; sz++) {
			for (int sx = 0; sx < n; sx++) {
				int bx = cx * this.cellSize + (int) ((sx + 0.5) * spacing);
				int bz = cz * this.cellSize + (int) ((sz + 0.5) * spacing);
				climate[sz * n + sx] = this.environment.climateAt(bx, bz);
			}
		}
		float elevation = 0.0F;
		if (config.elevation.terrainSampling) {
			int height = this.environment.terrainHeight(cx * this.cellSize + this.cellSize / 2, cz * this.cellSize + this.cellSize / 2);
			this.stats.terrainSamples++;
			if (height != WeatherEnvironment.UNKNOWN_HEIGHT) {
				elevation = Math.max(0, height - this.environment.seaLevel());
			}
		}
		WeatherCell cell = new WeatherCell(cx, cz, climate, elevation, this.clock);
		cell.lastUpdateTick = this.simTicks;
		Dormant old = this.dormant.remove(cell.key);
		if (old != null) {
			cell.depletion = (float) old.current(this.simTicks, config.stormBehavior.moistureRecoveryMinutes * TICKS_PER_MINUTE);
		}
		this.cells.put(cell.key, cell);
		this.stats.creations++;
		this.refreshCell(config, cell);
		return cell;
	}

	/** The cell at a cell position for answering a question, creating it within budget. Null if it is not ready. */
	private @Nullable WeatherCell cellForQuery(int cx, int cz) {
		WeatherCell cell = this.cells.get(WeatherCell.key(cx, cz));
		if (cell == null) {
			StormcellConfig config = StormcellConfig.get();
			if (this.creationBudget <= 0 || !this.makeRoom(config)) {
				this.stats.fallbacks++;
				return null;
			}
			this.creationBudget--;
			cell = this.createCell(config, cx, cz);
		}
		cell.lastQueriedTick = this.clock;
		return cell;
	}

	private void refreshCells(StormcellConfig config) {
		int rate = config.performance.inactiveCellUpdateRate;
		for (WeatherCell cell : this.cells.values()) {
			// Cells a player can see, or that chunk loaders asked about since the last step, are kept exact
			boolean needed = cell.near || this.clock - cell.lastQueriedTick <= config.general.simulationIntervalTicks;
			if (needed || rate <= 1 || (this.stepCount + cell.stagger) % rate == 0) {
				this.refreshCell(config, cell);
			}
		}
	}

	/** Brings a cell's atmosphere and samples up to date with the storms around it. */
	private void refreshCell(StormcellConfig config, WeatherCell cell) {
		long elapsed = Math.max(0L, this.simTicks - cell.lastUpdateTick);
		cell.lastUpdateTick = this.simTicks;
		double half = this.cellSize * 0.5;
		double centerX = cell.cx * (double) this.cellSize + half;
		double centerZ = cell.cz * (double) this.cellSize + half;
		StormcellConfig.Atmosphere atmosphere = config.atmosphere;

		// Season mods (Seasonfall, API providers) adjust the climate of each sample point
		int n = this.resolution;
		int count = n * n;
		double spacing = (double) this.cellSize / n;
		float[] humidityScale = new float[count];
		double biomeTemperature = 0.0;
		double biomeHumidity = 0.0;
		double seasonPrecipitation = 0.0;
		double seasonStorm = 0.0;
		for (int i = 0; i < count; i++) {
			BiomeClimate climate = cell.climate[i];
			int sx = (int) (cell.cx * (double) this.cellSize + (i % n + 0.5) * spacing);
			int sz = (int) (cell.cz * (double) this.cellSize + (i / n + 0.5) * spacing);
			ClimateModifiers modifiers = this.environment.modifiers(sx, sz, climate);
			humidityScale[i] = modifiers.humidityMultiplier();
			biomeTemperature += climate.temperature() + modifiers.temperatureOffset();
			biomeHumidity += climate.humidity() * modifiers.humidityMultiplier();
			seasonPrecipitation += modifiers.precipitationMultiplier();
			seasonStorm += modifiers.stormProbabilityMultiplier();
		}
		cell.seasonPrecipitation = (float) (seasonPrecipitation / count);
		cell.seasonStorm = (float) (seasonStorm / count);

		// Humidity: the biomes' own, swung up or down by regional moisture, minus what recent rain took out
		double anomaly = atmosphere.humidityEnabled ? this.moistureField(config, centerX, centerZ) * atmosphere.moistureVariation * atmosphere.humidityInfluence : 0.0;
		cell.humidity = atmosphere.humidityEnabled ? (float) Mth.clamp((biomeHumidity / count + anomaly) * (1.0 - cell.depletion), 0.0, 1.0) : 0.5F;

		// Temperature: biome temperature, cooled with height, warmed in the afternoon
		if (atmosphere.temperatureEnabled) {
			double temperature = biomeTemperature / count;
			if (config.elevation.elevationTemperatureEnabled && cell.elevation > 17.0F) {
				temperature -= (cell.elevation - 17.0F) * (0.05 / 40.0 + config.elevation.extraTemperatureLossPerBlock);
			}
			temperature += this.dayCycle() * atmosphere.dayNightTemperatureSwing;
			temperature += this.temperatureNoise.sample(centerX / 2000.0, this.simTicks / 36000.0, centerZ / 2000.0) * 0.08;
			cell.temperature = (float) temperature;
		} else {
			cell.temperature = 0.8F;
		}

		// Find the storms that can reach this cell at all, then fill in the samples
		double cellReach = half * Math.sqrt(2.0);
		List<StormSystem> nearby = this.nearbyScratch;
		nearby.clear();
		for (StormSystem storm : this.stormIndex().near(centerX, centerZ)) {
			double reach = storm.reach() + cellReach;
			double dx = storm.x - centerX;
			double dz = storm.z - centerZ;
			if (dx * dx + dz * dz < reach * reach) {
				nearby.add(storm);
			}
		}
		double pressure = atmosphere.pressureEnabled ? 1013.0 + 16.0 * this.pressureField(config, centerX, centerZ) * atmosphere.pressureInfluence : 1013.0;
		float[] stormValues = new float[count];
		float[] precipitationValues = new float[count];
		float[] humidityValues = new float[count];
		float[] dustValues = new float[count];
		int dryPoints = 0;
		float maxStorm = 0.0F;
		float totalPrecipitation = 0.0F;
		double dryGround = config.biomes.dryGroundHumidity;
		boolean biomeGround = config.biomes.biomeWeatherInfluence && atmosphere.humidityEnabled;
		for (int i = 0; i < count; i++) {
			double px = cell.cx * (double) this.cellSize + (i % n + 0.5) * spacing;
			double pz = cell.cz * (double) this.cellSize + (i / n + 0.5) * spacing;
			float aloft = combine(nearby, px, pz);
			float sampleHumidity = atmosphere.humidityEnabled
					? (float) Mth.clamp((cell.climate[i].humidity() * humidityScale[i] + anomaly) * (1.0 - cell.depletion * 0.5), 0.0, 1.0)
					: 0.5F;
			float ground = aloft;
			if (biomeGround) {
				double reaching = smoothstep(dryGround * 0.4, dryGround + 0.12, sampleHumidity);
				ground *= (float) reaching;
				dryPoints += reaching < 0.5 ? 1 : 0;
			}
			dustValues[i] = combineDust(nearby, px, pz);
			stormValues[i] = aloft;
			precipitationValues[i] = ground;
			humidityValues[i] = sampleHumidity;
			maxStorm = Math.max(maxStorm, aloft);
			totalPrecipitation += ground;
		}
		cell.samples = new WeatherCell.Samples(stormValues, precipitationValues, humidityValues, dustValues);
		cell.groundDryness = (float) dryPoints / count;
		for (StormSystem storm : nearby) {
			if (storm.kind == StormSystem.Kind.DUST_STORM) {
				continue;
			}
			pressure -= 10.0 * storm.intensityAt(centerX, centerZ);
		}
		cell.pressure = (float) pressure;
		cell.stormIntensity = maxStorm;
		cell.precipitationIntensity = totalPrecipitation / count;
		this.windInto(centerX, centerZ, this.windScratch);
		cell.windX = (float) this.windScratch[0];
		cell.windZ = (float) this.windScratch[1];

		// Rain dries the air out; the moisture comes back over time
		double minutes = elapsed / TICKS_PER_MINUTE;
		if (minutes > 0.0) {
			StormcellConfig.StormBehavior behavior = config.stormBehavior;
			cell.depletion *= (float) Math.exp(-minutes / behavior.moistureRecoveryMinutes);
			cell.depletion = (float) Math.min(0.6, cell.depletion + cell.precipitationIntensity * behavior.moistureConsumption * minutes);
		}
	}

	/** Storm strength at a point from the given storms: the strongest, plus a little of the second strongest. Dust storms do not count. */
	private static float combine(List<StormSystem> storms, double x, double z) {
		double strongest = 0.0;
		double second = 0.0;
		for (StormSystem storm : storms) {
			if (storm.kind == StormSystem.Kind.DUST_STORM) {
				continue;
			}
			double value = storm.intensityAt(x, z);
			if (value > strongest) {
				second = strongest;
				strongest = value;
			} else if (value > second) {
				second = value;
			}
		}
		return (float) Math.min(1.0, strongest + second * 0.25);
	}

	/** Dust thickness at a point: the thickest dust storm there. */
	private static float combineDust(List<StormSystem> storms, double x, double z) {
		double thickest = 0.0;
		for (StormSystem storm : storms) {
			if (storm.kind == StormSystem.Kind.DUST_STORM) {
				thickest = Math.max(thickest, storm.intensityAt(x, z));
			}
		}
		return (float) Math.min(1.0, thickest);
	}

	/** The storm index, rebuilt if the storms changed since it was made. */
	private StormIndex stormIndex() {
		if (this.stormIndex == null) {
			this.stormIndex = new StormIndex(this.storms, this.cellSize);
		}
		return this.stormIndex;
	}

	/** Storms were added, removed or moved: the index must be rebuilt before it is used again. */
	private void stormsChanged() {
		this.stormIndex = null;
	}

	private void publishSnapshot() {
		Long2ObjectOpenHashMap<WeatherCell.Samples> samples = new Long2ObjectOpenHashMap<>(this.cells.size());
		for (WeatherCell cell : this.cells.values()) {
			samples.put(cell.key, cell.samples);
		}
		this.snapshot = new Snapshot(this.cellSize, this.resolution, samples);
	}

	// ---- Storms

	private void updateStorms(StormcellConfig config, int dt, List<WeatherEnvironment.Observer> observers) {
		StormcellConfig.StormBehavior behavior = config.stormBehavior;
		StormcellConfig.Wind wind = config.wind;
		double minutes = dt / TICKS_PER_MINUTE;
		double despawn = config.performance.despawnDistanceChunks * 16.0;
		long maxAge = (long) (behavior.maximumStormLifetimeMinutes * TICKS_PER_MINUTE);

		Iterator<StormSystem> iterator = this.storms.iterator();
		while (iterator.hasNext()) {
			StormSystem storm = iterator.next();
			StormcellConfig.StormType type = storm.kind.settings(config);
			boolean holding = storm.anchored && storm.phase != StormSystem.Phase.DISSIPATING;

			// Movement: ease towards the local wind so storms turn and speed up gradually
			if (holding) {
				storm.vx = 0.0;
				storm.vz = 0.0;
			} else {
				double speedScale = type.speedFactor * behavior.stormMovementSpeedMultiplier / 20.0;
				double targetX;
				double targetZ;
				if (wind.windEnabled && wind.windAffectsStormMovement) {
					this.windInto(storm.x, storm.z, this.windScratch);
					targetX = this.windScratch[0] * speedScale;
					targetZ = this.windScratch[1] * speedScale;
				} else {
					targetX = -Math.sin(this.windAngle) * wind.calmDriftSpeed * speedScale;
					targetZ = Math.cos(this.windAngle) * wind.calmDriftSpeed * speedScale;
				}
				double ease = Math.min(1.0, dt / 600.0);
				storm.vx += (targetX - storm.vx) * ease;
				storm.vz += (targetZ - storm.vz) * ease;
				storm.x += storm.vx * dt;
				storm.z += storm.vz * dt;
				storm.updateHeading();
			}
			storm.ageTicks += dt;
			storm.phaseTicks += dt;

			// Conditions underneath: moist air feeds a storm, dry air starves it
			WeatherCell below = this.cells.get(WeatherCell.key(Math.floorDiv((int) Math.floor(storm.x), this.cellSize),
					Math.floorDiv((int) Math.floor(storm.z), this.cellSize)));
			double humidity = below == null ? 0.5 : below.humidity;
			double support = Mth.clamp((humidity - 0.15) / 0.5, 0.0, 1.2);
			// With no cell underneath yet (outside the simulated area) the ground is unknown: leave the storm as it is
			if (below != null && storm.dry && humidity >= DRY_AIR * 2.0) {
				// A heat storm that reaches moist land becomes an ordinary storm (and its rain reaches the ground)
				storm.dry = false;
			}
			if (storm.kind == StormSystem.Kind.DUST_STORM) {
				// Dust settles fast over moist, green land; dry air is what it needs
				if (!holding && below != null && humidity > 0.3) {
					storm.potential -= behavior.dryAirDecayRate * 2.0 * minutes * Math.min(1.0, (humidity - 0.3) / 0.2);
				}
			} else if (!holding && !storm.dry && below != null) {
				if (humidity < DRY_AIR) {
					storm.potential -= behavior.dryAirDecayRate * minutes * (DRY_AIR - humidity) / DRY_AIR;
				} else if (below != null && storm.kind == StormSystem.Kind.THUNDERSTORM && below.temperature < config.elevation.snowTemperatureThreshold) {
					// Thunderstorms cannot keep going in freezing air
					storm.potential -= behavior.stormDecayRate * 0.5 * minutes;
				}
			}

			switch (storm.phase) {
				case FORMING -> {
					storm.intensity += behavior.stormGrowthRate * minutes * (0.4 + 0.6 * support);
					if (storm.intensity >= storm.potential) {
						storm.intensity = storm.potential;
						storm.phase = StormSystem.Phase.MATURE;
						storm.phaseTicks = 0;
					}
				}
				case MATURE -> {
					storm.intensity += (storm.potential - storm.intensity) * Math.min(1.0, minutes);
					if (!storm.anchored) {
						// Rich, humid air keeps a natural mature storm going for longer
						storm.phaseTicks -= (long) (dt * Math.max(0.0, support - 0.8) * 0.5);
					}
					if (storm.phaseTicks >= storm.matureTicks) {
						storm.phase = StormSystem.Phase.DISSIPATING;
						storm.phaseTicks = 0;
						storm.anchored = false;
					}
				}
				case DISSIPATING -> storm.intensity -= behavior.stormDecayRate * storm.decayBoost * minutes;
			}
			storm.intensity = Math.min(storm.intensity, Math.max(0.0, storm.potential));
			if (storm.ageTicks > maxAge && storm.phase != StormSystem.Phase.DISSIPATING && !storm.anchored) {
				storm.phase = StormSystem.Phase.DISSIPATING;
				storm.phaseTicks = 0;
			}

			boolean gone = storm.phase == StormSystem.Phase.DISSIPATING && storm.intensity <= 0.01 || storm.potential <= 0.02;
			if (!gone && !observers.isEmpty()) {
				double nearest = Double.MAX_VALUE;
				for (WeatherEnvironment.Observer observer : observers) {
					double dx = observer.x() - storm.x;
					double dz = observer.z() - storm.z;
					nearest = Math.min(nearest, dx * dx + dz * dz);
				}
				double limit = despawn + storm.reach();
				gone = nearest > limit * limit;
			}
			if (gone) {
				iterator.remove();
			}
		}

		if (behavior.allowStormMerging) {
			this.mergeStorms();
		}
		if (behavior.allowStormSplitting) {
			this.splitStorms(config, minutes);
		}
		this.stormsChanged();
	}

	private void mergeStorms() {
		for (int i = 0; i < this.storms.size(); i++) {
			StormSystem a = this.storms.get(i);
			for (int j = this.storms.size() - 1; j > i; j--) {
				StormSystem b = this.storms.get(j);
				if ((a.kind == StormSystem.Kind.DUST_STORM) != (b.kind == StormSystem.Kind.DUST_STORM)) {
					continue; // dust and rain storms pass through each other
				}
				double dx = a.x - b.x;
				double dz = a.z - b.z;
				double touch = (a.radius + b.radius) * 0.45;
				if (dx * dx + dz * dz >= touch * touch) {
					continue;
				}
				double areaA = a.radius * a.radius * a.length * a.width;
				double areaB = b.radius * b.radius * b.length * b.width;
				double share = areaB / (areaA + areaB);
				a.x += (b.x - a.x) * share;
				a.z += (b.z - a.z) * share;
				a.vx += (b.vx - a.vx) * share;
				a.vz += (b.vz - a.vz) * share;
				a.updateHeading();
				a.radius = Math.min(Math.sqrt(a.radius * a.radius + b.radius * b.radius), Math.max(a.radius, b.radius) * 1.4);
				a.length += (b.length - a.length) * share;
				a.width += (b.width - a.width) * share;
				a.potential = Math.min(1.0, Math.max(a.potential, b.potential) + 0.05 * Math.min(a.potential, b.potential));
				a.intensity = Math.max(a.intensity, b.intensity);
				if (b.kind == StormSystem.Kind.THUNDERSTORM || b.kind == StormSystem.Kind.RAIN_SYSTEM && a.kind == StormSystem.Kind.SHOWER) {
					a.kind = b.kind;
				}
				if (a.phase == StormSystem.Phase.DISSIPATING && b.phase != StormSystem.Phase.DISSIPATING) {
					a.phase = b.phase;
					a.phaseTicks = b.phaseTicks;
					a.matureTicks = b.matureTicks;
				} else if (a.phase != StormSystem.Phase.DISSIPATING) {
					a.matureTicks += Math.max(0L, b.matureTicks - b.phaseTicks) / 2;
				}
				a.anchored |= b.anchored;
				a.dry &= b.dry;
				a.ageTicks = Math.min(a.ageTicks, b.ageTicks);
				this.storms.remove(j);
			}
		}
	}

	private void splitStorms(StormcellConfig config, double minutes) {
		double chance = config.stormBehavior.splitChancePerMinute * minutes;
		List<StormSystem> born = new ArrayList<>();
		for (StormSystem storm : this.storms) {
			if (storm.phase != StormSystem.Phase.MATURE || storm.anchored || storm.radius < 300.0 || this.random.nextDouble() >= chance
					|| this.storms.size() + born.size() >= config.performance.maximumStormSystems) {
				continue;
			}
			double px = -storm.headingZ;
			double pz = storm.headingX;
			double offset = storm.radius * 0.4;
			StormSystem twin = new StormSystem(this.nextStormId++, storm.kind, storm.x - px * offset, storm.z - pz * offset);
			storm.x += px * offset;
			storm.z += pz * offset;
			storm.radius /= Math.sqrt(2.0);
			storm.potential *= 0.9;
			storm.intensity = Math.min(storm.intensity, storm.potential);
			twin.radius = storm.radius;
			twin.length = storm.length;
			twin.width = storm.width;
			twin.potential = storm.potential;
			twin.intensity = storm.intensity;
			twin.phase = StormSystem.Phase.MATURE;
			twin.phaseTicks = storm.phaseTicks;
			twin.matureTicks = storm.matureTicks;
			twin.ageTicks = storm.ageTicks;
			// Drift apart: each turns about 15 degrees away from the other
			double turn = Math.toRadians(15.0);
			double cos = Math.cos(turn);
			double sin = Math.sin(turn);
			twin.vx = storm.vx * cos + storm.vz * sin;
			twin.vz = -storm.vx * sin + storm.vz * cos;
			double vx = storm.vx * cos - storm.vz * sin;
			storm.vz = storm.vx * sin + storm.vz * cos;
			storm.vx = vx;
			storm.updateHeading();
			twin.headingX = storm.headingX;
			twin.headingZ = storm.headingZ;
			twin.updateHeading();
			born.add(twin);
		}
		this.storms.addAll(born);
	}

	private void formStorms(StormcellConfig config, int dt) {
		int players = Math.max(1, this.environment.observers().size());
		int limit = (int) Math.min(config.performance.maximumStormSystems, (long) config.performance.stormSystemsPerPlayer * players);
		if (this.simTicks < this.formationSuppressedUntil || this.storms.size() >= limit) {
			return;
		}
		StormcellConfig.Generation generation = config.generation;
		StormcellConfig.Atmosphere atmosphere = config.atmosphere;
		double minutes = dt / TICKS_PER_MINUTE;
		double cloudy = config.thresholds.cloudy;
		double dayCycle = this.dayCycle();
		// Roll every active cell first, then pick among the successes, so no cell is favoured by iteration order
		List<StormSystem> candidates = new ArrayList<>();
		for (WeatherCell cell : this.cells.values()) {
			if (!cell.active || cell.stormIntensity >= cloudy) {
				continue;
			}
			double humidityFactor = atmosphere.humidityEnabled ? Math.min(1.6, Math.pow(cell.humidity / 0.5, atmosphere.humidityInfluence)) : 1.0;
			double pressureFactor = 1.0;
			if (atmosphere.pressureEnabled) {
				double field = (cell.pressure - 1013.0) / 16.0;
				pressureFactor = Mth.clamp(1.0 - 0.6 * field * atmosphere.pressureInfluence, 0.15, 2.5);
			}
			double biome = config.biomes.biomeWeatherInfluence ? cell.rainMultiplier : 1.0;
			double chance = generation.formationChancePerMinute * minutes * generation.rainChanceMultiplier * humidityFactor * pressureFactor * biome
					* cell.seasonPrecipitation;
			// A convective storm can form even where plain rain is unlikely (a savanna's rare, violent storms)
			double convection = this.convection(config, cell, dayCycle);
			double stormBias = convection * (config.biomes.biomeWeatherInfluence ? cell.stormMultiplier : 1.0) * cell.seasonStorm * generation.stormChanceMultiplier;
			double stormChance = generation.formationChancePerMinute * minutes * stormBias * pressureFactor * config.stormTypes.thunderstorm.weight;
			double roll = this.random.nextDouble();
			if (roll >= chance + stormChance) {
				continue;
			}
			StormSystem.Kind kind;
			if (roll < stormChance) {
				kind = StormSystem.Kind.THUNDERSTORM;
			} else {
				double rainWeight = config.stormTypes.rainSystem.weight * Math.max(1.0, pressureFactor);
				double showerWeight = config.stormTypes.shower.weight;
				if (rainWeight + showerWeight <= 0.0) {
					continue;
				}
				kind = this.random.nextDouble() * (rainWeight + showerWeight) < rainWeight ? StormSystem.Kind.RAIN_SYSTEM : StormSystem.Kind.SHOWER;
			}
			double x = cell.cx * (double) this.cellSize + this.random.nextDouble() * this.cellSize;
			double z = cell.cz * (double) this.cellSize + this.random.nextDouble() * this.cellSize;
			StormSystem storm = this.createStorm(config, kind, x, z, Double.NaN, -1L, false);
			if (kind == StormSystem.Kind.THUNDERSTORM && cell.temperature < config.elevation.snowTemperatureThreshold) {
				// Too cold for thunder: it becomes a heavy snow shower instead
				storm.kind = StormSystem.Kind.SHOWER;
				storm.potential = Math.min(storm.potential, config.thresholds.heavyRain + 0.05);
			}
			candidates.add(storm);
		}
		this.formDryWeather(config, minutes, dayCycle, candidates);
		int room = Math.min(generation.maximumFormationsPerStep * players, limit - this.storms.size());
		if (candidates.size() > room) {
			Collections.shuffle(candidates, new java.util.Random(this.random.nextLong()));
		}
		for (int i = 0; i < Math.min(room, candidates.size()); i++) {
			this.storms.add(candidates.get(i));
		}
		this.stormsChanged();
	}

	/**
	 * Weather of hot, dry land: heat-driven thunderstorms whose rain never reaches the ground, and dust storms over
	 * sandy biomes, which need wind. Rolled separately from rain, which hardly ever forms over such land.
	 */
	private void formDryWeather(StormcellConfig config, double minutes, double dayCycle, List<StormSystem> candidates) {
		StormcellConfig.DryWeather dry = config.dryWeather;
		double base = config.generation.formationChancePerMinute * minutes;
		double windShare = config.wind.maximumWindSpeed <= 0.0 ? 0.0 : Mth.clamp(this.windSpeed / config.wind.maximumWindSpeed, 0.0, 1.0);
		double cloudy = config.thresholds.cloudy;
		for (WeatherCell cell : this.cells.values()) {
			if (!cell.active || cell.groundDryness < 0.5) {
				continue;
			}
			double afternoon = 0.3 + 0.7 * Math.max(0.0, dayCycle);
			if (dry.dryThunderstorms && cell.stormIntensity < cloudy && cell.temperature >= dry.hotTemperature) {
				double heat = Mth.clamp((cell.temperature - dry.hotTemperature) / 0.6, 0.0, 1.3);
				if (this.random.nextDouble() < base * dry.dryThunderstormChanceMultiplier * heat * afternoon * cell.groundDryness * 2.0) {
					StormSystem storm = this.createStorm(config, StormSystem.Kind.THUNDERSTORM, cell.cx * (double) this.cellSize + this.random.nextDouble() * this.cellSize,
							cell.cz * (double) this.cellSize + this.random.nextDouble() * this.cellSize, Double.NaN, -1L, false);
					// Dry storms exist for their thunder: always strong enough for lightning
					storm.potential = Math.max(storm.potential, Math.min(1.0, config.thresholds.thunderstorm + 0.03));
					storm.dry = true;
					candidates.add(storm);
					continue;
				}
			}
			if (dry.dustStorms && cell.dustiness >= 0.5 && this.dustAtCell(cell) < cloudy) {
				if (this.random.nextDouble() < base * dry.dustStormChanceMultiplier * cell.dustiness * (0.2 + windShare) * afternoon * 2.2) {
					candidates.add(this.createStorm(config, StormSystem.Kind.DUST_STORM, cell.cx * (double) this.cellSize + this.random.nextDouble() * this.cellSize,
							cell.cz * (double) this.cellSize + this.random.nextDouble() * this.cellSize, Double.NaN, -1L, false));
				}
			}
		}
	}

	private float dustAtCell(WeatherCell cell) {
		float max = 0.0F;
		for (float value : cell.samples.dust()) {
			max = Math.max(max, value);
		}
		return max;
	}

	/** How favourable the air over a cell is for convective storms: warm, humid, and best in the afternoon. */
	private double convection(StormcellConfig config, WeatherCell cell, double dayCycle) {
		StormcellConfig.Atmosphere atmosphere = config.atmosphere;
		double warmth = atmosphere.temperatureEnabled
				? Mth.clamp((cell.temperature - atmosphere.convectionTemperature) / 0.5, 0.0, 1.5) * atmosphere.temperatureInfluence
				: 0.6;
		double moisture = atmosphere.humidityEnabled ? Mth.clamp((cell.humidity - 0.1) / 0.5, 0.0, 1.4) * atmosphere.humidityInfluence : 0.6;
		double value = warmth * moisture * (0.7 + 0.3 * dayCycle);
		if (cell.temperature < config.elevation.snowTemperatureThreshold) {
			value *= 0.1;
		}
		return value;
	}

	/**
	 * Builds a new storm of the given kind. A NaN intensity picks one from the kind's range; a negative mature time
	 * picks one too. Commands pass {@code quickStart} so the rain arrives within moments instead of minutes.
	 */
	private StormSystem createStorm(StormcellConfig config, StormSystem.Kind kind, double x, double z, double intensity, long matureTicks, boolean quickStart) {
		StormcellConfig.StormType type = kind.settings(config);
		StormSystem storm = new StormSystem(this.nextStormId++, kind, x, z);
		storm.radius = Mth.lerp(this.random.nextDouble(), type.minRadius, type.maxRadius);
		storm.length = type.length;
		storm.width = type.width;
		double peak;
		if (Double.isNaN(intensity)) {
			peak = Mth.lerp(this.random.nextDouble(), type.minIntensity, type.maxIntensity);
			if (kind == StormSystem.Kind.THUNDERSTORM) {
				double electrified = Mth.clamp(0.75 * config.generation.thunderChanceMultiplier, 0.0, 1.0);
				if (this.random.nextDouble() >= electrified) {
					// Convective, but not quite enough for thunder: a cloudburst
					peak = Math.min(peak, config.thresholds.thunderstorm - 0.04);
				}
			}
			peak = Mth.clamp(peak, config.generation.minimumStormIntensity, config.generation.maximumStormIntensity);
		} else {
			peak = Mth.clamp(intensity, 0.05, 1.0);
		}
		storm.potential = peak;
		storm.matureTicks = matureTicks >= 0 ? matureTicks
				: (long) (Mth.lerp(this.random.nextDouble(), type.minMatureMinutes, type.maxMatureMinutes) * TICKS_PER_MINUTE);
		double speedScale = type.speedFactor * config.stormBehavior.stormMovementSpeedMultiplier / 20.0;
		if (config.wind.windEnabled && config.wind.windAffectsStormMovement) {
			this.windInto(x, z, this.windScratch);
			storm.vx = this.windScratch[0] * speedScale;
			storm.vz = this.windScratch[1] * speedScale;
		} else {
			storm.vx = -Math.sin(this.windAngle) * config.wind.calmDriftSpeed * speedScale;
			storm.vz = Math.cos(this.windAngle) * config.wind.calmDriftSpeed * speedScale;
		}
		storm.updateHeading();
		if (quickStart) {
			storm.intensity = Math.min(peak, Math.max(config.thresholds.lightRain, peak * 0.95));
			storm.phase = storm.intensity >= peak ? StormSystem.Phase.MATURE : StormSystem.Phase.FORMING;
		}
		return storm;
	}

	// ---- Commands and events

	/** What to do when a command starts a storm but the storm limit is reached. */
	public enum Overflow {
		/** Refuse: the command reports the limit. */
		REJECT,
		/** Make room by removing the weakest storm. */
		REPLACE_WEAKEST
	}

	/**
	 * Starts a storm centred on a point, close to full strength. A command storm stays put while mature (see
	 * {@link StormSystem#anchored}) and fades faster once its time is up. Returns null if the storm limit is reached
	 * and {@code overflow} is REJECT.
	 *
	 * @param matureTicks how long it holds full strength, or negative for the kind's usual duration
	 */
	public @Nullable StormSystem spawnStorm(StormSystem.Kind kind, double x, double z, double intensity, long matureTicks, Overflow overflow) {
		StormcellConfig config = StormcellConfig.get();
		if (this.storms.size() >= config.performance.maximumStormSystems) {
			if (overflow == Overflow.REJECT || this.storms.isEmpty()) {
				return null;
			}
			this.storms.remove(Collections.min(this.storms, Comparator.comparingDouble(storm -> storm.intensity)));
		}
		StormSystem storm = this.createStorm(config, kind, x, z, intensity, matureTicks, true);
		storm.anchored = true;
		storm.decayBoost = 2.0;
		this.storms.add(storm);
		this.stormsChanged();
		this.formationSuppressedUntil = 0L;
		this.refreshAround(config, x, z, storm.reach());
		this.changes++;
		return storm;
	}

	/** Removes every storm whose centre is within the radius (all of them for a negative radius). Returns how many. */
	public int clearStorms(double x, double z, double radius) {
		int before = this.storms.size();
		if (radius < 0.0) {
			this.storms.clear();
		} else {
			this.storms.removeIf(storm -> {
				double dx = storm.x - x;
				double dz = storm.z - z;
				return dx * dx + dz * dz <= radius * radius;
			});
		}
		int removed = before - this.storms.size();
		if (removed > 0) {
			this.stormsChanged();
			StormcellConfig config = StormcellConfig.get();
			for (WeatherCell cell : this.cells.values()) {
				this.refreshCell(config, cell);
			}
			this.publishSnapshot();
			this.changes++;
		}
		return removed;
	}

	/**
	 * Players slept through the night. Vanilla ends its world-wide rain at that point; here the storms over the
	 * sleepers end, and weather elsewhere carries on. Returns how many storms were removed.
	 */
	public int clearStormsOver(List<WeatherEnvironment.Observer> sleepers) {
		int before = this.storms.size();
		this.storms.removeIf(storm -> {
			for (WeatherEnvironment.Observer sleeper : sleepers) {
				if (storm.intensityAt(sleeper.x(), sleeper.z()) > 0.0) {
					return true;
				}
			}
			return false;
		});
		int removed = before - this.storms.size();
		if (removed > 0) {
			this.stormsChanged();
			StormcellConfig config = StormcellConfig.get();
			for (WeatherCell cell : this.cells.values()) {
				this.refreshCell(config, cell);
			}
			this.publishSnapshot();
			this.changes++;
		}
		return removed;
	}

	/** Stops new storms forming for a while. */
	public void suppressFormation(long ticks) {
		this.formationSuppressedUntil = this.simTicks + Math.max(0L, ticks);
		this.changes++;
	}

	private void refreshAround(StormcellConfig config, double x, double z, double reach) {
		for (WeatherCell cell : this.cells.values()) {
			double cx = (cell.cx + 0.5) * this.cellSize;
			double cz = (cell.cz + 0.5) * this.cellSize;
			double limit = reach + this.cellSize;
			if (Math.abs(cx - x) < limit && Math.abs(cz - z) < limit) {
				this.refreshCell(config, cell);
			}
		}
		this.publishSnapshot();
	}

	public List<StormSystem> storms() {
		return this.storms;
	}

	public int cellCount() {
		return this.cells.size();
	}

	public int dormantCount() {
		return this.dormant.size();
	}

	public Stats stats() {
		return this.stats;
	}

	public double windAngleDegrees() {
		return Math.toDegrees(this.windAngle);
	}

	public double windSpeed() {
		return this.windSpeed;
	}

	public long simTicks() {
		return this.simTicks;
	}

	// ---- Questions about the weather

	/** Strength of the rain or snow reaching the ground at a point, 0 to 1, blended between samples. */
	public float precipitationAt(double x, double z) {
		return this.sample(x, z, PRECIPITATION);
	}

	/** Strength of the storm overhead at a point, 0 to 1, blended between samples. */
	public float stormAt(double x, double z) {
		return this.sample(x, z, STORM);
	}

	/** Thickness of the dust in a dust storm at a point, 0 to 1, blended between samples. */
	public float dustAt(double x, double z) {
		return this.sample(x, z, DUST);
	}

	private float sample(double x, double z, int channel) {
		if (!this.environment.isSimulationThread()) {
			return this.snapshot.sample(x, z, channel);
		}
		int n = this.resolution;
		double spacing = (double) this.cellSize / n;
		double gx = x / spacing - 0.5;
		double gz = z / spacing - 0.5;
		int ix = Mth.floor(gx);
		int iz = Mth.floor(gz);
		double fx = gx - ix;
		double fz = gz - iz;
		float v00 = this.gridValue(ix, iz, channel);
		float v10 = this.gridValue(ix + 1, iz, channel);
		float v01 = this.gridValue(ix, iz + 1, channel);
		float v11 = this.gridValue(ix + 1, iz + 1, channel);
		return (float) Mth.lerp(fz, Mth.lerp(fx, v00, v10), Mth.lerp(fx, v01, v11));
	}

	private float gridValue(int gx, int gz, int channel) {
		int n = this.resolution;
		WeatherCell cell = this.cellForQuery(Math.floorDiv(gx, n), Math.floorDiv(gz, n));
		if (cell == null) {
			// Not ready yet: answer from the storms overhead (ignoring how dry the ground is)
			double spacing = (double) this.cellSize / n;
			double px = (gx + 0.5) * spacing;
			double pz = (gz + 0.5) * spacing;
			return channel == DUST ? combineDust(this.storms, px, pz) : combine(this.storms, px, pz);
		}
		WeatherCell.Samples samples = cell.samples;
		int i = Math.floorMod(gz, n) * n + Math.floorMod(gx, n);
		if (i >= samples.size()) {
			return 0.0F;
		}
		return switch (channel) {
			case STORM -> samples.storm()[i];
			case DUST -> samples.dust()[i];
			default -> samples.precipitation()[i];
		};
	}

	/** The full local picture, for /stormcell info and the API. Server thread only; other threads get the samples alone. */
	public LocalWeather localWeather(double x, double z) {
		float storm = this.stormAt(x, z);
		float precipitation = this.precipitationAt(x, z);
		float dust = this.dustAt(x, z);
		if (!this.environment.isSimulationThread()) {
			return new LocalWeather(0.8F, 0.5F, 1013.0F, 0.0F, 0.0F, storm, precipitation, dust);
		}
		WeatherCell cell = this.cellForQuery(Math.floorDiv((int) Math.floor(x), this.cellSize), Math.floorDiv((int) Math.floor(z), this.cellSize));
		this.windInto(x, z, this.windScratch);
		float direction = (float) Math.toDegrees(Math.atan2(-this.windScratch[0], this.windScratch[1]));
		float speed = (float) Math.sqrt(this.windScratch[0] * this.windScratch[0] + this.windScratch[1] * this.windScratch[1]);
		if (cell == null) {
			return new LocalWeather(0.8F, 0.5F, 1013.0F, direction, speed, storm, precipitation, dust);
		}
		int n = this.resolution;
		int sx = Math.floorMod((int) Math.floor(x), this.cellSize) * n / this.cellSize;
		int sz = Math.floorMod((int) Math.floor(z), this.cellSize) * n / this.cellSize;
		WeatherCell.Samples samples = cell.samples;
		int i = Math.min(samples.size() - 1, sz * n + sx);
		return new LocalWeather(cell.temperature, samples.humidity()[i], cell.pressure, direction, speed, storm, precipitation, dust);
	}

	/** Whether the cell covering a point exists yet. Used by tests and diagnostics. */
	public boolean hasCellAt(double x, double z) {
		return this.cells.containsKey(WeatherCell.key(Math.floorDiv((int) Math.floor(x), this.cellSize), Math.floorDiv((int) Math.floor(z), this.cellSize)));
	}

	// ---- Saving

	public boolean isDirty() {
		return this.changes != this.savedChanges;
	}

	/** A copy of everything worth saving. Does not mark anything saved; call {@link #markSaved} once it is on disk. */
	public WeatherState snapshotState() {
		WeatherState state = new WeatherState();
		state.changeCount = this.changes;
		state.simTicks = this.simTicks;
		state.windAngle = this.windAngle;
		state.windSpeed = this.windSpeed;
		state.fieldOffsetX = this.fieldOffsetX;
		state.fieldOffsetZ = this.fieldOffsetZ;
		state.formationSuppressedUntil = this.formationSuppressedUntil;
		state.nextStormId = this.nextStormId;
		state.cellSize = this.cellSize;
		for (StormSystem storm : this.storms) {
			state.storms.add(WeatherState.Storm.of(storm));
		}
		for (WeatherCell cell : this.cells.values()) {
			if (cell.depletion > 0.01F) {
				state.dryCells.add(new WeatherState.DryCell(cell.cx, cell.cz, cell.depletion, this.simTicks));
			}
		}
		for (Long2ObjectMap.Entry<Dormant> entry : this.dormant.long2ObjectEntrySet()) {
			Dormant value = entry.getValue();
			state.dryCells.add(new WeatherState.DryCell(WeatherCell.keyX(entry.getLongKey()), WeatherCell.keyZ(entry.getLongKey()), value.depletion(), value.tick()));
		}
		return state;
	}

	/** The given state reached the disk. Clears the dirty flag unless something changed since it was taken. */
	public void markSaved(WeatherState state) {
		this.savedChanges = Math.max(this.savedChanges, state.changeCount);
	}

	/** Applies a saved state that has already been checked by {@link WeatherState#sanitize}. */
	private void restore(WeatherState state, StormcellConfig config) {
		this.simTicks = state.simTicks;
		this.windAngle = state.windAngle;
		this.windSpeed = Mth.clamp(state.windSpeed, config.wind.minimumWindSpeed, config.wind.maximumWindSpeed);
		this.fieldOffsetX = state.fieldOffsetX;
		this.fieldOffsetZ = state.fieldOffsetZ;
		this.formationSuppressedUntil = state.formationSuppressedUntil;
		this.nextStormId = Math.max(1, state.nextStormId);
		for (WeatherState.Storm saved : state.storms) {
			StormSystem storm = saved.toStorm(this.nextStormId++);
			if (storm != null && this.storms.size() < config.performance.maximumStormSystems) {
				this.storms.add(storm);
			}
		}
		this.stormsChanged();
		if (state.cellSize == this.cellSize) {
			for (WeatherState.DryCell dry : state.dryCells) {
				this.dormant.put(WeatherCell.key(dry.x(), dry.z()), new Dormant(dry.depletion(), dry.tick()));
			}
			this.pruneDormant(config);
		}
		this.savedChanges = this.changes;
		Stormcell.LOGGER.info("Restored {} storm system(s) in {}", this.storms.size(), this.environment.dimensionId());
	}

	private static double smoothstep(double edge0, double edge1, double x) {
		double t = Mth.clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
		return t * t * (3.0 - 2.0 * t);
	}

	/** What is remembered about a dropped cell: how dried out it was, and when. */
	private record Dormant(float depletion, long tick) {
		double current(long now, double recoveryTicks) {
			return this.depletion * Math.exp(-Math.max(0L, now - this.tick) / recoveryTicks);
		}
	}

	/**
	 * Read-only view of the samples for other threads. Carries its own grid layout, so a reader never combines a new
	 * layout with old samples.
	 */
	record Snapshot(int cellSize, int resolution, Long2ObjectOpenHashMap<WeatherCell.Samples> samples) {
		static Snapshot empty(int cellSize, int resolution) {
			return new Snapshot(cellSize, resolution, new Long2ObjectOpenHashMap<>());
		}

		float sample(double x, double z, int channel) {
			double spacing = (double) this.cellSize / this.resolution;
			double gx = x / spacing - 0.5;
			double gz = z / spacing - 0.5;
			int ix = Mth.floor(gx);
			int iz = Mth.floor(gz);
			double fx = gx - ix;
			double fz = gz - iz;
			float v00 = this.value(ix, iz, channel);
			float v10 = this.value(ix + 1, iz, channel);
			float v01 = this.value(ix, iz + 1, channel);
			float v11 = this.value(ix + 1, iz + 1, channel);
			return (float) Mth.lerp(fz, Mth.lerp(fx, v00, v10), Mth.lerp(fx, v01, v11));
		}

		private float value(int gx, int gz, int channel) {
			int n = this.resolution;
			WeatherCell.Samples cell = this.samples.get(WeatherCell.key(Math.floorDiv(gx, n), Math.floorDiv(gz, n)));
			if (cell == null) {
				return 0.0F;
			}
			int i = Math.floorMod(gz, n) * n + Math.floorMod(gx, n);
			if (i >= cell.size()) {
				return 0.0F;
			}
			return switch (channel) {
				case STORM -> cell.storm()[i];
				case DUST -> cell.dust()[i];
				default -> cell.precipitation()[i];
			};
		}
	}

	/** Timing and work counters for /stormcell stats and benchmarks. */
	public static final class Stats {
		public long steps;
		public long lastStepNanos;
		public long maxStepNanos;
		public double averageStepNanos;
		public long creations;
		public long terrainSamples;
		public long evictions;
		public long fallbacks;

		void recordStep(long nanos) {
			this.steps++;
			this.lastStepNanos = nanos;
			this.maxStepNanos = Math.max(this.maxStepNanos, nanos);
			this.averageStepNanos = this.steps == 1 ? nanos : this.averageStepNanos * 0.95 + nanos * 0.05;
		}
	}
}
