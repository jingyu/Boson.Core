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

package io.bosonnetwork.launcher;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

import io.bosonnetwork.DefaultNodeConfiguration;
import io.bosonnetwork.Id;
import io.bosonnetwork.NodeConfiguration;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.access.AccessManager;
import io.bosonnetwork.kademlia.KadNode;
import io.bosonnetwork.service.BosonService;
import io.bosonnetwork.service.BosonServiceException;
import io.bosonnetwork.service.ClientAuthenticator;
import io.bosonnetwork.service.ClientAuthorizer;
import io.bosonnetwork.service.DefaultServiceContext;
import io.bosonnetwork.service.FederationAuthenticator;
import io.bosonnetwork.service.ServiceContext;
import io.bosonnetwork.utils.ApplicationLock;
import io.bosonnetwork.utils.Json;

/**
 * @hidden
 */
public class Main {
	private static NodeConfiguration config;
	private static final Object shutdown = new Object();

	private static Vertx vertx;
	private static KadNode node;
	private static AccessManager accessManager;
	private static final List<BosonService> services = new ArrayList<>();

	private static void initBosonNode() {
		try {
			node = new KadNode(config);

			// TODO: initialize the user defined access manager
			accessManager = AccessManager.getDefault();

			node.start().thenRun(() -> System.out.format("Boson node %s is running.\n", node.getId())).get();
		} catch (Exception e) {
			System.out.println("Start boson super node failed, error: " + e.getMessage());
			e.printStackTrace(System.err);
			System.exit(-1);
		}
	}

	private static int compareConfigFileName(Path p1, Path p2) {
		String n1 = p1.getFileName().toString();
		String n2 = p2.getFileName().toString();

		int order1 = Integer.parseInt(n1.substring(0, n1.indexOf("-")));
		int order2 = Integer.parseInt(n2.substring(0, n1.indexOf("-")));

		return Integer.compare(order1, order2);
	}

	private static class ServiceConfig {
		@JsonProperty("class")
		public String className;
		@JsonProperty("configuration")
		public Map<String, Object> configuration;
	}

	private static ServiceConfig loadConfig(Path configFile) {
		try {
			ObjectMapper mapper = configFile.toString().endsWith(".json") ? Json.objectMapper() : Json.yamlMapper();
			return mapper.readValue(configFile.toFile(), ServiceConfig.class);
		} catch (IOException e) {
			System.out.println("Can not load the config file: " + configFile + ", error: " + e.getMessage());
			e.printStackTrace(System.err);
			return null;
		}
	}

	private static void loadServices() throws IOException {
		if (config.dataDir() == null)
			return;

		Path servicesDir = config.dataDir().resolve("services");
		try (Stream<Path> stream = Files.list(servicesDir)) {
			stream.filter(Files::isRegularFile)
					.sorted(Main::compareConfigFileName)
					.map(Main::loadConfig)
					.filter(Objects::nonNull)
					.forEach(Main::loadService);
		}
	}

	private static void loadService(ServiceConfig serviceConfig) {
		try {
			Class<?> clazz = Class.forName(serviceConfig.className);
			Object o = clazz.getDeclaredConstructor().newInstance();
			if (!(o instanceof BosonService svc)) {
				System.out.println("Class isn't a boson service: " + serviceConfig.className);
				return;
			}

			Path dataPath = config.dataDir() == null ? null : config.dataDir().resolve(svc.getId()).toAbsolutePath();
			ServiceContext ctx = new DefaultServiceContext(vertx, node,
					ClientAuthenticator.allowAll(), ClientAuthorizer.noop(),
					FederationAuthenticator.allowAll(), null, serviceConfig.configuration, dataPath);
			svc.init(ctx);
			System.out.format("Service %s[%s] is loaded.\n", svc.getName(), serviceConfig.className);

			svc.start().get();
			System.out.format("Service %s[%s] is started.\n", svc.getName(), serviceConfig.className);

			services.add(svc);
		} catch (ClassNotFoundException | InstantiationException | IllegalAccessException | IllegalArgumentException |
				 InvocationTargetException | NoSuchMethodException | SecurityException e) {
			System.out.println("Can not load service: " + serviceConfig.className);
			e.printStackTrace(System.err);
		} catch (BosonServiceException | ExecutionException e) {
			System.out.println("Failed to start service: " + serviceConfig.className);
			e.printStackTrace(System.err);
		} catch (InterruptedException  e) {
			System.out.println("Interrupted during service starting: " + serviceConfig.className);
			e.printStackTrace(System.err);
		}
	}

	private static void unloadServices() {
		List<CompletableFuture<Void>> stopFutures = new ArrayList<>(services.size());

		for (BosonService svc : services) {
			CompletableFuture<Void> f = svc.stop().thenRun(() -> {
				System.out.format("Service %s is stopped.\n", svc.getName());
			}).exceptionally(e -> {
				System.out.println("Failed to stop service: " + svc.getName());
				e.printStackTrace(System.err);
				return null;
			});

			stopFutures.add(f);
		}

		CompletableFuture.allOf(stopFutures.toArray(new CompletableFuture[0]));
	}

