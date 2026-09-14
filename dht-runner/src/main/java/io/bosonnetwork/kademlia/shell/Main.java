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

package io.bosonnetwork.kademlia.shell;

import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import io.bosonnetwork.ConnectionStatusListener;
import io.bosonnetwork.Id;
import io.bosonnetwork.NodeConfiguration;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.json.Json;
import io.bosonnetwork.kademlia.KadNode;
import io.bosonnetwork.utils.ApplicationLock;

/**
 * @hidden
 */
@Command(name = "shell", mixinStandardHelpOptions = true, version = "Boson shell 2.0",
		description = "Boson command line shell.")
public class Main implements Callable<Integer> {
	static {
		// Before anything creates a logger: logback reads its configuration once, on first use.
		ShellLogging.selectConfiguration();
	}

	private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

	@Option(names = {"-4", "--address4"}, description = "IPv4 address to listen.")
	private String addr4 = null;

	@Option(names = {"-6", "--address6"}, description = "IPv6 address to listen.")
	private String addr6 = null;

	@Option(names = {"-p", "--port"}, description = "The port to listen.")
	private int port = 0;

	@Option(names = {"-d", "--dataDir"}, description = "The directory to store the node data, default: the per-user Boson data directory.")
	private String dataDir = null;

	@Option(names = {"-s", "--storageURL"}, description = "The storage URL, default: jdbc:sqlite:node.db.")
	private String storageURL = null;

	@Option(names = {"-b", "--bootstrap"}, description = "The bootstrap node, format: ID:ADDRESS:PORT")
	private String bootstrap = null;

	@Option(names = {"--developerMode"}, description = "Enable developer mode")
	private boolean developerMode = false;

	@Option(names = {"-c", "--config"}, description = "The configuration file.")
	private String configFile = null;

	@Option(names = {"--saveConfig"}, description = "Save the configuration file.")
	private boolean saveConfig = false;

	private static KadNode bosonNode;

	private NodeConfiguration config;

	private final AtomicBoolean shutdown = new AtomicBoolean();

	// bootstrap formats:
	// - ID:ADDRESS4:PORT4
	// - ID:[ADDRESS6]:PORT4
	// - ID:ADDRESS4:PORT4:[ADDRESS6]:PORT6

	// Matches the ID at the beginning
	private static final Pattern ID_PATTERN = Pattern.compile("^([^:]+)");
	// Matches anything within brackets or any non-bracketed segment before a colon
	private static final Pattern ADDR_PORT_PATTERN = Pattern.compile("(?:\\[([^\\]]+)\\]|([^:\\[]+)):?([0-9]+)?");

	private NodeInfo parseBootstrap(String bootstrap) {
		// 1. Extract ID
		String id = null;
		Matcher idMatcher = ID_PATTERN.matcher(bootstrap);
		if (idMatcher.find())
			id = idMatcher.group(1);

		if (id == null || id.isEmpty()) {
			System.out.println("Invalid bootstrap format: " + bootstrap);
			return null;
		}

		String addr1 = null, port1 = null;
		// 2. Extract Addresses and Ports
		Matcher apMatcher = ADDR_PORT_PATTERN.matcher(bootstrap.substring(id.length() + 1));
		if (apMatcher.find()) {
			// Group 1 is matched for bracketed items (e.g. IPv6), Group 2 for unbracketed (e.g. IPv4)
			addr1 = apMatcher.group(1) != null ? apMatcher.group(1) : apMatcher.group(2);
			port1 = apMatcher.group(3);

			if (addr1 == null || addr1.isEmpty() || port1 == null || port1.isEmpty()) {
				System.out.println("Invalid bootstrap format: " + bootstrap);
				return null;
			}
		}

		String addr2 = null, port2 = null;
		if (apMatcher.find()) {
			// Group 1 is matched for bracketed items (e.g. IPv6), Group 2 for unbracketed (e.g. IPv4)
			addr2 = apMatcher.group(1) != null ? apMatcher.group(1) : apMatcher.group(2);
			port2 = apMatcher.group(3);

			if (addr2 == null || addr2.isEmpty() || port2 == null || port2.isEmpty()) {
				System.out.println("Invalid bootstrap format: " + bootstrap);
				return null;
			}
		}

		return NodeInfo.of(Id.of(id), addr1, port1 != null ? Integer.parseInt(port1) : -1,
				addr2, port2 != null ? Integer.parseInt(port2) : -1);
	}

