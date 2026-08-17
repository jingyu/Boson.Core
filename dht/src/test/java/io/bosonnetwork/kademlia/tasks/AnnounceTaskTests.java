package io.bosonnetwork.kademlia.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.vertx.core.Vertx;
import net.datafaker.Faker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.AnnounceResult;
import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.kademlia.exceptions.InvalidTokenException;
import io.bosonnetwork.kademlia.exceptions.InvalidValueException;
import io.bosonnetwork.kademlia.exceptions.SequenceNotExpectedException;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.impl.Network;
import io.bosonnetwork.kademlia.impl.TestKadContext;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.rpc.RpcCall;

/**
 * The accounting that decides whether a store or an announce actually happened.
 * <p>
 * Before this existed, both tasks ignored every reply: a publish that reached no node completed exactly
 * like one that reached all of them, and the caller's future succeeded either way.
 * <p>
 * Calls are settled through {@code RpcCall}'s own state machine rather than by invoking the task
 * callbacks, so these exercise the real path - the listener the task registers, and the iteration it
 * drives afterwards. Timeouts are the exception: reaching {@code TIMEOUT} needs the call's timer, so those
 * invoke the callback directly, where only the counter is under test.
 */
class AnnounceTaskTests {
	private static final Faker faker = new Faker();
	private static final Vertx vertx = Vertx.vertx();

	private KadContext context;
	private Id target;
	private List<RpcCall> sent;

	/** Captures what the task sends instead of putting it on a socket. */
	class TestValueAnnounceTask extends ValueAnnounceTask {
		TestValueAnnounceTask(KadContext context, Value value, int expectedSequenceNumber) {
			super(context, value, expectedSequenceNumber);
		}

		@Override
		protected void sendCall(RpcCall call) {
			sent.add(call);
		}
	}

	class TestPeerAnnounceTask extends PeerAnnounceTask {
		TestPeerAnnounceTask(KadContext context, PeerInfo peer, int expectedSequenceNumber) {
			super(context, peer, expectedSequenceNumber);
		}

		@Override
		protected void sendCall(RpcCall call) {
			sent.add(call);
		}
	}

	@BeforeEach
	void setUp() {
		context = new TestKadContext(vertx.getOrCreateContext(), new CryptoIdentity(), Network.IPv4);
		target = Id.random();
		sent = new ArrayList<>();
	}

	private void invoke(RpcCall call, String method, Class<?> argType, Object arg) {
		try {
			Method m = RpcCall.class.getDeclaredMethod(method, argType);
			m.setAccessible(true);
			m.invoke(call, arg);
		} catch (Exception e) {
			throw new RuntimeException(method + " failed", e);
		}
	}

	/** Settles the call as answered, driving the task's listener exactly as the RPC layer would. */
	private void respondCall(RpcCall call, Message response) {
		invoke(call, "respond", Message.class, response);
	}

	/** Settles the call as refused, carrying the typed cause the task classifies on. */
	private void failCall(RpcCall call, Throwable cause) {
		invoke(call, "fail", Throwable.class, cause);
	}

	private ClosestSet closestWithTokens(int count) {
		ClosestSet closest = new ClosestSet(target, context.getK());
		for (int i = 0; i < count; i++) {
			CandidateNode cn = new CandidateNode(
					NodeInfo.of(Id.random(), faker.internet().getPublicIpV4Address(), 39001));
			cn.setToken(Random.random().nextInt());
			closest.add(cn);
		}
		return closest;
	}

	private ClosestSet closestWithoutTokens(int count) {
		ClosestSet closest = new ClosestSet(target, context.getK());
		for (int i = 0; i < count; i++)
			closest.add(new CandidateNode(
					NodeInfo.of(Id.random(), faker.internet().getPublicIpV4Address(), 39001)));

		return closest;
	}

	private TestValueAnnounceTask storeTask(int candidates, int expectedSequenceNumber) {
		Value value = Value.of(Id.random(), Random.randomBytes(64));
		TestValueAnnounceTask task = new TestValueAnnounceTask(context, value, expectedSequenceNumber);
		task.closest(closestWithTokens(candidates));
		task.start();
		return task;
	}

	private AnnounceResult.Target targetOf(AnnounceResult result, Id nodeId) {
		return result.targets().stream()
				.filter(t -> t.nodeId().equals(nodeId))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no entry for " + nodeId));
	}

