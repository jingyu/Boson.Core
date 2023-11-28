/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
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

package io.bosonnetwork.am;

import java.io.File;
import java.io.IOException;

import io.bosonnetwork.Configuration;
import io.bosonnetwork.DefaultConfiguration;

import io.bosonnetwork.access.impl.AccessManager;
import picocli.CommandLine.Option;

public abstract class AmCommand {
	private static final String DEFAULT_CONFIG_FILE = "/etc/boson/default.conf";

	@Option(names = {"-c", "--config"}, description = "The boson configuration file, default: " + DEFAULT_CONFIG_FILE)
	private String configFile = DEFAULT_CONFIG_FILE;

	private AccessManager am;

	protected AccessManager getAccessManager() throws IOException {
		if (am == null) {
			DefaultConfiguration.Builder builder = new DefaultConfiguration.Builder();

			configFile = configFile.startsWith("~") ?
					System.getProperty("user.home") + configFile.substring(1) :
					configFile;

			try {
				builder.load(configFile);
			} catch (Exception e) {
				System.out.println("Can not load the config file: " + configFile + ", error: " + e.getMessage());
				e.printStackTrace(System.err);
				System.exit(-1);
			}

			Configuration config = builder.build();
			File dataDir = config.storagePath();
			if (dataDir == null) {
				System.out.println("No datadir in the configuration.");
				System.exit(-1);
			}

			File accessControlRoot = new File(dataDir.getAbsoluteFile(), "accesscontrol");

			am = new AccessManager(accessControlRoot);
		}

		return am;
	}
}