	private void parseArgs() throws IOException {
		if (dataDir != null && configFile == null)
			configFile = dataDir + File.separator + "config.yaml";

		NodeConfiguration.Builder builder = NodeConfiguration.builder();

		if (configFile != null && (!saveConfig || Files.exists(Path.of(configFile)))) {
			try {
				Map<String, Object> map = Json.yamlMapper().readValue(new File(configFile), Json.mapType());
				builder.fromMap(map);
			} catch (Exception e) {
				System.out.println("Can not load the config file: " + configFile + ", error: " + e.getMessage());
				e.printStackTrace(System.err);
				System.exit(-1);
			}
		}

		if (addr4 != null)
			builder.host4(addr4);

		if (addr6 != null)
			builder.host6(addr6);

		if (port != 0)
			builder.port(port);

		// Only when the user actually named one: setting it unconditionally would overwrite a dataDir
		// the configuration file names, and NodeConfiguration supplies its own default otherwise.
		if (dataDir != null)
			builder.dataDir(dataDir);

		if (storageURL != null)
			builder.databaseUri(storageURL);

		if (!builder.hasKeyPair())
			builder.generateKeyPair();

		if (bootstrap != null) {
			try {
				NodeInfo ni = parseBootstrap(bootstrap);
				builder.addBootstrap(ni);
			} catch (Exception e) {
				System.out.println("Invalid bootstrap format: " + bootstrap);
				System.exit(-1);
			}
		}

		if (developerMode)
			builder.developerMode(true);

		// The shell owns its Vert.x instance. A configuration must never conjure one of its own: it
		// would hand back an event loop group that nothing is left holding in order to close it.
		builder.vertx(Vertx.vertx(new VertxOptions()
				.setEventLoopPoolSize(4)
				.setWorkerPoolSize(4)
				.setPreferNativeTransport(true)));

		config = builder.build();

		if (saveConfig) {
			if (configFile == null && dataDir == null) {
				System.out.println("No config file and no data directory specified, can not save the configuration.");
				System.exit(-1);
			}

			try {
				Path targetFile = configFile != null ? Path.of(configFile) : Path.of(dataDir).resolve("config.yaml");
				Map<String, Object> map = config.toMap();
				Json.yamlMapper().writeValue(targetFile.toFile(), map);
			} catch (Exception e) {
				System.out.println("Can not save the config file: " + configFile + ", error: " + e.getMessage());
				e.printStackTrace(System.err);
				System.exit(-1);
			}
		}
	}

	private void initBosonNode() throws Exception {
		bosonNode = new KadNode(config);

		bosonNode.addConnectionStatusListener(new ConnectionStatusListener() {
			@Override
			public void connecting() {
				System.out.println("Boson node is connecting");
			}

			@Override
			public void connected() {
				System.out.println("Boson node connected");
			}

			@Override
			public void disconnected() {
				System.out.println("Boson node disconnected");
			}
		});

		bosonNode.start().thenRun(() -> System.out.println("Boson node started.")).get();
	}

	static KadNode getBosonNode() {
		return bosonNode;
	}

	@Override
	public Integer call() throws Exception {
		parseArgs();

		Path lockFile = config.dataDir().resolve("lock");
		ApplicationLock lock;
		try {
			lock = new ApplicationLock(lockFile);
		} catch (IOException | IllegalStateException e) {
			System.out.println("Another boson instance already running at " + config.dataDir());
			return -1;
		}

		try (lock) {
			initBosonNode();

			System.out.println("Boson Id: " + bosonNode.getId());

			Console console = System.console();
			Charset charset = console != null ? console.charset() : Charset.defaultCharset();
			return new Shell(new InputStreamReader(System.in, charset), new PrintWriter(System.out, true)).run();
		}
	}

	public static void main(String[] args) {
		Main app = new Main();
		// Ctrl-C ends the process, not the line being typed - there is no line editor to catch it - so
		// the node is stopped from a hook as well as on the normal way out.
		Runtime.getRuntime().addShutdownHook(new Thread(app::shutdown, "shell-shutdown"));
		int exitCode = new CommandLine(app).execute(args);
		app.shutdown();
		System.exit(exitCode);
	}

	private void shutdown() {
		if (!shutdown.compareAndSet(false, true))
			return;

		try {
			if (bosonNode != null && bosonNode.isRunning())
				bosonNode.stop().get(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (Exception ignored) {
		}

		if (config != null) {
			try {
				config.vertx().close().toCompletionStage().toCompletableFuture()
						.get(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			} catch (Exception ignored) {
			}
		}
	}
}
