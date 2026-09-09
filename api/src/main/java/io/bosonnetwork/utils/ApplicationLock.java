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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * File based application instance exclusive lock, guarantee the application can only run
 * in single instance mode.
 * <p>
 * <b>The holding process must not open its own lock file.</b> As {@link FileLock} puts it: "On some
 * systems, closing a channel releases all locks held by the Java virtual machine on the underlying
 * file regardless of whether the locks were acquired via that channel or via another channel open on
 * the same file. It is strongly recommended that, within a program, a unique channel be used to
 * acquire all locks on any given file." That is what this class does - the registry below keeps a
 * single channel per path - but the hazard reaches wider than locking: on POSIX the locks belong to
 * the process rather than to the descriptor that took them, so a read-only descriptor, or the
 * short-lived open/close pair inside {@link Files#readString(Path)}, releases them just as
 * effectively. Nothing reports this, and {@link FileLock#isValid()} keeps returning {@code true}
 * while another process is free to take the lock, so read the owner marker only from a process that
 * does not itself hold it.
 */
public class ApplicationLock implements AutoCloseable {
	/**
	 * Lock files held by this JVM, so that a second attempt can be refused without opening - and then
	 * closing - a descriptor that would release the lock the first one holds. See the class notes on
	 * the POSIX close semantics this works around.
	 */
	private static final List<Path> heldByThisJvm = new ArrayList<>(2);

	private final Path lockFile;
	/** Whether this instance owns {@code lockFile}'s entry above. Guarded by the same monitor. */
	private boolean registered;
	private @Nullable FileChannel fc;
	private @Nullable FileLock lock;

	/**
	 * Creates a {@code ApplicationLock} on the specified path, and try to acquire the
	 * lock at the same time.
	 *
	 * @param lockFile the path to the lock file.
	 * @throws IOException if the I/O error occurred.
	 * @throws IllegalStateException if the lock is already taken, by another application instance or
	 *         by this one.
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
		this(Paths.get(lockFile));
	}

	/**
	 * Claims {@link #lockFile} for this instance, so that no other instance in this JVM opens a second
	 * channel on it.
	 *
	 * @throws IllegalStateException if this JVM already holds the file
	 */
	private void register() throws IllegalStateException {
		synchronized (heldByThisJvm) {
			if (heldByThisJvm.contains(lockFile))
				throw new IllegalStateException("Already locked by this application instance.");

			heldByThisJvm.add(lockFile);
			registered = true;
		}
	}

	/**
	 * Gives up this instance's claim on {@link #lockFile}, once.
	 * <p>
	 * The claim is released only if this instance still owns it. Removing the path unconditionally
	 * would let a redundant {@link #close()} drop an entry belonging to a <em>different</em> instance
	 * that has since taken the same path, which would then let a third instance open a second channel
	 * on it and, on closing that channel, release the second instance's lock.
	 */
	private void unregister() {
		synchronized (heldByThisJvm) {
			if (registered) {
				heldByThisJvm.remove(lockFile);
				registered = false;
			}
		}
	}

	private void tryLock() throws IOException, IllegalStateException {
		// Claimed before the channel is opened: it is the close of a second descriptor that drops the
		// lock, and a losing attempt would otherwise open one and immediately close it again.
		register();

		try {
			open();
		} catch (IOException | RuntimeException | Error e) {
			unregister();
			throw e;
		}
	}

	private void open() throws IOException, IllegalStateException {
		Path parent = lockFile.getParent();
		if (parent != null && Files.notExists(parent))
			Files.createDirectories(parent);

		fc = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		try {
			lock = fc.tryLock(0, Long.MAX_VALUE, false);
			if (lock == null)
				throw new IllegalStateException("Already locked by another instance.");
			// Write owner metadata so a reader can answer "who is holding this?". Best-effort -
			// failure to write the marker does not affect the lock itself.
			try {
				String marker = ProcessHandle.current().pid() + " " + Instant.now() + System.lineSeparator();
				fc.truncate(0);
				// noinspection ResultOfMethodCallIgnored
				fc.write(ByteBuffer.wrap(marker.getBytes(StandardCharsets.UTF_8)));
				fc.force(true);
			} catch (IOException ignore) {
				// Owner marker is informational only; the lock itself is held by the file lock.
			}
		} catch (IOException | RuntimeException | Error e) {
			fc.close();
			fc = null;
			throw e;
		}
	}

	private void unlock() {
		// Only delete the lock file if we are confident we still own it. Without this guard, a
		// late-running close() after the OS released our file lock can end up deleting a successor
		// process's lock file (the file name is the same but the OS-level lock has moved on).
		boolean stillOwnsLock = lock != null && lock.isValid();
		try {
			if (lock != null) {
				lock.close();
				lock = null;
			}

			if (fc != null) {
				fc.close();
				fc = null;
			}

			if (stillOwnsLock)
				Files.deleteIfExists(lockFile);
		} catch (IOException ignore) {
			// Ignore cleanup errors
		} finally {
			lock = null;
			fc = null;
			unregister();
		}
	}

	@Override
	public void close() {
		unlock();
	}
}
