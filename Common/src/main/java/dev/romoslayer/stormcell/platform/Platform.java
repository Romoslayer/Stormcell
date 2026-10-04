package dev.romoslayer.stormcell.platform;

import java.nio.file.Path;

/** The few things the shared code needs from whichever loader is running it. */
public interface Platform {
	/** The folder config files go in. */
	Path configDir();

	boolean isModLoaded(String modId);
}