	private static void parseArgs(String[] args) {
		DefaultNodeConfiguration.Builder builder = NodeConfiguration.builder();

		int i = 0;
		while (i < args.length) {
			if (!args[i].startsWith("-")) {
				System.out.format("Unknown arg:%d %s\n", i, args[i]);
				i++;
				continue;
			}

			if (args[i].equalsIgnoreCase("--config") || args[i].equalsIgnoreCase("-c")) {
				if (i + 1 >= args.length) {
					System.out.format("Missing the value for arg:%d %s\n", i, args[i]);
					System.exit(-1);
				}

				String configFile = args[++i];
				try {
					Map<String, Object> map = Json.yamlMapper().readValue(configFile, Json.mapType());
					builder.template(map);
				} catch (Exception e) {
					System.out.println("Can not load the config file: " + configFile + ", error: " + e.getMessage());
					e.printStackTrace(System.err);
					System.exit(-1);
				}
			} else if (args[i].equals("--address4") || args[i].equals("-4")) {
				if (i + 1 >= args.length) {
					System.out.format("Missing the value for arg:%d %s\n", i, args[i]);
					System.exit(-1);
				}

				builder.host4(args[++i]);
			} else if (args[i].equals("--address6") || args[i].equals("-6")) {
				if (i + 1 >= args.length) {
					System.out.format("Missing the value for arg:%d %s\n", i, args[i]);
					System.exit(-1);
				}

				builder.host6(args[++i]);
			} else if (args[i].equals("--port") || args[i].equals("-p")) {
				if (i + 1 >= args.length) {
					System.out.format("Missing the value for arg:%d %s\n", i, args[i]);
					System.exit(-1);
				}

				builder.port(Integer.parseInt(args[++i]));
			} else if (args[i].equals("--data-dir") || args[i].equals("-d")) {
				if (i + 1 >= args.length) {
					System.out.format("Missing the value for arg:%d %s\n", i, args[i]);
					System.exit(-1);
				}

				builder.dataDir(args[++i]);
			} else if (args[i].equals("--bootstrap") || args[i].equals("-b")) {
				if (i + 1 >= args.length) {
					System.out.format("Missing the value for arg:%d %s\n", i, args[i]);
					System.exit(-1);
				}

				String[] parts = args[++i].split(":");
				if (parts.length != 3) {
					System.out.format("Invalid bootstrap format: %s\n", args[i]);
					System.exit(-1);
				}

				try {
					Id id = Id.of(parts[0]);
					String addr = parts[1];
					int port = Integer.parseInt(parts[2]);
					NodeInfo ni = new NodeInfo(id, addr, port);
					builder.addBootstrap(ni);
				} catch (Exception e) {
					System.out.format("Invalid bootstrap format: %s\n", args[i]);
					System.exit(-1);
				}
			} else if (args[i].equals("--developerMode")) {
				builder.enableDeveloperMode();
			} else if (args[i].equals("--help") || args[i].equals("-h")) {
				printUsage();
				System.exit(0);
			}

			i++;
		}

		Vertx vertx = Vertx.vertx(new VertxOptions()
				.setEventLoopPoolSize(8)
				.setWorkerPoolSize(8)
				.setPreferNativeTransport(true));

		builder.vertx(vertx);

		config = builder.build();
	}

	private static void printUsage() {
		System.out.println("Usage: launcher [OPTIONS]");
		System.out.println("Available options:");
		System.out.println("  -c, --config <CONFIGFILE>    The configuration file.");
		System.out.println("  -4, --address4 <ADDR4>       IPv4 address to listen.");
		System.out.println("  -6, --address6 <ADDR6>       IPv6 address to listen.");
		System.out.println("  -p, --port <PORT>            The port to listen.");
		System.out.println("  -d, --data-dir <DIR>         The directory to store the node data.");
		System.out.println("  -b, --bootstrap <NODE>       The bootstrap node, format: ID:ADDRESS:PORT.");
		System.out.println("      --developerMode          Enable developer mode.");
		System.out.println("  -h, --help                   Show this help message and exit.");
	}

	public static void main(String[] args) {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			if (node != null) {
				unloadServices();
				node.stop().whenComplete((v, t) -> {
					synchronized(shutdown) {
						shutdown.notifyAll();
					}
				});
				node = null;
			}
		}));

		parseArgs(args);

		Path lockFile = config.dataDir() != null ?
				config.dataDir().resolve("lock") :
				Path.of("./lock");
		try (ApplicationLock lock = new ApplicationLock(lockFile)) {
			initBosonNode();
			loadServices();

			synchronized(shutdown) {
				try {
					shutdown.wait();
					System.out.println("Boson node stopped.");
				} catch (InterruptedException ignore) {
				}
			}
		} catch (IOException | IllegalStateException e) {
			System.out.println("Another boson instance already running at " +
					(config.dataDir() != null ? config.dataDir() : "."));
		}
	}
}