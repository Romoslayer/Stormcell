package dev.romoslayer.stormcell.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.romoslayer.stormcell.world.LootOrigin;
import dev.romoslayer.stormcell.world.RegionalWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.WeatherCheck;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The weather_check loot condition (used by data packs) looks at the weather where the loot is rolled. */
@Mixin(WeatherCheck.class)
abstract class WeatherCheckMixin {
	@ModifyExpressionValue(method = "test(Lnet/minecraft/world/level/storage/loot/LootContext;)Z", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/level/ServerLevel;isRaining()Z"))
	private boolean stormcell$rainingHere(boolean raining, @Local(argsOnly = true) LootContext context) {
		Vec3 origin = LootOrigin.of(context);
		return origin == null ? raining : RegionalWeather.isRainingAt(context.getLevel(), BlockPos.containing(origin), raining);
	}

	@ModifyExpressionValue(method = "test(Lnet/minecraft/world/level/storage/loot/LootContext;)Z", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/level/ServerLevel;isThundering()Z"))
	private boolean stormcell$thunderingHere(boolean thundering, @Local(argsOnly = true) LootContext context) {
		Vec3 origin = LootOrigin.of(context);
		return origin == null ? thundering : RegionalWeather.isThunderingAt(context.getLevel(), BlockPos.containing(origin), thundering);
	}
}
