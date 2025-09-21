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

import java.util.concurrent.CompletableFuture;

import io.bosonnetwork.crypto.CryptoBox;

/**
 * The {@code Registry} interface defines the contract for registering and resolving Boson {@link Card}s.
 * <p>
 * Implementations of this interface are responsible for securely registering {@code Card} identities
 * and providing mechanisms for their asynchronous resolution. All registration and initialization
 * operations are performed asynchronously using {@link CompletableFuture}, enabling non-blocking workflows.
 * <p>
 * This interface also exposes a default Distributed Hash Table (DHT) based registry implementation
 * via the static {@link #DHTRegistry()} method.
 *
 * <h2>Asynchronous Operations</h2>
 * <ul>
 *   <li>All methods that modify or access the registry state are asynchronous and return {@code CompletableFuture}.</li>
 *   <li>Callers should handle completion and exceptions using the returned futures.</li>
 * </ul>
 *
 * <h2>Signatures</h2>
 * <ul>
 *   <li>Card registrations require a cryptographic signature for authentication and integrity.</li>
 * </ul>
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>The default implementation is a DHT-based registry accessible via {@link #DHTRegistry()}.</li>
 * </ul>
 */
public interface Registry {
	/**
	 * Initializes the registry with the specified arguments.
	 * <p>
	 * This method should be called before using the registry to perform any registration or resolution.
	 * The arguments are implementation-specific and may be used to configure network connections,
	 * cryptographic parameters, or other initialization requirements.
	 * </p>
	 *
	 * @param args implementation-specific initialization arguments
	 * @return a {@link CompletableFuture} that completes when initialization is finished.
	 *         If initialization fails, the future will complete exceptionally.
	 */
	CompletableFuture<Void> initialize(Object... args);

	/**
	 * Registers a {@link Card} in the registry.
	 * <p>
	 * The registration is performed asynchronously and requires a cryptographic
	 * signature to ensure the authenticity and integrity of the {@code Card} data.
	 * The {@code nonce} and {@code version} parameters provide replay protection and versioning.
	 * </p>
	 *
	 * @param card      the {@link Card} to register
	 * @param nonce     a unique {@link CryptoBox.Nonce} for this registration operation
	 * @param version   the version number of the {@code Card} being registered
	 * @param signature a cryptographic signature over the registration data
	 * @return a {@link CompletableFuture} that completes when registration is finished.
	 *         If registration fails, the future will complete exceptionally.
	 */
	CompletableFuture<Void> register(Card card, CryptoBox.Nonce nonce, int version, byte[] signature);

	/**
	 * Returns a {@link Resolver} for resolving registered {@link Card}s.
	 * <p>
	 * The resolver provides asynchronous lookup capabilities for retrieving cards by their identifier.
	 * </p>
	 *
	 * @return a {@link Resolver} instance associated with this registry
	 */
	Resolver getResolver();

	/**
	 * Returns the default DHT-based registry implementation.
	 * <p>
	 * This static factory method provides access to a singleton instance of the DHT-backed
	 * {@code Registry}. The DHTRegistry is suitable for decentralized, distributed identity management.
	 * </p>
	 *
	 * @return the singleton DHT-based {@code Registry} implementation
	 */
	static Registry DHTRegistry() {
		return DHTRegistry.getInstance();
	}
}