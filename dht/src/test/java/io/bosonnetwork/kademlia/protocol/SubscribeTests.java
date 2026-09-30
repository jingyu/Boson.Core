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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.CryptoBox;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.kademlia.exceptions.KadException;
import io.bosonnetwork.kademlia.exceptions.SubscriptionRefusedException;
import io.bosonnetwork.kademlia.exceptions.UnknownSubscriptionException;
import io.bosonnetwork.kademlia.impl.ErrorCode;
import io.bosonnetwork.kademlia.impl.Network;

public class SubscribeTests extends MessageTests {
	private Message roundTrip(Message msg) throws Exception {
		Id nodeId = Id.random();
		msg.setId(nodeId);
		byte[] bin = msg.toBytes();
		printMessage(msg);

		Message parsed = Message.parse(bin);
		parsed.setId(nodeId);
		assertEquals(msg, parsed);
		assertArrayEquals(bin, parsed.toBytes());

		Message fromJson = Message.parse(msg.toJson());
		fromJson.setId(nodeId);
		assertEquals(msg, fromJson);
		return parsed;
	}

	@Test
	void testValueSubscribe() throws Exception {
		Id target = Id.random();
		Message msg = Message.subscribeRequest(target, SubscriptionKind.VALUE, 0x87654321, 7, 600);
		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.SUBSCRIBE, msg.getMethod());

