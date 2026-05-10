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

package io.bosonnetwork.kademlia;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.utils.AddressUtils;
import io.bosonnetwork.utils.Base58;

/**
 * BootstrapSetup is a utility to initialize the configuration for a Boson Bootstrap Node.
 */
public class BootstrapSetup {
	private final Path homeDir;
	private final boolean batch;

	public static void main(String[] args) {
		Path home = null;
		boolean batch = false;

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--home":
					if (i + 1 < args.length)
						home = Path.of(args[++i]);
					break;
				case "--batch":
					batch = true;
					break;
			}
		}

		try {
			BootstrapSetup setup = new BootstrapSetup(home, batch);
			setup.run();
		} catch (Exception e) {
			System.err.println("Bootstrap setup failed: " + e.getMessage());
			System.exit(1);
		}
	}

	private BootstrapSetup(Path homeDir, boolean batch) {
		this.homeDir = homeDir;
		this.batch = batch;
	}

	public void run() throws IOException {
		// Define paths
		Path configDir = Path.of("/etc/boson/bootstrap");
		Path dataDir = Path.of("/var/lib/boson/bootstrap");
		Path logDir = Path.of("/var/log/boson/bootstrap");

		if (Files.exists(configDir.resolve("node.yaml"))) {
			if (batch) {
				System.out.println("Configuration already exists, skipping initialization.");
				return;
			}
		}

		// 1. Generate Identity
		Signature.KeyPair nodeKey = Signature.KeyPair.random();
		Id nodeId = Id.of(nodeKey.publicKey().bytes());

		// 2. Detect IP
		InetAddress defaultIp = AddressUtils.getDefaultRouteAddress(Inet4Address.class);
		String publicIp = (defaultIp != null) ? defaultIp.getHostAddress() : "127.0.0.1";

		// 3. Prepare Substitutions
		Map<String, String> vars = new HashMap<>();
		vars.put("NODE_PUBLIC_KEY", nodeId.toBase58String());
		vars.put("NODE_PRIVATE_KEY", Base58.encode(nodeKey.privateKey().bytes()));
		vars.put("PUBLIC_IPV4_ADDRESS", publicIp);
		vars.put("LOG_DIR", logDir.toAbsolutePath().toString());
		vars.put("DATA_DIR", dataDir.toAbsolutePath().toString());

		// 4. Process Template
		Path templateDir = getTemplateDir();
		if (templateDir == null) {
			throw new IOException("Bootstrap template directory not found.");
		}

		Files.createDirectories(configDir);
		Files.createDirectories(dataDir);
		Files.createDirectories(logDir);

		processTemplate(templateDir.resolve("node.yaml"), configDir.resolve("node.yaml"), vars);
		processTemplate(templateDir.resolve("logback.xml"), configDir.resolve("logback.xml"), vars);

		System.out.println("Bootstrap node initialized successfully.");
		System.out.println("  Node ID: " + nodeId);
		System.out.println("  Config: " + configDir.resolve("node.yaml"));
	}

	private Path getTemplateDir() {
		if (homeDir != null) {
			Path distTemplates = homeDir.resolve("config/templates/bootstrap");
			if (Files.exists(distTemplates)) return distTemplates;
		}

		Path systemTemplates = Path.of("/usr/share/boson/config/bootstrap");
		if (Files.exists(systemTemplates)) return systemTemplates;

		return null;
	}

	private void processTemplate(Path source, Path target, Map<String, String> vars) throws IOException {
		String content = Files.readString(source);
		for (Map.Entry<String, String> entry : vars.entrySet()) {
			content = content.replace("${" + entry.getKey() + "}", entry.getValue());
		}
		Files.writeString(target, content);
	}
}
