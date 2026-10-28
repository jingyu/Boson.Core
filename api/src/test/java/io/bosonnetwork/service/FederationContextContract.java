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


package io.bosonnetwork.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.authorization.RoleBasedAuthorization;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.json.Json;
import io.bosonnetwork.web.CwtAuth;

/**
 * The behaviour every {@link FederationContext} host implementation must have, whatever it is backed by.
 * <p>
 * A subclass sets the scene through the fixture methods - which nodes are admitted or refused, which
 * services are announced, which descriptors are published - in whatever way its implementation learns
 * them, and the tests check what the context answers. Admission of nodes the fixture never mentioned is
 * left to each implementation: it depends on its {@link FederationMode}.
 */
public abstract class FederationContextContract {
	/** The context under test, for the local service {@link #localService()} of {@link #localNode()}. */
	protected abstract FederationContext context();

	/** The local node. */
	protected abstract Identity localNode();

	/** The local service's peer id: the audience the web authenticator expects. */
	protected abstract Id localService();

	/** Makes a node admitted. */
	protected abstract void admitted(Id nodeId);

	/** Makes an admitted node refused, for a node reason: {@code SUSPENDED}, {@code BLOCKED}, ... */
	protected abstract void refused(Id nodeId, Admission.Reason reason);

	/**
	 * Announces a service of a node made {@link #admitted(Id) admitted} before: the announcement says
	 * what {@code announcement} says, signed by the service and attested by the node, as a real one is.
	 */
	protected abstract void announced(Identity service, Identity node, FederatedService announcement);

	/** Publishes a node's descriptor, signed by the node, where the node publishes it. */
	protected abstract void described(Identity node, NodeDescriptor descriptor);

	private final Identity remoteNode = new CryptoIdentity();
	private final Identity remoteService = new CryptoIdentity();

	private FederatedService remote() {
		return new FederatedService(remoteService.getId(), remoteNode.getId(), "io.bosonnetwork.test", "Test",
				"https://remote.example:9000", Map.of("federationEndpoint", "https://remote.example:9084"), 7, 3);
	}

	private static <T> T await(CompletionStage<T> stage) throws Exception {
		return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
	}

	// A token the remote service presents to the local one: what FederatedService#accessTokens gives a
	// service that calls another.
	private String tokenFrom(Identity service, Id node, Id audience) throws Exception {
		FederatedService target = new FederatedService(audience, localNode().getId(), null, null,
				"https://local.example", Map.of(), 0, 0);
		return await(target.accessTokens(service, node).build().token().toCompletionStage());
	}

	private User authenticate(String token) throws Exception {
		CwtAuth auth = context().getWebAuthenticator();
		return await(auth.authenticate(new TokenCredentials(token)).toCompletionStage());
	}

	private void assertRefused(String token) {
		ExecutionException e = assertThrows(ExecutionException.class, () -> authenticate(token));
		assertNotNull(e.getCause());
	}

	// ---- resolve -------------------------------------------------------------------------------------

	@Test
	void anUnknownPeerResolvesToNoServiceWithItsReason() throws Exception {
		Resolution r = await(context().resolve(Id.random()));
		assertNull(r.service());
		assertEquals(Admission.Reason.UNKNOWN_PEER, r.admission().reason());
		assertEquals(Admission.Decision.DENY, r.admission().decision());
		assertFalse(r.allowed());
	}

	@Test
	void anAnnouncedServiceOfAnAdmittedNodeResolvesAndIsAllowed() throws Exception {
		admitted(remoteNode.getId());
		announced(remoteService, remoteNode, remote());

		Resolution r = await(context().resolve(remoteService.getId()));
		assertTrue(r.allowed(), r.toString());
		FederatedService s = r.findService().orElseThrow();
		assertEquals(remoteService.getId(), s.peerId());
		assertEquals(remoteNode.getId(), s.nodeId());
		assertEquals("https://remote.example:9000", s.endpoint());
		assertEquals("https://remote.example:9084", s.extra().get("federationEndpoint"));
		assertEquals(7, s.fingerprint());
		assertEquals(3, s.sequenceNumber());
	}

	@Test
	void aServiceOfARefusedNodeResolvesWithTheRefusal() throws Exception {
		admitted(remoteNode.getId());
		announced(remoteService, remoteNode, remote());

		for (Admission.Reason reason : List.of(Admission.Reason.SUSPENDED, Admission.Reason.BLOCKED)) {
			refused(remoteNode.getId(), reason);

			Resolution r = await(context().resolve(remoteService.getId()));
			assertFalse(r.allowed());
			assertEquals(reason, r.admission().reason());
			// The service is still there, so that the caller can say whose refusal it is.
			assertEquals(remoteNode.getId(), r.findService().orElseThrow().nodeId());
		}
	}

	// ---- find ----------------------------------------------------------------------------------------

	@Test
	void findGivesTheServiceWithThisNodesOwnAdmission() throws Exception {
		admitted(remoteNode.getId());
		announced(remoteService, remoteNode, remote());

		Resolution r = await(context().find(remoteService.getId()));
		assertTrue(r.allowed());
		assertEquals(remoteNode.getId(), r.findService().orElseThrow().nodeId());

		refused(remoteNode.getId(), Admission.Reason.SUSPENDED);
		r = await(context().find(remoteService.getId()));
		assertEquals(Admission.Reason.SUSPENDED, r.admission().reason());
		assertEquals(remoteNode.getId(), r.findService().orElseThrow().nodeId());

		assertEquals(Admission.Reason.UNKNOWN_PEER, await(context().find(Id.random())).admission().reason());
	}

	// ---- resolve by node -----------------------------------------------------------------------------

