package io.bosonnetwork.kademlia.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import net.datafaker.Faker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.kademlia.impl.KadConstants;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.impl.Network;
import io.bosonnetwork.kademlia.impl.TestKadContext;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.rpc.RpcCall;
import io.bosonnetwork.utils.AddressUtils;

class LookupTaskTests {
	private static final Faker faker = new Faker();
	private static final Vertx vertx = Vertx.vertx();

	private TestLookupTask task;

	static class TestLookupTask extends LookupTask<Object, TestLookupTask> {
		private static final Logger log = LoggerFactory.getLogger(TestLookupTask.class);

		public TestLookupTask(KadContext context, Id target) {
			super(context, target, false);
		}

		@Override
		protected void iterate() { super.iterate(); }

		@Override
		protected Future<RpcCall> sendCall(RpcCall call) {
			return Future.succeededFuture(call);
		}

		@Override
		protected Logger getLogger() {
			return log;
		}
	}

	/**
	 * A lookup that queries its candidates, and whose transport refuses one of them.
	 * <p>
	 * The failure is injected at the transport hook, as a failed future, because that is the shape the
	 * real one has: the DHT fails a call immediately when it is not running. It therefore arrives before
	 * {@code sendCall} returns, inside the loop that is still iterating - which is what the lookup has to
	 * survive.
	 * </p>
	 */
	static class UnsendableCandidateTask extends TestLookupTask {
		private final Id unsendable;

		UnsendableCandidateTask(KadContext context, Id target, Id unsendable) {
			super(context, target);
			this.unsendable = unsendable;
		}

		@Override
		protected void iterate() {
			super.iterate();
			while (!isCandidatesEmpty() && canDoRequest()) {
				CandidateNode cn = getNextCandidate();
				if (cn == null)
					break;

				sendCall(cn, Message.pingRequest(), c -> cn.setSent(), (c, e) -> removeCandidate(cn.getId()));
			}
		}

