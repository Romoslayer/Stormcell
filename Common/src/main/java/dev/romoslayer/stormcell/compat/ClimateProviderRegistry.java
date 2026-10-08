package dev.romoslayer.stormcell.compat;

import dev.romoslayer.stormcell.api.ClimateModifierProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The climate providers other mods registered through StormcellApi, keyed by their id as text ("namespace:path"), so
 * the same code serves every Minecraft version whatever its id type is called.
 */
public final class ClimateProviderRegistry {
	private static final Map<String, ClimateModifierProvider> PROVIDERS = new LinkedHashMap<>();
	private static volatile List<ClimateModifierProvider> providerList = List.of();

	private ClimateProviderRegistry() {
	}

	public static synchronized void register(String id, ClimateModifierProvider provider) {
		PROVIDERS.put(id, provider);
		providerList = List.copyOf(PROVIDERS.values());
	}

	public static synchronized void unregister(String id) {
		PROVIDERS.remove(id);
		providerList = List.copyOf(PROVIDERS.values());
	}

	/** Whether a provider from the given mod (by id namespace) is registered. */
	public static synchronized boolean hasProviderFrom(String namespace) {
		return PROVIDERS.keySet().stream().anyMatch(id -> id.startsWith(namespace + ":"));
	}

	/** Every registered provider, in registration order. */
	public static List<ClimateModifierProvider> providers() {
		return providerList;
	}
}
