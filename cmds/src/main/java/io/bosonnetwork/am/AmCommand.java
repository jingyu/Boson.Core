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

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import picocli.CommandLine.Option;

import io.bosonnetwork.DefaultNodeConfiguration;
import io.bosonnetwork.NodeConfiguration;
import io.bosonnetwork.access.impl.AccessManager;
import io.bosonnetwork.utils.Json;

/**
 * @hidden
 */
public abstract class AmCommand {
	private static final String DEFAULT_CONFIG_FILE = "/etc/boson/default.conf";

	@Option(names = {"-c", "--config"}, description = "The boson configuration file, default: " + DEFAULT_CONFIG_FILE)
	private String configFile = DEFAULT_CONFIG_FILE;

	private AccessManager am;

	protected AccessManager getAccessManager() throws IOException {
		if (am == null) {
			DefaultNodeConfiguration.Builder builder = NodeConfiguration.builder();

			Path file = Path.of(configFile).normalize();
			if (file.startsWith("~"))
				file = Path.of(System.getProperty("user.home")).resolve(file.subpath(1, file.getNameCount()));
			else
				file = file.toAbsolutePath();

			try {
				Map<String, Object> map = Json.yamlMapper().readValue(configFile, Json.mapType());
				builder.template(map);
			} catch (Exception e) {
				System.out.println("Can not load the config file: " + configFile + ", error: " + e.getMessage());
				e.printStackTrace(System.err);
				System.exit(-1);
			}

			NodeConfiguration config = builder.build();
			Path dataPath = config.dataDir();
			if (dataPath == null) {
				System.out.println("No data path in the configuration.");
				System.exit(-1);
			}

			Path accessControlRoot = dataPath.resolve("accesscontrol").toAbsolutePath();

			am = new AccessManager(accessControlRoot);
		}

		return am;
	}
}