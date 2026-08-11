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

package io.bosonnetwork.kademlia.impl;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.security.Blacklist;

/**
 * Covers the two bounds on a FIND_PEER response: {@link DHT#peersPerResponse(int)}, which limits the
 * database query the request turns into, and {@link DHT#fitPeers(List)}, which limits the bytes that go
 * back out.
 * <p>
 * Both are needed, and that is the point of this class. The requested count arrives unvalidated from the
 * wire, so without the first a stranger can make this node select and serialize its entire peer table
 * for an id. But a count cannot bound the response the way it does for node lists, because a peer entry
 * is variable-size - so without the second, a handful of large peers still overflows the datagram. Since
 * UDP source addresses are unverified, an oversized response is aimed wherever the requester says.
 * </p>
 * <p>
 * Written against the helpers rather than against a live exchange: they are pure functions of the
 * request and the stored peers, so this needs no socket, no event loop and no deployed verticle.
 * </p>
 */
public class DHTPeerResponseBudgetTests {
	// Kademlia parameters, as KadNode would pass them down from NodeConfiguration.KademliaOptions.
	private static final int K = 16;
	private static final int REPLACEMENTS = 8;
	private static final int ALPHA = 3;
	private static final int CONCURRENT_TASKS = 32;

	/** Never deployed: the packet budget comes from the network type the constructor stores. */
	private static DHT newDHT(Network network) {
		String address = network.isIPv4() ? "127.0.0.1" : "::1";
		return new DHT(new CryptoIdentity(), network, address, 39201, List.of(),
				ALPHA, K, REPLACEMENTS, CONCURRENT_TASKS,
				null, null, new TokenManager(),
				Blacklist.empty(), false, false, true, null);
	}

	private static int budgetOf(Network network) {
		return network.maxPacketSize() - KadConstants.RESPONSE_OVERHEAD;
	}

	/** A peer at the announce-time limits: the largest entry a node will now accept. */
	private static PeerInfo maximalPeer() {
		return PeerInfo.builder()
				.key(Signature.KeyPair.random())
				.endpoint("tcp://" + "a".repeat(PeerInfo.MAX_ENDPOINT_BYTES - "tcp://".length()))
				.extra(new byte[PeerInfo.MAX_EXTRA_DATA_BYTES])
				.build();
	}

	private static PeerInfo smallPeer(int port) {
		return PeerInfo.builder()
				.key(Signature.KeyPair.random())
				.endpoint("tcp://203.0.113.10:" + port)
				.build();
	}

	private static int totalSize(List<PeerInfo> peers) {
		return peers.stream().mapToInt(DHT::peerEntrySize).sum();
	}

	/**
	 * The whole finding in one case: the count is read straight off the wire, and the wire is allowed
	 * to say anything.
	 */
	@Test
	void anUnboundedRequestedCountIsClampedToTheCap() {
		DHT dht = newDHT(Network.IPv4);

		assertEquals(KadConstants.MAX_PEERS_PER_RESPONSE, dht.peersPerResponse(Integer.MAX_VALUE),
				"a requested count from the wire must never reach the storage query unclamped");
		assertEquals(KadConstants.MAX_PEERS_PER_RESPONSE, dht.peersPerResponse(1_000_000));
	}

	/** Zero and negative mean "unspecified", and must still select something. */
	@Test
	void anUnspecifiedCountGetsTheDefault() {
		DHT dht = newDHT(Network.IPv4);

		assertEquals(KadConstants.MAX_PEERS_PER_RESPONSE, dht.peersPerResponse(0));
		assertEquals(KadConstants.MAX_PEERS_PER_RESPONSE, dht.peersPerResponse(-1));
		assertEquals(KadConstants.MAX_PEERS_PER_RESPONSE, dht.peersPerResponse(Integer.MIN_VALUE));
	}

	/** A modest request is a request, not a target to round up to. */
	@Test
	void aRequestUnderTheCapIsHonored() {
		DHT dht = newDHT(Network.IPv4);

		assertEquals(1, dht.peersPerResponse(1));
		assertEquals(3, dht.peersPerResponse(3));
	}

	/**
	 * The response leaves on this DHT's own socket, so this DHT's family sets the budget - an IPv6 node
	 * has less room even when the peers it is answering with are reachable over IPv4.
	 */
	@Test
	void theBudgetFollowsTheSocketFamily() {
		assertTrue(newDHT(Network.IPv6).peersPerResponse(Integer.MAX_VALUE)
						<= newDHT(Network.IPv4).peersPerResponse(Integer.MAX_VALUE),
				"the smaller IPv6 packet budget must never select more peers than the IPv4 one");
	}

