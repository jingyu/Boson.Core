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

package io.bosonnetwork;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.utils.Base58;
import io.bosonnetwork.utils.Hex;

public class IdTests {
	@Test
	void testOfHex() {
		var hexWithPrefix = "0x71e1b2ecdf528b623192f899d984c53f2b13508e21ccd53de5d7158672820636";
		var id = Id.ofHex(hexWithPrefix);
		assertEquals(hexWithPrefix, id.toHexString());

		var hexWithoutPrefix = "F897B6CB7969005520E6F6101EB5466D9859926A51653365E36C4A3C42E5DE6F";
		id = Id.ofHex(hexWithoutPrefix);
		assertEquals(hexWithoutPrefix.toLowerCase(), id.toHexString().substring(2));

		var hexWithPrefix2 = "0x71E1B2ECDF528B623192F899D984C53F2B13508E21CCD53DE5D71586728206";
		Exception e = assertThrows(IllegalArgumentException.class, () -> Id.ofHex(hexWithPrefix2));
		assertEquals("Hexadecimal string must represent exactly 32 bytes", e.getMessage());

		var hexWithoutPrefix2 = "f897b6cb7969005520e6f6101eb5466d9859926a51653365e36c4a3c42e5de";
		e = assertThrows(IllegalArgumentException.class, () -> Id.ofHex(hexWithoutPrefix2));
		assertEquals("Hexadecimal string must represent exactly 32 bytes", e.getMessage());

		var hexWithPrefix3 = "0x71E1B2ECDR528B623192F899D984C53F2B13508E21CCD53DE5D7158672820636";
		e = assertThrows(IllegalArgumentException.class, () -> Id.ofHex(hexWithPrefix3));
		assertEquals("Invalid hex byte 'DR' at index 10 of '0x71E1B2ECDR528B623192F899D984C53F2B13508E21CCD53DE5D7158672820636'", e.getMessage());

		var hexWithoutPrefix3 = "f897b6cb7969005520e6f6101ebx466d9859926a51653365e36c4a3c42e5de6f";
		e = assertThrows(IllegalArgumentException.class, () -> Id.ofHex(hexWithoutPrefix3));
		assertEquals("Invalid hex byte 'bx' at index 26 of 'f897b6cb7969005520e6f6101ebx466d9859926a51653365e36c4a3c42e5de6f'", e.getMessage());
	}

	@Test
	void testOfBytes() {
		var binId = new byte[Id.BYTES];
		new Random().nextBytes(binId);

		var id = Id.of(binId);
		assertArrayEquals(binId, id.getBytes());
		assertArrayEquals(binId, Hex.decode(id.toHexString().substring(2)));

		var binId2 = new byte[20];
		new Random().nextBytes(binId2);
		var e = assertThrows(IllegalArgumentException.class, () -> Id.of(binId2));
		assertEquals("Byte array should be exactly 32 bytes", e.getMessage());

		var binId3 = new byte[Id.BYTES + 18];
		new Random().nextBytes(binId3);

		id = Id.of(binId3, 5);
		assertArrayEquals(Arrays.copyOfRange(binId3, 5, 5 + Id.BYTES), id.getBytes());
		assertArrayEquals(Arrays.copyOfRange(binId3, 5, 5 + Id.BYTES), Hex.decode(id.toHexString().substring(2)));

		e = assertThrows(IllegalArgumentException.class, () -> Id.of(binId3, 20));
		assertEquals("Byte array must have at least 32 bytes available", e.getMessage());
	}

	@Test
	void testOfBase58() {
		var id1 = Id.random();
		var base58 = id1.toBase58String();

		var id2 = Id.ofBase58(base58);
		assertEquals(base58, id2.toBase58String());
		assertEquals(id1, id2);
	}

	@Test
	void testOfString() {
		var id1 = Id.random();

		var id2 = Id.of(id1.toHexString());
		assertEquals(id1, id2);

		id2 = Id.of(id1.toBase58String());
		assertEquals(id1, id2);

		id2 = Id.of("did:boson:" + id1.toBase58String());
		assertEquals(id1, id2);

		var ex = assertThrows(IllegalArgumentException.class, () -> Id.of("did:foobar:" + id1.toBase58String()));
		assertEquals("Invalid identifier string: must be Base58, hexadecimal, or Boson DID format", ex.getMessage());
	}

	@Test
	void testOfId() {
		var id1 = Id.random();
		var id2 = Id.of(id1);

		assertEquals(id1.toHexString(), id2.toHexString());
		assertEquals(id1.toInteger(), id2.toInteger());
		assertEquals(id1, id2);

		id2 = Id.random();
		assertNotEquals(id1, id2);
	}

	@Test
	void testOfBit() {
		for (int i = 0; i < Id.SIZE; i++) {
			var id = Id.ofBit(i);
			System.out.println(id.toBinaryString());
			assertEquals(id.toInteger(), BigInteger.ZERO.setBit(Id.SIZE - i - 1));
		}
	}

