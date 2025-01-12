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

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import org.jline.console.SystemRegistry;
import org.jline.console.impl.Builtins;
import org.jline.console.impl.SystemRegistryImpl;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.MaskingCallback;
import org.jline.reader.Parser;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.widget.TailTipWidgets;

import io.bosonnetwork.Configuration;
import io.bosonnetwork.ConnectionStatusListener;
import io.bosonnetwork.DefaultConfiguration;
import io.bosonnetwork.Network;
import io.bosonnetwork.NodeStatusListener;
import io.bosonnetwork.kademlia.Node;
import io.bosonnetwork.kademlia.exceptions.KadException;
import io.bosonnetwork.utils.ApplicationLock;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.shell.jline3.PicocliCommands;
import picocli.shell.jline3.PicocliCommands.PicocliCommandsFactory;

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
			DisplayCacheCommnand.class,
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

	@Option(names = {"-b", "--bootstrap"}, description = "The bootstrap node.")
	private String bootstrap = null;

	@Option(names = {"-c", "--config"}, description = "The configuration file.")
	private String configFile = null;

	static private LineReader reader;
	static private Node bosonNode;

	private SystemRegistry systemRegistry;
	private Configuration config;

	private void initCommandLine() {
		Supplier<Path> workDir = () -> Paths.get(System.getProperty("user.home"));
		// set up JLine built-in commands
		Builtins builtins = new Builtins(workDir, null, null);
		Main commands = new Main();
		PicocliCommandsFactory factory = new PicocliCommandsFactory();
		CommandLine cmd = new CommandLine(commands, factory);
		PicocliCommands picocliCommands = new PicocliCommands(cmd);

		Parser parser = new DefaultParser();
		try (Terminal terminal = TerminalBuilder.builder().build()) {
			systemRegistry = new SystemRegistryImpl(parser, terminal, workDir, null);
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
			TailTipWidgets widgets = new TailTipWidgets(reader, systemRegistry::commandDescription, 5, TailTipWidgets.TipType.COMPLETER);
			widgets.enable();
			KeyMap<Binding> keyMap = reader.getKeyMaps().get("main");
			keyMap.bind(new Reference("tailtip-toggle"), KeyMap.alt("s"));
		} catch (Exception e) {
			e.printStackTrace(System.err);
			System.exit(-1);
		}
	}

	private void initConfig() throws IOException {
		DefaultConfiguration.Builder builder = new DefaultConfiguration.Builder();

		if (configFile != null) {
			try {
				builder.load(configFile);
			} catch (Exception e) {
				System.out.println("Can not load the config file: " + configFile + ", error: " + e.getMessage());
				e.printStackTrace(System.err);
				System.exit(-1);
			}
		}

		if (addr4 != null)
			builder.setAddress4(addr4);

		if (addr6 != null)
			builder.setAddress6(addr6);

		if (port != 0)
			builder.setPort(port);

		if (dataDir != null) {
			builder.setDataPath(dataDir);
		} else {
			if (!builder.hasDataPath())
				builder.setDataPath(DEFAULT_DATA_DIR);
		}

		config = builder.build();
	}

	private void initBosonNode() throws KadException {
		bosonNode = new Node(config);

		bosonNode.addStatusListener(new NodeStatusListener() {
			@Override
			public void started() {
				System.out.println("Boson node started");
			}

			@Override
			public void stopped() {
				System.out.println("Boson node stopped");
			}
		});

		bosonNode.addConnectionStatusListener(new ConnectionStatusListener() {
			@Override
			public void connected(Network network) {
				System.out.format("DHT/%s connected\n", network);
			}

			@Override
			public void profound(Network network) {
				System.out.format("DHT/%s profound connected\n", network);
			}
		});

		bosonNode.start();
	}

	static Node getBosonNode() {
		return bosonNode;
	}

	private void setLogOutput() {
		if (dataDir != null && !dataDir.isEmpty()) {
			Path dir = Path.of(dataDir).normalize();
			if (dir.startsWith("~"))
				dir = Path.of(System.getProperty("user.home")).resolve(dir.subpath(1, dir.getNameCount()));
			else
				dir = dir.toAbsolutePath();

			Path logFile = dir.resolve("boson.log").toAbsolutePath();
			System.setProperty("BOSON_LOG", logFile.toString());
		}
	}

	@Override
	public Integer call() throws Exception {
		setLogOutput();

		initCommandLine();
		initConfig();

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
					return 0;
				} catch (Exception e) {
					systemRegistry.trace(e);
				}
			}
		} catch (IOException | IllegalStateException e) {
			System.out.println("Another boson instance alreay running at " + config.dataPath());
			return -1;
		}
	}

	public static void main(String[] args) {
		int exitCode = new CommandLine(new Main()).execute(args);
		System.exit(exitCode);
	}
}
