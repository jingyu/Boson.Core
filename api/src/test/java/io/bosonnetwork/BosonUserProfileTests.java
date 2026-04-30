package io.bosonnetwork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.identifier.Card;
import io.bosonnetwork.identifier.DIDDocument;
import io.bosonnetwork.json.Json;

public class BosonUserProfileTests {
	@Test
	void testBuilderAndFromCard() {
		Identity identity = new CryptoIdentity();
		Id homeNode = Id.random();
		Id messagingPeer = Id.random();

		BosonUserProfile profile = BosonUserProfile.builder(identity)
				.name("John Doe")
				.avatar("https://example.com/avatar.png")
				.bio("A boson user")
				.homeNode(homeNode)
				.messagingHomePeer(messagingPeer)
				.build();

		assertNotNull(profile);
		assertEquals(identity.getId(), profile.getId());
		assertEquals("John Doe", profile.getName());
		assertEquals("https://example.com/avatar.png", profile.getAvatar());
		assertEquals("A boson user", profile.getBio());
		assertEquals(homeNode, profile.getHomeNode());
		assertEquals(messagingPeer, profile.getMessagingHomePeer());

		Card card = profile.getCard();
		assertNotNull(card);
		System.out.println(Json.toPrettyString(card));
		System.out.println("\n================\n");
		System.out.println(Json.toPrettyString(DIDDocument.fromCard(card)));

		// Test fromCard
		BosonUserProfile profile2 = BosonUserProfile.fromCard(card);
		assertNotNull(profile2);
		assertEquals(profile.getId(), profile2.getId());
		assertEquals(profile.getName(), profile2.getName());
		assertEquals(profile.getAvatar(), profile2.getAvatar());
		assertEquals(profile.getBio(), profile2.getBio());
		assertEquals(profile.getHomeNode(), profile2.getHomeNode());
		assertEquals(profile.getMessagingHomePeer(), profile2.getMessagingHomePeer());
	}
}