package io.bosonnetwork.utils;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * File based application instance exclusive lock, guarantee the application can only run
 * in single instance mode.
 */
public class ApplicationLock implements AutoCloseable {
	private final Path lockFile;
	private FileChannel fc;
	private FileLock lock;

	/**
	 * Creates a {@code ApplicationLock} on the specified path, and try to acquire the
	 * lock at the same time.
	 *
	 * @param lockFile the path to the lock file.
	 * @throws IOException if the I/O error occurred.
	 * @throws IllegalStateException if another application instance already took the lock.
	 */
	public ApplicationLock(Path lockFile) throws IOException, IllegalStateException {
		this.lockFile = lockFile.normalize().toAbsolutePath();
		tryLock();
	}

	/**
	 * Creates a {@code ApplicationLock} on the specified path, and try to acquire the
	 * lock at the same time.
	 *
	 * @param lockFile the path to the lock file.
	 * @throws IOException if the I/O error occurred.
	 * @throws IllegalStateException if another application instance already took the lock.
	 */
	public ApplicationLock(File lockFile) throws IOException, IllegalStateException {
		this(lockFile.toPath());
	}

	/**
	 * Creates a {@code ApplicationLock} on the specified path, and try to acquire the
	 * lock at the same time.
	 *
	 * @param lockFile the path to the lock file.
	 * @throws IOException if the I/O error occurred.
	 * @throws IllegalStateException if another application instance already took the lock.
	 */
	public ApplicationLock(String lockFile) throws IOException, IllegalStateException {
		this(Path.of(lockFile));
	}

	private void tryLock() throws IOException, IllegalStateException {
		Path parent = lockFile.getParent();
		if (Files.notExists(parent))
			Files.createDirectories(parent);

		fc = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		lock = fc.tryLock(0, 0, false);
		if (lock == null)
			throw new IllegalStateException("Already locked by another instance.");
	}

	@Override
	public void close() {
		if (lock != null) {
			try {
				lock.close();
				Files.delete(lockFile);
				lock = null;
			} catch (IOException ignore) {
				lock = null;
			}
		}
	}
}