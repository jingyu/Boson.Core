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
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.utils.AddressUtils;
import io.bosonnetwork.utils.Base58;

/**
 * BootstrapSetup is a utility to initialize the configuration for a Boson Bootstrap Node.
 */
public class BootstrapSetup {
	/**
	 * Mode for the generated configuration: readable by the owner and the group the node runs as,
	 * and by nobody else. {@code node.yaml} carries the node's private key in clear text, so the
	 * default mode would publish the node's identity to every account on the host.
	 */
	private static final Set<PosixFilePermission> CONFIG_PERMISSIONS =
			PosixFilePermissions.fromString("rw-r-----");

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

			if (!confirmOverwrite(configDir.resolve("node.yaml")))
				return;
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

	/**
	 * Asks whether an existing bootstrap configuration may be replaced.
	 * <p>
	 * A bootstrap node's value is that other nodes have its id in their configuration, which makes
	 * its identity the one thing here that cannot be regenerated: replacing the key pair does not
	 * reconfigure this node, it removes it from the network as far as every node that already knows
	 * it is concerned. So this is a confirmation rather than a warning, and anything other than an
	 * explicit yes aborts - including a closed or redirected stdin, where there is nobody to ask.
	 * </p>
	 *
	 * @param configFile the configuration file that would be overwritten.
	 * @return {@code true} if the operator confirmed the overwrite.
	 */
	private boolean confirmOverwrite(Path configFile) {
		System.out.println("WARNING: This bootstrap node is already configured:");
		System.out.println("  " + configFile);
		System.out.println();
		System.out.println("Continuing generates a new node identity. The current private key is");
		System.out.println("lost, and every node configured with this bootstrap node's current id");
		System.out.println("will no longer be able to reach it.");
		System.out.println();
		System.out.print("Type 'yes' to confirm overwrite, or press Enter to abort: ");

		// Closing the Scanner closes System.in for the whole process. Safe here: this is the only
		// prompt in the setup, and nothing reads standard input after it either way.
		String answer;
		try (Scanner scanner = new Scanner(System.in)) {
			answer = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
		}

		if (!"yes".equals(answer)) {
			System.out.println("Setup aborted, the existing configuration is unchanged.");
			return false;
		}

		System.out.println();
		return true;
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

		restrict(target);
		Files.writeString(target, content);
	}

	/**
	 * Creates the target file empty and already restricted to its owner and group.
	 * <p>
	 * Done before the write rather than after it: setting the mode afterwards leaves the private key
	 * on disk under the default umask - world-readable on a stock system - for the moment in between,
	 * and that moment is enough on a host where the operator is not the only account.
	 * </p>
	 * <p>
	 * Skipped where the file system has no POSIX permissions to set. The bootstrap node's packaged
	 * layout is Linux-only; elsewhere this is a developer running the setup by hand.
	 * </p>
	 *
	 * @param target the file about to be written.
	 * @throws IOException if the file cannot be replaced.
	 */
	private static void restrict(Path target) throws IOException {
		if (!target.getFileSystem().supportedFileAttributeViews().contains("posix"))
			return;

		// An existing file keeps its own mode through a write, so replace it rather than truncate it.
		Files.deleteIfExists(target);
		Files.createFile(target, PosixFilePermissions.asFileAttribute(CONFIG_PERMISSIONS));
	}
}