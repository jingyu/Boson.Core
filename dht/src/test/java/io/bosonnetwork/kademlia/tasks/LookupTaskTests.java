package io.bosonnetwork.kademlia.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.datafaker.Faker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.Network;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.routing.KBucket;
import io.bosonnetwork.kademlia.rpc.RpcCall;
import io.bosonnetwork.utils.AddressUtils;

class LookupTaskTests {
	private static final Faker faker = new Faker();

	private TestLookupTask task;

	static class TestLookupTask extends LookupTask<Object, TestLookupTask> {
		private static final Logger log = LoggerFactory.getLogger(TestLookupTask.class);

		public TestLookupTask(KadContext context, Id target) {
			super(context, target, false);
		}

		@Override
		protected void iterate() { super.iterate(); }

		@Override
		protected void sendCall(RpcCall call) {
			// do nothing
		}

		@Override
		protected Logger getLogger() {
			return log;
		}
	}

	private InetSocketAddress randomAddress() {
		try {
			InetAddress addr;
			do {
				addr = InetAddress.getByName(faker.internet().publicIpV4Address());
			} while (!AddressUtils.isGlobalUnicast(addr));

			return new InetSocketAddress(addr, Random.random().nextInt(1024, 65535));
		} catch (Exception e) {
			throw new RuntimeException("randomAddress", e);
		}
	}

	@BeforeEach
	void setUp() {
		KadContext context = new KadContext(null, null, new CryptoIdentity(), Network.IPv4, null);
		task = new TestLookupTask(context, Id.random());
	}

	@Test
	void testIterationCount() {
		task.addCandidates(List.of(new NodeInfo(Id.random(), "100.1.1.8", 39001)));
		task.start();

		for (int i = 0; i < LookupTask.MAX_ITERATIONS; i++)
			task.iterate();

		assertTrue(task.isDone());
	}

	@Test
	void testCandidateManagement() {
		List<NodeInfo> nodes1 = new ArrayList<>(KBucket.MAX_ENTRIES * 2);
		for (int i = 0; i < KBucket.MAX_ENTRIES * 2; i++)
			nodes1.add(new NodeInfo(Id.random(), randomAddress()));

		task.addCandidates(nodes1);
		assertEquals(KBucket.MAX_ENTRIES * 2, task.getCandidateSize());

		nodes1.stream().map(n -> task.getCandidate(n.getId())).forEach(task::addClosest);
		assertEquals(KBucket.MAX_ENTRIES, task.getClosestSet().size());

		// add again, should no any change
		List<CandidateNode> closest = List.copyOf(task.getClosestSet().entries());
		task.addCandidates(nodes1);
		assertEquals(KBucket.MAX_ENTRIES * 2, task.getCandidateSize());

		nodes1.stream().map(n -> task.getCandidate(n.getId())).forEach(task::addClosest);
		assertEquals(KBucket.MAX_ENTRIES, task.getClosestSet().size());
		List<CandidateNode> newClosest = List.copyOf(task.getClosestSet().entries());
		assertEquals(closest, newClosest);

		List<NodeInfo> nodes2 = new ArrayList<>(KBucket.MAX_ENTRIES * 2);
		for (int i = 0; i < KBucket.MAX_ENTRIES * 2; i++)
			nodes2.add(new NodeInfo(Id.random(), randomAddress()));

		task.addCandidates(nodes2);
		assertEquals(KBucket.MAX_ENTRIES * 3, task.getCandidateSize());

		nodes2.stream().map(n -> task.getCandidate(n.getId())).filter(Objects::nonNull).forEach(task::addClosest);
		assertEquals(KBucket.MAX_ENTRIES, task.getClosestSet().size());

		List<NodeInfo> all = new ArrayList<>(nodes1);
		all.addAll(nodes2);
		Id target = task.getTarget();
		all.sort((n1, n2) -> target.threeWayCompare(n1.getId(), n2.getId()));
		assertEquals(all.subList(0, KBucket.MAX_ENTRIES * 3), task.getCandidates().entries().toList());
		assertEquals(all.subList(0, KBucket.MAX_ENTRIES), task.getClosestSet().stream().toList());
	}

	@Test
	void testRpcHandlingOnCallError() {
		NodeInfo node = new NodeInfo(Id.random(), "100.1.1.8", 39001);
		task.addCandidates(List.of(node));
		assertEquals(1, task.getCandidateSize());
		CandidateNode cn = task.getCandidate(node.getId());
		assertNotNull(cn);
		RpcCall call = new RpcCall(cn, Message.pingRequest());
		task.callError(call);
		assertEquals(0, task.getCandidateSize());
		task.addCandidates(List.of(node));
		// should be rejected due to this candidate already processed
		assertEquals(0, task.getCandidateSize());
		assertNull(task.getCandidate(node.getId()));
	}

	@Test
	void testRpcHandlingOnCallTimeoutReachable() {
		NodeInfo node = new NodeInfo(Id.random(), "100.1.1.8", 39001);
		task.addCandidates(List.of(node));
		assertEquals(1, task.getCandidateSize());
		CandidateNode cn = task.getCandidate(node.getId());
		assertNotNull(cn);
		assertFalse(cn.isUnreachable());
		RpcCall call = new RpcCall(cn, Message.pingRequest());
		task.callTimeout(call);
		assertFalse(cn.isSent());
		assertEquals(1, task.getCandidateSize());
		task.addCandidates(List.of(node));
		assertEquals(1, task.getCandidateSize());
		assertNotNull(task.getCandidate(node.getId()));
	}

	@Test
	void testRpcHandlingOnCallTimeoutUnreachable() {
		NodeInfo node = new NodeInfo(Id.random(), "100.1.1.8", 39001);
		task.addCandidates(List.of(node));
		assertEquals(1, task.getCandidateSize());
		CandidateNode cn = task.getCandidate(node.getId());
		assertNotNull(cn);
		cn.setSent();
		cn.setSent();
		cn.setSent();
		assertTrue(cn.isUnreachable());
		RpcCall call = new RpcCall(cn, Message.pingRequest());
		task.callTimeout(call);
		assertEquals(0, task.getCandidateSize());
		task.addCandidates(List.of(node));
		// should be rejected due to this candidate already processed
		assertEquals(0, task.getCandidateSize());
		assertNull(task.getCandidate(node.getId()));
	}

	@Test
	void testIsDoneConditions() {
		task.lookupDone = true;
		assertTrue(task.isDone());
		task.lookupDone = false;
		assertEquals(0, task.getCandidateSize());
		assertTrue(task.isDone());
	}
}