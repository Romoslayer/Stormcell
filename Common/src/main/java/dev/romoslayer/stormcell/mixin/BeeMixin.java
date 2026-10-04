package dev.romoslayer.stormcell.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.romoslayer.stormcell.world.RegionalWeather;
import net.minecraft.world.entity.animal.bee.Bee;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Bees head back to their hive when it rains where they are. */
@Mixin(Bee.class)
abstract class BeeMixin {
	@ModifyExpressionValue(method = "wantsToEnterHive", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/attribute/EnvironmentAttributeSystem;getValue(Lnet/minecraft/world/attribute/EnvironmentAttribute;Lnet/minecraft/world/phys/Vec3;)Ljava/lang/Object;"))
	private Object stormcell$hideFromRain(Object stayInHive) {
		if (Boolean.TRUE.equals(stayInHive)) {
			return stayInHive;
		}
		Bee bee = (Bee) (Object) this;
		return RegionalWeather.mobRainCheck(bee.level(), bee.blockPosition(), false) ? Boolean.TRUE : stayInHive;
	}
}
