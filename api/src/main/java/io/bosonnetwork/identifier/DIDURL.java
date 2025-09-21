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

package io.bosonnetwork.identifier;

import static java.text.Normalizer.Form.NFC;

import java.net.MalformedURLException;
import java.net.URI;
import java.text.Normalizer;
import java.util.Objects;

import io.bosonnetwork.Id;

/**
 * Represents a Decentralized Identifier URL (DIDURL) using the Boson DID method.
 * <p>
 * This class provides parsing, normalization, and construction of DID URLs conforming
 * to the "did:boson" scheme. It supports components such as path, query, and fragment,
 * and ensures proper normalization according to Unicode NFC form.
 */
public class DIDURL {
	/**
	 * The scheme of the DID URL, typically "did".
	 */
	private String scheme;

	/**
	 * The DID method, e.g., "boson".
	 */
	private String method;

	/**
	 * The method-specific identifier represented as an {@link Id}.
	 */
	private Id id;

	/**
	 * The path component of the DID URL, normalized to NFC.
	 */
	private String path;

	/**
	 * The query component of the DID URL, normalized to NFC.
	 */
	private String query;

	/**
	 * The fragment component of the DID URL, normalized to NFC.
	 */
	private String fragment;

	// TODO: do the URL encoding for the URL

	/**
	 * Constructs a DIDURL by parsing the given string specification.
	 *
	 * @param spec the DID URL string to parse
	 * @throws NullPointerException     if {@code spec} is null
	 * @throws MalformedURLException    if the given string is not a valid DID URL
	 */
	public DIDURL(String spec) throws MalformedURLException {
		Objects.requireNonNull(spec, "spec");
		parse(spec);
	}

	/**
	 * Constructs a DIDURL with the given method-specific ID and optional path, query, and fragment.
	 *
	 * @param id       the method-specific identifier
	 * @param path     the path component (may be null or empty)
	 * @param query    the query component (may be null or empty)
	 * @param fragment the fragment component (may be null or empty)
	 */
	public DIDURL(Id id, String path, String query, String fragment) {
		this.scheme = "did";
		this.method = "boson";
		this.id = id;

		this.path = path != null && !path.isEmpty() ? Normalizer.normalize(path, NFC) : null;

		if (query != null && !query.isEmpty()) {
			if (query.startsWith("?"))
				query = query.substring(1);

			if (!query.isEmpty()) {
				this.query = Normalizer.normalize(query, NFC);
			}
		}

		if (fragment != null && !fragment.isEmpty()) {
			if (fragment.startsWith("#"))
				fragment = fragment.substring(1);

			if (!fragment.isEmpty())
				this.fragment = Normalizer.normalize(fragment, NFC);
		}
	}

	/**
	 * Constructs a DIDURL by parsing the given specification and associating it with the provided method-specific ID.
	 * <p>
	 * If the parsed URL does not specify an ID, the provided ID will be used.
	 *
	 * @param id   the method-specific identifier to associate
	 * @param spec the DID URL string to parse (may be null)
	 * @throws NullPointerException     if {@code id} is null
	 * @throws MalformedURLException    if the given string is not a valid DID URL
	 */
	public DIDURL(Id id, String spec) throws MalformedURLException {
		Objects.requireNonNull(id, "id");

		if (spec != null)
			parse(spec);

		if (this.id == null) {
			this.id = id;
			this.scheme = "did";
			this.method = "boson";
		}
	}

	/**
	 * Constructs a DIDURL with the given method-specific ID and no path, query, or fragment.
	 *
	 * @param id the method-specific identifier
	 */
	public DIDURL(Id id) {
		this(id, null, null, null);
	}

	/**
	 * Creates a DIDURL by parsing the given string.
	 * <p>
	 * This convenience factory method works as if by invoking the {@link #DIDURL(String)} constructor;
	 * any {@link MalformedURLException} thrown by the constructor is caught and wrapped in a new
	 * {@link IllegalArgumentException} object, which is then thrown.
	 * This method is provided for use in situations where it is known that the given string is a legal DIDURL,
	 * for example for URI constants declared within a program, and so it would be considered a programming error
	 * for the string not to parse as such.
	 * The constructors, which throw {@link MalformedURLException} directly, should be used in situations where a DIDURL is
	 * being constructed from user input or from some other source that may be prone to errors.
	 *
	 * @param url The string to be parsed into a DIDURL
	 * @return The new DIDURL object
	 * @throws NullPointerException     If url is null
	 * @throws IllegalArgumentException If the given url is malformed DIDURL
	 */
	public static DIDURL create(String url)  {
		Objects.requireNonNull(url, "url");

		try {
			return new DIDURL(url);
		} catch (MalformedURLException e) {
			throw new IllegalArgumentException("Malformed url", e);
		}
	}

	/**
	 * Scans the given string {@code spec} from index {@code start} up to {@code limit} to find the first occurrence
	 * of any of the specified delimiter characters.
	 *
	 * @param spec       the string to scan
	 * @param start      the starting index (inclusive)
	 * @param limit      the ending index (exclusive)
	 * @param delimiters the delimiter characters to look for
	 * @return the index of the first delimiter found, or {@code limit} if none found
	 */
	private int scan(String spec, int start, int limit, char... delimiters) {
		for (int i = start; i < limit; i++) {
			char ch = spec.charAt(i);
			for (char delimiter : delimiters) {
				if (ch == delimiter)
					return i;
			}
		}
		return limit;
	}

