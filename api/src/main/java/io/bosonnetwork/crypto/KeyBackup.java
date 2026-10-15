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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import io.bosonnetwork.utils.Base58;

/**
 * The backup of a user key, in the forms every Boson app and the user portal share: the 64-byte private
 * key (seed || public key) as Base58, alone or in the key file. There is no word phrase: the key itself
 * is the backup.
 * <p>
 * The key is shown, written down and checked in groups of {@link #GROUP_SIZE} characters, {@link #ROW_SIZE}
 * groups a row, each row led by the number of its first group (1, 6, 11, ...). A backup is checked by
 * asking for two of its groups by number, not by retyping all of it.
 * <p>
 * A private key is never a Boson string (see {@code core/api/docs/formats.md}): it is shown and scanned
 * as bare Base58.
 */
public final class KeyBackup {
	/** The key file's name, as the apps and the user portal name it. */
	public static final String FILE_NAME = "boson-user-key.txt";

	/** The key's characters are shown, read and checked in groups of this size. */
	public static final int GROUP_SIZE = 4;

	/** How many groups a row of the key shows. */
	public static final int ROW_SIZE = 5;

	private static final String PRIVATE_KEY_LINE = "privateKey:";

	private static final java.util.Random RANDOM = new java.util.Random();

	private KeyBackup() {
	}

	/** A row of the key: the number of its first group (1-based), and its groups. */
	public static final class Row {
		private final int number;
		private final List<String> groups;

		Row(int number, List<String> groups) {
			this.number = number;
			this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
		}

		/**
		 * The number of the row's first group, 1-based.
		 *
		 * @return the number of the row's first group, 1-based
		 */
		public int getNumber() {
			return number;
		}

		/**
		 * Returns the groups of this row.
		 *
		 * @return the list of groups
		 */
		public List<String> getGroups() {
			return groups;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Row that && number == that.number && groups.equals(that.groups);
		}

		@Override
		public int hashCode() {
			return Objects.hash(number, groups);
		}

		@Override
		public String toString() {
			return number + " " + String.join(" ", groups);
		}
	}

	/**
	 * A check that the user saved the key, without retyping all of it: two different groups, by their
	 * 0-based position.
	 */
	public static final class Challenge {
		private final int first;
		private final int second;

		/**
		 * Constructs a challenge that asks the user to confirm two different groups
		 * of a saved key by their positions.
		 *
		 * @param first the first group asked for, 0-based
		 * @param second the second group asked for, 0-based
		 */
		public Challenge(int first, int second) {
			this.first = first;
			this.second = second;
		}

		/**
		 * The first group asked for, 0-based.
		 *
		 * @return the first group asked for, 0-based
		 */
		public int getFirst() {
			return first;
		}

		/**
		 * The second group asked for, 0-based.
		 *
		 * @return the second group asked for, 0-based
		 */
		public int getSecond() {
			return second;
		}

		/**
		 * The number the user sees for a group ("Group 7"), as the rows of the key count them.
		 *
		 * @param group a group's 0-based position
		 * @return its 1-based number
		 */
		public int number(int group) {
			return group + 1;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Challenge that && first == that.first && second == that.second;
		}

		@Override
		public int hashCode() {
			return Objects.hash(first, second);
		}

		@Override
		public String toString() {
			return "Challenge(" + first + ", " + second + ")";
		}
	}

	/**
	 * The Base58 text of a 64-byte private key.
	 *
	 * @param privateKey64 the private key, seed || public key
	 * @return its Base58 text
	 */
	public static String encode(byte[] privateKey64) {
		return Base58.encode(privateKey64);
	}

	/**
	 * The key split into groups of {@link #GROUP_SIZE} characters, the last one possibly shorter.
	 *
	 * @param base58 the key's Base58 text
	 * @return its groups
	 */
	public static List<String> groups(String base58) {
		List<String> groups = new ArrayList<>((base58.length() + GROUP_SIZE - 1) / GROUP_SIZE);
		for (int i = 0; i < base58.length(); i += GROUP_SIZE)
			groups.add(base58.substring(i, Math.min(i + GROUP_SIZE, base58.length())));
		return Collections.unmodifiableList(groups);
	}

	/**
	 * The key in rows of {@link #ROW_SIZE} groups, numbered 1, 6, 11, ... by their first group.
	 *
	 * @param base58 the key's Base58 text
	 * @return its rows
	 */
	public static List<Row> rows(String base58) {
		List<String> groups = groups(base58);
		List<Row> rows = new ArrayList<>();
		for (int i = 0; i < groups.size(); i += ROW_SIZE)
			rows.add(new Row(i + 1, groups.subList(i, Math.min(i + ROW_SIZE, groups.size()))));
		return Collections.unmodifiableList(rows);
	}

	/**
	 * The rows as text: the number right-aligned, two spaces, then the groups.
	 *
	 * @param base58 the key's Base58 text
	 * @return one line per row
	 */
	public static List<String> gridLines(String base58) {
		List<Row> rows = rows(base58);
		if (rows.isEmpty())
			return List.of();

		int width = String.valueOf(rows.get(rows.size() - 1).getNumber()).length();
		List<String> lines = new ArrayList<>(rows.size());
		for (Row row : rows) {
			StringBuilder line = new StringBuilder();
			String number = String.valueOf(row.getNumber());
			for (int i = number.length(); i < width; i++)
				line.append(' ');
			lines.add(line.append(number).append("  ").append(String.join(" ", row.getGroups())).toString());
		}
		return Collections.unmodifiableList(lines);
	}

