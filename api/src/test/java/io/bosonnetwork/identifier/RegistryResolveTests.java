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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.identifier.Registry.ResolutionResult;
import io.bosonnetwork.identifier.Registry.ResolutionStatus;

/**
 * Resolving cards through a DHT registry: what the network has, verified, and cached in memory.
 */
public class RegistryResolveTests {
	private final InMemoryNode node = new InMemoryNode();
	private final Registry registry = Registry.DHTRegistry(node);
	private final Identity alice = new CryptoIdentity();

	private Card card(String name) {
		return new CardBuilder(alice)
				.addCredential("profile", "BosonProfile", "name", name)
				.addService("homeNode", "BosonHomeNode", Id.random().toString())
				.build();
	}

	// Another publisher of the same owner (another of their devices) stores a version in the network.
	private void publishedElsewhere(int version) {
		node.network.put(alice.getId(), Value.signedBuilder()
				.identity(alice)
				.sequenceNumber(version)
				.data(card("Elsewhere").toBytes())
				.build());
	}

	private int resolvedVersion() throws Exception {
		return resolvedVersion(true);
	}

	private int resolvedVersion(boolean useCache) throws Exception {
		ResolutionResult<Card> result = registry.resolve(alice.getId(), useCache).get();
		assertEquals(ResolutionStatus.SUCCESS, result.getResolutionStatus());
		return result.getResultMetadata().getVersion();
	}

	@Test
	void aPublishedCardResolves() throws Exception {
		Card card = card("Alice");
		registry.publish(alice, card).get();

		ResolutionResult<Card> result = registry.resolve(alice.getId()).get();
		assertTrue(result.succeeded());
		assertEquals(card, result.getResult());
		assertEquals(0, result.getResultMetadata().getVersion());
		assertTrue(result.getResult().isGenuine());
	}

	@Test
	void anUnknownIdIsNotFound() throws Exception {
		ResolutionResult<Card> result = registry.resolve(Id.random()).get();
		assertEquals(ResolutionStatus.NOT_FOUND, result.getResolutionStatus());
		assertNull(result.getResult());
		assertNull(result.getResultMetadata());
	}

	@Test
	void aValueOfAnotherOwnerIsInvalid() throws Exception {
		// The DHT answers the id with a value someone else signed.
		Identity mallory = new CryptoIdentity();
		node.network.put(alice.getId(), Value.signedBuilder()
				.identity(mallory)
				.data(new CardBuilder(mallory).build().toBytes())
				.build());

		assertEquals(ResolutionStatus.INVALID, registry.resolve(alice.getId()).get().getResolutionStatus());
	}

	@Test
	void aCachedCardAnswersWithoutTheNetwork() throws Exception {
		registry.publish(alice, card("Alice")).get();
		assertEquals(0, resolvedVersion());
		publishedElsewhere(3);

		assertEquals(0, resolvedVersion());
		assertEquals(1, node.resolutions.get());
	}

	@Test
	void resolvingAfreshRefreshesTheCache() throws Exception {
		registry.publish(alice, card("Alice")).get();
		assertEquals(0, resolvedVersion());
		publishedElsewhere(3);

		assertEquals(3, resolvedVersion(false));
		assertEquals(3, resolvedVersion());
		assertEquals(2, node.resolutions.get());
	}

	@Test
	void publishingForgetsTheCachedCard() throws Exception {
		registry.publish(alice, card("Alice")).get();
		assertEquals(0, resolvedVersion());

		registry.publish(alice, card("Alice B.")).get();
		assertEquals(1, resolvedVersion());
	}

	@Test
	void unpublishingForgetsTheCachedCard() throws Exception {
		registry.publish(alice, card("Alice")).get();
		resolvedVersion();
		registry.unpublish(alice.getId()).get();

		// The network still has the copy; it is asked again.
		assertEquals(0, resolvedVersion());
		assertEquals(2, node.resolutions.get());
	}

	@Test
	void concurrentResolutionsShareOneLookup() throws Exception {
		registry.publish(alice, card("Alice")).get();
		node.gate = new CompletableFuture<>();

		CompletableFuture<ResolutionResult<Card>> first = registry.resolve(alice.getId());
		CompletableFuture<ResolutionResult<Card>> second = registry.resolve(alice.getId());
		assertFalse(first.isDone());
		node.gate.complete(null);

		assertSame(first.get(), second.get());
		assertEquals(1, node.resolutions.get());
	}

	@Test
	void aFailedLookupIsNotCached() throws Exception {
		registry.publish(alice, card("Alice")).get();
		node.networkDown = true;
		assertThrows(ExecutionException.class, () -> registry.resolve(alice.getId()).get());

		node.networkDown = false;
		assertEquals(0, resolvedVersion());
	}

	@Test
	void aCacheTtlMustBePositive() {
		assertThrows(IllegalArgumentException.class, () -> Registry.DHTRegistry(node, 16, Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> Registry.DHTRegistry(node, 16, Duration.ofSeconds(-1)));
	}

	@Test
	void aCardResolvesToItsDocument() throws Exception {
		registry.publish(alice, card("Alice")).get();
		ResolutionResult<DIDDocument> result = registry.resolveDocument(alice.getId()).get();
		assertTrue(result.succeeded());
		assertEquals(alice.getId(), result.getResult().getId());
	}

	@Test
	void aResolutionAnswersOnTheCallersContext() throws Exception {
		registry.publish(alice, card("Alice")).get();
		node.gate = new CompletableFuture<>();

		Vertx vertx = Vertx.vertx();
		try {
			Context caller = vertx.getOrCreateContext();
			AtomicReference<Context> answeredOn = new AtomicReference<>();
			CountDownLatch answered = new CountDownLatch(1);
			CountDownLatch asked = new CountDownLatch(1);
			caller.runOnContext(v -> {
				registry.resolve(alice.getId()).whenComplete((result, error) -> {
					answeredOn.set(Vertx.currentContext());
					answered.countDown();
				});
				asked.countDown();
			});

			assertTrue(asked.await(5, TimeUnit.SECONDS));
			// The lookup completes on this (foreign) thread.
			node.gate.complete(null);
			assertTrue(answered.await(5, TimeUnit.SECONDS));
			assertSame(caller, answeredOn.get());
		} finally {
			vertx.close().toCompletionStage().toCompletableFuture().join();
		}
	}
}
