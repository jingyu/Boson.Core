package io.bosonnetwork.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.identifier.Card;
import io.bosonnetwork.identifier.DIDDocument;
import io.bosonnetwork.json.Json;

public class BosonSuperNodeProfileTests {
	@Test
	void testBuilderAndFromCard() throws Exception {
		Identity identity = new CryptoIdentity();
		URI apiEndpoint = new URI("https://api.bosonnetwork.io");
		URI webGatewayEndpoint = new URI("https://gateway.bosonnetwork.io");
		URI ionStoreEndpoint = new URI("https://store.bosonnetwork.io");
		URI photonMessagingEndpoint = new URI("mqtts://messaging.bosonnetwork.io");
		URI activeProxyEndpoint = new URI("tcp://proxy.bosonnetwork.io");

		Id webGatewayPeer = Id.random();
		Id ionStorePeer = Id.random();
		Id photonMessagingPeer = Id.random();
		Id activeProxyPeer = Id.random();

		BosonSuperNodeProfile profile = BosonSuperNodeProfile.builder(identity)
				.name("SuperNode 1")
				.logo("https://bosonnetwork.io/logo.png")
				.website("https://bosonnetwork.io")
				.contact("contact@bosonnetwork.io")
				.apiService(apiEndpoint)
				.webGatewayService(webGatewayPeer, webGatewayEndpoint)
				.ionStoreService(ionStorePeer, ionStoreEndpoint)
				.photonMessagingService(photonMessagingPeer, photonMessagingEndpoint)
				.activeProxyService(activeProxyPeer, activeProxyEndpoint)
				.build();

		assertNotNull(profile);
		assertEquals(identity.getId(), profile.getNodeId());
		assertEquals("SuperNode 1", profile.getName());
		assertEquals("https://bosonnetwork.io/logo.png", profile.getLogo());
		assertEquals("https://bosonnetwork.io", profile.getWebsite());
		assertEquals("contact@bosonnetwork.io", profile.getContact());

		// Verify services
		assertNotNull(profile.getApiService());
		assertEquals(apiEndpoint.toString(), profile.getApiService().getEndpoint());

		assertEquals(1, profile.getWebGatewayServices().size());
		assertEquals(webGatewayEndpoint.toString(), profile.getWebGatewayServices().get(0).getEndpoint());

		assertEquals(1, profile.getIonStoreServices().size());
		assertEquals(ionStoreEndpoint.toString(), profile.getIonStoreServices().get(0).getEndpoint());

		assertEquals(1, profile.getPhotonMessagingServices().size());
		assertEquals(photonMessagingEndpoint.toString(), profile.getPhotonMessagingServices().get(0).getEndpoint());

		assertEquals(1, profile.getActiveProxyServices().size());
		assertEquals(activeProxyEndpoint.toString(), profile.getActiveProxyServices().get(0).getEndpoint());

		// Test fromCard
		Card card = profile.getCard();
		assertNotNull(card);
		System.out.println(Json.toPrettyString(card));
		System.out.println("\n================\n");
		System.out.println(Json.toPrettyString(DIDDocument.fromCard(card)));

		BosonSuperNodeProfile profile2 = BosonSuperNodeProfile.fromCard(card);

		assertNotNull(profile2);
		assertEquals(profile.getNodeId(), profile2.getNodeId());
		assertEquals(profile.getName(), profile2.getName());
		assertEquals(profile.getLogo(), profile2.getLogo());
		assertEquals(profile.getWebsite(), profile2.getWebsite());
		assertEquals(profile.getContact(), profile2.getContact());
		assertEquals(profile.getApiService(), profile2.getApiService());
		assertEquals(profile.getWebGatewayServices(), profile2.getWebGatewayServices());
		assertEquals(profile.getIonStoreServices(), profile2.getIonStoreServices());
		assertEquals(profile.getPhotonMessagingServices(), profile2.getPhotonMessagingServices());
		assertEquals(profile.getActiveProxyServices(), profile2.getActiveProxyServices());

		assertNull(profile2.getService(Id.random()));
	}
}