		@Override
		protected Future<RpcCall> sendCall(RpcCall call) {
			if (call.getTargetId().equals(unsendable))
				return Future.failedFuture(new IllegalStateException("cannot send to this candidate"));

			return super.sendCall(call);
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

	private void respondCall(RpcCall call, Message response) {
		try {
			java.lang.reflect.Method respond = RpcCall.class.getDeclaredMethod("respond", Message.class);
			respond.setAccessible(true);
			respond.invoke(call, response);
		} catch (Exception e) {
			throw new RuntimeException("respondCall failed", e);
		}
	}

	@BeforeEach
	void setUp() {
		KadContext context = new TestKadContext(vertx.getOrCreateContext(), new CryptoIdentity(), Network.IPv4);
		task = new TestLookupTask(context, Id.random());
	}

	@Test
	void testIterationCount() {
		task.addCandidates(List.of(NodeInfo.of(Id.random(), "100.1.1.8", 39001)));
		task.start();

		for (int i = 0; i < task.maxIterations; i++)
			task.iterate();

		assertTrue(task.isDone());
	}

	@Test
	void testIterationBudgetStaysAboveTheConvergenceFloor() {
		// The budget is a backstop. If it ever dropped below what convergence costs, every lookup would
		// end by exhaustion instead - and still report COMPLETED, so nothing downstream could tell.
		// k=64 is included because the slack term used to grow with k, which is backwards: convergence
		// is O(log_k N), so a larger k needs fewer rounds, not more.
		int slackAtSmallestK = -1;

		for (int k : new int[] { 8, 16, 64 }) {
			KadContext context = new TestKadContext(vertx.getOrCreateContext(), new CryptoIdentity(), Network.IPv4)
					.setK(k);
			TestLookupTask t = new TestLookupTask(context, Id.random());

			int floor = k + ClosestSet.stabilityMargin(k) + 1;
			assertEquals(floor + context.getAlpha() * KadConstants.LOOKUP_DEPTH_ALLOWANCE, t.maxIterations);
			assertTrue(t.maxIterations > floor, "the budget must leave room above the convergence floor");

			int slack = t.maxIterations - floor;
			if (slackAtSmallestK < 0)
				slackAtSmallestK = slack;
			else
				assertEquals(slackAtSmallestK, slack, "the slack above the floor must not scale with k");
		}
	}

	@Test
	void testCandidateManagement() {
		List<NodeInfo> nodes1 = new ArrayList<>(KadConstants.K * 2);
		for (int i = 0; i < KadConstants.K * 2; i++)
			nodes1.add(NodeInfo.of(Id.random(), randomAddress()));

		task.addCandidates(nodes1);
		assertEquals(KadConstants.K * 2, task.getCandidateSize());

		nodes1.stream().map(n -> task.getCandidate(n.getId())).forEach(task::addClosest);
		assertEquals(KadConstants.K, task.getClosestSet().size());

		// add again, should no any change
		List<CandidateNode> closest = List.copyOf(task.getClosestSet().entries());
		task.addCandidates(nodes1);
		assertEquals(KadConstants.K * 2, task.getCandidateSize());

		nodes1.stream().map(n -> task.getCandidate(n.getId())).forEach(task::addClosest);
		assertEquals(KadConstants.K, task.getClosestSet().size());
		List<CandidateNode> newClosest = List.copyOf(task.getClosestSet().entries());
		assertEquals(closest, newClosest);

		List<NodeInfo> nodes2 = new ArrayList<>(KadConstants.K * 2);
		for (int i = 0; i < KadConstants.K * 2; i++)
			nodes2.add(NodeInfo.of(Id.random(), randomAddress()));

		task.addCandidates(nodes2);
		assertEquals(KadConstants.K * 3, task.getCandidateSize());

		nodes2.stream().map(n -> task.getCandidate(n.getId())).filter(Objects::nonNull).forEach(task::addClosest);
		assertEquals(KadConstants.K, task.getClosestSet().size());

		List<NodeInfo> all = new ArrayList<>(nodes1);
		all.addAll(nodes2);
		Id target = task.getTarget();
		all.sort((n1, n2) -> target.threeWayCompare(n1.getId(), n2.getId()));
		assertEquals(all.subList(0, KadConstants.K * 3), task.getCandidates().entries().toList());
		assertEquals(all.subList(0, KadConstants.K), task.getClosestSet().stream().toList());
	}

	@Test
	void testRpcHandlingOnCallError() {
		NodeInfo node = NodeInfo.of(Id.random(), "100.1.1.8", 39001);
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
		NodeInfo node = NodeInfo.of(Id.random(), "100.1.1.8", 39001);
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
		NodeInfo node = NodeInfo.of(Id.random(), "100.1.1.8", 39001);
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

	private List<NodeInfo> randomNodes(int count) {
		List<NodeInfo> nodes = new ArrayList<>(count);
		for (int i = 0; i < count; i++)
			nodes.add(NodeInfo.of(Id.random(), randomAddress()));

		return nodes;
	}

	@Test
	void testEveryLookupResponseTypeCanBeRead() {
		// FindNodeResponse, FindValueResponse and FindPeerResponse are siblings under LookupResponse,
		// not subclasses of one another, so a reader that names any one of them concretely throws
		// ClassCastException on the other two - and it throws inside callResponded, where nothing
		// catches it and no timer exists to iterate the task again.
		List<NodeInfo> nodes = randomNodes(4);

		assertEquals(4, task.acceptResponse(Message.findNodeResponse(1, nodes, List.of(), 0)).size());
		assertEquals(4, task.acceptResponse(Message.findValueResponse(2, nodes, List.of())).size());
		assertEquals(4, task.acceptResponse(Message.findPeerResponse(3, nodes, List.of())).size());
	}

	@Test
	void testResponseAtTheCeilingIsTakenWhole() {
		List<NodeInfo> nodes = randomNodes(KadConstants.MAX_NODES_PER_RESPONSE);
		assertEquals(nodes, task.acceptResponse(Message.findNodeResponse(1, nodes, List.of(), 0)));
	}

	@Test
	void testResponseOverTheCeilingIsDropped() {
		// Dropped whole rather than trimmed: past the ceiling the cheap exit comes first.
		List<NodeInfo> nodes = randomNodes(KadConstants.MAX_NODES_PER_RESPONSE + 1);
		assertTrue(task.acceptResponse(Message.findNodeResponse(1, nodes, List.of(), 0)).isEmpty());
	}

	@Test
	void testOneResponseCanNotClaimMoreThanHalfTheCandidateQueue() {
		// The finding this guards: at the minimum k the queue holds 3k = 12, while the transport
		// ceiling alone would let a single answer supply 16 - every one of them closer to the target
		// than anything already queued, so the prune would evict the whole queue in favour of one
		// sender's list.
		KadContext context = new TestKadContext(vertx.getOrCreateContext(), new CryptoIdentity(), Network.IPv4)
				.setK(4);
		TestLookupTask t = new TestLookupTask(context, Id.random());

		int capacity = LookupTask.candidateCapacity(4);
		List<NodeInfo> nodes = randomNodes(KadConstants.MAX_NODES_PER_RESPONSE);
		List<NodeInfo> accepted = t.acceptResponse(Message.findNodeResponse(1, nodes, List.of(), 0));

		assertEquals(capacity / 2, accepted.size());
		assertTrue(accepted.size() < capacity, "one response must not be able to fill the queue by itself");

		// And what it keeps is the closest, which is what insertion would have kept anyway.
		List<NodeInfo> closest = new ArrayList<>(nodes);
		closest.sort((a, b) -> t.getTarget().threeWayCompare(a.getId(), b.getId()));
		assertEquals(closest.subList(0, capacity / 2), accepted);
	}

	@Test
	void testTheQuotaIsTheTighterOfTheTwoCeilings() {
		// Pinned directly, because which of the two binds flips with k and neither is obvious at a
		// glance: the transport ceiling is flat while the queue share grows as 3k/2, so the share is
		// the tighter one up to k=10 and never again after it.
		for (int k : new int[] { 4, 8, 10, 11, 16, 64 }) {
			KadContext context = new TestKadContext(vertx.getOrCreateContext(), new CryptoIdentity(), Network.IPv4)
					.setK(k);
			TestLookupTask t = new TestLookupTask(context, Id.random());

			int expected = Math.min(KadConstants.MAX_NODES_PER_RESPONSE, LookupTask.candidateCapacity(k) / 2);
			List<NodeInfo> nodes = randomNodes(KadConstants.MAX_NODES_PER_RESPONSE);
			assertEquals(expected, t.acceptResponse(Message.findNodeResponse(1, nodes, List.of(), 0)).size(),
					"quota at k=" + k);
			assertTrue(expected <= LookupTask.candidateCapacity(k) / 2, "never more than half the queue at k=" + k);
		}
	}

	@Test
	void testEmptyResponseIsTakenAsNothingToAdd() {
		assertTrue(task.acceptResponse(Message.findNodeResponse(1, List.of(), List.of(), 0)).isEmpty());
	}

	/**
	 * A dual-stack candidate is retried on its first timeout, like any other.
	 * <p>
	 * {@code Task.sendCall} narrows a node holding both families to one address before building the call,
	 * and narrowing yields a plain {@code NodeInfo} - so the call carries something that is no longer the
	 * queue's {@code CandidateNode}. Reading the candidate off the call therefore missed every dual-stack
	 * node and removed it on the first timeout instead of clearing it for retry. Bootstrap nodes are the
	 * likeliest to publish both families and the likeliest to be worth a second attempt.
	 * </p>
	 */
	@Test
	void testADualStackCandidateIsRetriedOnTimeout() {
		NodeInfo dualStack = NodeInfo.of(Id.random(), "100.1.1.8", 39001, "2001:db8::8", 39001);
		assertTrue(dualStack.hasMultiAddresses());
		task.addCandidates(List.of(dualStack));

		CandidateNode cn = task.getCandidate(dualStack.getId());
		assertNotNull(cn);
		cn.setSent();

		// What sendCall hands to the RPC layer for a node with both families: narrowed, and no longer a
		// CandidateNode.
		NodeInfo narrowed = dualStack.narrowDown(StandardProtocolFamily.INET);
		assertFalse(narrowed instanceof CandidateNode);
		task.callTimeout(new RpcCall(narrowed, Message.pingRequest()));

		assertEquals(1, task.getCandidateSize(), "one timeout must not discard a dual-stack candidate");
		assertFalse(cn.isSent(), "it must be eligible again, not merely present");
	}

	/**
	 * A response from somebody other than the node we asked is not an answer, and must not reach the
	 * closest set.
	 * <p>
	 * The closest set is the lookup's result and the target list a publish then writes to, so a stranger
	 * promoted into it is a stranger the caller stores to. The check used to run in each subclass
	 * <em>after</em> the base class had already promoted the responder, and only to skip harvesting the
	 * nodes it offered. Unreachable through the RPC server, which fails a call whose response carries a
	 * different id before it can be answered - which is the reason to get the ordering right here rather
	 * than to rely on that.
	 * </p>
	 */
	@Test
	void testAResponseFromADifferentIdIsNotPromoted() {
		NodeInfo node = NodeInfo.of(Id.random(), "100.1.1.8", 39001);
		task.addCandidates(List.of(node));
		CandidateNode cn = task.getCandidate(node.getId());
		assertNotNull(cn);

		RpcCall call = new RpcCall(cn, Message.pingRequest());
		Message response = Message.findNodeResponse(call.getTxid(), List.of(), List.of(), 0);
		response.setId(Id.random());
		respondCall(call, response);

		assertTrue(call.isIdMismatched());
		task.callResponded(call);

		assertEquals(0, task.getClosestSet().size(), "a stranger must not enter the lookup's result");
		assertEquals(0, task.getCandidateSize(), "and must not stay in the queue to be asked again");
	}

	@Test
	void testIsDoneConditions() {
		task.lookupDone = true;
		assertTrue(task.isDone());
		task.lookupDone = false;
		assertEquals(0, task.getCandidateSize());
		assertTrue(task.isDone());
	}

	/**
	 * A candidate that cannot be sent to is dropped and the lookup carries on with the rest.
	 * <p>
	 * Dropping it is what makes the iteration terminate: the candidate becomes ineligible in the
	 * {@code beforeSend} callback, which never runs when the send throws ahead of it, so a candidate left
	 * in the queue would be picked again by the very next pass. And the lookup has to survive it - one
	 * unreachable candidate is not a reason to abandon a search that still has others to ask.
	 * </p>
	 */
	@Test
	void testAnUnsendableCandidateIsDroppedAndTheLookupContinues() {
		KadContext context = new TestKadContext(vertx.getOrCreateContext(), new CryptoIdentity(), Network.IPv4);
		List<NodeInfo> nodes = new ArrayList<>();
		for (int i = 0; i < 3; i++)
			nodes.add(NodeInfo.of(Id.random(), randomAddress()));

		Id unsendable = nodes.get(1).getId();
		UnsendableCandidateTask lookup = new UnsendableCandidateTask(context, Id.random(), unsendable);
		lookup.addCandidates(nodes);

		lookup.start();

		assertNull(lookup.getCandidate(unsendable), "the candidate we could not send to must not remain queued");
		assertEquals(2, lookup.getInFlightCalls(), "the other candidates must still have been asked");
		assertEquals(Task.State.RUNNING, lookup.getState());
	}
}
