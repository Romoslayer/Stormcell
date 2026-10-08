package dev.romoslayer.stormcell.mc;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.animal.horse.SkeletonHorse;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The few Minecraft calls whose names or shapes differ between the Minecraft versions Stormcell supports. Each version
 * folder (Common/src/mc20, mc21, mc26) has its own copy of this class with the same methods; this one is for 1.21.x.
 */
public final class Versioned {
	private Versioned() {
	}

	/** A registry key as "namespace:path". */
	public static String id(ResourceKey<?> key) {
		return key.location().toString();
	}

	/** A dimension key from "namespace:path". */
	public static ResourceKey<Level> dimension(String id) {
		return ResourceKey.create(Registries.DIMENSION, new ResourceLocation(id));
	}

	/** An entity type's registry path, such as "fox". */
	public static String entityTypePath(EntityType<?> type) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
	}

	/** A biome tag from "namespace:path", or null if the text is not a valid id. */
	public static @Nullable TagKey<Biome> biomeTag(String id) {
		ResourceLocation tag = ResourceLocation.tryParse(id);
		return tag == null ? null : TagKey.create(Registries.BIOME, tag);
	}

	/** The doWeatherCycle game rule (advance_weather in 26.x). */
	public static boolean advancesWeather(ServerLevel level) {
		return level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE);
	}

	/** The doMobSpawning game rule (spawn_mobs in 26.x). */
	public static boolean spawnsMobs(ServerLevel level) {
		return level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);
	}

	public static int minY(LevelHeightAccessor level) {
		return level.getMinBuildHeight();
	}

	public static int maxY(LevelHeightAccessor level) {
		return level.getMaxBuildHeight() - 1;
	}

	/** What a biome lets fall at a spot (rain, snow or nothing). 1.21.x has no sea level in this calculation. */
	public static Biome.Precipitation precipitationAt(Biome biome, BlockPos pos, int seaLevel) {
		return biome.getPrecipitationAt(pos);
	}

	public static boolean warmEnoughToRain(Biome biome, BlockPos pos, int seaLevel) {
		return biome.warmEnoughToRain(pos);
	}

	public static boolean coldEnoughToSnow(Biome biome, BlockPos pos, int seaLevel) {
		return biome.coldEnoughToSnow(pos);
	}

	/** Whether this dimension has weather at all (skylight; not the Nether or the End). */
	public static boolean canHaveWeather(ServerLevel level) {
		return level.dimensionType().hasSkyLight();
	}

	/** The time of day that drives the day/night cycle, in ticks. */
	public static long dayTime(ServerLevel level) {
		return level.getDayTime();
	}

	/** The level a player is in. */
	public static ServerLevel level(ServerPlayer player) {
		return player.serverLevel();
	}

	/** The dimension a player joins in. */
	public static ResourceKey<Level> dimension(ClientboundLoginPacket packet) {
		return packet.dimension();
	}

	/** The dimension a player respawns or travels into. */
	public static ResourceKey<Level> dimension(ClientboundRespawnPacket packet) {
		return packet.getDimension();
	}

	/** Whether the game runs at its normal pace (1.20.1 has no /tick command, so always). */
	public static boolean ticksNormally(MinecraftServer server) {
		return true;
	}

	/** A biome as the server sends it to clients (its data-pack climate, before any mod shifts it), as JSON. */
	public static JsonObject encodeForNetwork(Biome biome, RegistryAccess registries) {
		return Biome.NETWORK_CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), biome).getOrThrow(false, message -> {}).getAsJsonObject();
	}

	/** Who may use /stormcell: operators at the game-master level. */
	public static Predicate<CommandSourceStack> operators() {
		return source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS);
	}

	/** The stop-sound packet that cuts a dust storm's wind (the elytra sound, weather category) off. */
	public static ClientboundStopSoundPacket stopDustWind() {
		return new ClientboundStopSoundPacket(SoundEvents.ELYTRA_FLYING.getLocation(), SoundSource.WEATHER);
	}

	/** Whether a stop-sound packet is the one that cuts the dust storm wind off. */
	public static boolean isDustWindStop(ClientboundStopSoundPacket packet) {
		return packet.getSource() == SoundSource.WEATHER && SoundEvents.ELYTRA_FLYING.getLocation().equals(packet.getName());
	}

	/** Whether a block is a lightning rod (1.21.x has no lightning rod block tag; NeoForge checks the block class too). */
	public static boolean isLightningRod(BlockState state) {
		return state.getBlock() instanceof LightningRodBlock;
	}

	/** Spawns a skeleton horse trap at a spot, as vanilla's thunder tick does. */
	public static void spawnSkeletonTrap(ServerLevel level, BlockPos pos) {
		SkeletonHorse horse = EntityType.SKELETON_HORSE.create(level);
		if (horse != null) {
			horse.setTrap(true);
			horse.setAge(0);
			horse.setPos(pos.getX(), pos.getY(), pos.getZ());
			level.addFreshEntity(horse);
		}
	}

	/** Strikes lightning at a spot. A visual-only bolt starts no fires and hurts nothing. */
	public static void strikeLightning(ServerLevel level, BlockPos pos, boolean visualOnly) {
		LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
		if (bolt != null) {
			bolt.moveTo(Vec3.atBottomCenterOf(pos));
			bolt.setVisualOnly(visualOnly);
			level.addFreshEntity(bolt);
		}
	}
}