	@Test
	void testEveryNodeAcknowledgingIsASuccess() {
		// Not an aspirational case. Every target answered a lookup seconds earlier and handed out a
		// write token good for minutes, so this is what a healthy network is expected to produce.
		TestValueAnnounceTask task = storeTask(3, -1);
		assertEquals(3, sent.size());

		for (RpcCall call : sent)
			respondCall(call, Message.storeValueResponse(call.getTxid()));

		AnnounceResult result = task.getResult();
		assertEquals(AnnounceResult.Status.SUCCESS, result.status());
		assertEquals(3, result.acknowledged());
		assertTrue(result.isAnnounced());
	}

	@Test
	void testOneRefusalCanNotVetoTheAnnounce() {
		// The regression guard. A refusal used to abandon the queue and fail the caller, which handed
		// any one of the k closest nodes a veto over the publish: answer STORE_VALUE with "invalid
		// value" and the other nodes never hear of it. One node's answer is evidence, not a verdict.
		TestValueAnnounceTask task = storeTask(5, -1);
		assertEquals(3, sent.size());

		failCall(sent.get(0), new InvalidValueException("Invalid value for STORE VALUE request"));
		assertEquals(4, sent.size(), "the freed slot must still go to the next candidate");

		for (int i = 1; i < sent.size(); i++)
			respondCall(sent.get(i), Message.storeValueResponse(sent.get(i).getTxid()));

		AnnounceResult result = task.getResult();
		assertEquals(AnnounceResult.Status.PARTIAL_SUCCESS, result.status());
		assertTrue(result.isAnnounced(), "one liar must not be able to unpublish a value");
		assertEquals(4, result.acknowledged());
	}

	@Test
	void testALostCompareAndSetIsReportedPerNodeAndDoesNotStopTheWrite() {
		TestValueAnnounceTask task = storeTask(5, 7);
		Id refuser = sent.get(0).getTargetId();

		SequenceNotExpectedException lost = new SequenceNotExpectedException("Sequence number not expected");
		failCall(sent.get(0), lost);

		// The typed cause survives to the caller - it is the answer a caller that supplied a sequence
		// number asked for - but it arrives as one node's claim among several rather than as a verdict.
		AnnounceResult.Target target = targetOf(task.getResult(), refuser);
		assertEquals(AnnounceResult.Outcome.REFUSED, target.outcome());
		assertSame(lost, target.cause());
		assertEquals(4, sent.size(), "the announce continues");
	}

	@Test
	void testEveryNodeRefusingIsAFailureThatKeepsTheirReasons() {
		TestValueAnnounceTask task = storeTask(3, -1);

		for (RpcCall call : sent)
			failCall(call, new InvalidTokenException("Invalid token for STORE VALUE request"));

		AnnounceResult result = task.getResult();
		assertEquals(AnnounceResult.Status.FAILED, result.status());
		assertFalse(result.isAnnounced());
		assertEquals(3, result.targets().size());
		result.targets().forEach(t -> assertEquals(AnnounceResult.Outcome.REFUSED, t.outcome()));
	}

	@Test
	void testUnanimousRefusalIsOfferedAsACauseAndAMixedOneIsNot() {
		// So a caller can still write catch (SequenceNotExpectedException) for the case the network
		// agrees on, without any single node being able to produce that outcome by itself.
		TestValueAnnounceTask agreed = storeTask(3, 7);
		for (RpcCall call : sent)
			failCall(call, new SequenceNotExpectedException("Sequence number not expected"));

		assertInstanceOf(SequenceNotExpectedException.class, agreed.getResult().unanimousRefusal());

		sent.clear();
		TestValueAnnounceTask mixed = storeTask(3, 7);
		failCall(sent.get(0), new SequenceNotExpectedException("Sequence number not expected"));
		failCall(sent.get(1), new InvalidTokenException("Invalid token for STORE VALUE request"));
		failCall(sent.get(2), new SequenceNotExpectedException("Sequence number not expected"));

		assertNull(mixed.getResult().unanimousRefusal(), "disagreement is not a network verdict");
	}

	@Test
	void testEveryNodeTimingOutIsAFailure() {
		TestValueAnnounceTask task = storeTask(3, -1);

		for (RpcCall call : sent)
			task.callTimeout(call);

		AnnounceResult result = task.getResult();
		assertEquals(AnnounceResult.Status.FAILED, result.status());
		result.targets().forEach(t -> assertEquals(AnnounceResult.Outcome.TIMED_OUT, t.outcome()));
		assertNull(result.unanimousRefusal(), "a silence is not a refusal");
	}

