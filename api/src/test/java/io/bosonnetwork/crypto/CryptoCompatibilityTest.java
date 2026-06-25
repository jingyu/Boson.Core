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

package io.bosonnetwork.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import org.apache.tuweni.crypto.sodium.Sodium;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Proves that the production {@link BouncyCastleCryptoProvider} is byte-for-byte compatible
 * with libsodium (via {@link SodiumCryptoProvider}) for every primitive Boson uses, and that
 * the provider key objects honour the destroy/equality contract.
 */
public class CryptoCompatibilityTest {
	private static final CryptoProvider BC = new BouncyCastleCryptoProvider();
	private static final CryptoProvider LS = new SodiumCryptoProvider();
	private static final SecureRandom RND = new SecureRandom();

	// libsodium crypto_pwhash INTERACTIVE limits for Argon2id.
	private static final long OPS = 2L;
	private static final long MEM = 67108864L;

	@BeforeAll
	static void setup() {
		assumeTrue(Sodium.isAvailable(), "Sodium native library is not available");
	}

	private static byte[] rb(int n) {
		byte[] b = new byte[n];
		RND.nextBytes(b);
		return b;
	}

	private static CryptoBox.PrivateKey boxSk(CryptoProvider p, byte[] seed) {
		return p.signSecretKeyToBoxSecretKey(p.ed25519SecretKeyFromSeed(seed));
	}

	@Test
	void ed25519Matches() {
		byte[] seed = rb(32);
		byte[] msg = "boson ed25519".getBytes(StandardCharsets.UTF_8);

		Signature.PrivateKey bcSk = BC.ed25519SecretKeyFromSeed(seed);
		Signature.PrivateKey lsSk = LS.ed25519SecretKeyFromSeed(seed);
		assertArrayEquals(lsSk.bytes(), bcSk.bytes(), "secret key from seed");
		assertArrayEquals(LS.ed25519PublicKeyFromSecretKey(lsSk).bytes(),
				BC.ed25519PublicKeyFromSecretKey(bcSk).bytes(), "public key from secret key");

		byte[] bcSig = BC.ed25519Sign(msg, bcSk);
		byte[] lsSig = LS.ed25519Sign(msg, lsSk);
		assertArrayEquals(lsSig, bcSig, "deterministic signature");

		Signature.PublicKey bcPk = BC.ed25519PublicKeyFromSecretKey(bcSk);
		Signature.PublicKey lsPk = LS.ed25519PublicKeyFromSecretKey(lsSk);
		assertTrue(BC.ed25519Verify(msg, lsSig, bcPk), "BC verifies libsodium signature");
		assertTrue(LS.ed25519Verify(msg, bcSig, lsPk), "libsodium verifies BC signature");
	}

	@Test
	void ed25519FromBytesMatches() {
		// Regression: PrivateKey.fromBytes must accept the 64-byte (seed || pub) secret key on
		// both backends (libsodium rejects a 64-byte value passed to the seed factory).
		byte[] seed = rb(32);
		byte[] sk64 = BC.ed25519SecretKeyFromSeed(seed).bytes();
		assertEquals(64, sk64.length);
		assertArrayEquals(LS.ed25519SecretKeyFromBytes(sk64).bytes(),
				BC.ed25519SecretKeyFromBytes(sk64).bytes());
	}

	@Test
	void kdfMatches() {
		byte[] master = rb(32);
		byte[] ctx = "boson-kd".getBytes(StandardCharsets.US_ASCII);
		assertArrayEquals(LS.kdfDeriveFromKey(master, 7L, ctx, 32), BC.kdfDeriveFromKey(master, 7L, ctx, 32));
	}

	@Test
	void ed25519ToCurveMatches() {
		byte[] seed = rb(32);
		Signature.PrivateKey bcSk = BC.ed25519SecretKeyFromSeed(seed);
		Signature.PrivateKey lsSk = LS.ed25519SecretKeyFromSeed(seed);
		assertArrayEquals(LS.signPublicKeyToBoxPublicKey(LS.ed25519PublicKeyFromSecretKey(lsSk)).bytes(),
				BC.signPublicKeyToBoxPublicKey(BC.ed25519PublicKeyFromSecretKey(bcSk)).bytes(), "public key conversion");
		assertArrayEquals(LS.signSecretKeyToBoxSecretKey(lsSk).bytes(),
				BC.signSecretKeyToBoxSecretKey(bcSk).bytes(), "secret key conversion");
	}

