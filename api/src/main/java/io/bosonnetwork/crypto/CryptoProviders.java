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

import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Resolves and holds the active {@link CryptoProvider}.
 * <p>
 * The provider is discovered once via the {@link ServiceLoader} mechanism (allowing an
 * alternative backend, such as a future JNI binding, to register through a
 * {@code META-INF/services/io.bosonnetwork.crypto.CryptoProvider} entry). When no provider is
 * registered, the built-in pure-Java {@link BouncyCastleCryptoProvider} is used.
 */
public final class CryptoProviders {
	private static volatile CryptoProvider current = resolve();

	private CryptoProviders() {
	}

	/**
	 * Returns the active crypto provider.
	 *
	 * @return the active {@link CryptoProvider}.
	 */
	public static CryptoProvider getDefault() {
		return current;
	}

	/**
	 * Overrides the active crypto provider. Package-private: intended for the compatibility
	 * test suite to run the wrapper classes against an alternative backend.
	 *
	 * @param provider the provider to activate.
	 */
	static void setDefault(CryptoProvider provider) {
		current = Objects.requireNonNull(provider, "provider");
	}

	private static CryptoProvider resolve() {
		return ServiceLoader.load(CryptoProvider.class)
				.findFirst()
				.orElseGet(BouncyCastleCryptoProvider::new);
	}
}
