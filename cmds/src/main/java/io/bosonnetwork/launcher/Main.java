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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import io.bosonnetwork.Configuration;
import io.bosonnetwork.DefaultConfiguration;
import io.bosonnetwork.NodeStatusListener;
import io.bosonnetwork.access.impl.AccessManager;
import io.bosonnetwork.kademlia.Node;
import io.bosonnetwork.service.BosonService;
import io.bosonnetwork.service.BosonServiceException;
import io.bosonnetwork.service.DefaultServiceContext;
import io.bosonnetwork.service.ServiceContext;
import io.bosonnetwork.utils.ApplicationLock;

/**
 * @hidden
 */
public class Main {
	private static Configuration config;
	private static Object shutdown = new Object();

	private static Node node;
	private static AccessManager accessManager;
	private static List<BosonService> services = new ArrayList<>();

	private static void initBosonNode() {
		try {
			shutdown = new Object();
			node = new Node(config);

			// TODO: initialize the user defined access manager

			accessManager = config.accessControlsPath() != null ?
					new io.bosonnetwork.access.impl.AccessManager(config.accessControlsPath()) :
					new io.bosonnetwork.access.impl.AccessManager();

			accessManager.init(node);

			node.addStatusListener(new NodeStatusListener() {
				@Override
				public void stopped() {
					synchronized(shutdown) {
						shutdown.notifyAll();
					}
				}
			});
			node.start();

			System.out.format("Boson node %s is running.\n", node.getId());
		} catch (Exception e) {
			System.out.println("Start boson super node failed, error: " + e.getMessage());
			e.printStackTrace(System.err);
			System.exit(-1);
		}
	}

	private static void loadServices() {
		if (config.services().isEmpty())
			return;

		config.services().forEach(Main::loadService);
	}

	private static void loadService(String className, Map<String, Object> configuration) {
		try {
			Class<?> clazz = Class.forName(className);
			Object o = clazz.getDeclaredConstructor().newInstance();
			if (!(o instanceof BosonService)) {
				System.out.println("Class isn't a boson service: " + className);
				return;
			}

			BosonService svc = (BosonService)o;
			Path dataPath = config.dataPath() == null ? null :
				config.dataPath().resolve(svc.getId()).toAbsolutePath();
			ServiceContext ctx = new DefaultServiceContext(node, accessManager, configuration, dataPath);
			svc.init(ctx);
			System.out.format("Service %s[%s] is loaded.\n", svc.getName(), className);

			svc.start().get();
			System.out.format("Service %s[%s] is started.\n", svc.getName(), className);

			services.add(svc);

		} catch (ClassNotFoundException | InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e) {
			System.out.println("Can not load service: " + className);
			e.printStackTrace(System.err);
		} catch (BosonServiceException e) {
			System.out.println("Failed to start service: " + className);
			e.printStackTrace(System.err);
		} catch (InterruptedException | ExecutionException e) {
			System.out.println("Failed to start service: " + className);
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
		DefaultConfiguration.Builder builder = new DefaultConfiguration.Builder();

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
					builder.load(configFile);
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

				builder.setAddress4(args[++i]);
			} else if (args[i].equals("--address6") || args[i].equals("-6")) {
				if (i + 1 >= args.length) {
					System.out.format("Missing the value for arg:%d %s\n", i, args[i]);
					System.exit(-1);
				}

				builder.setAddress6(args[++i]);
			} else if (args[i].equals("--port") || args[i].equals("-p")) {
				if (i + 1 >= args.length) {
					System.out.format("Missing the value for arg:%d %s\n", i, args[i]);
					System.exit(-1);
				}

				builder.setPort(Integer.valueOf(args[++i]));
			} else if (args[i].equals("--data-dir") || args[i].equals("-d")) {
				if (i + 1 >= args.length) {
					System.out.format("Missing the value for arg:%d %s\n", i, args[i]);
					System.exit(-1);
				}

				builder.setDataPath(args[++i]);
			} else if (args[i].equals("--help") || args[i].equals("-h")) {
				System.out.println("Usage: launcher [OPTIONS]");
				System.out.println("Available options:");
				System.out.println("  -c, --config <configFile>    The configuration file.");
				System.out.println("  -4, --address4 <addr4>       IPv4 address to listen.");
				System.out.println("  -6, --address6 <addr6>       IPv6 address to listen.");
				System.out.println("  -p, --port <port>            The port to listen.");
				System.out.println("  -d, --data-dir <DIR>         The directory to store the node data.");
				System.out.println("  -h, --help                   Show this help message and exit.");

				System.exit(0);
			}

			i++;
		}

		config = builder.build();
	}

	public static void main(String[] args) {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			if (node != null) {
				unloadServices();
				node.stop();
				node = null;
			}
		}));

		parseArgs(args);

		Path lockFile = config.dataPath() != null ?
				config.dataPath().resolve("lock") :
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
			System.out.println("Another boson instance alreay running at " +
					(config.dataPath() != null ? config.dataPath() : "."));
		}
	}
}
