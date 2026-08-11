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

package io.bosonnetwork.kademlia.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.kademlia.impl.Network;

/**
 * Pins {@link PeerInfo#MAX_PAYLOAD_BYTES} to what it was derived from: a peer at the limit has to
 * travel in one UDP datagram on the smaller of the two packet budgets.
 * <p>
 * The limit is a number in {@code api}, but what makes it the right number lives here - the message
 * envelope around a peer and the framing the RPC layer prepends. Nothing in {@code api} can check
 * that, so without this the derivation is a comment that stops being true the first time a field is
 * added to a message. The failure it guards is silent: oversized datagrams are lost only on paths
 * that drop fragments, and only for the largest peers.
 * </p>
 * <p>
 * A FIND_PEER response carries several peers and trims them to fit, so no single peer has to leave
 * room for others - but every peer does have to be deliverable on its own, or it is stored and never
 * served. That is what the limit guarantees and what this measures.
 * </p>
 */
class PeerSizeLimitTests {
	private static final CryptoIdentity sender = new CryptoIdentity();
	private static final Id receiver = new CryptoIdentity().getId();

	/** Exactly what {@code RpcServer.sendMessage} puts in the datagram: sender id + encrypted message. */
	private static int onWire(Message message) throws Exception {
		message.setId(sender.getId());
		return Id.BYTES + sender.encrypt(receiver, message.toBytes()).length;
	}

	private static PeerInfo maximalPeer(boolean authenticated, boolean spendOnEndpoint) {
		String endpoint = spendOnEndpoint ?
				"tcp://" + "a".repeat(PeerInfo.MAX_PAYLOAD_BYTES - "tcp://".length()) :
				"tcp://203.0.113.10:5678";
		byte[] extra = spendOnEndpoint ?
				null : Random.randomBytes(PeerInfo.MAX_PAYLOAD_BYTES - endpoint.length());

		PeerInfo.Builder builder = PeerInfo.builder().key(Signature.KeyPair.random()).endpoint(endpoint);
		if (extra != null)
			builder.extra(extra);
		if (authenticated)
			builder.node(new CryptoIdentity());

		return builder.build();
	}

	/**
	 * Both directions a peer travels, against the IPv6 budget - the smaller one, and the one whose
	 * 1280-byte minimum MTU is a hard guarantee rather than a hope about the path.
	 */
	private static void assertFitsOneDatagram(String label, PeerInfo peer) throws Exception {
		int budget = Network.IPv6.maxPacketSize();
		assertEquals(PeerInfo.MAX_PAYLOAD_BYTES, peer.payloadSize(), label + ": must be at the limit");

		int announce = onWire(Message.announcePeerRequest(peer, 0x12345678, 9));
		assertTrue(announce <= budget, label + ": an ANNOUNCE_PEER of " + announce
				+ " bytes exceeds the " + budget + "-byte IPv6 packet budget");

		int found = onWire(Message.findPeerResponse(0x76543210L, List.of(peer)));
		assertTrue(found <= budget, label + ": a FIND_PEER response of " + found
				+ " bytes exceeds the " + budget + "-byte IPv6 packet budget");
	}

	/**
	 * All four ways the budget can be spent. The authenticated cases are the binding ones - they pay
	 * for a node id and a second signature - and they are why the limit is not per-type: authentication
	 * can be added to an existing peer by an update, so an allowance that assumed its absence would be
	 * one an update could invalidate.
	 */
	@Test
	void aPeerAtTheLimitFitsOneDatagram() throws Exception {
		assertFitsOneDatagram("plain, endpoint", maximalPeer(false, true));
		assertFitsOneDatagram("plain, extra", maximalPeer(false, false));
		assertFitsOneDatagram("authenticated, endpoint", maximalPeer(true, true));
		assertFitsOneDatagram("authenticated, extra", maximalPeer(true, false));
	}

	/**
	 * The property that makes one shared budget safe where two separate limits were: what has to fit is
	 * the sum, so it cannot matter which field the bytes went to.
	 */
	@Test
	void theSplitBetweenTheTwoFieldsDoesNotChangeTheSize() throws Exception {
		int onEndpoint = onWire(Message.announcePeerRequest(maximalPeer(true, true), 0x12345678, 9));
		int onExtra = onWire(Message.announcePeerRequest(maximalPeer(true, false), 0x12345678, 9));

		assertTrue(Math.abs(onEndpoint - onExtra) <= 8,
				"the same payload split differently must cost the same, give or take CBOR framing: "
						+ onEndpoint + " vs " + onExtra);
	}
}
