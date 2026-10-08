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

package io.bosonnetwork.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

public class BosonStringTests {
	@Test
	void testFormatsTheCanonicalForm() {
		assertEquals("boson:signin:1:abc", BosonString.format("signin", 1, "abc"));
		assertEquals("boson:supernode:1:id:https://n.example:8443/x", BosonString.format("supernode", 1, "id", "https://n.example:8443/x"));
		assertEquals("boson:prf:1:passkey-device", BosonString.format("prf", 1, "passkey-device"));
		assertArrayEquals("boson:kdf:1:ed25519:device-key".getBytes(StandardCharsets.US_ASCII),
				BosonString.of("kdf", 1, "ed25519", "device-key").bytes());
	}

	@Test
	void testRefusesWhatBreaksTheGrammar() {
		assertThrows(IllegalArgumentException.class, () -> BosonString.of("Pair", 1, "a"));
		assertThrows(IllegalArgumentException.class, () -> BosonString.of("1pair", 1, "a"));
		assertThrows(IllegalArgumentException.class, () -> BosonString.of("pair", 0, "a"));
		assertThrows(IllegalArgumentException.class, () -> BosonString.of("pair", 1, ""));
		assertThrows(IllegalArgumentException.class, () -> BosonString.of("pair", 1, "a:b", "c"));
		assertThrows(IllegalArgumentException.class, () -> BosonString.of("pair", 1, "a b"));
		assertThrows(IllegalArgumentException.class, () -> BosonString.of("pair", 1, "a?b"));
		assertThrows(IllegalArgumentException.class, () -> BosonString.of("pair", 1, "a#b"));
		assertThrows(IllegalArgumentException.class, () -> BosonString.of("pair", 1, "café"));
	}

	@Test
	void testParsesWithTheLastFieldTakingTheRest() {
		BosonString s = BosonString.parse("  boson:supernode:1:9KHW:https://n.example:8443/a  ", "supernode", 1, 2).orElseThrow();
		assertEquals("supernode", s.namespace());
		assertEquals(1, s.version());
		assertEquals(List.of("9KHW", "https://n.example:8443/a"), s.fields());
		assertEquals("boson:supernode:1:9KHW:https://n.example:8443/a", s.toString());
	}

	@Test
	void testParsesOnlyTheKindAskedFor() {
		assertTrue(BosonString.parse("boson:signin:1:abc", "signin", 1, 1).isPresent());
		assertFalse(BosonString.parse("boson:signin:2:abc", "signin", 1, 1).isPresent());
		assertFalse(BosonString.parse("boson:signin:01:abc", "signin", 1, 1).isPresent());
		assertFalse(BosonString.parse("boson:pair:1:abc", "signin", 1, 1).isPresent());
		assertFalse(BosonString.parse("BOSON:signin:1:abc", "signin", 1, 1).isPresent());
		assertFalse(BosonString.parse("boson:signin:1:", "signin", 1, 1).isPresent());
		// Too few fields; and an empty field where one is required.
		assertFalse(BosonString.parse("boson:pair:1:abc", "pair", 1, 2).isPresent());
		assertFalse(BosonString.parse("boson:pair:1::key", "pair", 1, 2).isPresent());
		assertFalse(BosonString.parse("boson:signin:1:abc?x=1", "signin", 1, 1).isPresent());
		assertFalse(BosonString.parse("bosonsignin:1:abc", "signin", 1, 1).isPresent());
	}

	@Test
	void testEquality() {
		assertEquals(BosonString.of("pair", 1, "a", "b"), BosonString.parse("boson:pair:1:a:b", "pair", 1, 2).orElseThrow());
	}
}
