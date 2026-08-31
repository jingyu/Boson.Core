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

package io.bosonnetwork.service.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.bosonnetwork.utils.ConfigMap;

/**
 * How a service announces itself to the DHT as a peer.
 *
 * @param endpoint       the publicly reachable endpoint URL to announce, or {@code null} to derive
 *                       it from the listen address
 * @param fingerprint    the peer fingerprint used for stable peer identification, or {@code 0} for
 *                       none
 * @param sequenceNumber the peer-info sequence number, or {@code 0} if unset
 * @param extra          the service-defined values to announce alongside the endpoint, empty if
 *                       there are none
 */
public record PeerOptions(@Nullable String endpoint, long fingerprint, int sequenceNumber, Map<String, Object> extra)
		implements ConfigOptions {
	/** Announce nothing in particular: derive the endpoint, with no fingerprint or sequence number. */
	public static final PeerOptions DEFAULT = new PeerOptions(null, 0, 0, Map.of());

	/**
	 * Canonical constructor.
	 *
	 * @param endpoint       the endpoint URL to announce, or {@code null} to derive it
	 * @param fingerprint    the peer fingerprint, or {@code 0} for none
	 * @param sequenceNumber the peer-info sequence number, or {@code 0} if unset
	 * @param extra          the service-defined values to announce, empty if there are none
	 * @throws IllegalArgumentException if {@code sequenceNumber} is negative
	 */
	public PeerOptions {
		if (sequenceNumber < 0)
			throw new IllegalArgumentException("Invalid sequence number - " + sequenceNumber);

		// These options are a value: copy the map so a caller holding on to its own reference cannot
		// change what an already-constructed options object means.
		extra = extra.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extra));
	}

	/**
	 * Construct a PeerOption with empty extra data.
	 *
	 * @param endpoint       the endpoint URL to announce, or {@code null} to derive it
	 * @param fingerprint    the peer fingerprint, or {@code 0} for none
	 * @param sequenceNumber the peer-info sequence number, or {@code 0} if unset
	 * @throws IllegalArgumentException if {@code sequenceNumber} is negative
	 */
	public PeerOptions(@Nullable String endpoint, long fingerprint, int sequenceNumber) {
		this(endpoint, fingerprint, sequenceNumber, Map.of());
	}

	static PeerOptions fromMap(@Nullable ConfigMap cm, String... expectedSchemes) {
		if (cm == null || cm.isEmpty())
			return PeerOptions.DEFAULT;

		String endpoint = cm.getString("endpoint", null);
		if (endpoint != null && endpoint.isEmpty())
			endpoint = null;
		if (endpoint != null)
			validateEndpoint(endpoint, expectedSchemes);

		ConfigMap extra = cm.getObject("extra");

		return new PeerOptions(endpoint, cm.getLong("fingerprint", 0L),
				cm.getNonNegativeInteger("sequenceNumber", 0), extra != null ? extra : Map.of());
	}

	/**
	 * Checks that the announced endpoint is a well-formed absolute URI. The syntax check is
	 * unconditional - an endpoint nobody can parse is useless whether or not the caller cares which
	 * schemes are acceptable - and the scheme is checked on top of it when the caller names the
	 * schemes it supports.
	 */
	private static void validateEndpoint(String endpoint, String... expectedSchemes) {
		String scheme;
		try {
			scheme = new URI(endpoint).getScheme();
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Invalid endpoint - " + endpoint, e);
		}

		if (scheme == null)
			throw new IllegalArgumentException("Invalid endpoint - " + endpoint + ": missing scheme");

		if (expectedSchemes.length > 0 && !Arrays.asList(expectedSchemes).contains(scheme))
			throw new IllegalArgumentException("Invalid endpoint scheme: " + scheme +
					", expected: " + Arrays.toString(expectedSchemes));
	}

	@Override
	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		if (endpoint != null)
			map.put("endpoint", endpoint);
		if (fingerprint != 0)
			map.put("fingerprint", fingerprint);
		if (sequenceNumber != 0)
			map.put("sequenceNumber", sequenceNumber);
		if (!extra.isEmpty())
			map.put("extra", extra);
		return map;
	}
}
