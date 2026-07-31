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

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.bosonnetwork.utils.ConfigMap;

/**
 * An operator-provided TLS certificate and its private key.
 * <p>
 * This is separate from {@link ListenOptions#ssl()}, which only says whether a listener speaks TLS.
 * These are the credentials it speaks TLS <em>with</em>, and they are optional even when TLS is
 * enabled: a node with no certificate configured generates a self-signed one bound to its own
 * identity.
 * <p>
 * Most Boson services never configure this - they receive their certificate through the service
 * context, from the node that provisions it - so this block belongs only to the components that can
 * be handed material directly.
 *
 * @param certFile the PEM certificate file
 * @param keyFile  the PEM private key file matching {@code certFile}
 */
public record TlsOptions(Path certFile, Path keyFile) implements ConfigOptions {
	/** The top-level keys these options occupy, so that a writer can clear them again. */
	static final Set<String> KEYS = Set.of("sslCertFile", "sslKeyFile");

	/**
	 * Canonical constructor.
	 * <p>
	 * Both halves are required: a certificate without its key, or a key without its certificate,
	 * cannot be used for anything. The pair is optional as a whole - a node with no certificate
	 * configured has no {@code TlsOptions} at all, rather than one with null components - so that
	 * half a pair cannot leave the node quietly falling back to a self-signed certificate while the
	 * operator believes theirs is in force.
	 *
	 * @param certFile the PEM certificate file
	 * @param keyFile  the PEM private key file
	 * @throws NullPointerException if either is null
	 */
	public TlsOptions {
		Objects.requireNonNull(certFile, "certFile");
		Objects.requireNonNull(keyFile, "keyFile");
	}

	/**
	 * Reads the certificate pair from the given configuration, which carries them as the top-level
	 * {@code sslCertFile} and {@code sslKeyFile} entries.
	 *
	 * @param cm the configuration to read from, may be {@code null}
	 * @return the options, or {@code null} when neither entry is present - meaning the node should
	 *         generate a self-signed certificate
	 * @throws IllegalArgumentException if only one of the two is present
	 */
	static @Nullable TlsOptions fromMap(@Nullable ConfigMap cm) {
		if (cm == null)
			return null;

		Path certFile = cm.getPath("sslCertFile", null);
		Path keyFile = cm.getPath("sslKeyFile", null);
		if (certFile == null && keyFile == null)
			return null;
		if (certFile == null || keyFile == null)
			throw new IllegalArgumentException("sslCertFile and sslKeyFile must be specified together");

		return new TlsOptions(certFile, keyFile);
	}

	@Override
	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("sslCertFile", certFile.toString());
		map.put("sslKeyFile", keyFile.toString());
		return map;
	}
}
