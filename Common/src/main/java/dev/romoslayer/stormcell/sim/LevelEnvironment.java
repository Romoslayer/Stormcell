package dev.romoslayer.stormcell.sim;

import dev.romoslayer.stormcell.api.ClimateModifiers;
import dev.romoslayer.stormcell.climate.BiomeClimate;
import dev.romoslayer.stormcell.compat.ClimateHooks;
import dev.romoslayer.stormcell.mc.Versioned;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

/** A real dimension, as seen by the weather simulation. */
public final class LevelEnvironment implements WeatherEnvironment {
	private final ServerLevel level;
	private final Thread serverThread;
	private final int sampleQuartY;

	public LevelEnvironment(ServerLevel level) {
		this.level = level;
		this.serverThread = level.getServer().getRunningThread();
		// High above the terrain the biome source always answers with the surface biome, never a cave biome
		this.sampleQuartY = QuartPos.fromBlock(Math.min(Versioned.maxY(level) - 1, level.getSeaLevel() + 160));
	}

	public ServerLevel level() {
		return this.level;
	}

	@Override
	public long seed() {
		return this.level.getSeed();
	}

	@Override
	public String dimensionId() {
		return Versioned.id(this.level.dimension());
	}

	@Override
	public int seaLevel() {
		return this.level.getSeaLevel();
	}

	@Override
	public BiomeClimate climateAt(int x, int z) {
		return BiomeClimate.of(this.level.getUncachedNoiseBiome(QuartPos.fromBlock(x), this.sampleQuartY, QuartPos.fromBlock(z)), this.level.registryAccess());
	}

	@Override
	public int terrainHeight(int x, int z) {
		try {
			return this.level.getChunkSource().getGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, this.level,
					this.level.getChunkSource().randomState());
		} catch (RuntimeException e) {
			return UNKNOWN_HEIGHT;
		}
	}

	@Override
	public ClimateModifiers modifiers(int x, int z, BiomeClimate climate) {
		if (climate.holder() == null || !ClimateHooks.anyActive()) {
			return ClimateModifiers.NONE;
		}
		return ClimateHooks.modifiers(this.level, new BlockPos(x, this.level.getSeaLevel(), z), climate.holder());
	}

	@Override
	public List<Observer> observers() {
		List<ServerPlayer> players = this.level.players();
		List<Observer> observers = new ArrayList<>(players.size());
		for (ServerPlayer player : players) {
			observers.add(new Observer(player.getX(), player.getZ()));
		}
		return observers;
	}

	@Override
	public long dayTime() {
		return Versioned.dayTime(this.level);
	}

	@Override
	public int viewDistanceChunks() {
		return this.level.getServer().getPlayerList().getViewDistance();
	}

	@Override
	public boolean isSimulationThread() {
		return Thread.currentThread() == this.serverThread;
	}
}
