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


package io.bosonnetwork.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authentication.TokenCredentials;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.identifier.InMemoryNode;
import io.bosonnetwork.service.Admission;
import io.bosonnetwork.service.FederatedService;
import io.bosonnetwork.service.FederationContext;
import io.bosonnetwork.service.Resolution;

class OpenFederationContextTests {
	private final InMemoryNode node = new InMemoryNode();
	private final Id localService = Id.random();
	private final FederationContext context = FederationContext.open(node, localService);

	private static PeerInfo announcement(CryptoIdentity service, CryptoIdentity owner, String endpoint, int seq) {
		return PeerInfo.builder().identity(service).node(owner).endpoint(endpoint).sequenceNumber(seq)
				.extra(Map.of("k", "v")).build();
	}

	@Test
	void anAttestedServiceResolvesToItsNodeAndIsOpen() throws Exception {
		CryptoIdentity service = new CryptoIdentity();
		CryptoIdentity owner = new CryptoIdentity();
		node.addPeer(announcement(service, owner, "https://old.example", 1));
		node.addPeer(announcement(service, owner, "https://new.example", 2));

		Resolution r = context.resolve(service.getId()).get(10, TimeUnit.SECONDS);
		assertTrue(r.allowed());
		assertEquals(Admission.Reason.OPEN, r.admission().reason());
		FederatedService s = r.findService().orElseThrow();
		assertEquals(owner.getId(), s.nodeId());
		assertEquals("https://new.example", s.endpoint());
		assertEquals(Map.of("k", "v"), s.extra());
	}

	@Test
	void unknownAndUnattestedPeersAreTold() throws Exception {
		Resolution unknown = context.resolve(Id.random()).get(10, TimeUnit.SECONDS);
		assertNull(unknown.service());
		assertEquals(Admission.Reason.UNKNOWN_PEER, unknown.admission().reason());

		CryptoIdentity anonymous = new CryptoIdentity();
		node.addPeer(PeerInfo.builder().identity(anonymous).endpoint("https://x.example").build());
		assertEquals(Admission.Reason.UNVERIFIED_PEER,
				context.resolve(anonymous.getId()).get(10, TimeUnit.SECONDS).admission().reason());

		// Two nodes attesting one service: neither is believed.
		CryptoIdentity contested = new CryptoIdentity();
		node.addPeer(announcement(contested, new CryptoIdentity(), "https://a.example", 1));
		node.addPeer(announcement(contested, new CryptoIdentity(), "https://b.example", 1));
		assertEquals(Admission.Reason.UNVERIFIED_PEER,
				context.resolve(contested.getId()).get(10, TimeUnit.SECONDS).admission().reason());
	}

	private User authenticate(CryptoIdentity service, Id subject, Id audience) throws Exception {
		FederatedService target = new FederatedService(audience, Id.random(), null, null, "https://local.example",
				Map.of(), 0, 0);
		String token = target.accessTokens(service, subject).build().token().toCompletionStage()
				.toCompletableFuture().get(10, TimeUnit.SECONDS);
		return context.getWebAuthenticator().authenticate(new TokenCredentials(token)).toCompletionStage()
				.toCompletableFuture().get(10, TimeUnit.SECONDS);
	}

	@Test
	void aTokenAuthenticatesAsTheAttestedServiceOfItsNodeOnly() throws Exception {
		CryptoIdentity service = new CryptoIdentity();
		CryptoIdentity owner = new CryptoIdentity();
		node.addPeer(announcement(service, owner, "https://remote.example", 0));

		User user = authenticate(service, owner.getId(), localService);
		assertEquals(owner.getId().toString(), user.subject());

		assertThrows(ExecutionException.class, () -> authenticate(service, Id.random(), localService));
		assertThrows(ExecutionException.class, () -> authenticate(service, owner.getId(), Id.random()));
		assertThrows(ExecutionException.class, () -> authenticate(new CryptoIdentity(), owner.getId(), localService));
	}

	@Test
	void everyNodeIsAdmittedAndNothingIsDescribed() throws Exception {
		assertEquals(Admission.Reason.OPEN, context.admit(Id.random()).get().reason());
		assertFalse(context.describe(Id.random()).get().isPresent());
	}
}