	@Test
	void testAdd() {
		var id1 = Id.of("0x71e1b2ecdf528b623192f899d984c53f2b13508e21ccd53de5d7158672820636");
		var id2 = Id.of("0xf897b6cb7969005520e6f6101eb5466d9859926a51653365e36c4a3c42e5de6f");

		var id3 = id1.add(id2);
		assertEquals("0x6a7969b858bb8bb75279eea9f83a0bacc36ce2f8733208a3c9435fc2b567e4a5", id3.toHexString());
		var n = id1.toInteger().add(id2.toInteger()).clearBit(Id.SIZE);
		assertEquals(n, id3.toInteger());

		for (int i = 0; i < 1000; i++) {
			id1 = Id.random();
			id2 = Id.random();

			id3 = id1.add(id2);
			n = id1.toInteger().add(id2.toInteger()).clearBit(Id.SIZE);
			assertEquals(n, id3.toInteger());
		}
	}

	@Test
	void testDistance() {
		var id1 = Id.of("0x00000000f528d6132c15787ed16f09b08a4e7de7e2c5d3838974711032cb7076");
		var id2 = Id.of("0x00000000f0a8d6132c15787ed16f09b08a4e7de7e2c5d3838974711032cb7076");

		assertEquals("0x0000000005800000000000000000000000000000000000000000000000000000", Id.distance(id1, id2).toHexString());

		id1 = Id.max();
		id2 = Id.min();
		assertEquals(Id.max(), Id.distance(id1, id2));

		id1 = Id.random();
		assertEquals(Id.zero(), Id.distance(id1, id1));
		assertEquals(id1, Id.distance(id1, Id.zero()));

		var distance = id1.getBytes();
		for (int i = 0; i < distance.length; i++)
			distance[i] ^= (byte) 0xFF;

		assertEquals(Id.of(distance), Id.distance(id1, Id.max()));

		for (int i = 0; i < 1000; i++) {
			id1 = Id.random();
			id2 = Id.random();

			Id id3 = id1.distance(id2);
			BigInteger n = id1.toInteger().xor(id2.toInteger());
			assertEquals(n, id3.toInteger());
		}
	}

	@Test
	void testApproxDistance() {
		assertEquals(Id.SIZE, Id.approxDistance(Id.zero(), Id.max()));
		assertEquals(0, Id.approxDistance(Id.min(), Id.min()));
		assertEquals(0, Id.approxDistance(Id.max(), Id.max()));

		var id1 = Id.of("0x8000000000000000000000000000000000000000000000000000000000000000");
		assertEquals(255, Id.approxDistance(id1, Id.max()));
		assertEquals(256, Id.approxDistance(id1, Id.min()));

		var id2 = Id.of("0x0000000000000000000000000000000000000000000000000000000000000001");
		assertEquals(256, Id.approxDistance(id2, Id.max()));
		assertEquals(1, Id.approxDistance(id2, Id.min()));

		id1 = Id.of("0x00000000f528d6132c15787ed16f09b08a4e7de7e2c5d3838974711032cb7076");
		id2 = Id.of("0x00000000f0a8d6132c15787ed16f09b08a4e7de7e2c5d3838974711032cb7076");
		assertEquals(219, Id.approxDistance(id1, id2));

		for (int i = 0; i < 1000; i++) {
			id1 = Id.random();
			id2 = Id.random();

			int d = id1.approxDistance(id2);
			int n = id1.toInteger().xor(id2.toInteger()).bitLength();
			assertEquals(n, d);
		}
	}

	@Test
	void testGetIdByDistance() {
		for (int i = 0; i <= Id.SIZE; i++) {
			var id = Id.min().getIdByDistance(i);
			assertEquals(Id.SIZE - i, id.getLeadingZeros());
			assertEquals(i == 0 ? Id.SIZE : 0, id.getTrailingZeros());

			id = Id.max().getIdByDistance(i);
			System.out.printf("%-3d: %s\n", i, id.toBinaryString());
			assertEquals(i, id.getTrailingZeros());
			assertEquals(i == Id.SIZE ? Id.SIZE : 0, id.getLeadingZeros());
		}

		var id1 = Id.random();
		for (int i = 0; i <= Id.SIZE; i++) {
			var id2 = id1.getIdByDistance(i);
			assertEquals(i, id1.approxDistance(id2));
			var distance = Id.distance(id1, id2);
			assertEquals(Id.SIZE - i, distance.getLeadingZeros());
			assertEquals(i == 0 ? Id.SIZE : 0, distance.getTrailingZeros());
		}

		var id = Id.of("0x00000000f528d6132c15787ed16f09b08a4e7de7e2c5d3838974711032cb7076");

		assertEquals("0x00000000f528d6132c15787ed16f09b08a4e7de7e2c5d3838974711032cb7076", id.getIdByDistance(0).toHexString());
		assertEquals("0x00000000f528d6132c15787ed16f09b08a4e7de7e2c5d383868b8eefcd348f89", id.getIdByDistance(60).toHexString());
		assertEquals("0x00000000f528d6132c15787ed16f09b095b182181d3a2c7c768b8eefcd348f89", id.getIdByDistance(125).toHexString());
		assertEquals("0x7fffffff0ad729ecd3ea87812e90f64f75b182181d3a2c7c768b8eefcd348f89", id.getIdByDistance(255).toHexString());
	}

