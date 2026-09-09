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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ApplicationLock}.
 * <p>
 * The point of the class is mutual exclusion <em>between processes</em>, which no assertion made
 * inside a single JVM can establish: a file lock is held on behalf of the whole JVM, so this process
 * can never observe its own lock as contended. The contention tests therefore fork a real child JVM
 * and drive it from both sides: {@link #forkLockAttempt(Path)} lets the child contend for a lock this
 * JVM holds, and {@link #forkLockHolder(Path)} has the child hold one so that this JVM is the party
 * being refused - the only way to reach the "already taken" branch of the lock itself, since the
 * same-JVM guard would otherwise answer first.
 */
public class ApplicationLockTests {
	/** Exit code the child uses to report that the lock was already taken. */
	private static final int CHILD_REJECTED = 10;
	/** Exit code the child uses to report an unexpected I/O failure. */
	private static final int CHILD_IO_ERROR = 11;

	private static final int CHILD_TIMEOUT = 60;

	/** Line the holding child prints once it owns the lock, so the parent knows when to contend. */
	private static final String CHILD_READY = "LOCKED";

	/**
	 * Entry point of the forked child.
	 * <p>
	 * With {@code try} it takes the lock and releases it again, reporting the outcome as an exit code.
	 * With {@code hold} it keeps the lock, announces {@link #CHILD_READY}, and waits for its standard
	 * input to close, so the parent decides when - and whether - it is released. With {@code read} it
	 * prints the owner marker without contending for the lock at all.
	 *
	 * @param args the mode ({@code try}, {@code hold} or {@code read}) and the lock file to act on
	 * @throws Exception if the child cannot run at all
	 */
	public static void main(String[] args) throws Exception {
		if (args[0].equals("read")) {
			// Reading the lock file opens and closes a descriptor for it, which would release the
			// lock if done by the holder; a separate process can do it safely.
			System.out.println(Files.readString(Path.of(args[1])).trim());
			System.exit(0);
		}

		boolean hold = args[0].equals("hold");

		try (ApplicationLock ignored = new ApplicationLock(Path.of(args[1]))) {
			if (hold) {
				System.out.println(CHILD_READY);
				System.out.flush();
				// Blocks until the parent closes this child's input or kills it outright; either way
				// the lock is held for exactly as long as the parent wants it held.
				// noinspection ResultOfMethodCallIgnored
				System.in.read();
			}

			System.exit(0);
		} catch (IllegalStateException e) {
			System.exit(CHILD_REJECTED);
		} catch (IOException e) {
			System.exit(CHILD_IO_ERROR);
		}
	}

	/**
	 * Runs {@link #main(String[])} in a separate JVM and returns its exit code: {@code 0} if the
	 * child took the lock, {@link #CHILD_REJECTED} if it found it already held.
	 *
	 * @param lockFile the lock file to contend for
	 * @return the child's exit code
	 * @throws Exception if the child could not be started or did not finish in time
	 */
	private static int forkLockAttempt(Path lockFile) throws Exception {
		Process child = fork("try", lockFile).inheritIO().start();

		assertTrue(child.waitFor(CHILD_TIMEOUT, TimeUnit.SECONDS), "the forked JVM did not finish in time");
		return child.exitValue();
	}

	/**
	 * Starts a child JVM that takes the lock and keeps it, returning only once the child has actually
	 * acquired it. The caller owns the process and must destroy it to release the lock.
	 *
	 * @param lockFile the lock file the child should hold
	 * @return the running child, holding the lock
	 * @throws Exception if the child could not be started or never reported that it holds the lock
	 */
	private static Process forkLockHolder(Path lockFile) throws Exception {
		Process child = fork("hold", lockFile).redirectErrorStream(true).start();

		// readLine() returns null if the child dies instead of acquiring, so this cannot hang.
		BufferedReader out = new BufferedReader(new InputStreamReader(child.getInputStream()));
		String ready = out.readLine();
		if (!CHILD_READY.equals(ready)) {
			child.destroyForcibly();
			fail("the forked JVM did not take the lock, it said: " + ready);
		}

		return child;
	}

	/**
	 * Reads the lock file's owner marker in a separate JVM.
	 * <p>
	 * The holder must never read its own lock file: opening and closing a descriptor for it releases
	 * the lock on POSIX systems, silently and while {@code isValid()} still reports {@code true}. So
	 * every assertion about the marker that this JVM makes while holding the lock has to come from
	 * somewhere else.
	 *
	 * @param lockFile the lock file to read
	 * @return the trimmed marker
	 * @throws Exception if the child could not be started or did not finish in time
	 */
	private static String forkMarkerRead(Path lockFile) throws Exception {
		Process child = fork("read", lockFile).redirectErrorStream(true).start();
		String marker = new String(child.getInputStream().readAllBytes()).trim();

		assertTrue(child.waitFor(CHILD_TIMEOUT, TimeUnit.SECONDS), "the forked JVM did not finish in time");
		assertEquals(0, child.exitValue(), "could not read the lock file: " + marker);
		return marker;
	}

	private static ProcessBuilder fork(String mode, Path lockFile) {
		return new ProcessBuilder(
				Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-cp", System.getProperty("java.class.path"),
				ApplicationLockTests.class.getName(),
				mode, lockFile.toString());
	}

	@Test
	void acquiresTheLockAndCreatesTheFile(@TempDir Path dir) throws Exception {
		Path lockFile = dir.resolve("lock");

		try (ApplicationLock ignored = new ApplicationLock(lockFile)) {
			assertTrue(Files.isRegularFile(lockFile));
		}

		assertFalse(Files.exists(lockFile), "the lock file should be removed on release");
	}

	@Test
	void createsMissingParentDirectories(@TempDir Path dir) throws Exception {
		Path lockFile = dir.resolve("no").resolve("such").resolve("dir").resolve("lock");

		try (ApplicationLock ignored = new ApplicationLock(lockFile)) {
			assertTrue(Files.isRegularFile(lockFile));
		}
	}

	@Test
	void recordsTheOwningProcessInTheLockFile(@TempDir Path dir) throws Exception {
		Path lockFile = dir.resolve("lock");

		try (ApplicationLock ignored = new ApplicationLock(lockFile)) {
			String[] marker = forkMarkerRead(lockFile).split(" ");
			assertEquals(2, marker.length, "expected a 'pid timestamp' marker");
			assertEquals(ProcessHandle.current().pid(), Long.parseLong(marker[0]));
			// Parses, and is not from the future: enough to prove it is a real acquisition stamp.
			assertFalse(Instant.parse(marker[1]).isAfter(Instant.now().plusSeconds(60)));

			// Inspecting the marker must not cost us the lock. It would, had it been read in this
			// process: the open/close pair inside readString releases every fcntl lock we hold on it.
			assertEquals(CHILD_REJECTED, forkLockAttempt(lockFile), "reading the marker released the lock");
		}
	}

	@Test
	void relativePathsResolveToTheSameLock(@TempDir Path dir) throws Exception {
		Path lockFile = dir.resolve("lock");
		Path indirect = dir.resolve("sub").resolve("..").resolve("lock");
		Files.createDirectories(dir.resolve("sub"));

		try (ApplicationLock ignored = new ApplicationLock(lockFile)) {
			// The un-normalized spelling names the same file, so it must be refused as well.
			assertThrows(IllegalStateException.class, () -> new ApplicationLock(indirect));
		}
	}

	@Test
	void anotherProcessCannotTakeAHeldLock(@TempDir Path dir) throws Exception {
		Path lockFile = dir.resolve("lock");

		try (ApplicationLock ignored = new ApplicationLock(lockFile)) {
			assertEquals(CHILD_REJECTED, forkLockAttempt(lockFile),
					"a second process took a lock this one is holding");
		}
	}

	@Test
	void aLockHeldByAnotherProcessIsRefused(@TempDir Path dir) throws Exception {
		Path lockFile = dir.resolve("lock");
		Process holder = forkLockHolder(lockFile);

		try {
			IllegalStateException e = assertThrows(IllegalStateException.class, () -> new ApplicationLock(lockFile));
			// Distinguishes the real contended-lock branch from the same-JVM guard, which would
			// otherwise be the only path any test reaches and would hide a broken tryLock().
			assertTrue(e.getMessage().contains("another instance"),
					"expected the contended-lock error, got: " + e.getMessage());
		} finally {
			holder.destroy();
			assertTrue(holder.waitFor(CHILD_TIMEOUT, TimeUnit.SECONDS), "the holding JVM did not exit");
		}
	}

	@Test
	void theLockFileNamesTheHoldingProcess(@TempDir Path dir) throws Exception {
		Path lockFile = dir.resolve("lock");
		Process holder = forkLockHolder(lockFile);

		try {
			// The marker is what a contender reads to report who is in the way, so it has to name the
			// other process, not whoever happens to be reading it. Reading it here is safe precisely
			// because this JVM holds no lock on the file - the holder is the child.
			// noinspection resource
			String[] marker = Files.readString(lockFile).trim().split(" ");
			assertEquals(holder.pid(), Long.parseLong(marker[0]));
			assertNotEquals(ProcessHandle.current().pid(), Long.parseLong(marker[0]));
		} finally {
			holder.destroy();
			assertTrue(holder.waitFor(CHILD_TIMEOUT, TimeUnit.SECONDS), "the holding JVM did not exit");
		}
	}

	@Test
	void aKilledHolderReleasesTheLock(@TempDir Path dir) throws Exception {
		Path lockFile = dir.resolve("lock");
		Process holder = forkLockHolder(lockFile);

		assertThrows(IllegalStateException.class, () -> new ApplicationLock(lockFile));

		// No orderly release and no chance to delete the file: the OS is the only thing that can
		// hand the lock on, which is exactly what happens to a process that is killed.
		holder.destroyForcibly();
		assertTrue(holder.waitFor(CHILD_TIMEOUT, TimeUnit.SECONDS), "the holding JVM did not exit");

		// Also proves the refused attempt above left nothing claimed behind in this JVM.
		try (ApplicationLock ignored = new ApplicationLock(lockFile)) {
			assertEquals(ProcessHandle.current().pid(), Long.parseLong(forkMarkerRead(lockFile).split(" ")[0]));
		}
	}

	@Test
	void anotherProcessCanTakeTheLockAfterItIsReleased(@TempDir Path dir) throws Exception {
		Path lockFile = dir.resolve("lock");

		ApplicationLock lock = new ApplicationLock(lockFile);
		lock.close();

		assertEquals(0, forkLockAttempt(lockFile), "the released lock was not available to another process");
	}

	@Test
	void aSecondAttemptInThisJvmIsRejectedWithoutReleasingTheLock(@TempDir Path dir) throws Exception {
		Path lockFile = dir.resolve("lock");

		try (ApplicationLock ignored = new ApplicationLock(lockFile)) {
			assertThrows(IllegalStateException.class, () -> new ApplicationLock(lockFile));

			// The regression this guards: on POSIX, closing any descriptor for a file drops every
			// lock the process holds on it, so a losing attempt that opened and closed its own
			// channel used to hand the lock to the next process that asked for it.
			assertEquals(CHILD_REJECTED, forkLockAttempt(lockFile),
					"a failed attempt in this JVM released the lock held by this JVM");
			assertTrue(Files.isRegularFile(lockFile), "a failed attempt removed the holder's lock file");
		}
	}

	@Test
	void aRedundantCloseDoesNotDeregisterAnotherInstance(@TempDir Path dir) throws Exception {
		Path lockFile = dir.resolve("lock");

		ApplicationLock first = new ApplicationLock(lockFile);
		first.close();

		try (ApplicationLock ignored = new ApplicationLock(lockFile)) {
			// close() is idempotent by contract, but the second call no longer owns the registration:
			// the path now belongs to the instance above, and dropping its entry would let the next
			// caller open a second channel and release its lock on closing that channel.
			first.close();

			IllegalStateException e = assertThrows(IllegalStateException.class, () -> new ApplicationLock(lockFile));
			// A null message would mean OverlappingFileLockException - i.e. the registry let it
			// through and only the JVM's own lock table stopped it, after a channel was opened.
			assertTrue(e.getMessage() != null && e.getMessage().contains("this application instance"),
					"expected the registry to refuse it, got: " + e.getMessage());
			assertEquals(CHILD_REJECTED, forkLockAttempt(lockFile),
					"a redundant close released the lock held by another instance");
		}
	}

	@Test
	void theLockIsReusableInThisJvmAfterRelease(@TempDir Path dir) throws Exception {
		Path lockFile = dir.resolve("lock");

		try (ApplicationLock ignored = new ApplicationLock(lockFile)) {
			assertTrue(Files.isRegularFile(lockFile));
		}

		// The JVM-level bookkeeping must be cleared by close(), not just the OS lock.
		try (ApplicationLock ignored = new ApplicationLock(lockFile)) {
			assertTrue(Files.isRegularFile(lockFile));
		}
	}

	@Test
	void closeIsIdempotent(@TempDir Path dir) throws Exception {
		Path lockFile = dir.resolve("lock");

		ApplicationLock lock = new ApplicationLock(lockFile);
		lock.close();
		lock.close();

		assertFalse(Files.exists(lockFile));
	}

	@Test
	void aLeftoverLockFileFromACrashedInstanceCanBeTaken(@TempDir Path dir) throws Exception {
		Path lockFile = dir.resolve("lock");
		// What a SIGKILLed instance leaves behind: the file survives, the OS lock does not.
		Files.writeString(lockFile, "999999 " + Instant.now() + System.lineSeparator());

		try (ApplicationLock ignored = new ApplicationLock(lockFile)) {
			assertNotEquals("999999", forkMarkerRead(lockFile).split(" ")[0],
					"the stale owner marker should have been overwritten");
		}
	}

	@Test
	void independentLockFilesDoNotInterfere(@TempDir Path dir) throws Exception {
		Path one = dir.resolve("one.lock");
		Path two = dir.resolve("two.lock");

		try (ApplicationLock first = new ApplicationLock(one); ApplicationLock second = new ApplicationLock(two)) {
			assertTrue(Files.isRegularFile(one));
			assertTrue(Files.isRegularFile(two));
			assertNotEquals(first, second);
		}

		assertFalse(Files.exists(one));
		assertFalse(Files.exists(two));
	}

	@Test
	void aDirectoryInPlaceOfTheLockFileFailsWithIoException(@TempDir Path dir) throws Exception {
		Path lockFile = dir.resolve("lock");
		Files.createDirectory(lockFile);

		assertThrows(IOException.class, () -> new ApplicationLock(lockFile));

		// The failed attempt must not leave the path claimed for this JVM.
		Files.delete(lockFile);
		try (ApplicationLock ignored = new ApplicationLock(lockFile)) {
			assertTrue(Files.isRegularFile(lockFile));
		}
	}

	@Test
	void acceptsStringAndFileSpellingsOfThePath(@TempDir Path dir) throws Exception {
		Path lockFile = dir.resolve("lock");

		try (ApplicationLock ignored = new ApplicationLock(lockFile.toString())) {
			assertTrue(Files.isRegularFile(lockFile));
		}

		try (ApplicationLock ignored = new ApplicationLock(lockFile.toFile())) {
			assertTrue(Files.isRegularFile(lockFile));
		}
	}
}
