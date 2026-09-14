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

package io.bosonnetwork.kademlia.shell;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellLoggingTests {
	@Test
	void testTheBundledConfigurationIsOnTheClassPath() {
		assertTrue(ShellLogging.exists(ShellLogging.DEFAULT_CONFIGURATION));
	}

	@Test
	void testAnExistingFileIsUsable(@TempDir Path dir) throws Exception {
		Path file = Files.writeString(dir.resolve("logback.xml"), "<configuration/>");
		assertTrue(ShellLogging.exists(file.toString()));
		assertTrue(ShellLogging.exists(file.toUri().toString()));
	}

	@Test
	void testAMissingFileIsNotUsable(@TempDir Path dir) {
		// What dht-shell.sh names when the setup wizard has never written a configuration.
		Path missing = dir.resolve("logback.xml");
		assertFalse(ShellLogging.exists(missing.toString()));
		assertFalse(ShellLogging.exists(missing.toUri().toString()));
		assertFalse(ShellLogging.exists(dir.toString()));
	}

	@Test
	void testANonFileUrlIsLeftToLogback() {
		assertTrue(ShellLogging.exists("https://config.example/logback.xml"));
	}
}