	@Test
	void cryptoBoxMatches() throws CryptoException {
		byte[] msg = "boson crypto_box payload".getBytes(StandardCharsets.UTF_8);
		byte[] seedA = rb(32);
		byte[] seedB = rb(32);
		CryptoBox.Nonce nonce = BC.boxNonceFromBytes(rb(24));

		CryptoBox.PrivateKey bcSkA = boxSk(BC, seedA);
		CryptoBox.PublicKey bcPkA = BC.boxPublicKeyFromSecretKey(bcSkA);
		CryptoBox.PrivateKey bcSkB = boxSk(BC, seedB);
		CryptoBox.PublicKey bcPkB = BC.boxPublicKeyFromSecretKey(bcSkB);

		CryptoBox.PrivateKey lsSkA = boxSk(LS, seedA);
		CryptoBox.PrivateKey lsSkB = boxSk(LS, seedB);
		CryptoBox.PublicKey lsPkB = LS.boxPublicKeyFromSecretKey(lsSkB);

		assertArrayEquals(LS.boxPublicKeyFromSecretKey(lsSkA).bytes(), bcPkA.bytes(), "public key from secret key");

		byte[] bcBox = BC.boxEncrypt(msg, nonce, bcPkB, bcSkA);
		byte[] lsBox = LS.boxEncrypt(msg, nonce, lsPkB, lsSkA);
		assertArrayEquals(lsBox, bcBox, "crypto_box ciphertext (validates HSalsa20 beforenm)");

		// precomputed (beforenm/afternm) path equals the full path on both backends
		try (CryptoBox bcPre = BC.boxBeforeNm(bcPkB, bcSkA); CryptoBox lsPre = LS.boxBeforeNm(lsPkB, lsSkA)) {
			assertArrayEquals(lsBox, bcPre.encrypt(msg, CryptoBox.Nonce.fromBytes(nonce.bytes())), "BC afternm == full");
			assertArrayEquals(lsBox, lsPre.encrypt(msg, CryptoBox.Nonce.fromBytes(nonce.bytes())), "libsodium afternm == full");
		}

		// receiver opens with sender public key + own secret key, across backends
		assertArrayEquals(msg, BC.boxDecrypt(lsBox, nonce, bcPkA, bcSkB), "BC opens libsodium box");
		assertArrayEquals(msg, LS.boxDecrypt(bcBox, nonce, LS.boxPublicKeyFromSecretKey(lsSkA), lsSkB),
				"libsodium opens BC box");

		try (CryptoBox bcDec = BC.boxBeforeNm(bcPkA, bcSkB)) {
			assertArrayEquals(msg, bcDec.decrypt(lsBox, CryptoBox.Nonce.fromBytes(nonce.bytes())), "BC afternm opens libsodium box");
		}
	}

	@Test
	void crossProviderKeyFallback() {
		// A key object from one provider must still work when handed to another (keyOf falls
		// back to its raw bytes). This is the contract that keeps provider swapping safe.
		byte[] msg = "fallback".getBytes(StandardCharsets.UTF_8);
		CryptoBox.Nonce nonce = BC.boxNonceFromBytes(rb(24));
		CryptoBox.PrivateKey bcSk = boxSk(BC, rb(32));
		CryptoBox.PrivateKey lsPeerSk = boxSk(LS, rb(32));
		CryptoBox.PublicKey lsPeerPk = LS.boxPublicKeyFromSecretKey(lsPeerSk);
		CryptoBox.PublicKey bcSelfPk = BC.boxPublicKeyFromSecretKey(bcSk);

		// BC encrypts using an LS public key object (foreign to BC)
		byte[] cipher = BC.boxEncrypt(msg, nonce, lsPeerPk, bcSk);
		assertArrayEquals(msg, LS.boxDecrypt(cipher, nonce, bcSelfPk, lsPeerSk), "foreign key object accepted");
	}