	@Test
	void aNodesServiceResolvesByItsType() throws Exception {
		admitted(remoteNode.getId());
		announced(remoteService, remoteNode, remote());
		described(remoteNode, NodeDescriptor.builder().name("Remote")
				.service(new NodeDescriptor.Service("io.bosonnetwork.test", null, remoteService.getId(),
						"https://remote.example:9000", true, Map.of()))
				.sign(remoteNode));

		Resolution r = await(context().resolve(remoteNode.getId(), "io.bosonnetwork.test"));
		assertTrue(r.allowed());
		assertEquals(remoteService.getId(), r.findService().orElseThrow().peerId());

		assertEquals(Admission.Reason.UNKNOWN_PEER,
				await(context().resolve(remoteNode.getId(), "io.bosonnetwork.other")).admission().reason());
	}

	// ---- admit ---------------------------------------------------------------------------------------

	@Test
	void admissionFollowsTheNodesState() throws Exception {
		admitted(remoteNode.getId());
		assertTrue(await(context().admit(remoteNode.getId())).allowed());

		refused(remoteNode.getId(), Admission.Reason.BLOCKED);
		Admission a = await(context().admit(remoteNode.getId()));
		assertEquals(Admission.Reason.BLOCKED, a.reason());
		assertFalse(a.allowed());
		assertFalse(a.maxAge().isNegative());
	}

	// ---- describe ------------------------------------------------------------------------------------

	@Test
	void aPublishedDescriptorIsFoundAndAnUnknownOneIsNot() throws Exception {
		NodeDescriptor d = NodeDescriptor.builder().name("Remote")
				.service(new NodeDescriptor.Service("io.bosonnetwork.test", null, remoteService.getId(),
						"https://remote.example:9000", true, Map.of()))
				.sign(remoteNode);
		described(remoteNode, d);

		NodeDescriptor found = await(context().describe(remoteNode.getId())).orElseThrow();
		assertEquals(remoteNode.getId(), found.getNodeId());
		assertEquals(remoteService.getId(), found.getServices().get(0).peerId());

		assertTrue(await(context().describe(Id.random())).isEmpty());
	}

	// ---- onChange and report -------------------------------------------------------------------------

	@Test
	void listenersHearOfAdmissionChangesUntilTheyUnsubscribe() {
		admitted(remoteNode.getId());

		List<FederationEvent> events = new CopyOnWriteArrayList<>();
		FederationContext.Subscription subscription = context().onChange(events::add);

		refused(remoteNode.getId(), Admission.Reason.SUSPENDED);
		assertTrue(events.stream().anyMatch(e -> e.nodeId().equals(remoteNode.getId()) &&
				e.admission().reason() == Admission.Reason.SUSPENDED), events.toString());

		subscription.close();
		subscription.close(); // idempotent
		int seen = events.size();
		refused(remoteNode.getId(), Admission.Reason.BLOCKED);
		assertEquals(seen, events.size());
	}

	@Test
	void reportingNeitherBlocksNorFails() {
		context().report(remoteNode.getId(), remoteService.getId(), FederationContext.Outcome.TIMEOUT, "no answer");
		context().report(remoteNode.getId(), remoteService.getId(), FederationContext.Outcome.SUCCESS, null);
	}

	// ---- getWebAuthenticator -------------------------------------------------------------------------

	@Test
	void aTokenFromAnAdmittedServiceAuthenticatesAsThatService() throws Exception {
		admitted(remoteNode.getId());
		announced(remoteService, remoteNode, remote());

		User user = authenticate(tokenFrom(remoteService, remoteNode.getId(), localService()));
		assertEquals(remoteNode.getId().toString(), user.subject());
		assertEquals(remoteService.getId(), user.get("clientId"));
		assertTrue(RoleBasedAuthorization.create(Role.FEDERATION.toString()).match(user));

		FederatedService principal = (FederatedService) user.attributes().getValue("client");
		assertEquals(remoteService.getId(), principal.peerId());
		assertEquals(remoteNode.getId(), principal.nodeId());
	}

	@Test
	void aTokenForAnotherServiceIsRefused() throws Exception {
		admitted(remoteNode.getId());
		announced(remoteService, remoteNode, remote());

		assertRefused(tokenFrom(remoteService, remoteNode.getId(), Id.random()));
	}

	@Test
	void aServiceCannotSpeakForAnotherNode() throws Exception {
		// The remote service belongs to the remote node; a token naming another admitted node as its
		// subject must not be taken for that node's.
		Identity otherNode = new CryptoIdentity();
		admitted(remoteNode.getId());
		admitted(otherNode.getId());
		announced(remoteService, remoteNode, remote());

		assertRefused(tokenFrom(remoteService, otherNode.getId(), localService()));
	}

	@Test
	void aTokenFromAnUnknownServiceIsRefused() throws Exception {
		admitted(remoteNode.getId());

		assertRefused(tokenFrom(new CryptoIdentity(), remoteNode.getId(), localService()));
	}

	@Test
	void aTokenFromARefusedNodesServiceIsRefused() throws Exception {
		admitted(remoteNode.getId());
		announced(remoteService, remoteNode, remote());
		refused(remoteNode.getId(), Admission.Reason.BLOCKED);

		assertRefused(tokenFrom(remoteService, remoteNode.getId(), localService()));
	}

	@Test
	void aSignedStatementIsNotAnAccessToken() throws Exception {
		admitted(remoteNode.getId());
		announced(remoteService, remoteNode, remote());

		// Signed by the admitted service itself and addressed to the local one, but a statement.
		byte[] statement = FederationStatement.builder(remoteService, FederationStatement.Purpose.PROPOSE)
				.audience(localService())
				.build();
		assertRefused(Json.BASE64_ENCODER.encodeToString(statement));
	}
}
