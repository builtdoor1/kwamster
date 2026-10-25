package kwamster.core.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Crash-safe file writes (§10): the content goes to {@code <file>.tmp} first and is then moved over
 * the target with {@code ATOMIC_MOVE}, so a crash mid-save never leaves a half-written file. Falls
 * back to a plain replace where the file system has no atomic move. Minecraft-free.
 */
public final class AtomicFiles {
	private AtomicFiles() {
	}

	/**
	 * Writes {@code content} as UTF-8 to {@code file} atomically, creating parent directories.
	 *
	 * @throws IOException if the write or the move fails (the temporary file is removed best-effort)
	 */
	public static void writeString(Path file, String content) throws IOException {
		Path parent = file.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			Files.writeString(tmp, content, StandardCharsets.UTF_8);
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException ignored) {
				// best effort
			}
			throw e;
		}
	}

	/**
	 * Moves an unreadable file aside as {@code <name>.broken-<epochMs><.ext>} next to it (for example
	 * {@code config.json} becomes {@code config.broken-1729430000000.json}) so it is kept for
	 * inspection and the caller can start fresh.
	 *
	 * @return the backup path
	 * @throws IOException if the rename fails
	 */
	public static Path backupBroken(Path file, long epochMs) throws IOException {
		String name = file.getFileName().toString();
		int dot = name.lastIndexOf('.');
		String backupName = dot > 0
				? name.substring(0, dot) + ".broken-" + epochMs + name.substring(dot)
				: name + ".broken-" + epochMs;
		Path backup = file.resolveSibling(backupName);
		Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
		return backup;
	}
}