	@Test
	void testCompare() {
		var binId = new byte[Id.BYTES];

		Arrays.fill(binId, (byte)1);
		var id1 = Id.of(binId);

		Arrays.fill(binId, (byte)2);
		var id2 = Id.of(binId);

		var rc = Id.compare(id1, id2);
		assertEquals(-1, rc);
		rc = id1.compareTo(id2);
		assertEquals(-1, rc);

		id1 = Id.of(binId);

		rc = Id.compare(id1, id2);
		assertEquals(0, rc);
		rc = id1.compareTo(id2);
		assertEquals(0, rc);

		Arrays.fill(binId, (byte)3);
		id1 = Id.of(binId);

		rc = Id.compare(id1, id2);
		assertEquals(1, rc);
		rc = id1.compareTo(id2);
		assertEquals(1, rc);
	}

	@Test
	void testThreeWayCompare() {
		var id = Id.of("0x4833af415161cbd0a3ef83aa59a55fbadc9bd520a886a8ca214a3d09b6676cb8");
		var id1 = Id.of("0x4833af415161cbd0a3ef83aa59a55fbadc9bd520a886a8fa214a3d09b6676cb8");
		var id2 = Id.of("0x4833af415161cbd0a3ef83aa59a55fbadc9bd520a885a8ca214a3d09b6676cb8");

		assertTrue(id.threeWayCompare(id1, id2) < 0);

		id1 = Id.of("0xf833af415161cbd0a3ef83aa59a55fbadc9bd520b886a8ca214a3d09b6676cb8");
		id2 = Id.of("0xf833af415161cbd0a3ef83aa59a55fbadc9bd520b886a8ca214a3d09b6676cb8");

		assertEquals(0, id.threeWayCompare(id1, id2));

		id1 = Id.of("0x4833af415161cbd0a3ef83aa59a55f1adc9bd520a886a8ca214a3d09b6676cb8");
		id2 = Id.of("0x4833af415161cbd0a3ef83aa59a55fcadc9bd520a886a8ca214a3d09b6676cb8");

		assertTrue(id.threeWayCompare(id1, id2) > 0);

		for (int i = 0; i < 1000; i++) {
			id1 = Id.random();
			id2 = Id.random();
			int d = id.threeWayCompare(id1, id2);

			var d1 = id.distance(id1);
			var d2 = id.distance(id2);
			int n = d1.toInteger().compareTo(d2.toInteger());

			assertEquals(n, d);
		}
	}

	@Test
	void testBitsEqual() {
		var id1 = Id.of("0x4833af415161cbd0a3ef83aa59a55fbadc9bd520a886a8fa214a3d09b6676cb8");
		var id2 = Id.of("0x4833af415166cbd0a3ef83aa59a55fbadc9bd520a886a8fa214a3d09b6676cb8");

		System.out.println(id1.toBinaryString());
		System.out.println(id2.toBinaryString());
		for (int i = 0; i < 45; i++)
			assertTrue(Id.bitsEqual(id1, id2, i));

		for (int i = 45; i < Id.SIZE; i++)
			assertFalse(Id.bitsEqual(id1, id2, i));

		id2 = Id.of(id1);
		for (int i = 0; i < Id.SIZE; i++)
			assertTrue(Id.bitsEqual(id1, id2, i));

		id2 = Id.of("0x4833af415161cbd0a3ef83aa59a55fbadc9bd520a886a8fa214a3d09b6676cb9");

		for (int i = 0; i < Id.SIZE - 1; i++)
			assertTrue(Id.bitsEqual(id1, id2, i));

		assertFalse(Id.bitsEqual(id1, id2, Id.SIZE -1));
	}