	@Test
	void boxSeedKeyPairMatches() {
		// crypto_box_seed_keypair: BC computes SHA-512(seed)[0..32] (unclamped); libsodium uses
		// Box.KeyPair.fromSeed. They must agree on both the secret and derived public key.
		byte[] seed = rb(32);
		assertArrayEquals(LS.boxSecretKeyFromSeed(seed).bytes(), BC.boxSecretKeyFromSeed(seed).bytes(),
				"box seed -> secret key");
		assertArrayEquals(
				LS.boxPublicKeyFromSecretKey(LS.boxSecretKeyFromSeed(seed)).bytes(),
				BC.boxPublicKeyFromSecretKey(BC.boxSecretKeyFromSeed(seed)).bytes(),
				"box seed -> public key");
	}

	@Test
	void nonceIncrementMatches() {
		byte[] init = rb(24);
		CryptoBox.Nonce bc = BC.boxNonceFromBytes(init);
		CryptoBox.Nonce ls = LS.boxNonceFromBytes(init);
		assertArrayEquals(ls.bytes(), bc.bytes());
		for (int i = 0; i < 5; i++) {
			bc = bc.increment();
			ls = ls.increment();
			assertArrayEquals(ls.bytes(), bc.bytes(), "increment step " + i);
		}

		// carry across byte boundaries (little-endian sodium_increment)
		byte[] edge = new byte[24];
		edge[0] = (byte) 0xff;
		edge[1] = (byte) 0xff;
		assertArrayEquals(
				LS.boxNonceFromBytes(edge).increment().bytes(),
				BC.boxNonceFromBytes(edge).increment().bytes(), "carry");
	}

	@Test
	void sealedBoxMatches() {
		byte[] msg = "boson sealed box".getBytes(StandardCharsets.UTF_8);
		byte[] seed = rb(32);
		CryptoBox.PrivateKey bcSk = boxSk(BC, seed);
		CryptoBox.PublicKey bcPk = BC.boxPublicKeyFromSecretKey(bcSk);
		CryptoBox.PrivateKey lsSk = boxSk(LS, seed);
		CryptoBox.PublicKey lsPk = LS.boxPublicKeyFromSecretKey(lsSk);

		assertArrayEquals(msg, BC.boxSealOpen(LS.boxSeal(msg, lsPk), bcPk, bcSk), "BC opens libsodium sealed box");
		assertArrayEquals(msg, LS.boxSealOpen(BC.boxSeal(msg, bcPk), lsPk, lsSk), "libsodium opens BC sealed box");
	}

	// Message sizes exercising XSalsa20 / Poly1305 block boundaries (empty, sub-block, exact
	// block multiples, +/- 1) plus a multi-block payload.
	private static final int[] SIZES = {0, 1, 15, 16, 17, 31, 32, 33, 63, 64, 65, 127, 128, 255, 256, 1000};

	@Test
	void ed25519PublicKeyFromBytesMatches() {
		for (int i = 0; i < 16; i++) {
			byte[] seed = rb(32);
			byte[] msg = rb(1 + (i * 13));
			byte[] pkBytes = BC.ed25519PublicKeyFromSecretKey(BC.ed25519SecretKeyFromSeed(seed)).bytes();

			// round-trip identical on both backends
			assertArrayEquals(pkBytes, BC.ed25519PublicKeyFromBytes(pkBytes).bytes());
			assertArrayEquals(pkBytes, LS.ed25519PublicKeyFromBytes(pkBytes).bytes());

			// a from-bytes public key verifies a real signature on both backends
			byte[] sig = LS.ed25519Sign(msg, LS.ed25519SecretKeyFromSeed(seed));
			assertTrue(BC.ed25519Verify(msg, sig, BC.ed25519PublicKeyFromBytes(pkBytes)));
			assertTrue(LS.ed25519Verify(msg, sig, LS.ed25519PublicKeyFromBytes(pkBytes)));
		}
	}

	@Test
	void ed25519SignMatchesAcrossSizesAndSeeds() {
		for (int iter = 0; iter < 8; iter++) {
			byte[] seed = rb(32);
			Signature.PrivateKey bcSk = BC.ed25519SecretKeyFromSeed(seed);
			Signature.PrivateKey lsSk = LS.ed25519SecretKeyFromSeed(seed);
			assertArrayEquals(lsSk.seed(), bcSk.seed(), "seed()");
			Signature.PublicKey bcPk = BC.ed25519PublicKeyFromSecretKey(bcSk);
			Signature.PublicKey lsPk = LS.ed25519PublicKeyFromSecretKey(lsSk);

			for (int size : SIZES) {
				byte[] msg = rb(size);
				byte[] bcSig = BC.ed25519Sign(msg, bcSk);
				byte[] lsSig = LS.ed25519Sign(msg, lsSk);
				assertArrayEquals(lsSig, bcSig, "signature size=" + size);
				assertTrue(BC.ed25519Verify(msg, lsSig, bcPk), "BC verify size=" + size);
				assertTrue(LS.ed25519Verify(msg, bcSig, lsPk), "LS verify size=" + size);
			}
		}
	}

