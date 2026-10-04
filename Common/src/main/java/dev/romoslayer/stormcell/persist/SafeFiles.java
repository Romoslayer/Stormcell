package dev.romoslayer.stormcell.persist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Writing files so that a crash or a full disk never leaves a half-written one behind. */
public final class SafeFiles {
	private SafeFiles() {
	}

	/**
	 * Writes text to a temporary file and moves it over the target. Falls back to a plain replacing move on file systems
	 * that cannot move atomically. If anything fails, the previous file is left as it was.
	 */
	public static void replace(Path file, String text) throws IOException {
		Path parent = file.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temp, text, StandardCharsets.UTF_8);
		try {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