	@Test
	void testBitsCopy() {
		for (int i = 0; i < Id.SIZE; i++) {
			var target = Id.of(Id.min());
			Id.bitsCopy(Id.max(), target, i);
			System.out.println(target.toBinaryString());

			assertEquals(0, target.getLeadingZeros());
			assertEquals(Id.SIZE - i - 1, target.getTrailingZeros());
		}

		for (int i = 0; i < Id.SIZE; i++) {
			var target = Id.of(Id.max());
			Id.bitsCopy(Id.min(), target, i);
			System.out.println(target.toBinaryString());

			assertEquals(i + 1, target.getLeadingZeros());
			assertEquals(i < Id.SIZE - 1 ? 0 : Id.SIZE, target.getTrailingZeros());
		}

		var id1 = Id.random();
		var id2 = Id.random();

		for (int i = 0; i < Id.SIZE; i++) {
			var target = Id.of(id2);

			Id.bitsCopy(id1, target, i);
			System.out.printf("  -: %s\n", id1.toBinaryString());
			System.out.printf("  -: %s\n", id2.toBinaryString());
			System.out.printf("%-3d: %s\n\n", i, target.toBinaryString());

			assertTrue(Id.bitsEqual(id1, target, i));

			if (i == Id.SIZE - 1)
				assertEquals(id1, target);
		}
	}

	private static void timing(String name, Runnable action) {
		var begin = System.currentTimeMillis();
		action.run();
		var end = System.currentTimeMillis();
		var duration = end - begin;
		System.out.println(name + ": " + duration + "ms");
	}

	@Disabled("Performance")
	@Test
	@SuppressWarnings("ResultOfMethodCallIgnored")
	void testToHexPerf() {
		System.out.println("Testing to hex performance...");

		var id = Id.random();
		var bi = id.toInteger();

		int loops = 1000000;

		timing("BigInteger to hex", () -> {
			for (int i = 0; i < loops; i++)
				bi.toString(16);
		});

		timing("Bytes to hex", () -> {
			for (int i = 0; i < loops; i++)
				id.toHexString();
		});
	}

	@Disabled("Performance")
	@Test
	@SuppressWarnings("ResultOfMethodCallIgnored")
	void testXorPerf() {
		System.out.println("Testing XOR performance...");

		var id1 = Id.random();
		var id2 = Id.random();

		var bi1 = id1.toInteger();
		var bi2 = id2.toInteger();

		int loops = 10000000;

		timing("BigInteger XOR", () -> {
			for (int i = 0; i < loops; i++)
				bi1.xor(bi2);
		});

		timing("Bytes XOR", () -> {
			for (int i = 0; i < loops; i++)
				id1.distance(id2);
		});
	}

	@Disabled("Performance")
	@Test
	@SuppressWarnings("ResultOfMethodCallIgnored")
	void testAddPerf() {
		System.out.println("Testing ADD performance...");

		var id1 = Id.random();
		var id2 = Id.random();

		var bi1 = id1.toInteger();
		var bi2 = id2.toInteger();

		int loops = 10000000;

		timing("BigInteger ADD", () -> {
			for (int i = 0; i < loops; i++)
				bi1.add(bi2);
		});

		timing("Bytes ADD", () -> {
			for (int i = 0; i < loops; i++)
				id1.add(id2);
		});
	}

	@Disabled("Performance")
	@Test
	@SuppressWarnings("ResultOfMethodCallIgnored")
	void testMSBPerf() {
		System.out.println("Testing MSB performance...");

		var id = Id.of(Hex.decode("0000000000000000000000000000000000000000000000000000000000000100"));
		assertEquals(Id.SIZE - id.toInteger().bitLength(), id.getLeadingZeros());

		int loops = 100000000;

		timing("BigInteger MSB", () -> {
			for (int i = 0; i < loops; i++)
				id.toInteger().bitLength();
		});

		timing("Bytes MSB", () -> {
			for (int i = 0; i < loops; i++)
				id.getLeadingZeros();
		});
	}

	@Disabled("Performance")
	@Test
	@SuppressWarnings("ResultOfMethodCallIgnored")
	void testLSBPerf() {
		System.out.println("Testing LSB performance...");

		var id = Id.of(Hex.decode("0010000000000000000000000000000000000000000000000000000000000000"));
		assertEquals(id.toInteger().getLowestSetBit(), id.getTrailingZeros());

		int loops = 100000000;

		timing("BigInteger LSB", () -> {
			for (int i = 0; i < loops; i++)
				id.toInteger().getLowestSetBit();
		});

		timing("Bytes LSB", () -> {
			for (int i = 0; i < loops; i++)
				id.getTrailingZeros();
		});
	}

	@Disabled("Performance")
	@Test
	void testToStringPerf() {
		System.out.println("Testing to hex performance...");

		var id = Id.random();
		assertEquals(id.toHexString().substring(2), Hex.encode(Base58.decode(id.toBase58String())));

		System.out.println("   Hex format: " + id.toHexString());
		System.out.println("Base58 format: " + id.toBase58String());

		int loops = 1000000;

		timing("To hex string", () -> {
			for (int i = 0; i < loops; i++)
				id.toHexString();
		});

		timing("To base58 string", () -> {
			for (int i = 0; i < loops; i++)
				id.toBase58String();
		});
	}
}