	@Test
	void kdfMatchesAcrossLengthsIdsAndContexts() {
		byte[][] contexts = {
				"boson-kd".getBytes(StandardCharsets.US_ASCII),
				"01234567".getBytes(StandardCharsets.US_ASCII),
				new byte[8] };
		long[] ids = {0L, 1L, 42L, 0xFFFFFFFFL, Long.MAX_VALUE};
		int[] lengths = {16, 24, 32, 48, 64};
		for (int i = 0; i < 8; i++) {
			byte[] master = rb(32);
			for (byte[] ctx : contexts)
				for (long id : ids)
					for (int len : lengths)
						assertArrayEquals(LS.kdfDeriveFromKey(master, id, ctx, len),
								BC.kdfDeriveFromKey(master, id, ctx, len),
								"kdf len=" + len + " id=" + id);
		}
	}

	@Test
	void boxMatchesAcrossSizes() throws CryptoException {
		for (int iter = 0; iter < 4; iter++) {
			byte[] seedA = rb(32);
			byte[] seedB = rb(32);
			CryptoBox.PrivateKey bcSkA = boxSk(BC, seedA);
			CryptoBox.PublicKey bcPkA = BC.boxPublicKeyFromSecretKey(bcSkA);
			CryptoBox.PrivateKey bcSkB = boxSk(BC, seedB);
			CryptoBox.PublicKey bcPkB = BC.boxPublicKeyFromSecretKey(bcSkB);
			CryptoBox.PrivateKey lsSkA = boxSk(LS, seedA);
			CryptoBox.PublicKey lsPkA = LS.boxPublicKeyFromSecretKey(lsSkA);
			CryptoBox.PrivateKey lsSkB = boxSk(LS, seedB);
			CryptoBox.PublicKey lsPkB = LS.boxPublicKeyFromSecretKey(lsSkB);
			CryptoBox.Nonce nonce = BC.boxNonceFromBytes(rb(24));
			CryptoBox.Nonce lsNonce = LS.boxNonceFromBytes(nonce.bytes());

			for (int size : SIZES) {
				byte[] msg = rb(size);
				byte[] bcBox = BC.boxEncrypt(msg, nonce, bcPkB, bcSkA);
				byte[] lsBox = LS.boxEncrypt(msg, lsNonce, lsPkB, lsSkA);
				assertArrayEquals(lsBox, bcBox, "box ciphertext size=" + size);
				assertArrayEquals(msg, BC.boxDecrypt(lsBox, nonce, bcPkA, bcSkB), "BC opens LS size=" + size);
				assertArrayEquals(msg, LS.boxDecrypt(bcBox, lsNonce, lsPkA, lsSkB), "LS opens BC size=" + size);
			}
		}
	}

	@Test
	void boxFromBytesRoundTrips() {
		byte[] seedA = rb(32);
		byte[] seedB = rb(32);
		byte[] skABytes = BC.boxSecretKeyFromSeed(seedA).bytes();
		byte[] skBBytes = BC.boxSecretKeyFromSeed(seedB).bytes();
		byte[] pkBBytes = BC.boxPublicKeyFromSecretKey(BC.boxSecretKeyFromBytes(skBBytes)).bytes();

		// raw bytes survive the round trip identically on both backends
		assertArrayEquals(skABytes, BC.boxSecretKeyFromBytes(skABytes).bytes());
		assertArrayEquals(skABytes, LS.boxSecretKeyFromBytes(skABytes).bytes());
		assertArrayEquals(pkBBytes, BC.boxPublicKeyFromBytes(pkBBytes).bytes());
		assertArrayEquals(pkBBytes, LS.boxPublicKeyFromBytes(pkBBytes).bytes());

		// and from-bytes keys produce byte-identical ciphertext
		byte[] msg = rb(100);
		byte[] nonceBytes = rb(24);
		byte[] bcBox = BC.boxEncrypt(msg, BC.boxNonceFromBytes(nonceBytes),
				BC.boxPublicKeyFromBytes(pkBBytes), BC.boxSecretKeyFromBytes(skABytes));
		byte[] lsBox = LS.boxEncrypt(msg, LS.boxNonceFromBytes(nonceBytes),
				LS.boxPublicKeyFromBytes(pkBBytes), LS.boxSecretKeyFromBytes(skABytes));
		assertArrayEquals(lsBox, bcBox, "from-bytes key ciphertext");
	}

