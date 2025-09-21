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

package io.bosonnetwork.shell;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.jline.builtins.ConfigurationPath;
import org.jline.console.SystemRegistry;
import org.jline.console.impl.Builtins;
import org.jline.console.impl.SystemRegistryImpl;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.MaskingCallback;
import org.jline.reader.Parser;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.shell.jline3.PicocliCommands;
import picocli.shell.jline3.PicocliCommands.PicocliCommandsFactory;

import io.bosonnetwork.ConnectionStatusListener;
import io.bosonnetwork.DefaultNodeConfiguration;
import io.bosonnetwork.Id;
import io.bosonnetwork.Network;
import io.bosonnetwork.NodeConfiguration;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.kademlia.KadNode;
import io.bosonnetwork.utils.ApplicationLock;

/**
 * @hidden
 */
@Command(name = "shell", mixinStandardHelpOptions = true, version = "Boson shell 2.0",
		description = "Boson command line shell.",
		subcommands = {
			IdCommand.class,
			BootstrapCommand.class,
			FindValueCommand.class,
			StoreValueCommand.class,
			FindPeerCommand.class,
			AnnouncePeerCommand.class,
			FindNodeCommand.class,
			RoutingTableCommand.class,
			StorageCommand.class,
			StopCommand.class,
			DisplayCacheCommand.class,
			GenerateKeyPairCommand.class
		})
public class Main implements Callable<Integer> {
	private static final String DEFAULT_DATA_DIR = "~/.cache/boson";

	@Option(names = {"-4", "--address4"}, description = "IPv4 address to listen.")
	private String addr4 = null;

	@Option(names = {"-6", "--address6"}, description = "IPv6 address to listen.")
	private String addr6 = null;

	@Option(names = {"-p", "--port"}, description = "The port to listen.")
	private int port = 0;

	@Option(names = {"-d", "--dataDir"}, description = "The directory to store the node data, default: ~/.cache/boson.")
	private String dataDir = null;

	@Option(names = {"-s", "--storageURL"}, description = "The storage URL, default: jdbc:sqlite:<dataDir>/node.db.")
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

	private Terminal terminal;
	private LineReader reader;

	private SystemRegistry systemRegistry;
	private NodeConfiguration config;

	private Terminal buildTerminal(boolean dumb) throws IOException {
		TerminalBuilder builder = TerminalBuilder.builder();

		if (dumb) {
			builder.system(false)
					.system(false)  // Disable system terminal detection
					.dumb(true)     // Explicitly use dumb mode
					.streams(System.in, System.out)
					.size(new Size(80, 24));  // Provide default columns and rows (adjust as needed)
		} else {
			builder.system(true);
		}

		return builder.build();
	}

	private void closeTerminal() {
		if (terminal != null) {
			try {
				terminal.close();
			} catch (Exception ignored) {
			}
		}
	}

	private void initTerminal() {
		try {
			terminal = buildTerminal(false);
			Path workDir = Path.of(System.getProperty("user.home"));
			// set up JLine built-in commands
			Builtins builtins = new Builtins(() -> workDir, new ConfigurationPath(null, null), null);
			// builtins.rename(Builtins.Command.HELP, "builtin-help");

			Main commands = new Main();
			PicocliCommandsFactory factory = new PicocliCommandsFactory();
			CommandLine cmd = new CommandLine(commands, factory);
			PicocliCommands picocliCommands = new PicocliCommands(cmd);

			Parser parser = new DefaultParser();

			systemRegistry = new SystemRegistryImpl(parser, terminal, () -> workDir, null);
			systemRegistry.setCommandRegistries(builtins, picocliCommands);
			// systemRegistry.register("help", picocliCommands);

			reader = LineReaderBuilder.builder()
					.terminal(terminal)
					.completer(systemRegistry.completer())
					.parser(parser)
					.variable(LineReader.LIST_MAX, 50)   // max tab completion candidates
					.build();
			builtins.setLineReader(reader);

			factory.setTerminal(terminal);
			// TailTipWidgets widgets = new TailTipWidgets(reader, systemRegistry::commandDescription, 5,
			//		TailTipWidgets.TipType.COMPLETER);
			// widgets.enable();

			// bind alt-s to toggle tailtip
			// KeyMap<Binding> keyMap = reader.getKeyMaps().get("main");
			// keyMap.bind(new Reference("tailtip-toggle"), KeyMap.alt("s"));
		} catch (Exception e) {
			closeTerminal();
			e.printStackTrace(System.err);
			System.exit(-1);
		}
	}

