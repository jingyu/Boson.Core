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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.CryptoIdentity;

/**
 * The card lifecycle an app runs for its user through a registry: publish (the registry choosing the
 * version), read back what is published, and stop publishing.
 */
public class RegistryPublishTests {
	private final InMemoryNode node = new InMemoryNode();
	private final Registry registry = Registry.DHTRegistry(node, 0);
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

	@Test
	void theFirstPublicationIsVersionZeroAndIsKept() throws Exception {
		assertEquals(0, registry.publish(alice, card("Alice")).get());

		Registry.PublishedCard published = registry.getPublished(alice.getId()).get().orElseThrow();
		assertEquals(0, published.getVersion());
		assertEquals(alice.getId(), published.getCard().orElseThrow().getId());
	}

	@Test
	void eachPublicationTakesTheNextVersion() throws Exception {
		assertEquals(0, registry.publish(alice, card("Alice")).get());
		assertEquals(1, registry.publish(alice, card("Alice B.")).get());
		assertEquals(1, registry.getPublished(alice.getId()).get().orElseThrow().getVersion());
	}

	@Test
	void aNewerVersionPublishedElsewhereIsPassed() throws Exception {
		registry.publish(alice, card("Alice")).get();
		publishedElsewhere(7);
		assertEquals(8, registry.publish(alice, card("Alice again")).get());
	}

	@Test
	void theCallersLastVersionIsPassedEvenWhenNothingElseKnowsIt() throws Exception {
		// The card expired everywhere, but resolvers may still remember version 4.
		assertEquals(5, registry.publish(alice, card("Alice"), 4).get());
	}

	@Test
	void aNetworkThatCantBeAskedIsNoError() throws Exception {
		registry.publish(alice, card("Alice")).get();
		node.networkDown = true;
		assertEquals(1, registry.publish(alice, card("Alice B.")).get());
	}

	@Test
	void unpublishingStopsKeepingTheCard() throws Exception {
		registry.publish(alice, card("Alice")).get();
		assertTrue(registry.unpublish(alice.getId()).get());
		assertEquals(Optional.empty(), registry.getPublished(alice.getId()).get());
		assertFalse(registry.unpublish(alice.getId()).get());

		// The network may still hold version 0: the next publication passes it.
		assertEquals(1, registry.publish(alice, card("Alice")).get());
	}

	@Test
	void nothingPublishedIsEmpty() throws Exception {
		assertEquals(Optional.empty(), registry.getPublished(Id.random()).get());
	}

	@Test
	void aCardOfAnotherIdentityIsRefused() {
		Identity bob = new CryptoIdentity();
		Card bobs = new CardBuilder(bob).addCredential("profile", "BosonProfile", "name", "Bob").build();
		assertThrows(IllegalArgumentException.class, () -> registry.publish(alice, bobs));
	}

	@Test
	void aRegistryWithoutCacheAsksTheNetworkEveryTime() throws Exception {
		registry.publish(alice, card("Alice")).get();
		Registry.ResolutionResult<Card> result = registry.resolve(alice.getId()).get();
		assertEquals(Registry.ResolutionStatus.SUCCESS, result.getResolutionStatus());
		assertEquals(alice.getId(), result.result().orElseThrow().getId());
		assertEquals(0, result.getResultMetadata().getVersion());

		publishedElsewhere(7);
		assertEquals(7, registry.resolve(alice.getId()).get().getResultMetadata().getVersion());
	}

	@Test
	void unpublishedCopiesStillResolve() throws Exception {
		// What the registry publishes is not what the network has: copies live until they expire.
		registry.publish(alice, card("Alice")).get();
		registry.unpublish(alice.getId()).get();
		assertEquals(Optional.empty(), registry.getPublished(alice.getId()).get());
		assertTrue(registry.resolve(alice.getId()).get().succeeded());
	}

	@Test
	void aNegativeCacheSizeIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> Registry.DHTRegistry(node, -1));
	}
}
