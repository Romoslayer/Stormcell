package dev.romoslayer.stormcell.persist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.romoslayer.stormcell.Stormcell;
import dev.romoslayer.stormcell.sim.StormSystem;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Everything needed to pick a dimension's weather up where it left off: the storm systems, the wind, how far the
 * pressure patterns have drifted, and which regions were recently rained dry. Saved as JSON in
 * {@code <world>/stormcell/}. The per-cell climate is not saved; it is cheap to work out again.
 *
 * <p>Files are checked thoroughly on loading ({@link #sanitize}): a hand-edited or damaged file never takes the
 * simulation down, and a copy of it is kept next to it before anything is thrown away.
 */
public final class WeatherState {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int VERSION = 1;
	/** Positions further out than this are treated as nonsense (the world border is at 30 million). */
	private static final double MAX_COORDINATE = 1.0E8;
	private static final long MAX_TICKS = Long.MAX_VALUE / 4;

	public int version = VERSION;
	public long simTicks;
	public double windAngle;
	public double windSpeed;
	public double fieldOffsetX;
	public double fieldOffsetZ;
	public long formationSuppressedUntil;
	public int nextStormId = 1;
	public int cellSize;
	public List<Storm> storms = new ArrayList<>();
	public List<DryCell> dryCells = new ArrayList<>();
	/** Which simulation change this state was taken at (not saved). */
	public transient long changeCount;

	public static final class Storm {
		public String kind;
		public String phase;
		public double x;
		public double z;
		public double vx;
		public double vz;
		public double radius;
		public double length;
		public double width;
		public double intensity;
		public double potential;
		public long phaseTicks;
		public long matureTicks;
		public long ageTicks;
		public double decayBoost = 1.0;
		public boolean anchored;
		public boolean dry;

		public static Storm of(StormSystem storm) {
			Storm saved = new Storm();
			saved.kind = storm.kind.id();
			saved.phase = storm.phase.name();
			saved.x = storm.x;
			saved.z = storm.z;
			saved.vx = storm.vx;
			saved.vz = storm.vz;
			saved.radius = storm.radius;
			saved.length = storm.length;
			saved.width = storm.width;
			saved.intensity = storm.intensity;
			saved.potential = storm.potential;
			saved.phaseTicks = storm.phaseTicks;
			saved.matureTicks = storm.matureTicks;
			saved.ageTicks = storm.ageTicks;
			saved.decayBoost = storm.decayBoost;
			saved.anchored = storm.anchored;
			saved.dry = storm.dry;
			return saved;
		}

		/** Null if the values make no sense (a hand-edited or damaged file). Expects {@link #sanitize} to have run. */
		public @Nullable StormSystem toStorm(int id) {
			if (!this.isValid()) {
				return null;
			}
			StormSystem storm = new StormSystem(id, StormSystem.Kind.byId(Objects.requireNonNullElse(this.kind, "")), this.x, this.z);
			storm.phase = StormSystem.Phase.byName(Objects.requireNonNullElse(this.phase, ""));
			storm.vx = this.vx;
			storm.vz = this.vz;
			storm.updateHeading();
			storm.radius = this.radius;
			storm.length = this.length;
			storm.width = this.width;
			storm.intensity = this.intensity;
			storm.potential = this.potential;
			storm.phaseTicks = this.phaseTicks;
			storm.matureTicks = this.matureTicks;
			storm.ageTicks = this.ageTicks;
			storm.decayBoost = this.decayBoost;
			storm.anchored = this.anchored;
			storm.dry = this.dry;
			return storm;
		}

		/** Whether every value is finite and within sensible bounds; a storm that is not gets dropped. */
		boolean isValid() {
			return within(this.x, MAX_COORDINATE) && within(this.z, MAX_COORDINATE)
					&& within(this.vx, 10.0) && within(this.vz, 10.0)
					&& Double.isFinite(this.radius) && this.radius >= 1.0 && this.radius <= 65536.0
					&& Double.isFinite(this.length) && this.length >= 0.1 && this.length <= 10.0
					&& Double.isFinite(this.width) && this.width >= 0.1 && this.width <= 10.0
					&& Double.isFinite(this.intensity) && this.intensity >= 0.0 && this.intensity <= 1.0
					&& Double.isFinite(this.potential) && this.potential >= 0.0 && this.potential <= 1.0
					&& this.phaseTicks >= 0 && this.phaseTicks <= MAX_TICKS
					&& this.matureTicks >= 0 && this.matureTicks <= MAX_TICKS
					&& this.ageTicks >= 0 && this.ageTicks <= MAX_TICKS
					&& Double.isFinite(this.decayBoost) && this.decayBoost > 0.0 && this.decayBoost <= 100.0;
		}
	}

	public record DryCell(int x, int z, float depletion, long tick) {
	}

	private static boolean within(double value, double limit) {
		return Double.isFinite(value) && Math.abs(value) <= limit;
	}

	/**
	 * Checks a freshly loaded state and repairs it in place: bad storms and dry regions are dropped, out-of-range
	 * numbers are reset or clamped, and the lists are cut down to the given limits. Returns a description of each
	 * repair (empty if the file was fine).
	 */
	public List<String> sanitize(int maxStorms, int maxDryCells) {
		List<String> problems = new ArrayList<>();
		if (this.simTicks < 0 || this.simTicks > MAX_TICKS) {
			problems.add("simulation time " + this.simTicks + " reset to 0");
			this.simTicks = 0;
		}
		if (!Double.isFinite(this.windAngle)) {
			problems.add("wind direction was not a number; reset");
			this.windAngle = 0.0;
		}
		if (!Double.isFinite(this.windSpeed) || this.windSpeed < 0.0 || this.windSpeed > 1000.0) {
			problems.add("wind speed " + this.windSpeed + " reset");
			this.windSpeed = 1.0;
		}
		if (!within(this.fieldOffsetX, MAX_COORDINATE) || !within(this.fieldOffsetZ, MAX_COORDINATE)) {
			problems.add("pressure pattern drift reset");
			this.fieldOffsetX = 0.0;
			this.fieldOffsetZ = 0.0;
		}
		if (this.formationSuppressedUntil < 0 || this.formationSuppressedUntil > this.simTicks + MAX_TICKS / 2) {
			this.formationSuppressedUntil = 0;
		}
		if (this.nextStormId < 1) {
			this.nextStormId = 1;
		}

		List<Storm> goodStorms = new ArrayList<>();
		int badStorms = 0;
		for (Storm storm : this.storms == null ? List.<Storm>of() : this.storms) {
			if (storm == null || !storm.isValid()) {
				badStorms++;
			} else {
				goodStorms.add(storm);
			}
		}
		if (badStorms > 0) {
			problems.add(badStorms + " invalid storm(s) dropped");
		}
		if (goodStorms.size() > maxStorms) {
			problems.add((goodStorms.size() - maxStorms) + " storm(s) over the limit of " + maxStorms + " dropped");
			goodStorms = new ArrayList<>(goodStorms.subList(0, maxStorms));
		}
		this.storms = goodStorms;

		List<DryCell> goodCells = new ArrayList<>();
		int badCells = 0;
		for (DryCell cell : this.dryCells == null ? List.<DryCell>of() : this.dryCells) {
			if (cell == null || !Float.isFinite(cell.depletion()) || cell.depletion() <= 0.0F) {
				badCells++;
				continue;
			}
			// A timestamp in the future would stop the region from ever recovering; one before the start is harmless
			long tick = Math.clamp(cell.tick(), 0L, this.simTicks);
			goodCells.add(new DryCell(cell.x(), cell.z(), Math.min(cell.depletion(), 0.6F), tick));
		}
		if (badCells > 0) {
			problems.add(badCells + " invalid dry region(s) dropped");
		}
		if (goodCells.size() > maxDryCells) {
			goodCells.sort((a, b) -> Float.compare(b.depletion(), a.depletion()));
			problems.add((goodCells.size() - maxDryCells) + " dry region(s) over the limit dropped");
			goodCells = new ArrayList<>(goodCells.subList(0, maxDryCells));
		}
		this.dryCells = goodCells;
		return problems;
	}

	/**
	 * Loads and checks a saved state. Returns null if there is none or it cannot be read; in that case, and whenever
	 * repairs were needed, the original file is copied aside first so nothing is lost.
	 */
	public static @Nullable WeatherState load(Path file, int maxStorms, int maxDryCells) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		WeatherState state;
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			state = GSON.fromJson(reader, WeatherState.class);
		} catch (IOException | JsonParseException e) {
			Stormcell.LOGGER.error("Could not read the weather from {}; starting fresh (the file was kept as {})", file, keepCopy(file), e);
			return null;
		}
		if (state == null) {
			Stormcell.LOGGER.error("{} is empty; starting fresh", file);
			return null;
		}
		if (state.version > VERSION) {
			Stormcell.LOGGER.warn("{} was saved by a newer version of Stormcell; reading what it can", file);
		}
		List<String> problems = state.sanitize(maxStorms, maxDryCells);
		if (!problems.isEmpty()) {
			Stormcell.LOGGER.warn("Repaired the weather loaded from {} (original kept as {}): {}", file, keepCopy(file), String.join("; ", problems));
		}
		return state;
	}

	private static @Nullable Path keepCopy(Path file) {
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		Path copy = file.resolveSibling(file.getFileName() + ".corrupt-" + stamp);
		try {
			Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
			return copy;
		} catch (IOException e) {
			Stormcell.LOGGER.error("Could not keep a copy of {}", file, e);
			return null;
		}
	}

	/** Writes the state. Returns true once it is safely on disk; the previous file is untouched on failure. */
	public boolean save(Path file) {
		String json;
		try {
			json = GSON.toJson(this);
		} catch (IllegalArgumentException e) {
			// Gson refuses non-finite numbers; the simulation should never produce them
			Stormcell.LOGGER.error("Could not save the weather to {}: {}", file, e.getMessage());
			return false;
		}
		try {
			SafeFiles.replace(file, json);
			return true;
		} catch (IOException e) {
			Stormcell.LOGGER.error("Could not save the weather to {}", file, e);
			return false;
		}
	}

}
