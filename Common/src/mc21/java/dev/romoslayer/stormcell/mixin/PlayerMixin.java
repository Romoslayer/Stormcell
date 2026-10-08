package dev.romoslayer.stormcell.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.romoslayer.stormcell.world.BedWeather;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps a player who went to bed during a thunderstorm from being woken straight back up by the daylight check. In its
 * own mixin config (stormcell.bed.mixins.json), loaded only by Fabric: NeoForge and Forge ask an event instead.
 */
@Mixin(Player.class)
abstract class PlayerMixin {
	@ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isDay()Z"))
	private boolean stormcell$stayAsleepInStorm(boolean day) {
		Player player = (Player) (Object) this;
		return day && !(player.isSleeping() && BedWeather.stormLetsSleep(player.level(), player.blockPosition()));
	}
}
