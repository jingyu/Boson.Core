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

import java.util.Date;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import io.bosonnetwork.Id;
import io.bosonnetwork.LookupOption;
import io.bosonnetwork.Node;
import io.bosonnetwork.Value;

/**
 * A resolver implementation that uses a Distributed Hash Table (DHT) to resolve entities.
 * The DHTResolver interacts with a specified {@link Node} to perform lookups and resolve
 * given identifiers into entities.
 * <p>
 * This class implements the {@code Resolver} interface, which defines the contract for resolving
 * identifiers to entities.
 */
public class DHTResolver implements Resolver {
	private final Node node;

	/**
	 * Constructs a new {@code DHTResolver} with the specified {@code Node}.
	 * This resolver uses the given {@code Node} to query a Distributed Hash Table (DHT)
	 * for resolving identifiers and retrieving associated entities.
	 *
	 * @param node the {@code Node} instance used for DHT interactions. Must not be {@code null}.
	 *             The {@code Node} facilitates communication with the DHT for performing
	 *             lookups and other operations.
	 * @throws NullPointerException if {@code node} is {@code null}.
	 */
	public DHTResolver(Node node) {
		this.node = Objects.requireNonNull(node, "node");
	}

	/**
	 * Performs a lookup operation in the Distributed Hash Table (DHT) to retrieve a value
	 * associated with the specified identifier and lookup option.
	 *
	 * @param id the identifier to be looked up in the DHT. Must not be {@code null}.
	 * @param option the lookup option specifying how the lookup operation should be performed.
	 *               May include constraints or preferences for the lookup behavior.
	 * @return a {@code CompletableFuture} that resolves to the value associated with the
	 *         given identifier, or {@code null} if no value is found.
	 * @throws NullPointerException if {@code id} is {@code null}.
	 */
	protected CompletableFuture<Value> lookup(Id id, LookupOption option) {
		Objects.requireNonNull(id, "id");
		return node.findValue(id, option);
	}

	/**
	 * Resolves the given identifier to a card entity by performing a lookup in the
	 * Distributed Hash Table (DHT). The resolution process verifies the integrity of the
	 * retrieved data, checks the identifier's validity, and ensures the resolved card
	 * is genuine and signed correctly.
	 *
	 * @param id the identifier to resolve. Must not be null.
	 * @param options the resolution options that determine how the lookup is performed. Optional;
	 *                may include preferences such as whether to use caching during the lookup.
	 * @return a {@code CompletableFuture} that resolves to a {@code ResolutionResult<Card>}
	 *         representing the outcome of the resolution. This may include a successfully resolved
	 *         card with metadata, an indication of a not found result, or an invalid result
	 *         if resolution failed due to verification or parsing errors.
	 */
	@Override
	public CompletableFuture<ResolutionResult<Card>> resolve(Id id, ResolutionOptions options) {
		// Ensure the id is not null
		Objects.requireNonNull(id, "id");

		LookupOption lookupOption = options != null && options.usingCache() ?
				LookupOption.ARBITRARY : LookupOption.OPTIMISTIC;

		return lookup(id, lookupOption).thenApply(value -> {
			// If no value found in DHT, return the not found result
			if (value == null)
				return ResolutionResult.notfound();

			// Check that the id matches the public key in the retrieved value
			if (!Objects.equals(id, value.getPublicKey()))
				return ResolutionResult.invalid();

			Card card;
			try {
				// Attempt to parse the Card from the value's data bytes
				card = Card.parse(value.getData());
			} catch (Exception e) {
				// Parsing failed, return the invalid result
				return ResolutionResult.invalid();
			}

			// Verify the Card's signature and integrity
			if (!card.isGenuine())
				return ResolutionResult.invalid();

			// Retrieve the version (sequence number) from the value
			int version = value.getSequenceNumber();

			// Return a successful resolution result with metadata including signature timestamps and version
			return new ResolutionResult<>(card, new ResolutionResultMetadata(
					card.getSignedAt(), card.getSignedAt(), new Date(), false, version));
		});
	}
}