		SubscribeRequest body = roundTrip(msg).getBody();
		assertEquals(target, body.getTarget());
		assertEquals(SubscriptionKind.VALUE, body.getKind());
		assertEquals(0x87654321, body.getToken());
		assertEquals(7, body.getKnownSequenceNumber());
		assertEquals(600, body.getLease());
		assertFalse(body.isCancel());
	}

	@Test
	void testValueSubscribeHoldingNothingAndDefaultLease() throws Exception {
		SubscribeRequest body = roundTrip(Message.subscribeRequest(Id.random(), SubscriptionKind.VALUE, 5, -1, -1)).getBody();
		assertEquals(-1, body.getKnownSequenceNumber());
		assertEquals(-1, body.getLease());
	}

	@Test
	void testPeerSubscribeCarriesNoKnownSequence() throws Exception {
		Message msg = Message.subscribeRequest(Id.random(), SubscriptionKind.PEER, 5, 7, 300);
		SubscribeRequest body = roundTrip(msg).getBody();
		assertEquals(SubscriptionKind.PEER, body.getKind());
		assertEquals(-1, body.getKnownSequenceNumber());
		assertFalse(msg.toJson().contains("\"cas\""), "a peer subscription must not put cas on the wire");
	}

	@Test
	void testCancelCarriesNoToken() throws Exception {
		Message msg = Message.subscribeRequest(Id.random(), SubscriptionKind.VALUE, 0, -1, 0);
		SubscribeRequest body = roundTrip(msg).getBody();
		assertTrue(body.isCancel());
		assertEquals(0, body.getToken());
		assertFalse(msg.toJson().contains("\"tok\""));
	}

	@Test
	void testSubscribeResponse() throws Exception {
		Message msg = Message.subscribeResponse(0x78901234L, -12345678, 600);
		assertEquals(Message.Type.RESPONSE, msg.getType());
		SubscribeResponse body = roundTrip(msg).getBody();
		assertEquals(-12345678, body.getToken());
		assertEquals(600, body.getLease());

		// The answer to a cancel: nothing to carry.
		SubscribeResponse cancel = roundTrip(Message.subscribeResponse(0x78901235L, 0, 0)).getBody();
		assertEquals(0, cancel.getToken());
		assertEquals(0, cancel.getLease());
	}

	@Test
	void testNotifyValue() throws Exception {
		for (Value value : new Value[] {
				Value.immutableBuilder().data("immutable".getBytes()).build(),
				Value.signedBuilder().sequenceNumber(3).data("signed".getBytes()).build(),
				Value.encryptedBuilder().recipient(Id.of(Signature.KeyPair.random().publicKey().bytes()))
						.sequenceNumber(9).data("encrypted".getBytes()).build() }) {
			Message msg = Message.notifyValueRequest(value);
			assertEquals(Message.Method.NOTIFY_VALUE, msg.getMethod());
			assertEquals(value, roundTrip(msg).<NotifyValueRequest>getBody().getValue());
		}

		Message ack = roundTrip(Message.notifyResponse(Message.Method.NOTIFY_VALUE, 0x78901234L));
		assertNull(ack.getBody());
	}

	@Test
	void testNotifyPeer() throws Exception {
		for (PeerInfo peer : new PeerInfo[] {
				PeerInfo.builder().sequenceNumber(6).fingerprint(1000).endpoint("tcp://203.0.113.10:3456").build(),
				PeerInfo.builder().node(new CryptoIdentity()).fingerprint(-1234).sequenceNumber(9)
						.endpoint("tcp://203.0.113.10:3456").extra(Map.of("foo", "bar")).build() }) {
			Message msg = Message.notifyPeerRequest(peer);
			assertEquals(Message.Method.NOTIFY_PEER, msg.getMethod());
			assertEquals(peer, roundTrip(msg).<NotifyPeerRequest>getBody().getPeer());
		}

		Message ack = roundTrip(Message.notifyResponse(Message.Method.NOTIFY_PEER, 0x78901234L));
		assertNull(ack.getBody());
	}

	@Test
	void testNotifyResponseRefusesOtherMethods() {
		assertThrows(IllegalArgumentException.class, () -> Message.notifyResponse(Message.Method.STORE_VALUE, 1));
	}

	private static String subscribeJson(String body) {
		return "{\"y\":" + (Message.Type.REQUEST.value() | Message.Method.SUBSCRIBE.value()) +
				",\"t\":1,\"q\":{\"t\":\"" + Id.random() + "\"" + body + "}}";
	}

	@Test
	void testMalformedSubscribeIsRejected() {
		// Well-formed, as a control.
		Message.parse(subscribeJson(",\"m\":1,\"tok\":5"));

		assertThrows(IllegalArgumentException.class, () -> Message.parse(subscribeJson(",\"m\":3,\"tok\":5")),
				"unknown kind");
		assertThrows(IllegalArgumentException.class, () -> Message.parse(subscribeJson(",\"tok\":5")),
				"missing kind");
		assertThrows(IllegalArgumentException.class, () -> Message.parse(subscribeJson(",\"m\":1,\"cas\":-1")),
				"negative cas");
		assertThrows(IllegalArgumentException.class, () -> Message.parse(subscribeJson(",\"m\":1,\"l\":65536")),
				"lease beyond u16");
	}

	@Test
	void testErrorCodes() {
		assertEquals(403, ErrorCode.SubscriptionRefused.value());
		assertEquals(404, ErrorCode.UnknownSubscription.value());
		assertInstanceOf(SubscriptionRefusedException.class, KadException.fromErrorCode(403, "x"));
		assertInstanceOf(UnknownSubscriptionException.class, KadException.fromErrorCode(404, "x"));
	}

	private static final CryptoIdentity sender = new CryptoIdentity();
	private static final Id receiver = new CryptoIdentity().getId();

	private static int onWire(Message message) throws Exception {
		message.setId(sender.getId());
		return Id.BYTES + sender.encrypt(receiver, message.toBytes()).length;
	}

	/**
	 * The single-datagram guarantee: a notification carries the record as the write that stored it did,
	 * minus the token and cas, so a record that fitted when written always fits when notified. Checked at
	 * each value type's size limit, against the smaller packet budget.
	 */
	@Test
	void testANotificationIsNeverLargerThanTheWriteThatStoredTheRecord() throws Exception {
		int budget = Network.IPv6.maxPacketSize();
		Value[] maximal = {
				Value.immutableBuilder().data(Random.randomBytes(Value.MAX_IMMUTABLE_DATA_BYTES)).build(),
				Value.signedBuilder().sequenceNumber(Integer.MAX_VALUE)
						.data(Random.randomBytes(Value.MAX_MUTABLE_DATA_BYTES)).build(),
				Value.encryptedBuilder().recipient(Id.of(Signature.KeyPair.random().publicKey().bytes()))
						.sequenceNumber(Integer.MAX_VALUE)
						.data(Random.randomBytes(Value.MAX_ENCRYPTED_DATA_BYTES - CryptoBox.MAC_BYTES)).build() };

		for (Value value : maximal) {
			// The smallest store that could have written it: a one-byte token, no cas.
			int store = onWire(Message.storeValueRequest(value, 1, -1));
			int notify = onWire(Message.notifyValueRequest(value));
			assertTrue(notify <= store, "NOTIFY_VALUE of " + notify + " bytes exceeds the STORE_VALUE of " + store);
			assertTrue(notify <= budget, "NOTIFY_VALUE of " + notify + " bytes exceeds the " + budget + "-byte budget");
		}

		PeerInfo peer = PeerInfo.builder().node(new CryptoIdentity()).fingerprint(Long.MIN_VALUE)
				.sequenceNumber(Integer.MAX_VALUE).endpoint("tcp://203.0.113.10:3456")
				.extra(Map.of("foo", "bar")).build();
		int announce = onWire(Message.announcePeerRequest(peer, 1, -1));
		int notify = onWire(Message.notifyPeerRequest(peer));
		assertTrue(notify <= announce, "NOTIFY_PEER of " + notify + " bytes exceeds the ANNOUNCE_PEER of " + announce);
	}
}
