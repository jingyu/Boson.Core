/*
 * Copyright (c) 2023 -      bosonnetwork.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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