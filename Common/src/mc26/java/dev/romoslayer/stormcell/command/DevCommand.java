package dev.romoslayer.stormcell.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.romoslayer.stormcell.mc.Versioned;
import dev.romoslayer.stormcell.world.RegionalWeather;
import java.util.Locale;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.entity.animal.panda.Panda;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * /stormcell dev, for live testing only: registered only when the server runs with -Dstormcell.devCommands=true
 * (the live tests set it), never on a normal server.
 * <ul>
 * <li>{@code use <player> <pos>} hands the server the packet a client sends when its player right-clicks the top of
 * that block, so the whole vanilla path runs (reach checks, the block's own use, sleeping and Stormcell's hooks).
 * <li>{@code mobs <targets>} shows what bees, foxes and pandas make of the weather where they are.
 * </ul>
 */
final class DevCommand {
	static final boolean ENABLED = Boolean.getBoolean("stormcell.devCommands");

	private DevCommand() {
	}

	static LiteralArgumentBuilder<CommandSourceStack> node() {
		return Commands.literal("dev")
				.then(Commands.literal("use")
						.then(Commands.argument("player", EntityArgument.player())
								.then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(DevCommand::use))))
				.then(Commands.literal("mobs")
						.then(Commands.argument("targets", EntityArgument.entities()).executes(DevCommand::mobs)));
	}

	private static int use(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = EntityArgument.getPlayer(context, "player");
		BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0.0, 0.5, 0.0), Direction.UP, pos, false);
		player.connection.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, 0));
		boolean sleeping = player.isSleeping();
		context.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "%s used %d %d %d: %s", player.getScoreboardName(),
				pos.getX(), pos.getY(), pos.getZ(), sleeping ? "sleeping" : "awake")), false);
		return sleeping ? 1 : 0;
	}

	private static int mobs(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		int count = 0;
		for (Entity entity : EntityArgument.getEntities(context, "targets")) {
			String line = describe(entity);
			context.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
			count++;
		}
		return count;
	}

	private static String describe(Entity entity) {
		BlockPos pos = entity.blockPosition();
		StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%s at %d %d %d: rain here %s, thunder here %s",
				Versioned.entityTypePath(entity.getType()), pos.getX(), pos.getY(), pos.getZ(),
				RegionalWeather.mobRainCheck(entity.level(), pos, entity.level().isRaining()),
				RegionalWeather.mobThunderCheck(entity.level(), pos, entity.level().isThundering())));
		if (entity instanceof Panda panda) {
			line.append(String.format(Locale.ROOT, "; worried %s, scared %s, sitting %s", panda.isWorried(), panda.isScared(), panda.isSitting()));
		}
		if (entity instanceof Fox fox) {
			line.append("; sleeping ").append(fox.isSleeping());
		}
		if (entity instanceof Bee bee) {
			line.append("; hive ").append(bee.hasHive() ? bee.getHivePos().toShortString() : "none");
		}
		if (entity instanceof Mob mob) {
			line.append("; goals [").append(mob.getGoalSelector().getAvailableGoals().stream().filter(goal -> goal.isRunning())
					.map(goal -> goal.getGoal().getClass().getSimpleName()).sorted().collect(Collectors.joining(", "))).append(']');
		}
		return line.toString();
	}
}
