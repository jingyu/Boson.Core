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

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Chooses the shell's logging configuration.
 * <p>
 * With no configuration it can find, logback logs DEBUG to the console. From the shell that means
 * the node's threads writing all over the prompt, so when the configuration named is missing - or none
 * is named - the shell falls back to a bundled one that logs to a file.
 * </p>
 * <p>
 * The bundled configuration has a name logback never looks for by itself. Every jar in a
 * distribution shares one class path, and a {@code logback.xml} would be picked up by the director
 * and the services too.
 * </p>
 */
final class ShellLogging {
	static final String CONFIGURATION_PROPERTY = "logback.configurationFile";
	static final String DEFAULT_CONFIGURATION = "io/bosonnetwork/kademlia/shell/logback-shell.xml";
	static final String LOG_DIR_VARIABLE = "LOG_DIR";

	private ShellLogging() {
	}

	/**
	 * Selects the bundled configuration unless a usable one is already named. Must run before the
	 * first logger is created.
	 */
	static void selectConfiguration() {
		String named = System.getProperty(CONFIGURATION_PROPERTY);
		if (named != null && exists(named))
			return;

		if (System.getProperty(LOG_DIR_VARIABLE) == null && System.getenv(LOG_DIR_VARIABLE) == null)
			System.setProperty(LOG_DIR_VARIABLE,
					Path.of(System.getProperty("user.home"), ".cache", "boson", "shell", "logs").toString());

		System.setProperty(CONFIGURATION_PROPERTY, DEFAULT_CONFIGURATION);
	}

	/**
	 * Whether logback can load a configuration from the location, which - as for logback - may be a
	 * URL, a class path resource or a file.
	 */
	static boolean exists(String location) {
		if (ShellLogging.class.getClassLoader().getResource(location) != null)
			return true;

		try {
			URI uri = new URI(location);
			// A one-letter scheme is a Windows drive, not a URL.
			if (uri.getScheme() != null && uri.getScheme().length() > 1)
				return !uri.getScheme().equalsIgnoreCase("file") || Files.isRegularFile(Path.of(uri));
		} catch (Exception ignored) {
			// Not a URL: try it as a file
		}

		try {
			return Files.isRegularFile(Path.of(location));
		} catch (Exception e) {
			return false;
		}
	}
}