	/**
	 * The half a count cap cannot do. Every one of these peers is individually legal - each is exactly
	 * at the announce-time limits - and the count cap would let all eight through.
	 */
	@Test
	void largePeersAreTrimmedToOneDatagram() {
		DHT dht = newDHT(Network.IPv4);

		List<PeerInfo> selected = new ArrayList<>();
		for (int i = 0; i < KadConstants.MAX_PEERS_PER_RESPONSE; i++)
			selected.add(maximalPeer());

		assertTrue(totalSize(selected) > budgetOf(Network.IPv4),
				"precondition: the selection must be too large to send whole");

		List<PeerInfo> fitted = dht.fitPeers(selected);

		assertFalse(fitted.isEmpty(), "a trim that drops everything sends no peers at all");
		assertTrue(fitted.size() < selected.size(), "the oversized selection must actually be trimmed");
		assertTrue(totalSize(fitted) <= budgetOf(Network.IPv4),
				"what is left must fit one datagram, or the response fragments");
	}

	/**
	 * A peer stored before the announce-time limits existed can be larger than a datagram on its own.
	 * It is skipped rather than ending the list: it cannot be delivered over this transport whatever we
	 * do, and the peers behind it still can.
	 */
	@Test
	void aPeerTooLargeToSendIsSkippedRatherThanEndingTheList() {
		DHT dht = newDHT(Network.IPv4);

		// Built through of() rather than the builder, because the builder now rejects this - which is
		// exactly the pre-limit record this case is about.
		PeerInfo oversized = PeerInfo.of(Id.random(), null, 0, null, null, new byte[Signature.BYTES], 0,
				"tcp://203.0.113.10:1234", new byte[2048]);
		assertTrue(DHT.peerEntrySize(oversized) > budgetOf(Network.IPv4), "precondition: it must not fit");

		List<PeerInfo> behind = List.of(smallPeer(1235), smallPeer(1236));
		List<PeerInfo> selected = new ArrayList<>();
		selected.add(oversized);
		selected.addAll(behind);

		assertEquals(behind, dht.fitPeers(selected),
				"the undeliverable entry must be dropped and the deliverable ones kept");
	}

	/**
	 * Keeps the estimate honest against the encoder, the way the golden vectors do for the node entry
	 * sizes. The peers here are the ones {@code FindPeerTests} encodes, including the authenticated one,
	 * so any change to the peer encoding shows up as an under-estimate here rather than as a fragmented
	 * datagram in production.
	 */
	@Test
	void theEstimateNeverUndershootsTheEncoder() throws Exception {
		Signature.KeyPair peerKey = Signature.KeyPair.random();
		List<PeerInfo> peers = List.of(
				PeerInfo.builder().key(peerKey).fingerprint(0x1234).endpoint("tcp://203.0.113.10:65519").build(),
				PeerInfo.builder().key(peerKey).fingerprint(0x1235).endpoint("tcp://203.0.113.11:65518").build(),
				PeerInfo.builder().key(peerKey).fingerprint(0x1236).endpoint("http://abc.example.com/").build(),
				PeerInfo.builder().key(peerKey).fingerprint(0x1237).endpoint("http://foo.example.com/").build(),
				PeerInfo.builder().key(peerKey).fingerprint(0x1238).node(new CryptoIdentity())
						.endpoint("http://bar.example.com/").build());

		// Measured rather than asserted against a constant, so the encoder itself is the reference.
		int encoded = encodedSize(peers) - encodedSize(List.of());

		assertTrue(totalSize(peers) >= encoded,
				"the per-entry estimate must never come in under what the encoder actually writes: "
						+ totalSize(peers) + " estimated for " + encoded + " encoded");
	}

	private static int encodedSize(List<PeerInfo> peers) throws Exception {
		Message message = Message.findPeerResponse(0x76543210L, peers);
		message.setId(Id.random());
		return message.toBytes().length;
	}

	/** The endpoint is measured in bytes, not characters, so a non-ASCII endpoint is not undercounted. */
	@Test
	void theEndpointIsSizedInBytes() {
		// Escaped rather than written literally, so the source stays ASCII.
		String endpoint = "http://\u00e9\u00e9\u00e9.example.com/";
		PeerInfo peer = PeerInfo.of(Id.random(), null, 0, null, null, new byte[Signature.BYTES], 0,
				endpoint, null);

		assertEquals(KadConstants.PEER_ENTRY_BASE_SIZE + endpoint.getBytes(UTF_8).length,
				DHT.peerEntrySize(peer));
		assertTrue(endpoint.getBytes(UTF_8).length > endpoint.length(), "precondition: multi-byte endpoint");
	}
}
