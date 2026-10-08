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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A Boson string: the one form of Boson's own machine-readable strings, the codes people scan or paste
 * and the labels that separate cryptographic domains.
 * <pre>
 * boson-string = "boson:" namespace ":" version *( ":" field )
 * namespace    = lowercase letter, then lowercase letters, digits or "-"
 * version      = 1, 2, ... (no leading zeros)
 * </pre>
 * <ul>
 *   <li>Every namespace is versioned. Each version of a namespace fixes how many fields it has and
 *       what each may contain; a new version is an incompatible change.</li>
 *   <li>Fields are visible ASCII, never empty, never {@code ?} or {@code #} (a Boson string is an
 *       opaque URI, with no query or fragment). Only the last field may contain {@code :}, so a
 *       reader splits with a limit: that is what lets a URL be the last field.</li>
 *   <li>Readers trim surrounding whitespace and accept nothing else; writers write the canonical form.</li>
 *   <li>Field encodings: Boson ids in Base58, other bytes in base64url without padding, integers in
 *       decimal.</li>
 *   <li>A label (a constant fed to a hash, PRF or KDF) is never parsed from input. Label namespaces are
 *       named by role: {@code prf}, {@code kdf}.</li>
 *   <li>No secret goes in a Boson string: any app may claim the {@code boson:} URI scheme.</li>
 * </ul>
 * The registry of namespaces is {@code core/api/docs/formats.md}.
 */
public final class BosonString {
	/** The prefix of every Boson string. */
	public static final String PREFIX = "boson:";

	private static final Pattern NAMESPACE = Pattern.compile("[a-z][a-z0-9-]*");

	private final String namespace;
	private final int version;
	private final List<String> fields;

	private BosonString(String namespace, int version, List<String> fields) {
		this.namespace = namespace;
		this.version = version;
		this.fields = fields;
	}

	/**
	 * Builds a Boson string, checking it against the grammar.
	 *
	 * @param namespace the namespace
	 * @param version   the version, 1 or more
	 * @param fields    the fields, in order; only the last may contain {@code :}
	 * @return the Boson string
	 * @throws IllegalArgumentException if any part breaks the grammar
	 */
	public static BosonString of(String namespace, int version, String... fields) {
		Objects.requireNonNull(namespace, "namespace");
		Objects.requireNonNull(fields, "fields");
		if (!NAMESPACE.matcher(namespace).matches())
			throw new IllegalArgumentException("Invalid namespace: " + namespace);
		if (version < 1)
			throw new IllegalArgumentException("Invalid version: " + version);
		for (int i = 0; i < fields.length; i++)
			checkField(fields[i], i == fields.length - 1);
		return new BosonString(namespace, version, List.of(fields));
	}

	/**
	 * Formats a Boson string; see {@link #of(String, int, String...)}.
	 *
	 * @param namespace the namespace
	 * @param version   the version, 1 or more
	 * @param fields    the fields, in order
	 * @return its text
	 */
	public static String format(String namespace, int version, String... fields) {
		return of(namespace, version, fields).toString();
	}

	/**
	 * Reads {@code text} as a Boson string of {@code namespace}, version {@code version}, with exactly
	 * {@code fieldCount} fields.
	 *
	 * @param text       the text, as scanned or pasted
	 * @param namespace  the namespace expected
	 * @param version    the version expected
	 * @param fieldCount how many fields that version has
	 * @return the Boson string, or empty if {@code text} is not one of that kind
	 */
	public static Optional<BosonString> parse(String text, String namespace, int version, int fieldCount) {
		Objects.requireNonNull(text, "text");
		String trimmed = text.trim();
		// The expected head, written canonically: "1" and never "01", so only the canonical form matches.
		String head = PREFIX + namespace + ":" + version + ":";
		if (fieldCount < 1 || !trimmed.startsWith(head))
			return Optional.empty();

		String[] fields = trimmed.substring(head.length()).split(":", fieldCount);
		if (fields.length != fieldCount)
			return Optional.empty();
		try {
			return Optional.of(of(namespace, version, fields));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	private static void checkField(String field, boolean last) {
		Objects.requireNonNull(field, "field");
		if (field.isEmpty())
			throw new IllegalArgumentException("Empty field");
		for (int i = 0; i < field.length(); i++) {
			char c = field.charAt(i);
			if (c < 0x21 || c > 0x7E || c == '?' || c == '#')
				throw new IllegalArgumentException("Invalid character in field: " + field);
			if (c == ':' && !last)
				throw new IllegalArgumentException("Only the last field may contain ':'");
		}
	}

	/**
	 * Returns the namespace.
	 *
	 * @return the namespace
	 */
	public String namespace() {
		return namespace;
	}

	/**
	 * Returns the version.
	 *
	 * @return the version
	 */
	public int version() {
		return version;
	}

	/**
	 * Returns the fields, in order.
	 *
	 * @return the fields
	 */
	public List<String> fields() {
		return fields;
	}

	/**
	 * Returns a field.
	 *
	 * @param index the field's position, from 0
	 * @return the field
	 */
	public String field(int index) {
		return fields.get(index);
	}

	/**
	 * Returns the text as bytes, as a label is fed to a hash or a key derivation: ASCII.
	 *
	 * @return the bytes
	 */
	public byte[] bytes() {
		return toString().getBytes(StandardCharsets.US_ASCII);
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(PREFIX).append(namespace).append(':').append(version);
		for (String field : fields)
			sb.append(':').append(field);
		return sb.toString();
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof BosonString that && version == that.version && namespace.equals(that.namespace)
				&& fields.equals(that.fields);
	}

	@Override
	public int hashCode() {
		return Objects.hash(namespace, version, fields);
	}
}