	private NodeInfo parseBootstrap(String bootstrap) {
		String[] parts = bootstrap.split(":");
		if (parts.length != 3) {
			System.out.println("Invalid bootstrap format: " + bootstrap);
			return null;
		}

		Id id = Id.of(parts[0]);
		String addr = parts[1];
		int port = Integer.parseInt(parts[2]);

		return new NodeInfo(id, addr, port);
	}

	private void parseArgs() throws IOException {
		if (dataDir != null && configFile == null)
			configFile = dataDir + File.separator + "config.yaml";

		DefaultNodeConfiguration.Builder builder = NodeConfiguration.builder();

		if (configFile != null && (!saveConfig || Files.exists(Path.of(configFile)))) {
			try {
				builder.load(configFile);
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

		if (dataDir != null) {
			builder.dataPath(dataDir);
		} else {
			if (!builder.hasDataPath())
				builder.dataPath(DEFAULT_DATA_DIR);
		}

		if (storageURL != null) {
			builder.storageURL(storageURL);
		} else {
			if (builder.hasDataPath())
				builder.storageURL("jdbc:sqlite:" + builder.dataPath().resolve("node.db"));
		}

		if (!builder.hasPrivateKey())
			builder.generatePrivateKey();

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
			builder.enableDeveloperMode();

		if (saveConfig) {
			if (configFile == null && dataDir == null) {
				System.out.println("No config file and no data directory specified, can not save the configuration.");
				System.exit(-1);
			}

			try {
				Path targetFile = configFile != null ? Path.of(configFile) : Path.of(dataDir).resolve("config.yaml");
				builder.save(targetFile);
			} catch (Exception e) {
				System.out.println("Can not save the config file: " + configFile + ", error: " + e.getMessage());
				e.printStackTrace(System.err);
				System.exit(-1);
			}
		}

		config = builder.build();
	}

	private void initBosonNode() throws Exception {
		bosonNode = new KadNode(config);

		bosonNode.addConnectionStatusListener(new ConnectionStatusListener() {
			@Override
			public void connecting(Network network) {
				System.out.format("DHT/%s is connecting\n", network);
			}

			@Override
			public void connected(Network network) {
				System.out.format("DHT/%s connected\n", network);
			}

			@Override
			public void disconnected(Network network) {
				System.out.format("DHT/%s disconnected\n", network);
			}
		});

		bosonNode.run().thenRun(() -> System.out.println("Boson node started.")).get();
	}

	static KadNode getBosonNode() {
		return bosonNode;
	}

	private void setLogOutput() {
		Path logDir = config.dataPath() != null ? config.dataPath() : Path.of("").toAbsolutePath();
		// with trailing slash
		System.setProperty("BOSON_LOG_DIR", logDir.toString() + File.separator);
	}

	@Override
	public Integer call() throws Exception {
		parseArgs();
		setLogOutput();

		initTerminal();

		Path lockFile = config.dataPath().resolve("lock");

		try (ApplicationLock lock = new ApplicationLock(lockFile)) {
			initBosonNode();

			System.out.println("Boson Id: " + bosonNode.getId());

			String prompt = "Boson $ ";
			String rightPrompt = null;

			String line;
			while (true) {
				try {
					systemRegistry.cleanUp();
					line = reader.readLine(prompt, rightPrompt, (MaskingCallback)null, null);
					systemRegistry.execute(line);
				} catch (UserInterruptException e) {
					// Ignore
				} catch (EndOfFileException e) {
					closeTerminal();
					return 0;
				} catch (Exception e) {
					systemRegistry.trace(e);
				}
			}
		} catch (IOException | IllegalStateException e) {
			System.out.println("Another boson instance already running at " + config.dataPath());
			closeTerminal();
			return -1;
		}
	}

	public static void main(String[] args) {
		int exitCode = new CommandLine(new Main()).execute(args);
		System.exit(exitCode);
	}
}