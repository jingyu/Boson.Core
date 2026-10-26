package io.bosonnetwork.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Identity;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.identifier.Card;
import io.bosonnetwork.identifier.DIDDocument;
import io.bosonnetwork.json.Json;

public class SuperNodeProfileTests {
	@Test
	void testBuilderAndFromCard() throws Exception {
		Identity identity = new CryptoIdentity();

		SuperNodeProfile profile = SuperNodeProfile.builder(identity)
				.name("SuperNode 1")
				.logo("https://bosonnetwork.io/logo.png")
				.website("https://bosonnetwork.io")
				.contact("contact@bosonnetwork.io")
				.build();

		assertNotNull(profile);
		assertEquals(identity.getId(), profile.getNodeId());
		assertEquals("SuperNode 1", profile.getName().orElseThrow());
		assertEquals("https://bosonnetwork.io/logo.png", profile.getLogo().orElseThrow());
		assertEquals("https://bosonnetwork.io", profile.getWebsite().orElseThrow());
		assertEquals("contact@bosonnetwork.io", profile.getContact().orElseThrow());

		// The card carries the profile and nothing about services.
		Card card = profile.getCard();
		assertNotNull(card);
		assertTrue(card.getServices().isEmpty());
		System.out.println(Json.toPrettyString(card));
		System.out.println("\n================\n");
		System.out.println(Json.toPrettyString(DIDDocument.fromCard(card)));

		SuperNodeProfile profile2 = SuperNodeProfile.fromCard(card);

		assertNotNull(profile2);
		assertEquals(profile.getNodeId(), profile2.getNodeId());
		assertEquals(profile.getName(), profile2.getName());
		assertEquals(profile.getLogo(), profile2.getLogo());
		assertEquals(profile.getWebsite(), profile2.getWebsite());
		assertEquals(profile.getContact(), profile2.getContact());
	}

	@Test
	void aProfileFitsInAMutableValue() {
		SuperNodeProfile profile = SuperNodeProfile.builder(new CryptoIdentity())
				.name("A node with a long display name, to be safe about the size")
				.logo("https://a-rather-long-host-name.example.com/images/the-logo-of-the-node.png")
				.website("https://a-rather-long-host-name.example.com/about/the-node")
				.contact("the-administrator-of-the-node@a-rather-long-host-name.example.com")
				.build();
		assertTrue(profile.getCard().toBytes().length <= Value.MAX_MUTABLE_DATA_BYTES,
				"card of " + profile.getCard().toBytes().length + " bytes");
	}

	@Test
	void aNameIsRequired() {
		assertThrows(IllegalStateException.class, () -> SuperNodeProfile.builder(new CryptoIdentity()).build());
	}
}
