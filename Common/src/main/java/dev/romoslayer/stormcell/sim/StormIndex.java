package dev.romoslayer.stormcell.sim;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;

/**
 * Storms bucketed by area, so refreshing a cell only looks at the storms that could reach it instead of every storm in
 * the dimension. Built for one arrangement of storms and thrown away as soon as any storm moves or changes.
 */
final class StormIndex {
	private static final int BUCKET_CELLS = 4;

	private final int bucketSize;
	private final Long2ObjectOpenHashMap<List<StormSystem>> buckets = new Long2ObjectOpenHashMap<>();

	/** @param cellSize the weather cell size; storms are registered far enough out to reach any cell in a bucket */
	StormIndex(List<StormSystem> storms, int cellSize) {
		this.bucketSize = cellSize * BUCKET_CELLS;
		double margin = cellSize;
		for (StormSystem storm : storms) {
			double reach = storm.reach() + margin;
			int minX = Math.floorDiv((int) Math.floor(storm.x - reach), this.bucketSize);
			int maxX = Math.floorDiv((int) Math.floor(storm.x + reach), this.bucketSize);
			int minZ = Math.floorDiv((int) Math.floor(storm.z - reach), this.bucketSize);
			int maxZ = Math.floorDiv((int) Math.floor(storm.z + reach), this.bucketSize);
			for (int bx = minX; bx <= maxX; bx++) {
				for (int bz = minZ; bz <= maxZ; bz++) {
					this.buckets.computeIfAbsent(WeatherCell.key(bx, bz), key -> new ArrayList<>(4)).add(storm);
				}
			}
		}
	}

	/** Every storm that might reach a cell centred on this point (a superset; callers still check the distance). */
	List<StormSystem> near(double x, double z) {
		List<StormSystem> found = this.buckets.get(WeatherCell.key(Math.floorDiv((int) Math.floor(x), this.bucketSize),
				Math.floorDiv((int) Math.floor(z), this.bucketSize)));
		return found == null ? List.of() : found;
	}
}
