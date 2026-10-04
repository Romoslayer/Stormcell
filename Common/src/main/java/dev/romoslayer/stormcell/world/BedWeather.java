package dev.romoslayer.stormcell.world;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * In vanilla, a thunderstorm darkens the whole sky enough to sleep during the day. With regional weather the sky is
 * only stormy under the storm, so beds that work "when dark" also work under a thunderstorm.
 */
public final class BedWeather {
	private BedWeather() {
	}

	public static boolean stormLetsSleep(BedRule rule, Level level, BlockPos pos) {
		return rule.canSleep() == BedRule.Rule.WHEN_DARK && RegionalWeather.weather(level) != null && RegionalWeather.mobThunderCheck(level, pos, false);
	}

	/**
	 * For loaders that report sleep problems through an event (NeoForge): if the only thing stopping the player is
	 * the bed's daylight rule and a thunderstorm is overhead, returns what should stop them instead (monsters nearby,
	 * as vanilla checks next), or null to let them sleep. Returns {@code problem} unchanged otherwise.
	 */
	public static Player.@Nullable BedSleepingProblem stormSleepProblem(ServerPlayer player, BlockPos pos, Player.@Nullable BedSleepingProblem problem) {
		if (problem == null) {
			return null;
		}
		ServerLevel level = player.level();
		// The regular bed rule (BedBlock reads this attribute in every supported version)
		BedRule rule = level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, pos);
		if (!problem.equals(rule.asProblem()) || !stormLetsSleep(rule, level, pos)) {
			return problem;
		}
		if (!player.isCreative()) {
			Vec3 center = Vec3.atBottomCenterOf(pos);
			List<Monster> monsters = level.getEntitiesOfClass(Monster.class,
					new AABB(center.x() - 8.0, center.y() - 5.0, center.z() - 8.0, center.x() + 8.0, center.y() + 5.0, center.z() + 8.0),
					monster -> monster.isPreventingPlayerRest(level, player));
			if (!monsters.isEmpty()) {
				return Player.BedSleepingProblem.NOT_SAFE;
			}
		}
		return null;
	}
}
