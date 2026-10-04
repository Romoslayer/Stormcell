package dev.romoslayer.stormcell.sim;

/**
 * Smooth 3D value noise in the range -1 to 1. Used for the large, slowly changing atmospheric patterns (pressure,
 * regional moisture, local wind). Deterministic from its seed, so the patterns cost nothing to store and are the same
 * after a restart.
 */
final class ValueNoise {
	private final long seed;

	ValueNoise(long seed) {
		this.seed = seed;
	}

	double sample(double x, double y, double z) {
		long x0 = (long) Math.floor(x);
		long y0 = (long) Math.floor(y);
		long z0 = (long) Math.floor(z);
		double fx = fade(x - x0);
		double fy = fade(y - y0);
		double fz = fade(z - z0);
		double c000 = this.corner(x0, y0, z0);
		double c100 = this.corner(x0 + 1, y0, z0);
		double c010 = this.corner(x0, y0 + 1, z0);
		double c110 = this.corner(x0 + 1, y0 + 1, z0);
		double c001 = this.corner(x0, y0, z0 + 1);
		double c101 = this.corner(x0 + 1, y0, z0 + 1);
		double c011 = this.corner(x0, y0 + 1, z0 + 1);
		double c111 = this.corner(x0 + 1, y0 + 1, z0 + 1);
		double x00 = lerp(fx, c000, c100);
		double x10 = lerp(fx, c010, c110);
		double x01 = lerp(fx, c001, c101);
		double x11 = lerp(fx, c011, c111);
		return lerp(fz, lerp(fy, x00, x10), lerp(fy, x01, x11));
	}

	/** Two octaves, which looks less blobby than one for barely more work. */
	double fractal(double x, double y, double z) {
		return (this.sample(x, y, z) * 2.0 + this.sample(x * 2.03 + 17.1, y * 2.03, z * 2.03 - 9.7)) / 3.0;
	}

	private double corner(long x, long y, long z) {
		long h = this.seed;
		h ^= x * 0x9E3779B97F4A7C15L;
		h ^= y * 0xC2B2AE3D27D4EB4FL;
		h ^= z * 0x165667B19E3779F9L;
		h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
		h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
		h ^= h >>> 31;
		return (h >>> 11) * 0x1.0p-53 * 2.0 - 1.0;
	}

	private static double fade(double t) {
		return t * t * (3.0 - 2.0 * t);
	}

	private static double lerp(double t, double a, double b) {
		return a + t * (b - a);
	}
}