	@Test
	void boxAuthFailureRejectedByBoth() {
		byte[] seedA = rb(32);
		byte[] seedB = rb(32);
		CryptoBox.PrivateKey bcSkA = boxSk(BC, seedA);
		CryptoBox.PublicKey bcPkA = BC.boxPublicKeyFromSecretKey(bcSkA);
		CryptoBox.PrivateKey bcSkB = boxSk(BC, seedB);
		CryptoBox.PublicKey bcPkB = BC.boxPublicKeyFromSecretKey(bcSkB);
		CryptoBox.PrivateKey lsSkA = boxSk(LS, seedA);
		CryptoBox.PublicKey lsPkA = LS.boxPublicKeyFromSecretKey(lsSkA);
		CryptoBox.PrivateKey lsSkB = boxSk(LS, seedB);

		CryptoBox.Nonce nonce = BC.boxNonceFromBytes(rb(24));
		byte[] box = BC.boxEncrypt(rb(64), nonce, bcPkB, bcSkA);

		// tampered ciphertext is rejected (null) by both backends
		byte[] tampered = box.clone();
		tampered[tampered.length - 1] ^= 0x01;
		assertNull(BC.boxDecrypt(tampered, nonce, bcPkA, bcSkB), "BC rejects tampered");
		assertNull(LS.boxDecrypt(tampered, nonce, lsPkA, lsSkB), "LS rejects tampered");

		// wrong nonce is rejected by both
		CryptoBox.Nonce wrong = BC.boxNonceFromBytes(rb(24));
		assertNull(BC.boxDecrypt(box, wrong, bcPkA, bcSkB), "BC rejects wrong nonce");
		assertNull(LS.boxDecrypt(box, LS.boxNonceFromBytes(wrong.bytes()), lsPkA, lsSkB), "LS rejects wrong nonce");
	}

	@Test
	void sealedBoxAuthFailureRejectedByBoth() {
		byte[] seed = rb(32);
		CryptoBox.PrivateKey bcSk = boxSk(BC, seed);
		CryptoBox.PublicKey bcPk = BC.boxPublicKeyFromSecretKey(bcSk);
		CryptoBox.PrivateKey lsSk = boxSk(LS, seed);
		CryptoBox.PublicKey lsPk = LS.boxPublicKeyFromSecretKey(lsSk);

		byte[] sealed = BC.boxSeal(rb(48), bcPk);
		byte[] tampered = sealed.clone();
		tampered[tampered.length - 1] ^= 0x01;
		assertNull(BC.boxSealOpen(tampered, bcPk, bcSk), "BC rejects tampered sealed box");
		assertNull(LS.boxSealOpen(tampered, lsPk, lsSk), "LS rejects tampered sealed box");
	}

	@Test
	void pwHashArgon2iMatches() {
		// Argon2i (distinct from Argon2id): interactive limits (ops=4, mem=32 MiB).
		byte[] pw = "correct horse battery staple".getBytes(StandardCharsets.UTF_8);
		byte[] salt = rb(16);
		long ops = 4L;
		long mem = 33554432L;
		assertArrayEquals(
				LS.pwHash(pw, 32, salt, ops, mem, CryptoProvider.PWHASH_ALG_ARGON2I13),
				BC.pwHash(pw, 32, salt, ops, mem, CryptoProvider.PWHASH_ALG_ARGON2I13),
				"argon2i raw");
		// a different output length, still byte-identical
		assertArrayEquals(
				LS.pwHash(pw, 64, salt, ops, mem, CryptoProvider.PWHASH_ALG_ARGON2I13),
				BC.pwHash(pw, 64, salt, ops, mem, CryptoProvider.PWHASH_ALG_ARGON2I13),
				"argon2i raw len=64");
	}

