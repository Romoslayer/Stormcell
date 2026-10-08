package dev.romoslayer.stormcell.world;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Where a loot roll happens. LootContext's optional-parameter getter is named getOptionalParameter in 26.2 and
 * getOptional in 26.3, so whichever exists is looked up once.
 */
public final class LootOrigin {
	private static final @Nullable MethodHandle GETTER = findGetter();

	private LootOrigin() {
	}

	public static @Nullable Vec3 of(LootContext context) {
		if (GETTER == null || !context.hasParameter(LootContextParams.ORIGIN)) {
			return null;
		}
		try {
			return (Vec3) (Object) GETTER.invoke(context, LootContextParams.ORIGIN);
		} catch (Throwable e) {
			return null;
		}
	}

	private static @Nullable MethodHandle findGetter() {
		MethodType type = MethodType.methodType(Object.class, ContextKey.class);
		for (String name : new String[] {"getOptional", "getOptionalParameter"}) {
			try {
				return MethodHandles.publicLookup().findVirtual(LootContext.class, name, type);
			} catch (ReflectiveOperationException ignored) {
				// Try the other name
			}
		}
		return null;
	}
}
