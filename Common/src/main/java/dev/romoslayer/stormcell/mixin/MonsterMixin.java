package dev.romoslayer.stormcell.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.romoslayer.stormcell.world.RegionalWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Monsters spawn in the gloom under a thunderstorm, as they do in vanilla thunderstorms, but only under the storm. */
@Mixin(Monster.class)
abstract class MonsterMixin {
	@ModifyExpressionValue(method = "isDarkEnoughToSpawn", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;isThundering()Z"))
	private static boolean stormcell$thunderHere(boolean thundering, @Local(argsOnly = true) ServerLevelAccessor level, @Local(argsOnly = true) BlockPos pos) {
		return RegionalWeather.mobThunderCheck(level.getLevel(), pos, thundering);
	}
}
