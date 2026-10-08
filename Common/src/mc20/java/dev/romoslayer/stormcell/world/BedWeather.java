package dev.romoslayer.stormcell.world;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Sleeping during a thunderstorm, as vanilla allows, but for the thunderstorm overhead rather than a world-wide one.
 * In 1.21.x a bed is refused (and a sleeper woken) by daylight alone, through Level.isDay(); a storm overhead lifts that.
 */
public final class BedWeather {
	private BedWeather() {
	}

	/** Whether a thunderstorm over this spot lets a player sleep (or stay asleep) in daylight. */
	public static boolean stormLetsSleep(Level level, BlockPos pos) {
		return RegionalWeather.weather(level) != null && RegionalWeather.mobThunderCheck(level, pos, false);
	}

	/**
	 * For loaders that report sleep problems through an event (NeoForge): if the only thing stopping the player is
	 * daylight and a thunderstorm is overhead, returns what should stop them instead (monsters nearby, as vanilla checks
	 * next), or null to let them sleep. Returns {@code problem} unchanged otherwise.
	 */
	public static Player.@Nullable BedSleepingProblem stormSleepProblem(ServerPlayer player, BlockPos pos, Player.@Nullable BedSleepingProblem problem) {
		if (problem != Player.BedSleepingProblem.NOT_POSSIBLE_NOW) {
			return problem;
		}
		ServerLevel level = player.serverLevel();
		if (!stormLetsSleep(level, pos)) {
			return problem;
		}
		if (!player.isCreative()) {
			Vec3 center = Vec3.atBottomCenterOf(pos);
			List<Monster> monsters = level.getEntitiesOfClass(Monster.class,
					new AABB(center.x() - 8.0, center.y() - 5.0, center.z() - 8.0, center.x() + 8.0, center.y() + 5.0, center.z() + 8.0),
					monster -> monster.isPreventingPlayerRest(player));
			if (!monsters.isEmpty()) {
				return Player.BedSleepingProblem.NOT_SAFE;
			}
		}
		return null;
	}
}
