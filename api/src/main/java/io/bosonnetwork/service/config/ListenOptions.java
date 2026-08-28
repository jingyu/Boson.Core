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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.bosonnetwork.utils.ConfigMap;

/**
 * The endpoint a service's server binds to.
 * <p>
 * Unlike the other options in this package these settings sit at the top level of a service's
 * configuration document rather than in a named block, so they are read from the document itself
 * and written back as three flat entries.
 *
 * @param host the host or interface to bind to, for example {@code 0.0.0.0} for all interfaces
 * @param port the TCP port to listen on, in the range {@code [1, 65535]}
 * @param ssl  whether TLS is enabled
 */
public record ListenOptions(String host, int port, boolean ssl) implements ConfigOptions {
	/** The top-level keys these options occupy, so that a writer can clear them again. */
	static final Set<String> KEYS = Set.of("host", "port", "ssl");

	/**
	 * Canonical constructor, and the only place these settings are validated.
	 *
	 * @param host the host or interface to bind to
	 * @param port the TCP port to listen on, in the range {@code [1, 65535]}
	 * @param ssl  whether TLS is enabled
	 * @throws NullPointerException     if {@code host} is null
	 * @throws IllegalArgumentException if {@code host} is empty or {@code port} is out of range
	 */
	public ListenOptions {
		Objects.requireNonNull(host, "host");
		if (host.isEmpty())
			throw new IllegalArgumentException("Invalid host: empty");
		// Port 0 (automatic allocation) is not allowed; port must be in the range [1, 65535]
		if (port <= 0 || port > 65535)
			throw new IllegalArgumentException("Invalid port: " + port);
	}

	static ListenOptions fromMap(@Nullable ConfigMap cm, String defaultHost, int defaultPort, boolean defaultSsl) {
		if (cm == null || cm.isEmpty())
			return new ListenOptions(defaultHost, defaultPort, defaultSsl);

		// The canonical constructor validates; reading is only about picking up defaults.
		return new ListenOptions(Objects.requireNonNullElse(cm.getString("host", defaultHost), defaultHost),
				cm.getInteger("port", defaultPort),
				cm.getBoolean("ssl", defaultSsl));
	}

	@Override
	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("host", host);
		map.put("port", port);
		map.put("ssl", ssl);
		return map;
	}
}