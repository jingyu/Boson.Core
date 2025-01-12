package io.bosonnetwork.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

public class ApplicationLockTests {
	private static final Path lockFile = Path.of(System.getProperty("java.io.tmpdir"), "boson", "lock");

	@Test
	void testLock() {
		try (ApplicationLock lock = new ApplicationLock(lockFile)) {
			System.out.println("Acquired lock!");

			assertTrue(Files.exists(lockFile));
			assertTrue(Files.isRegularFile(lockFile));
		} catch (IOException | IllegalStateException e) {
			System.out.println("Acquired lock failed!");
			fail("Acquired lock failed!", e);
			return;
		}

		assertFalse(Files.exists(lockFile));
	}
}
