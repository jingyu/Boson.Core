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

package io.bosonnetwork.kademlia.shell;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;

/**
 * The shell's read-execute loop, and the root of the commands typed at its prompt.
 * <p>
 * Input is read a line at a time with no line editor: the terminal stays in its ordinary line mode,
 * so output another thread writes while a command is being typed can land in the middle of it, but
 * cannot leave the terminal in a state it does not recover from.
 * </p>
 *
 * @hidden
 */
@Command(name = "", synopsisSubcommandLabel = "COMMAND",
		description = "Commands of the Boson DHT shell.",
		footer = {"", "Type 'exit' or 'quit', or press Ctrl-D, to leave the shell."},
		subcommands = {
			HelpCommand.class,
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
public class Shell {
	static final String PROMPT = "Boson $ ";

	private final BufferedReader in;
	private final PrintWriter out;
	private final CommandLine commandLine;

	Shell(Reader in, PrintWriter out) {
		this.in = in instanceof BufferedReader br ? br : new BufferedReader(in);
		this.out = out;
		this.commandLine = new CommandLine(this)
				.setOut(out)
				.setErr(out)
				.setExecutionExceptionHandler((e, cmd, parseResult) -> {
					cmd.getErr().println("Error: " + describe(e));
					return 1;
				});

		// Usage lines are "Usage: " followed by the command's qualified name, which starts with its
		// parent's. The root of these commands has no name, which would leave two spaces there.
		dropUsageHeadingSpace(commandLine);
	}

	private static void dropUsageHeadingSpace(CommandLine commandLine) {
		commandLine.getCommandSpec().usageMessage().synopsisHeading("Usage:");
		commandLine.getSubcommands().values().forEach(Shell::dropUsageHeadingSpace);
	}

	/**
	 * Reads and executes commands until {@code exit}, {@code quit} or the end of the input.
	 *
	 * @return the exit code of the shell.
	 * @throws IOException if reading the input fails.
	 */
	int run() throws IOException {
		while (true) {
			out.print(PROMPT);
			out.flush();

			String line = in.readLine();
			if (line == null) {
				// Ctrl-D, or the end of piped input: end the line the prompt left open.
				out.println();
				return 0;
			}

			if (!execute(line))
				return 0;
		}
	}

	/**
	 * Executes one line of input.
	 *
	 * @param line the line as typed.
	 * @return {@code false} if the line asks to leave the shell, {@code true} otherwise.
	 */
	boolean execute(String line) {
		List<String> args;
		try {
			args = tokenize(line);
		} catch (IllegalArgumentException e) {
			out.println("Error: " + e.getMessage());
			return true;
		}

		if (args.isEmpty())
			return true;

		String name = args.get(0);
		if (name.equals("exit") || name.equals("quit"))
			return false;

		if (!commandLine.getSubcommands().containsKey(name)) {
			out.println("Unknown command '" + name + "'. Type 'help' for the list of commands.");
			return true;
		}

		commandLine.execute(args.toArray(new String[0]));
		return true;
	}

	/**
	 * Splits a line into arguments the way a shell would, for the parts a command line here needs.
	 * <ul>
	 * <li>Unquoted whitespace separates arguments.</li>
	 * <li>Single quotes take everything up to the closing quote literally.</li>
	 * <li>Double quotes do too, except that {@code \"} and {@code \\} stand for {@code "} and
	 * {@code \}.</li>
	 * <li>A backslash anywhere else is an ordinary character, so a Windows path needs no escaping.</li>
	 * <li>Quoted and unquoted parts with no whitespace between them form one argument, and {@code ""}
	 * is an empty one.</li>
	 * </ul>
	 *
	 * @param line the line to split.
	 * @return the arguments, empty for a blank line.
	 * @throws IllegalArgumentException if a quote is not closed.
	 */
	static List<String> tokenize(String line) {
		List<String> args = new ArrayList<>();
		StringBuilder arg = new StringBuilder();
		boolean inArg = false;
		char quote = 0;

		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);

			if (quote == '\'') {
				if (c == '\'')
					quote = 0;
				else
					arg.append(c);
			} else if (quote == '"') {
				if (c == '"') {
					quote = 0;
				} else if (c == '\\' && i + 1 < line.length() &&
						(line.charAt(i + 1) == '"' || line.charAt(i + 1) == '\\')) {
					arg.append(line.charAt(++i));
				} else {
					arg.append(c);
				}
			} else if (c == '\'' || c == '"') {
				quote = c;
				inArg = true;
			} else if (Character.isWhitespace(c)) {
				if (inArg) {
					args.add(arg.toString());
					arg.setLength(0);
					inArg = false;
				}
			} else {
				arg.append(c);
				inArg = true;
			}
		}

		if (quote != 0)
			throw new IllegalArgumentException("Unterminated " + (quote == '"' ? "double" : "single") + " quote");

		if (inArg)
			args.add(arg.toString());

		return args;
	}

	/**
	 * Describes a failure in one line. Commands wait on futures, so the interesting exception is
	 * usually the cause of the one that reached the shell.
	 */
	static String describe(Throwable e) {
		while ((e instanceof ExecutionException || e instanceof CompletionException) && e.getCause() != null)
			e = e.getCause();

		String name = e.getClass().getSimpleName();
		return e.getMessage() != null ? name + ": " + e.getMessage() : name;
	}
}
