package dev.romoslayer.stormcell.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.romoslayer.stormcell.world.RegionalWeather;
import net.minecraft.world.entity.animal.panda.Panda;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Worried pandas are scared of thunderstorms that are overhead. */
@Mixin(Panda.class)
abstract class PandaMixin {
	@ModifyExpressionValue(method = {"tick", "isScared"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isThundering()Z"))
	private boolean stormcell$thunderHere(boolean thundering) {
		Panda panda = (Panda) (Object) this;
		return RegionalWeather.mobThunderCheck(panda.level(), panda.blockPosition(), thundering);
	}
}
