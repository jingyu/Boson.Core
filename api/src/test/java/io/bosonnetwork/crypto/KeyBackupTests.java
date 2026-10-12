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

package io.bosonnetwork.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;

public class KeyBackupTests {
	private final Signature.KeyPair keyPair = Signature.KeyPair.random();
	private final byte[] private64 = keyPair.privateKey().bytes();
	private final String base58 = KeyBackup.encode(private64);
	private final String userId = Id.of(keyPair.publicKey().bytes()).toString();

	@Test
	void keyFileReadsBackInThePortalsOwnFormat() {
		String text = KeyBackup.keyFileText(base58, userId);
		assertTrue(text.startsWith("Boson User Identity Key\n\nprivateKey: " + base58 + "\npublicKey:  " + userId + "\n"));
		for (String line : KeyBackup.gridLines(base58))
			assertTrue(text.contains(line + "\n"));
		assertArrayEquals(private64, KeyBackup.parse(text));
	}

	@Test
	void rowsHoldFiveGroupsAndAreNumberedByTheirFirstGroup() {
		List<KeyBackup.Row> rows = KeyBackup.rows(base58);
		StringBuilder joined = new StringBuilder();
		for (int i = 0; i < rows.size(); i++) {
			assertEquals(i * 5 + 1, rows.get(i).getNumber());
			if (i < rows.size() - 1)
				assertEquals(5, rows.get(i).getGroups().size());
			rows.get(i).getGroups().forEach(joined::append);
		}
		assertEquals(base58, joined.toString());

		List<String> lines = KeyBackup.gridLines(base58);
		assertTrue(lines.get(0).startsWith(" 1  " + rows.get(0).getGroups().get(0) + " "));
		assertTrue(lines.get(lines.size() - 1).startsWith(rows.get(rows.size() - 1).getNumber() + "  "));
	}

	@Test
	void keyTypedFromItsRowsReadsWithOrWithoutTheirNumbers() {
		List<String> lines = KeyBackup.gridLines(base58);
		assertArrayEquals(private64, KeyBackup.parse(String.join("\n", lines)));
		assertArrayEquals(private64, KeyBackup.parse(String.join(" ", lines)));
		assertArrayEquals(private64, KeyBackup.parse(String.join(" ", KeyBackup.groups(base58))));

		List<String> rowsWithoutNumbers = new ArrayList<>();
		for (KeyBackup.Row row : KeyBackup.rows(base58))
			rowsWithoutNumbers.add(String.join("", row.getGroups()));
		assertArrayEquals(private64, KeyBackup.parse(String.join("\r\n", rowsWithoutNumbers)));
	}

	@Test
	void rowNumberIsDroppedOnlyWhereTheRowsPutOne() {
		// "6" before the 6th group is the second row's number; "4" before the 4th is part of the key, which
		// then no longer reads.
		List<String> words = new ArrayList<>(KeyBackup.groups(base58));
		words.add(5, "6");
		assertArrayEquals(private64, KeyBackup.parse(String.join(" ", words)));
		words.add(3, "4");
		assertThrows(IllegalArgumentException.class, () -> KeyBackup.parse(String.join(" ", words)));
	}

	@Test
	void bareKeyReadsInBase58AndInHex() {
		assertArrayEquals(private64, KeyBackup.parse("  " + base58 + "\n"));
		StringBuilder hex = new StringBuilder("0x");
		for (byte b : private64)
			hex.append(String.format("%02x", b));
		assertArrayEquals(private64, KeyBackup.parse(hex.toString()));
	}

	@Test
	void keyWhoseHalvesDontBelongTogetherIsRefused() {
		byte[] other = Signature.KeyPair.random().publicKey().bytes();
		byte[] mixed = Arrays.copyOf(private64, 64);
		System.arraycopy(other, 0, mixed, 32, 32);
		assertThrows(IllegalArgumentException.class, () -> KeyBackup.parse(KeyBackup.encode(mixed)));
	}

	@Test
	void textThatIsNoKeyIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> KeyBackup.parse("not a key at all"));
		assertThrows(IllegalArgumentException.class, () -> KeyBackup.parse("   "));
	}

	@Test
	void groupsAreFourCharactersAndCoverTheWholeKey() {
		List<String> groups = KeyBackup.groups(base58);
		for (int i = 0; i < groups.size() - 1; i++)
			assertEquals(4, groups.get(i).length());
		assertEquals(base58, String.join("", groups));
	}

	@Test
	void challengeAsksForTwoDifferentFullGroupsInOrder() {
		for (int seed = 0; seed < 200; seed++) {
			KeyBackup.Challenge c = KeyBackup.challenge(base58, new java.util.Random(seed));
			assertTrue(c.getFirst() < c.getSecond());
			assertTrue(c.getSecond() < base58.length() / 4);
		}
	}

	@Test
	void rightGroupsVerifySpacesIgnoredCaseKept() {
		KeyBackup.Challenge c = new KeyBackup.Challenge(2, 9);
		List<String> groups = KeyBackup.groups(base58);
		String split = groups.get(9).substring(0, 2) + " " + groups.get(9).substring(2);
		assertTrue(KeyBackup.verify(base58, c, " " + groups.get(2) + " ", split));
		assertFalse(KeyBackup.verify(base58, c, groups.get(9), groups.get(2)));

		StringBuilder swapped = new StringBuilder();
		for (char ch : groups.get(2).toCharArray())
			swapped.append(Character.isUpperCase(ch) ? Character.toLowerCase(ch) : Character.toUpperCase(ch));
		if (!swapped.toString().equals(groups.get(2)))
			assertFalse(KeyBackup.verify(base58, c, swapped.toString(), groups.get(9)));
	}

	@Test
	void groupsAreNumberedFromOneAsTheRowsShowThem() {
		KeyBackup.Challenge c = new KeyBackup.Challenge(3, 15);
		assertEquals(4, c.number(c.getFirst()));
		assertEquals(16, c.number(c.getSecond()));
		assertEquals(KeyBackup.groups(base58).get(5), KeyBackup.rows(base58).get(1).getGroups().get(0));
		assertEquals(new KeyBackup.Challenge(3, 15), c);
	}
}
