/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
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

package io.bosonnetwork.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.utils.Hex;

public class SignatureTests {
	@Test
	public void keyPairFromSeed() {
		var seed = new byte[Signature.KeyPair.SEED_BYTES];
		Random.random().nextBytes(seed);

		var kp = Signature.KeyPair.fromSeed(seed);
		var kp2 = Signature.KeyPair.fromSeed(seed);

		assertEquals(kp.privateKey(), kp2.privateKey());
		assertEquals(kp.publicKey(), kp2.publicKey());

		assertEquals(Signature.PrivateKey.BYTES, kp.privateKey().bytes().length);
		assertEquals(Signature.PublicKey.BYTES, kp.publicKey().bytes().length);
	}

	@Test
	public void testEqualityAndRecovery() {
		var kp = Signature.KeyPair.random();
		var otherKp1 = Signature.KeyPair.fromPrivateKey(kp.privateKey());
		var otherKp2 = Signature.KeyPair.fromPrivateKey(kp.privateKey().bytes());

		assertEquals(kp, otherKp1);
		assertEquals(kp, otherKp2);

		assertEquals(Signature.PrivateKey.BYTES, kp.privateKey().bytes().length);
		assertEquals(Signature.PublicKey.BYTES, kp.publicKey().bytes().length);
	}

	@Test
	public void testKeyBytes() {
		var kp = Signature.KeyPair.random();
		var sk = Signature.PrivateKey.fromBytes(kp.privateKey().bytes());
		var pk = Signature.PublicKey.fromBytes(kp.publicKey().bytes());

		assertEquals(kp.privateKey(), sk);
		assertEquals(kp.publicKey(), pk);

		assertArrayEquals(kp.privateKey().bytes(), sk.bytes());
		assertArrayEquals(kp.publicKey().bytes(), pk.bytes());
	}

	@Test
	public void checkSignAndVerify() {
		var kp = Signature.KeyPair.random();
		var sig = Signature.sign(Hex.decode("deadbeef"), kp.privateKey());
		assertEquals(Signature.BYTES, sig.length);

		var result = Signature.verify(Hex.decode("deadbeef"), sig, kp.publicKey());
		assertTrue(result);
	}

	@Test
	public void checkSignAndVerifyWithKey() {
		var kp = Signature.KeyPair.random();
		var sig = kp.privateKey().sign(Hex.decode("deadbeef"));
		assertEquals(Signature.BYTES, sig.length);

		var result = kp.publicKey().verify(Hex.decode("deadbeef"), sig);
		assertTrue(result);
	}

	@Test
	public void testDestroy() {
		var keyPair = Signature.KeyPair.random();
		keyPair.privateKey().destroy();
		assertTrue(keyPair.privateKey().isDestroyed());
		assertFalse(keyPair.publicKey().isDestroyed());

		keyPair.publicKey().destroy();
		assertTrue(keyPair.privateKey().isDestroyed());
		assertTrue(keyPair.publicKey().isDestroyed());

		var ex = assertThrows(IllegalStateException.class, () -> keyPair.privateKey().bytes());
		assertEquals("allocated value has been destroyed", ex.getMessage());

		ex = assertThrows(IllegalStateException.class, () -> keyPair.publicKey().bytes());
		assertEquals("allocated value has been destroyed", ex.getMessage());
	}
}