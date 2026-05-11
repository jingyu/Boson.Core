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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

import io.bosonnetwork.DefaultNodeConfiguration;
import io.bosonnetwork.Id;
import io.bosonnetwork.NodeConfiguration;
import io.bosonnetwork.json.Json;
import io.bosonnetwork.utils.ApplicationLock;
import io.bosonnetwork.vertx.VertxFuture;

/**
 * Launcher is the entry point for the Boson DHT Node application.
 * It is responsible for initializing and starting the node, parsing command-line arguments,
 * and managing the application lifecycle, including graceful shutdown.
 */
public class Launcher {
	private static KadNode node;

	private static DefaultNodeConfiguration buildConfigFromArgs(String[] args) throws IllegalArgumentException {
		DefaultNodeConfiguration.Builder builder = NodeConfiguration.builder();

		int i = 0;
		while (i < args.length) {
			switch (args[i]) {
				case "--config", "-c" -> {
					if (i + 1 >= args.length)
						throw new IllegalArgumentException("Missing file path for config option");

					String configFile = args[++i];
					try {
						Map<String, Object> map = Json.yamlMapper().readValue(new File(configFile), Json.mapType());
						builder.template(map);
					} catch (Exception e) {
						throw new IllegalArgumentException("Failed to load configuration file: " + configFile, e);
					}
				}
				case "--host4", "-4" -> {
					if (i + 1 >= args.length)
						throw new IllegalArgumentException("Missing value for host4 option");

					builder.host4(args[++i]);
				}
				case "--host6", "-6" -> {
					if (i + 1 >= args.length)
						throw new IllegalArgumentException("Missing value for host6 option");

					builder.host6(args[++i]);
				}
				case "--port", "-p" -> {
					if (i + 1 >= args.length)
						throw new IllegalArgumentException("Missing value for port option");

					try {
						builder.port(Integer.parseInt(args[++i]));
					} catch (NumberFormatException e) {
						throw new IllegalArgumentException("Invalid port number: " + args[i]);
					}
				}
				case "--data-dir", "-d" -> {
					if (i + 1 >= args.length)
						throw new IllegalArgumentException("Missing data path for data-dir option");

					builder.dataDir(args[++i]);
				}
				case "--bootstrap", "-b" -> {
					if (i + 1 >= args.length)
						throw new IllegalArgumentException("Missing value for bootstrap option");

					String bootstrapValue = args[++i];
					int firstColon = bootstrapValue.indexOf(':');
					int lastColon = bootstrapValue.lastIndexOf(':');

					if (firstColon == -1 || lastColon == -1 || firstColon == lastColon)
						throw new IllegalArgumentException("Invalid bootstrap format '" + bootstrapValue + "'. Expected ID:ADDRESS:PORT");

					try {
						String idStr = bootstrapValue.substring(0, firstColon);
						String addr = bootstrapValue.substring(firstColon + 1, lastColon);
						String portStr = bootstrapValue.substring(lastColon + 1);

						Id id;
						try {
							id = Id.of(idStr);
						} catch (Exception e) {
							throw new IllegalArgumentException("Invalid bootstrap node ID: " + idStr, e);
						}

						int port;
						try {
							port = Integer.parseInt(portStr);
						} catch (NumberFormatException e) {
							throw new IllegalArgumentException("Invalid bootstrap node port: " + portStr);
						}

						builder.addBootstrap(id, addr, port);
					} catch (IllegalArgumentException e) {
						throw e;
					} catch (Exception e) {
						throw new IllegalArgumentException("Failed to parse bootstrap node " + bootstrapValue, e);
					}
				}
				case "--developerMode" -> builder.enableDeveloperMode();
				case "--help", "-h" -> {
					printUsage();
					System.exit(0);
				}
				default -> throw new IllegalArgumentException("Unknown argument '" + args[i] + "' at index " + i);
			}

			i++;
		}

		return (DefaultNodeConfiguration) builder.build();
	}

	private static void printUsage() {
		System.out.println("Boson DHT Node Launcher");
		System.out.println("Usage: launcher [OPTIONS]");
		System.out.println();
		System.out.println("Options:");
		System.out.println("  -c, --config <FILE>       Path to the YAML configuration file.");
		System.out.println("  -4, --host4 <ADDR>        IPv4 address to listen on.");
		System.out.println("  -6, --host6 <ADDR>        IPv6 address to listen on.");
		System.out.println("  -p, --port <PORT>         UDP port to listen on.");
		System.out.println("  -d, --data-dir <DIR>      Directory for node data and persistent storage.");
		System.out.println("  -b, --bootstrap <NODE>    Bootstrap node (Format: ID:ADDRESS:PORT).");
		System.out.println("      --developerMode       Enable developer mode.");
		System.out.println("  -h, --help                Display this help message and exit.");
	}

	public static void main(String[] args) {
		DefaultNodeConfiguration config;
		try {
			config = buildConfigFromArgs(args);
		} catch (IllegalArgumentException e) {
			System.err.println("Error: " + e.getMessage());
			if (e.getCause() != null && e.getCause().getMessage() != null)
				System.err.println("Details: " + e.getCause().getMessage());
			System.exit(-1);
			return;
		}

		Vertx vertx = Vertx.vertx(new VertxOptions()
				.setEventLoopPoolSize(4)
				.setWorkerPoolSize(4)
				.setPreferNativeTransport(true));
		config.setVertx(vertx);

		Object shutdown = new Object();
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			synchronized (shutdown) {
				try {
					if (node == null || !node.isRunning())
						return;

					System.out.println("Shutting down Boson DHT node...");
					node.stop().thenCompose(v -> VertxFuture.of(vertx.close())).get();
					System.out.println("Node stopped.");
				} catch (Exception e) {
					System.err.println("Error during shutdown: " + e.getMessage());
				} finally {
					shutdown.notifyAll();
				}
			}
		}));

		System.out.println("Native transport: " + vertx.isNativeTransportEnabled() + "\n");

		int rc = 0;
		Path dataDir = config.dataDir() != null ? config.dataDir() : Path.of(".");
		Path lockFile = dataDir.resolve("lock");
		// noinspection unused
		try (ApplicationLock lock = new ApplicationLock(lockFile)) {
			try {
				System.out.println("Starting Boson DHT node...");
				node = new KadNode(config);
				node.start().get();

				System.out.println("Node is running.");
				System.out.println("  ID: " + node.getId());
				System.out.println("  IPv4: " + (config.host4() != null ? config.host4() + ":" + config.port() : "N/A"));
				System.out.println("  IPv6: " + (config.host6() != null ? config.host6() + ":" + config.port() : "N/A"));
				System.out.println("  Data directory: " + dataDir.toAbsolutePath());

				synchronized(shutdown) {
					try {
						shutdown.wait();
					} catch (InterruptedException ignore) {
					}
				}
			} catch (Exception e) {
				System.err.println("Failed to start Boson DHT node. Error: " + e.getMessage());
				if (e.getCause() != null && e.getCause().getMessage() != null)
					System.err.println("Details: " + e.getCause().getMessage());
				rc = -1;
			}

		} catch (IOException | IllegalStateException e) {
			System.err.println("Error: Another instance is already running.");
			System.err.println("Lock file: " + lockFile.toAbsolutePath());
			rc = -1;
		} finally {
			System.exit(rc);
		}
	}
}