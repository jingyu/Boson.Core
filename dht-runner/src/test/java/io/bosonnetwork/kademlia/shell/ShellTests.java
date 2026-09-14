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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

class ShellTests {
	@Test
	void testUnquotedWhitespaceSeparatesArguments() {
		assertEquals(List.of("findvalue", "-m", "optimistic", "abc"),
				Shell.tokenize("  findvalue\t-m  optimistic abc  "));
	}

	@Test
	void testABlankLineHasNoArguments() {
		assertEquals(List.of(), Shell.tokenize(""));
		assertEquals(List.of(), Shell.tokenize(" \t "));
	}

	@Test
	void testQuotesKeepWhitespaceInOneArgument() {
		assertEquals(List.of("storevalue", "hello from the shell"), Shell.tokenize("storevalue \"hello from the shell\""));
		assertEquals(List.of("storevalue", "hello from the shell"), Shell.tokenize("storevalue 'hello from the shell'"));
	}

	@Test
	void testSingleQuotesCarryJsonUnchanged() {
		assertEquals(List.of("announcepeer", "-e", "{\"a\": \"b\\\\c\"}", "tcp://x"),
				Shell.tokenize("announcepeer -e '{\"a\": \"b\\\\c\"}' tcp://x"));
	}

	@Test
	void testDoubleQuotesUnescapeOnlyQuoteAndBackslash() {
		assertEquals(List.of("say \"hi\" \\ \\n"), Shell.tokenize("\"say \\\"hi\\\" \\\\ \\n\""));
	}

	@Test
	void testABackslashOutsideQuotesIsOrdinary() {
		assertEquals(List.of("displaycache", "C:\\Users\\boson\\data"), Shell.tokenize("displaycache C:\\Users\\boson\\data"));
	}

	@Test
	void testAdjacentPartsFormOneArgumentAndEmptyQuotesAreAnArgument() {
		assertEquals(List.of("abc d", ""), Shell.tokenize("a'bc'\" d\" \"\""));
	}

	@Test
	void testAnUnterminatedQuoteIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> Shell.tokenize("storevalue \"hello"));
		assertThrows(IllegalArgumentException.class, () -> Shell.tokenize("storevalue 'hello"));
	}

	private static String session(String input) throws Exception {
		StringWriter output = new StringWriter();
		int rc = new Shell(new StringReader(input), new PrintWriter(output, true)).run();
		assertEquals(0, rc);
		return output.toString();
	}

	@Test
	void testTheShellEndsAtTheEndOfInput() throws Exception {
		assertEquals(Shell.PROMPT + Shell.PROMPT + System.lineSeparator(), session("\n"));
	}

	@Test
	void testExitAndQuitEndTheShellWithoutReadingFurther() throws Exception {
		assertEquals(Shell.PROMPT, session("exit\nbogus\n"));
		assertEquals(Shell.PROMPT, session("  quit  \nbogus\n"));
	}

	@Test
	void testAnUnknownCommandIsReportedAndTheShellContinues() throws Exception {
		String output = session("bogus arg\nexit\n");
		assertTrue(output.contains("Unknown command 'bogus'"), output);
		assertTrue(output.endsWith(Shell.PROMPT), output);
	}

	@Test
	void testAnUnterminatedQuoteIsReportedAndTheShellContinues() throws Exception {
		String output = session("storevalue \"oops\nexit\n");
		assertTrue(output.contains("Error: Unterminated double quote"), output);
		assertFalse(output.contains("Unknown command"), output);
	}

	@Test
	void testHelpListsTheCommandsAndHowToLeave() throws Exception {
		String output = session("help\nexit\n");
		for (String command : List.of("id", "bootstrap", "findvalue", "storevalue", "findpeer", "announcepeer",
				"findnode", "routingtable", "storage", "stop", "displaycache", "keygen"))
			assertTrue(output.contains(command), command + " missing from:\n" + output);
		assertTrue(output.contains("Type 'exit' or 'quit'"), output);
		assertTrue(output.contains("Usage: COMMAND"), output);
	}

	@Test
	void testACommandsOwnHelpIsReachable() throws Exception {
		String output = session("findvalue --help\nexit\n");
		assertTrue(output.contains("Usage: findvalue"), output);

		output = session("storage listvalue --help\nexit\n");
		assertTrue(output.contains("Usage: storage listvalue"), output);
	}

	@Test
	void testAParameterErrorIsReportedAndTheShellContinues() throws Exception {
		String output = session("findvalue\nexit\n");
		assertTrue(output.contains("Missing required parameter"), output);
		assertTrue(output.endsWith(Shell.PROMPT), output);
	}

	@Test
	void testAFailureIsDescribedByItsCause() {
		assertEquals("IllegalStateException: Node not running",
				Shell.describe(new ExecutionException(new CompletionException(new IllegalStateException("Node not running")))));
		assertEquals("NullPointerException", Shell.describe(new NullPointerException()));
	}
}
