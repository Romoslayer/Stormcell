package dev.romoslayer.stormcell.mixin;

import dev.romoslayer.stormcell.command.VanillaWeatherCommand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.WeatherCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** /weather in a dimension Stormcell runs works on its storms instead of the (switched off) world-wide weather. */
@Mixin(WeatherCommand.class)
abstract class WeatherCommandMixin {
	@Inject(method = "setClear", at = @At("HEAD"), cancellable = true)
	private static void stormcell$clear(CommandSourceStack source, int duration, CallbackInfoReturnable<Integer> cir) {
		Integer result = VanillaWeatherCommand.clear(source, duration);
		if (result != null) {
			cir.setReturnValue(result);
		}
	}

	@Inject(method = "setRain", at = @At("HEAD"), cancellable = true)
	private static void stormcell$rain(CommandSourceStack source, int duration, CallbackInfoReturnable<Integer> cir) {
		Integer result = VanillaWeatherCommand.rain(source, duration);
		if (result != null) {
			cir.setReturnValue(result);
		}
	}

	@Inject(method = "setThunder", at = @At("HEAD"), cancellable = true)
	private static void stormcell$thunder(CommandSourceStack source, int duration, CallbackInfoReturnable<Integer> cir) {
		Integer result = VanillaWeatherCommand.thunder(source, duration);
		if (result != null) {
			cir.setReturnValue(result);
		}
	}
}
