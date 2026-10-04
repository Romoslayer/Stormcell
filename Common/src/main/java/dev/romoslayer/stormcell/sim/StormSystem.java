package dev.romoslayer.stormcell.sim;

import dev.romoslayer.stormcell.config.StormcellConfig;
import java.util.Locale;

/**
 * One moving weather system: a shower, a band of steady rain, or a thunderstorm. Its strength peaks at the centre and
 * falls away towards the edge, so as one passes over you the rain starts light, builds, and eases off again.
 */
public final class StormSystem {
	/** Share of the radius at full strength. */
	static final double CORE = 0.35;

	public enum Kind {
		SHOWER, RAIN_SYSTEM, THUNDERSTORM,
		/** Blowing sand: no rain or lightning, its own channel (see LevelWeather#dustAt). */
		DUST_STORM;

		public StormcellConfig.StormType settings(StormcellConfig config) {
			return switch (this) {
				case SHOWER -> config.stormTypes.shower;
				case RAIN_SYSTEM -> config.stormTypes.rainSystem;
				case THUNDERSTORM -> config.stormTypes.thunderstorm;
				case DUST_STORM -> config.stormTypes.dustStorm;
			};
		}

		public String id() {
			return this.name().toLowerCase(Locale.ROOT);
		}

		public static Kind byId(String id) {
			for (Kind kind : values()) {
				if (kind.id().equals(id)) {
					return kind;
				}
			}
			return SHOWER;
		}
	}

	public enum Phase {
		/** Building up towards its peak strength. */
		FORMING,
		/** Holding its peak strength. */
		MATURE,
		/** Weakening until it is gone. */
		DISSIPATING;

		public static Phase byName(String name) {
			for (Phase phase : values()) {
				if (phase.name().equalsIgnoreCase(name)) {
					return phase;
				}
			}
			return MATURE;
		}
	}

	public final int id;
	public Kind kind;
	public Phase phase = Phase.FORMING;
	/** Centre, in blocks. */
	public double x;
	public double z;
	/** Velocity, in blocks per tick. */
	public double vx;
	public double vz;
	/** Size in blocks; {@link #length} and {@link #width} stretch it along and across the direction of travel. */
	public double radius;
	public double length = 1.0;
	public double width = 1.0;
	/** Current strength at the centre, 0 to 1. */
	public double intensity;
	/** The strength it is building towards (or holding). Poor conditions wear it down. */
	public double potential;
	/** Ticks spent in the current phase, and how long the mature phase should last. */
	public long phaseTicks;
	public long matureTicks;
	public long ageTicks;
	/** Multiplies the decay rate once the storm weakens (command storms fade faster when their time is up). */
	public double decayBoost = 1.0;
	/**
	 * Started by a command: stays where it was started and ignores dry air while mature, so "/weather rain" means rain
	 * here for the requested time. Once the mature phase ends it drifts and fades like any other storm.
	 */
	public boolean anchored;
	/**
	 * A heat storm over dry land: thunder and lightning but its rain evaporates before reaching the ground, and dry air
	 * does not wear it down. It turns into an ordinary storm if it drifts over moist land.
	 */
	public boolean dry;
	/** Direction of travel (unit vector), kept while the storm is still so its shape stays put. */
	public double headingX;
	public double headingZ = 1.0;

	public StormSystem(int id, Kind kind, double x, double z) {
		this.id = id;
		this.kind = kind;
		this.x = x;
		this.z = z;
	}

	/** The furthest any part of the storm reaches from its centre. */
	public double reach() {
		return this.radius * Math.max(this.length, this.width);
	}

	/**
	 * Strength at a point, 0 to 1: full strength across the core (the inner {@link #CORE} of the radius), then easing
	 * smoothly to nothing at the (stretched) edge. Points 70% of the way out get about half the core strength, which is
	 * what makes rain build and fade as a storm passes, while a thunderstorm's core is wide enough to thunder over.
	 */
	public double intensityAt(double px, double pz) {
		double dx = px - this.x;
		double dz = pz - this.z;
		double reach = this.reach();
		if (dx * dx + dz * dz >= reach * reach) {
			return 0.0;
		}
		double along = dx * this.headingX + dz * this.headingZ;
		double across = -dx * this.headingZ + dz * this.headingX;
		double a = along / (this.radius * this.length);
		double c = across / (this.radius * this.width);
		double d2 = a * a + c * c;
		if (d2 >= 1.0) {
			return 0.0;
		}
		double d = Math.sqrt(d2);
		if (d <= CORE) {
			return this.intensity;
		}
		double t = (d - CORE) / (1.0 - CORE);
		return this.intensity * (1.0 - t * t * (3.0 - 2.0 * t));
	}

	/** Points the storm along its velocity, if it is moving. */
	public void updateHeading() {
		double speed = Math.sqrt(this.vx * this.vx + this.vz * this.vz);
		if (speed > 1.0E-6) {
			this.headingX = this.vx / speed;
			this.headingZ = this.vz / speed;
		}
	}

	public String describe() {
		return String.format(Locale.ROOT, "#%d %s%s %s at %.0f, %.0f, radius %.0f, intensity %.2f/%.2f", this.id, this.kind.id(),
				this.dry ? " (dry)" : "", this.phase.name().toLowerCase(Locale.ROOT), this.x, this.z, this.radius, this.intensity, this.potential);
	}
}
