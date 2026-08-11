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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.CryptoBox;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.kademlia.impl.Network;

/**
 * Pins the three {@code Value} data limits to the thing they were derived from: a value at its limit
 * has to travel in one UDP datagram on the smaller of the two packet budgets.
 * <p>
 * The limits are numbers in {@code api}, but what makes them the right numbers lives here - the
 * message envelope around a value and the framing the RPC layer prepends. Nothing in {@code api} can
 * check that, so without this test the derivation is a comment that stops being true the first time a
 * field is added to a message. That is the failure mode worth guarding: it is silent, it only shows
 * up as lost datagrams on paths that drop fragments, and it shows up for the largest values first.
 * </p>
 * <p>
 * The three limits differ because the three envelopes do, which is the whole reason for having three.
 * Each is checked at its own limit, so a change that grows only one envelope is caught even though
 * the others still fit.
 * </p>
 */
class ValueSizeLimitTests {
	private static final CryptoIdentity sender = new CryptoIdentity();
	private static final Id receiver = new CryptoIdentity().getId();

	/** Exactly what {@code RpcServer.sendMessage} puts in the datagram: sender id + encrypted message. */
	private static int onWire(Message message) throws Exception {
		message.setId(sender.getId());
		return Id.BYTES + sender.encrypt(receiver, message.toBytes()).length;
	}

	/**
	 * Both directions a value travels, against the IPv6 budget - the smaller one, and the one whose
	 * 1280-byte minimum MTU is a hard guarantee rather than a hope about the path.
	 */
	private static void assertFitsOneDatagram(String label, Value value) throws Exception {
		int budget = Network.IPv6.maxPacketSize();

		int store = onWire(Message.storeValueRequest(value, 0x12345678, 9));
		assertTrue(store <= budget, label + ": a STORE_VALUE of " + store
				+ " bytes exceeds the " + budget + "-byte IPv6 packet budget");

		int found = onWire(Message.findValueResponse(0x76543210L, value));
		assertTrue(found <= budget, label + ": a FIND_VALUE response of " + found
				+ " bytes exceeds the " + budget + "-byte IPv6 packet budget");
	}

	private static Value maximalImmutable() {
		return Value.immutableBuilder().data(Random.randomBytes(Value.MAX_IMMUTABLE_DATA_BYTES)).build();
	}

	private static Value maximalMutable() {
		return Value.signedBuilder().data(Random.randomBytes(Value.MAX_MUTABLE_DATA_BYTES)).build();
	}

	private static Value maximalEncrypted() {
		return Value.encryptedBuilder()
				.recipient(Id.of(Signature.KeyPair.random().publicKey().bytes()))
				.data(Random.randomBytes(Value.MAX_ENCRYPTED_DATA_BYTES - CryptoBox.MAC_BYTES))
				.build();
	}

	@Test
	void eachTypeAtItsOwnLimitFitsOneDatagram() throws Exception {
		assertFitsOneDatagram("immutable", maximalImmutable());
		assertFitsOneDatagram("mutable", maximalMutable());
		assertFitsOneDatagram("encrypted", maximalEncrypted());
	}

	/**
	 * The limits exist in the order the envelopes do. Asserted rather than assumed, because getting
	 * this backwards would hand the largest allowance to the type with the least room for it - which
	 * no single test above would catch, since each only checks its own type.
	 */
	@Test
	void theLimitsDecreaseAsTheEnvelopeGrows() {
		assertTrue(Value.MAX_IMMUTABLE_DATA_BYTES > Value.MAX_MUTABLE_DATA_BYTES,
				"an immutable value carries no owner id or signature, so it must get the most room");
		assertTrue(Value.MAX_MUTABLE_DATA_BYTES > Value.MAX_ENCRYPTED_DATA_BYTES,
				"an encrypted value pays for a recipient id, a nonce and a MAC on top of a mutable one");

		assertEquals(Value.MAX_IMMUTABLE_DATA_BYTES, maximalImmutable().maxDataBytes());
		assertEquals(Value.MAX_MUTABLE_DATA_BYTES, maximalMutable().maxDataBytes());
		assertEquals(Value.MAX_ENCRYPTED_DATA_BYTES, maximalEncrypted().maxDataBytes());
	}

	/**
	 * The limit bounds the stored field, and for an encrypted value that field is the ciphertext. A
	 * caller passing the full limit as plaintext would produce a value 16 bytes over it, so it is
	 * refused rather than quietly stored at a length the receiving side will reject.
	 */
	@Test
	void anEncryptedValuePaysForItsMac() {
		Id recipient = Id.of(Signature.KeyPair.random().publicKey().bytes());

		Value atLimit = maximalEncrypted();
		assertTrue(atLimit.isValid());
		assertEquals(Value.MAX_ENCRYPTED_DATA_BYTES, atLimit.getData().length,
				"the stored ciphertext, not the plaintext, is what has to fit");

		byte[] overLimit = Random.randomBytes(Value.MAX_ENCRYPTED_DATA_BYTES - CryptoBox.MAC_BYTES + 1);
		assertThrows(IllegalArgumentException.class,
				() -> Value.encryptedBuilder().recipient(recipient).data(overLimit).build());
	}

	/**
	 * What keeps three limits from interacting: a value's type is fixed when it is created, so it is
	 * judged by one limit for its whole life. Were an update able to add a recipient, a mutable value
	 * at its own limit would land 64 bytes over the encrypted one.
	 */
	@Test
	void aValueCannotBeUpdatedIntoASmallerLimit() {
		Value mutable = maximalMutable();
		assertTrue(mutable.isValid());
		assertTrue(mutable.getData().length > Value.MAX_ENCRYPTED_DATA_BYTES - CryptoBox.MAC_BYTES,
				"precondition: this value would not fit the encrypted limit");

		Id recipient = Id.of(Signature.KeyPair.random().publicKey().bytes());
		assertThrows(UnsupportedOperationException.class, () -> mutable.update().recipient(recipient));

		Value updated = mutable.update().data(Random.randomBytes(Value.MAX_MUTABLE_DATA_BYTES)).build();
		assertEquals(Value.MAX_MUTABLE_DATA_BYTES, updated.maxDataBytes(), "the type, and so the limit, is stable");
		assertTrue(updated.isValid());
	}

	/**
	 * A sender that does not use this builder can sign whatever length it likes, so the receiving side
	 * has to re-check rather than trust that a well-formed signature implies a sendable value. Each
	 * type is judged by its own limit, so a length legal for one can be illegal for another.
	 */
	@Test
	void anOverLongValueFromTheWireIsInvalid() {
		byte[] data = Random.randomBytes(Value.MAX_IMMUTABLE_DATA_BYTES + 1);

		Value hostile = Value.of(Id.random(), data);
		assertFalse(hostile.isValid(), "an over-length value must be rejected however it was built");

		assertThrows(IllegalArgumentException.class, () -> Value.immutableBuilder().data(data).build());

		// Legal for an immutable value, too long for a mutable one - the limits are not interchangeable.
		byte[] between = Random.randomBytes(Value.MAX_MUTABLE_DATA_BYTES + 1);
		assertTrue(Value.immutableBuilder().data(between).build().isValid());
		assertThrows(IllegalArgumentException.class, () -> Value.signedBuilder().data(between).build());
	}
}