	@Test
	void testCandidatesWithoutATokenAreRecordedNotSilentlyDropped() {
		// The path that used to report success having sent nothing at all.
		Value value = Value.of(Id.random(), Random.randomBytes(64));
		TestValueAnnounceTask task = new TestValueAnnounceTask(context, value, -1);
		task.closest(closestWithoutTokens(3));
		task.start();

		assertEquals(0, sent.size());

		AnnounceResult result = task.getResult();
		// FAILED rather than NO_TARGETS: the lookup did find nodes, we simply had no token to write
		// with. A network that was there and did not take the payload is a failure; only an empty
		// target list means nobody was found to ask.
		assertEquals(AnnounceResult.Status.FAILED, result.status());
		assertTrue(result.isFailure());
		assertEquals(3, result.targets().size(), "a node we never asked still belongs in the account");
		result.targets().forEach(t -> assertEquals(AnnounceResult.Outcome.NOT_SENT, t.outcome()));
	}

	@Test
	void testPeerAnnounceKeepsTheSameAccounts() {
		PeerInfo peer = PeerInfo.builder().endpoint("tcp:///203.0.113.10:39001").build();
		TestPeerAnnounceTask task = new TestPeerAnnounceTask(context, peer, -1);
		task.closest(closestWithTokens(2));
		task.start();

		assertEquals(2, sent.size());
		assertFalse(task.isAnnounced());

		// Both have to settle before the aggregate means anything: a call still in flight has no
		// outcome yet and is simply absent, which is why the status is only read once the task ends.
		respondCall(sent.get(0), Message.announcePeerResponse(sent.get(0).getTxid()));
		task.callTimeout(sent.get(1));

		AnnounceResult result = task.getResult();
		assertEquals(AnnounceResult.Status.PARTIAL_SUCCESS, result.status());
		assertEquals(1, result.acknowledged());
		assertTrue(task.isAnnounced());
	}

	@Test
	void testNoNodeToAskIsNotAFailure() {
		// A node still bootstrapping, or one whose network really contains only itself, finds an empty
		// closest set and the announce is cancelled without running. Nothing refused - nothing was
		// asked - so this must not reach the caller as an error, or every publish on a starting node
		// throws. It is still not announced: the two questions are separate, which is the whole reason
		// the status exists.
		Value value = Value.of(Id.random(), Random.randomBytes(64));
		TestValueAnnounceTask task = new TestValueAnnounceTask(context, value, -1);
		task.cancel();

		assertEquals(0, sent.size());
		AnnounceResult result = task.getResult();
		assertEquals(AnnounceResult.Status.NO_TARGETS, result.status());
		assertTrue(result.targets().isEmpty());
		assertFalse(result.isAnnounced(), "nothing reached the network");
		assertFalse(result.isFailure(), "but nothing went wrong either");
	}

	@Test
	void testAskingAndBeingRefusedIsAFailureUnlikeAskingNobody() {
		// The pair that pins the distinction. Same zero acknowledgements, opposite reporting.
		TestValueAnnounceTask asked = storeTask(3, -1);
		for (RpcCall call : sent)
			failCall(call, new InvalidTokenException("Invalid token for STORE VALUE request"));

		assertEquals(AnnounceResult.Status.FAILED, asked.getResult().status());
		assertTrue(asked.getResult().isFailure());

		Value value = Value.of(Id.random(), Random.randomBytes(64));
		TestValueAnnounceTask neverAsked = new TestValueAnnounceTask(context, value, -1);
		neverAsked.cancel();

		assertEquals(AnnounceResult.Status.NO_TARGETS, neverAsked.getResult().status());
		assertFalse(neverAsked.getResult().isFailure());
	}


	@Test
	void testMergeAcrossAddressFamiliesTakesTheUnion() {
		// A dual-stack node whose IPv6 has no reachable peers is an ordinary deployment, so a family
		// that reached nobody must leave the publish a partial success rather than a failure. The gap
		// that let announcePeer keep using Future.all after storeValue stopped.
		Id acked = Id.random();
		AnnounceResult v4 = AnnounceResult.of(List.of(
				new AnnounceResult.Target(acked, AnnounceResult.Outcome.ACKNOWLEDGED, null)));
		AnnounceResult v6 = AnnounceResult.of(List.of(
				new AnnounceResult.Target(Id.random(), AnnounceResult.Outcome.TIMED_OUT, null)));

		assertEquals(AnnounceResult.Status.SUCCESS, v4.status());
		assertEquals(AnnounceResult.Status.FAILED, v6.status());

		AnnounceResult merged = AnnounceResult.merge(v4, v6);
		assertEquals(AnnounceResult.Status.PARTIAL_SUCCESS, merged.status());
		assertTrue(merged.isAnnounced());
		assertEquals(2, merged.targets().size());
		assertEquals(1, merged.acknowledged());
	}
}
