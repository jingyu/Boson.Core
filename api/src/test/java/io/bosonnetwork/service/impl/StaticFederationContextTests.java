package io.bosonnetwork.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.service.Admission;
import io.bosonnetwork.service.FederatedService;
import io.bosonnetwork.service.FederationContext;
import io.bosonnetwork.service.FederationMode;
import io.bosonnetwork.service.NodeDescriptor;
import io.bosonnetwork.service.Resolution;

// How the static context is populated; what it answers is the contract's (StaticFederationContextContractTests).
public class StaticFederationContextTests {
	private StaticFederationContext context;

	@BeforeEach
	public void setUp() {
		context = new StaticFederationContext(new CryptoIdentity());
	}

	@Test
	public void addedNodesAreActiveAndKnown() throws ExecutionException, InterruptedException {
		Id nodeId = Id.random();
		assertTrue(context.addNode(nodeId, "localhost", 8080));
		assertFalse(context.addNode(nodeId, "localhost", 8080)); // repeat

		assertTrue(context.existsNodeSync(nodeId));
		assertEquals(Admission.Reason.ACTIVE, context.admit(nodeId).get().reason());
		// A node it doesn't know is refused, as on a node that federates only by agreement.
		assertEquals(Admission.Reason.NO_RELATIONSHIP, context.admit(Id.random()).get().reason());

		assertThrows(IllegalArgumentException.class, () -> context.addNode(Id.random(), "localhost", 0));
	}

	@Test
	public void servicesAreAddedPerNodeAndResolve() throws ExecutionException, InterruptedException {
		Id nodeId = Id.random();
		Id peerId = Id.random();
		assertThrows(IllegalArgumentException.class, () -> context.addService(nodeId, peerId, 1L, "https://svc"));

		context.addNode(nodeId, "localhost", 8080);
		assertTrue(context.addService(nodeId, peerId, 123L, "https://svc/1"));
		assertFalse(context.addService(nodeId, peerId, 123L, "https://svc/1")); // repeat
		assertTrue(context.addService(nodeId, peerId, 456L, "https://svc/2"));
		assertTrue(context.addService(nodeId, Id.random(), 0, "https://another.svc"));

		List<FederatedService> services = context.getServicesSync(peerId, nodeId);
		assertEquals(2, services.size());
		for (FederatedService s : services) {
			assertEquals(peerId, s.peerId());
			assertEquals(nodeId, s.nodeId());
			assertTrue(Set.of(123L, 456L).contains(s.fingerprint()));
		}
		assertEquals("https://svc/1", context.getServiceSync(peerId, 123L, nodeId).orElseThrow().endpoint());

		Resolution r = context.resolve(peerId).get();
		assertTrue(r.allowed());
		assertEquals(nodeId, r.findService().orElseThrow().nodeId());
	}

	@Test
	public void removingServicesAndNodes() throws ExecutionException, InterruptedException {
		Id nodeId = Id.random();
		Id peerId = Id.random();
		context.addNode(nodeId, "localhost", 8080);
		for (long f = 1; f <= 5; f++)
			context.addService(nodeId, peerId, f, "http://svc/" + f);
		context.addService(nodeId, Id.random(), 0L, "http://svc");
		assertEquals(5, context.getServicesSync(peerId, nodeId).size());

		assertFalse(context.removeServices(Id.random(), nodeId));
		assertFalse(context.removeServices(peerId, Id.random()));
		assertFalse(context.removeService(peerId, 123L, nodeId));

		assertTrue(context.removeService(peerId, 3L, nodeId));
		assertEquals(4, context.getServicesSync(peerId, nodeId).size());
		assertFalse(context.removeService(peerId, 3L, nodeId)); // repeat

		assertTrue(context.removeServices(peerId, nodeId));
		assertTrue(context.getServicesSync(peerId, nodeId).isEmpty());
		assertNull(context.resolve(peerId).get().service());
		assertFalse(context.removeServices(peerId, nodeId)); // repeat

		assertTrue(context.removeNode(nodeId));
		assertFalse(context.removeNode(nodeId)); // repeat
		assertFalse(context.existsNodeSync(nodeId));
		assertFalse(context.removeNode(Id.random()));
	}

	@Test
	public void admissionIsSetPerNodeAndOnlyForNodeReasons() throws ExecutionException, InterruptedException {
		Id nodeId = Id.random();
		context.addNode(nodeId, "localhost", 8080);

		context.setAdmission(nodeId, Admission.Reason.BLOCKED);
		assertEquals(Admission.Reason.BLOCKED, context.admit(nodeId).get().reason());

		assertThrows(IllegalArgumentException.class, () -> context.setAdmission(nodeId, Admission.Reason.UNKNOWN_PEER));
		assertThrows(IllegalArgumentException.class, () -> context.setAdmission(Id.random(), Admission.Reason.ACTIVE));
	}

	@Test
	public void reportsAreKeptForTests() {
		Id nodeId = Id.random();
		Id peerId = Id.random();
		context.report(nodeId, peerId, FederationContext.Outcome.TIMEOUT, "slow");
		assertEquals(List.of(new StaticFederationContext.Report(nodeId, peerId, FederationContext.Outcome.TIMEOUT, "slow")),
				context.getReports());
	}

	@Test
	public void aNodesServiceResolvesByItsType() throws ExecutionException, InterruptedException {
		CryptoIdentity node = new CryptoIdentity();
		Id open = Id.random();
		Id closed = Id.random();
		context.addNode(node.getId(), "localhost", 8080);
		context.addService(node.getId(), open, 1L, "https://svc/open");
		context.addService(node.getId(), closed, 2L, "https://svc/closed");
		context.putDescriptor(NodeDescriptor.builder()
				.nodeId(node.getId())
				.software("Boson Director", "test")
				.mode(FederationMode.OPEN)
				.service(new NodeDescriptor.Service("io.test.open", null, open, "https://svc/open", true, Map.of()))
				.service(new NodeDescriptor.Service("io.test.closed", null, closed, "https://svc/closed", false, Map.of()))
				.lifetime(Duration.ofHours(6))
				.build());

		Resolution r = context.resolve(node.getId(), "io.test.open").get();
		assertTrue(r.allowed());
		assertEquals(open, r.findService().orElseThrow().peerId());

		// Listed, but not for other nodes.
		r = context.resolve(node.getId(), "io.test.closed").get();
		assertEquals(Admission.Reason.NOT_FEDERATING, r.admission().reason());
		assertEquals(closed, r.findService().orElseThrow().peerId());

		assertEquals(Admission.Reason.UNKNOWN_PEER, context.resolve(node.getId(), "io.test.none").get().admission().reason());
		assertEquals(Admission.Reason.UNKNOWN_PEER, context.resolve(Id.random(), "io.test.open").get().admission().reason());
	}
}
