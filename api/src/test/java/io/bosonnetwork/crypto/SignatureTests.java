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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.utils.Base58;
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

		assertThrows(IllegalStateException.class, () -> keyPair.privateKey().bytes());

		assertThrows(IllegalStateException.class, () -> keyPair.publicKey().bytes());
	}

	@Test
	public void testDerive() {
		Signature.KeyPair keyPair = Signature.KeyPair.random();

		Signature.KeyPair subKeyPair1 = keyPair.derive(1, "io.bosonnetwork.foo");
		Signature.KeyPair subKeyPair2 = keyPair.derive(2, "io.bosonnetwork.bar");

		Signature.KeyPair subKeyPairFoo = keyPair.derive(1, "io.bosonnetwork.foo");
		Signature.KeyPair subKeyPairBar = keyPair.derive(2, "io.bosonnetwork.bar");

		assertEquals(subKeyPair1, subKeyPairFoo);
		assertEquals(subKeyPair2, subKeyPairBar);
		assertNotEquals(subKeyPair1, subKeyPair2);

		Signature.PrivateKey subKey1 = keyPair.privateKey().derive(1, "io.bosonnetwork.foo");
		Signature.PrivateKey subKey2 = keyPair.privateKey().derive(2, "io.bosonnetwork.bar");

		Signature.PrivateKey subKeyFoo = keyPair.privateKey().derive(1, "io.bosonnetwork.foo");
		Signature.PrivateKey subKeyBar = keyPair.privateKey().derive(2, "io.bosonnetwork.bar");

		assertEquals(subKey1, subKeyFoo);
		assertEquals(subKey2, subKeyBar);
		assertNotEquals(subKey1, subKey2);

		assertEquals(subKeyPair1.privateKey(), subKey1);
		assertEquals(subKeyPair2.privateKey(), subKey2);
	}

	private void printSpecialKey(byte[] seed) {
		System.out.println("Seed:");
		System.out.println("- Hex:    " + Hex.encode(seed));
		System.out.println("- Base58: " + Base58.encode(seed));

		Signature.KeyPair keyPair = Signature.KeyPair.fromSeed(seed);
		System.out.println("Private key:");
		System.out.println("- Hex:    " + Hex.encode(keyPair.privateKey().bytes()));
		System.out.println("- Base58: " + Base58.encode(keyPair.privateKey().bytes()));
		System.out.println("Public key:");
		System.out.println("- Hex:    " + Hex.encode(keyPair.publicKey().bytes()));
		System.out.println("- Base58: " + Base58.encode(keyPair.publicKey().bytes()));
		System.out.println();
	}

	@Test
	public void testSpecialKeys() {
		byte[] seed = new byte[Signature.KeyPair.SEED_BYTES];
		printSpecialKey(seed);

		Arrays.fill(seed, (byte) 0xFF);
		printSpecialKey(seed);
	}
}