	@Test
	void pwHashNeedsRehashMatches() {
		byte[] pw = "correct horse battery staple".getBytes(StandardCharsets.UTF_8);
		String phc = BC.pwHashString(pw, OPS, MEM, CryptoProvider.PWHASH_ALG_ARGON2ID13);

		// same limits -> no rehash; changed ops or mem -> rehash. Both backends must agree.
		assertEquals(LS.pwHashNeedsRehash(phc, OPS, MEM), BC.pwHashNeedsRehash(phc, OPS, MEM));
		assertFalse(BC.pwHashNeedsRehash(phc, OPS, MEM), "matching params -> no rehash");

		assertEquals(LS.pwHashNeedsRehash(phc, OPS + 1, MEM), BC.pwHashNeedsRehash(phc, OPS + 1, MEM));
		assertTrue(BC.pwHashNeedsRehash(phc, OPS + 1, MEM), "more ops -> rehash");

		assertEquals(LS.pwHashNeedsRehash(phc, OPS, MEM * 2), BC.pwHashNeedsRehash(phc, OPS, MEM * 2));
		assertTrue(BC.pwHashNeedsRehash(phc, OPS, MEM * 2), "more mem -> rehash");
	}

	@Test
	void keyEqualityAndDestroy() {
		byte[] seed = rb(32);

		// value equality (not identity), on both providers
		assertEquals(BC.ed25519SecretKeyFromSeed(seed), BC.ed25519SecretKeyFromSeed(seed));
		assertEquals(LS.ed25519SecretKeyFromSeed(seed), LS.ed25519SecretKeyFromSeed(seed));
		assertEquals(BC.ed25519SecretKeyFromSeed(seed).hashCode(), BC.ed25519SecretKeyFromSeed(seed).hashCode());
		assertNotEquals(BC.ed25519SecretKeyFromSeed(seed), BC.ed25519SecretKeyFromSeed(rb(32)));

		// wrapper KeyPair equality flows through the key objects
		assertEquals(Signature.KeyPair.fromSeed(seed), Signature.KeyPair.fromSeed(seed));
		assertEquals(CryptoBox.KeyPair.fromSeed(seed), CryptoBox.KeyPair.fromSeed(seed));

		// destroy() wipes and blocks further use on both backends
		for (Signature.PrivateKey sk : new Signature.PrivateKey[] {
				BC.ed25519SecretKeyFromSeed(seed), LS.ed25519SecretKeyFromSeed(seed) }) {
			assertFalse(sk.isDestroyed());
			sk.destroy();
			assertTrue(sk.isDestroyed());
			assertThrows(Exception.class, sk::bytes);
		}

		CryptoBox.PrivateKey boxSk = BC.signSecretKeyToBoxSecretKey(BC.ed25519SecretKeyFromSeed(seed));
		boxSk.destroy();
		assertTrue(boxSk.isDestroyed());
		assertThrows(Exception.class, boxSk::bytes);
	}

	@Test
	void pwHashRawMatches() {
		byte[] pw = "correct horse battery staple".getBytes(StandardCharsets.UTF_8);
		byte[] salt = rb(16);
		assertArrayEquals(
				LS.pwHash(pw, 32, salt, OPS, MEM, CryptoProvider.PWHASH_ALG_ARGON2ID13),
				BC.pwHash(pw, 32, salt, OPS, MEM, CryptoProvider.PWHASH_ALG_ARGON2ID13));
	}

	@Test
	void pwHashStringInteroperatesBothDirections() {
		byte[] pw = "correct horse battery staple".getBytes(StandardCharsets.UTF_8);

		String lsPhc = LS.pwHashString(pw, OPS, MEM, CryptoProvider.PWHASH_ALG_ARGON2ID13);
		assertTrue(BC.pwHashVerify(lsPhc, pw), "BC verifies a libsodium-generated PHC string");

		String bcPhc = BC.pwHashString(pw, OPS, MEM, CryptoProvider.PWHASH_ALG_ARGON2ID13);
		assertTrue(LS.pwHashVerify(bcPhc, pw), "libsodium verifies a BC-generated PHC string");
		assertTrue(BC.pwHashVerify(bcPhc, pw), "BC verifies its own PHC string");
		assertFalse(BC.pwHashVerify(bcPhc, "wrong".getBytes(StandardCharsets.UTF_8)), "wrong password rejected");
	}
}