	/**
	 * The key file, exactly as the user portal writes it, so either can read the other's: the key on its
	 * {@code privateKey:} line (the only line either reads), and below it the same key in numbered groups,
	 * for checking or copying it by hand.
	 *
	 * @param privateKeyBase58 the private key's Base58 text
	 * @param userId           the user id, Base58
	 * @return the file's text
	 */
	public static String keyFileText(String privateKeyBase58, String userId) {
		StringBuilder text = new StringBuilder(512)
				.append("Boson User Identity Key\n\n")
				.append(PRIVATE_KEY_LINE).append(' ').append(privateKeyBase58).append('\n')
				.append("publicKey:  ").append(userId).append("\n\n")
				.append("The private key in numbered groups of four, to check or copy by hand:\n\n");
		for (String line : gridLines(privateKeyBase58))
			text.append(line).append('\n');
		return text.append('\n')
				.append("Keep your private key secret. Anyone with this value can act as you.\n")
				.toString();
	}

	/**
	 * Reads a key from what the user pasted, scanned, opened or typed: a key file (its {@code privateKey:}
	 * line), or the key itself as Base58 or hex ({@code 0x} optional). Spaces and line breaks are
	 * ignored, and so are the row numbers of the key as the apps show it. Returns the validated 64-byte
	 * private key.
	 *
	 * @param text what the user gave
	 * @return the 64-byte private key, seed || public key
	 * @throws IllegalArgumentException if it is not a usable Ed25519 private key
	 */
	public static byte[] parse(String text) {
		String source = text;
		for (String line : text.split("\r\n|\r|\n")) {
			String trimmed = line.trim();
			if (trimmed.startsWith(PRIVATE_KEY_LINE)) {
				source = trimmed.substring(PRIVATE_KEY_LINE.length());
				break;
			}
		}

		String value = String.join("", withoutRowNumbers(source));
		if (value.isEmpty())
			throw new IllegalArgumentException("Empty key");

		byte[] bytes = decode(value);
		if (bytes.length != Signature.PrivateKey.BYTES)
			throw new IllegalArgumentException("A user key is " + Signature.PrivateKey.BYTES + " bytes, not " + bytes.length);

		// The key is seed || public key. Boson builds the pair from the seed alone, so check here that the
		// public half is the seed's own: a damaged backup must not pass for a different identity.
		Signature.KeyPair derived = Signature.KeyPair.fromSeed(Arrays.copyOfRange(bytes, 0, Signature.KeyPair.SEED_BYTES));
		try {
			if (!Arrays.equals(derived.publicKey().bytes(), Arrays.copyOfRange(bytes, Signature.KeyPair.SEED_BYTES, bytes.length)))
				throw new IllegalArgumentException("The key's two halves don't belong together");
		} finally {
			derived.destroy();
		}
		return bytes;
	}

	// A 64-byte key is 128 hex characters; its Base58 is about 88, so the length tells them apart.
	private static byte[] decode(String value) {
		String hex = value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;
		if (hex.length() == 128 && isHex(hex)) {
			byte[] bytes = new byte[64];
			for (int i = 0; i < bytes.length; i++)
				bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
			return bytes;
		}

		try {
			return Base58.decode(value);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Not a Base58 or hex key", e);
		}
	}

	private static boolean isHex(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = Character.toLowerCase(s.charAt(i));
			if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f'))
				return false;
		}
		return true;
	}

	// The words of a typed key, without the numbers of its rows if it was copied from the grid: a word is
	// taken for a row number only where the grid puts one, before the first group of each row, and only if
	// it is that row's number. A group is never that short, except the key's last, so no part of the key
	// is dropped, though the digits 1 to 9 are Base58 too.
	private static List<String> withoutRowNumbers(String text) {
		List<String> words = new ArrayList<>();
		for (String word : text.trim().split("\\s+")) {
			if (!word.isEmpty())
				words.add(word);
		}

		List<String> kept = new ArrayList<>(words.size());
		int groups = 0;
		for (int i = 0; i < words.size(); i++) {
			String word = words.get(i);
			boolean atRowStart = groups % ROW_SIZE == 0 && i < words.size() - 1;
			if (atRowStart && word.length() < GROUP_SIZE && word.equals(String.valueOf(groups + 1)))
				continue;
			kept.add(word);
			groups++;
		}
		return kept;
	}

	/**
	 * Picks two different full groups of the key to ask for.
	 *
	 * @param base58 the key's Base58 text
	 * @return the challenge, its groups in order
	 */
	public static Challenge challenge(String base58) {
		return challenge(base58, RANDOM);
	}

	/**
	 * Picks two different full groups of the key to ask for.
	 *
	 * @param base58 the key's Base58 text
	 * @param random the source of the choice
	 * @return the challenge, its groups in order
	 */
	public static Challenge challenge(String base58, java.util.Random random) {
		int full = base58.length() / GROUP_SIZE;
		if (full < 2)
			throw new IllegalArgumentException("Key too short");

		int first = random.nextInt(full);
		int second = random.nextInt(full - 1);
		if (second >= first)
			second++;
		return first < second ? new Challenge(first, second) : new Challenge(second, first);
	}

	/**
	 * Whether the two answers match the challenged groups. Base58 is case-sensitive; spaces are ignored.
	 *
	 * @param base58       the key's Base58 text
	 * @param challenge    the groups asked for
	 * @param firstAnswer  what the user typed for the first
	 * @param secondAnswer what the user typed for the second
	 * @return true if both match
	 */
	public static boolean verify(String base58, Challenge challenge, String firstAnswer, String secondAnswer) {
		List<String> groups = groups(base58);
		return groups.get(challenge.getFirst()).equals(clean(firstAnswer)) &&
				groups.get(challenge.getSecond()).equals(clean(secondAnswer));
	}

	private static String clean(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (!Character.isWhitespace(c))
				sb.append(c);
		}
		return sb.toString();
	}
}