	/**
	 * Parses the given DID URL string specification into its components.
	 *
	 * @param spec the DID URL string to parse
	 * @throws MalformedURLException if the specification is not a valid DID URL
	 */
	private void parse(String spec) throws MalformedURLException {
		int start = 0;
		int limit = spec.length();
		while ((limit > 0) && (spec.charAt(limit - 1) <= ' '))
			limit--;        // eliminate trailing whitespace

		while ((start < limit) && (spec.charAt(start) <= ' '))
			start++;        // eliminate leading whitespace

		if (start == limit)
			return;

		char ch = spec.charAt(start);
		if (ch != '/' && ch != '#') { // not relative url or fragment/reference
			// scan scheme
			int pos = scan(spec, start, limit, ':', '/', '?', '#');
			if (pos > start) {
				String s = spec.substring(start, pos).toLowerCase();
				if (!s.equals("did"))
					throw new MalformedURLException("Invalid scheme: " + s);

				scheme = "did";
				start = (spec.charAt(pos) == ':' ? pos + 1 : pos);
			} else {
				throw new MalformedURLException("Missing DIDURL scheme");
			}

			// scan method
			pos = scan(spec, start, limit, ':', '/', '?', '#');
			if (pos > start) {
				String s = spec.substring(start, pos).toLowerCase();
				if (!s.equals("boson"))
					throw new MalformedURLException("Unsupported method: " + s);

				method = "boson";
				start = (spec.charAt(pos) == ':' ? pos + 1 : pos);
			} else {
				throw new MalformedURLException("Missing DIDURL method");
			}

			// scan method specific id
			pos =scan(spec, start, limit, '/', '?', '#');
			if (pos > start) {
				String s = spec.substring(start, pos);
				try {
					id = Id.ofBase58(s);
				} catch (Exception e) {
					throw new MalformedURLException("Invalid method specific id: " + s);
				}

				start = pos;
			} else {
				throw new MalformedURLException("Missing method specific id");
			}
		}

		// Parse and normalize path component if present
		if (start < limit && spec.charAt(start) == '/') {
			int pos = scan(spec, start + 1, limit, '?', '#');
			path = Normalizer.normalize(spec.substring(start, pos), NFC);
			start = pos;
		}

		// Parse and normalize query component if present
		if (start < limit && spec.charAt(start) == '?') {
			int pos = scan(spec, ++start, limit, '#');
			if (pos > start)
				query = Normalizer.normalize(spec.substring(start, pos), NFC);
			start = pos;
		}

		// Parse and normalize fragment component if present
		if (start < limit && spec.charAt(start) == '#') {
			if (++start < limit)
				fragment = Normalizer.normalize(spec.substring(start, limit), NFC);
		}
	}

	/**
	 * Returns the scheme component of this DID URL.
	 *
	 * @return the scheme, typically "did"
	 */
	public String getScheme() {
		return scheme;
	}

	/**
	 * Returns the method component of this DID URL.
	 *
	 * @return the method, e.g., "boson"
	 */
	public String getMethod() {
		return method;
	}

	/**
	 * Returns the method-specific identifier component of this DID URL.
	 *
	 * @return the method-specific identifier as an {@link Id}
	 */
	public Id getId() {
		return id;
	}

	/**
	 * Returns the path component of this DID URL, or {@code null} if none.
	 *
	 * @return the path component or {@code null}
	 */
	public String getPath() {
		return path;
	}

	/**
	 * Returns the query component of this DID URL, or {@code null} if none.
	 *
	 * @return the query component or {@code null}
	 */
	public String getQuery() {
		return query;
	}

	/**
	 * Returns the fragment component of this DID URL, or {@code null} if none.
	 *
	 * @return the fragment component or {@code null}
	 */
	public String getFragment() {
		return fragment;
	}

	/**
	 * Converts this DIDURL to a {@link URI} instance.
	 *
	 * @return a URI representing this DIDURL
	 */
	public URI toURI() {
		return URI.create(toString());
	}

	/**
	 * Returns a hash code value for this DIDURL.
	 *
	 * @return a hash code value for this object
	 */
	@Override
	public int hashCode() {
		return Objects.hash(scheme, method, id, path, query, fragment);
	}

	/**
	 * Compares this DIDURL to the specified object for equality.
	 * <p>
	 * Two DIDURLs are equal if all their components (scheme, method, id, path, query, fragment) are equal.
	 *
	 * @param o the object to compare with
	 * @return {@code true} if the objects are equal; {@code false} otherwise
	 */
	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;

		if (o instanceof DIDURL that)
			return Objects.equals(scheme, that.scheme) &&
					Objects.equals(method, that.method) &&
					Objects.equals(id, that.id) &&
					Objects.equals(path, that.path) &&
					Objects.equals(query, that.query) &&
					Objects.equals(fragment, that.fragment);

		return false;
	}

	/**
	 * Returns a string representation of this DIDURL.
	 *
	 * @return the string form of the DID URL
	 */
	@Override
	public String toString() {
		return (scheme != null ? scheme + ':' : "")
				+ (method != null ? method + ':' : "")
				+ (id != null ? id.toBase58String() : "")
				+ (path != null ? path : "")
				+ (query != null ? '?' + query : "")
				+ (fragment != null ? '#' + fragment : "");
	}
}