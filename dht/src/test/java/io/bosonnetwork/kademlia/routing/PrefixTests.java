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

package io.bosonnetwork.kademlia.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.Random;

public class PrefixTests {
	@Test
	void testPrefix() {
		var ids = List.of(
				Id.min(),
				Id.random(),
				Id.max());

		for (var testId : ids) {
			for (var i = -1; i < Id.SIZE; i++) {
				var prefix = new Prefix(testId, i);
				System.out.format("%-3d: %s\n%s\n", i, prefix, prefix.toBinaryString());

				assertEquals(i, prefix.getDepth());
				assertTrue(prefix.isPrefixOf(testId));

				if (testId == Id.max())
					assertEquals(Id.SIZE - i - 1, prefix.getTrailingZeros());

				assertEquals(i != Id.SIZE - 1, prefix.isSplittable());
				var id = prefix.first();
				assertEquals(Id.of(prefix.getBytes()), id);
				if (i > -1)
					assertTrue(Id.bitsEqual(prefix, id, prefix.getDepth()));
				assertTrue(prefix.isPrefixOf(id));

				id = prefix.last();
				if (testId == Id.max())
					assertEquals(Id.max(), id);
				assertTrue(prefix.isPrefixOf(id));

				var parent = prefix.getParent();
				if (i == -1) {
					assertSame(parent, prefix);
				} else {
					assertEquals(i - 1, parent.getDepth());
					assertTrue(parent.isPrefixOf(prefix));
				}

				id = prefix.createRandomId();
				assertTrue(prefix.isPrefixOf(id));
				if (prefix.getDepth() > -1)
					assertTrue(Id.bitsEqual(prefix, id, prefix.getDepth()));

				if (i < Id.SIZE - 1) {
					var olderChild = prefix.splitBranch(true);
					var youngerChild = prefix.splitBranch(false);
					assertTrue(prefix.isPrefixOf(olderChild));
					assertTrue(prefix.isPrefixOf(youngerChild));
					assertEquals(prefix.getDepth() + 1, olderChild.getDepth());
					assertEquals(prefix.getDepth() + 1, youngerChild.getDepth());
					assertTrue(olderChild.isSiblingOf(youngerChild));
				} else {
					var ex = assertThrows(IllegalStateException.class, () -> prefix.splitBranch(true));
					assertTrue(ex.getMessage().startsWith("Prefix is not splittable"));
				}
			}
		}
	}

	@Test
	void testIsPrefixOf() {
		var id = Id.of("0x4833af415161cbd0a3ef83aa59a55fbadc9bd520a886a8fa214a3d09b6676cb8");
		var prefix = new Prefix(id, 64);

		assertTrue(prefix.isPrefixOf(id));
		assertTrue(prefix.isPrefixOf(Id.of("0x4833af415161cbd0f3ef83aa59a55fbadc9bd520a886a8fa214a3d09b6676cb8")));
		assertTrue(prefix.isPrefixOf(Id.of("0x4833af415161cbd0ffffffffffffffffffffffffffffffffffffffffffffffff")));
		assertFalse(prefix.isPrefixOf(Id.of("0x4833af415161cbd1f3ef83aa59a55fbadc9bd520a886a8fa214a3d09b6676cb8")));
	}

	@Test
	void testIsSplittable() {
		for (var i = -1; i < Id.SIZE - 2; i++) {
			Id id = Id.random();
			Prefix p = new Prefix(id, i);
			assertTrue(p.isSplittable());
		}

		var id = Id.random();
		var p = new Prefix(id, Id.SIZE - 1);
		assertFalse(p.isSplittable());
	}

	@Test
	void testIsSiblingOf() {
		var id  = Id.of("0x4833af415161cbd0a3ef83aa59a55fbadc9bd520a886a8fa214a3d09b6676cb8");
		var id2 = Id.of("0x4833af415161cbd0a3ef8faa59a55fbadc9bd520a886a8fa214a3d09b6676cb8");
		var id3 = Id.of("0x4833af415161cbd0a3ef93aa59a55fbadc9bd520a886a8fa214a3d09b6676cb8");

		var p = new Prefix(id, 84);
		var p2 = new Prefix(id2, 84);
		var p3 = new Prefix(id3, 84);

		assertTrue(p2.isSiblingOf(p));
		assertFalse(p3.isSiblingOf(p));
	}

	@Test
	void testFirstAndLast() {
		for (var i = -1; i < Id.SIZE - 1; i++) {
			var id = Id.random();
			var prefix = new Prefix(id, i);

			var first = prefix.first();
			var last = prefix.last();

			assertTrue(prefix.isPrefixOf(first));
			assertTrue(prefix.isPrefixOf(last));
			if (i > -1)
				assertFalse(prefix.isPrefixOf(last.add(Id.of("0x0000000000000000000000000000000000000000000000000000000000000001"))));
		}
	}

	@Test
	void testGetParent() {
		var id = Id.random();
		var prefix = new Prefix(id, -1);
		assertEquals(prefix, prefix.getParent());

		for (var i = 0; i < Id.SIZE; i++) {
			id = Id.random();

			prefix = new Prefix(id, i);
			var parent = prefix.getParent();
			assertEquals(prefix.getDepth(), parent.getDepth() + 1);

			assertTrue(parent.isPrefixOf(prefix));
			if (i > 1)
				assertTrue(Prefix.bitsEqual(prefix, parent, i - 1));
		}

		id = Id.max();
		for (var i = 0; i < Id.SIZE; i++) {
			prefix = new Prefix(id, i);
			var parent = prefix.getParent();

			assertEquals(prefix.getDepth(), parent.getDepth() + 1);

			assertTrue(parent.isPrefixOf(prefix));
			if (i > 1)
				assertTrue(Prefix.bitsEqual(prefix, parent, i - 1));
			assertFalse(Prefix.bitsEqual(prefix, parent, i));
		}

		id = Id.min();
		for (var i = 0; i < Id.SIZE; i++) {
			prefix = new Prefix(id, i);
			var parent = prefix.getParent();

			assertEquals(prefix.getDepth(), parent.getDepth() + 1);

			assertTrue(parent.isPrefixOf(prefix));
			if (i > 1)
				assertTrue(Prefix.bitsEqual(prefix, parent, i - 1));
			assertTrue(Prefix.bitsEqual(prefix, parent, i));
		}
	}

	@Test
	void testCreateRandomId() {
		for (var i = -1; i < Id.SIZE; i++) {
			var id = Id.random();
			var prefix = new Prefix(id, i);

			var rid = prefix.createRandomId();

			assertTrue(prefix.isPrefixOf(id));
			assertTrue(prefix.isPrefixOf(rid));
			if (i > -1)
				assertTrue(Id.bitsEqual(id, rid, i));
		}
	}

	@Test
	void testSplitBranch() {
		for  (var i = -1; i < Id.SIZE - 1; i++) {
			var id = Id.random();
			var p = new Prefix(id, i);

			var p1 = p.splitBranch(false);
			var p2 = p.splitBranch(true);

			assertTrue(p.isPrefixOf(p1));
			assertTrue(p.isPrefixOf(p2));

			assertEquals(p, p1.getParent());
			assertEquals(p, p2.getParent());

			if (i != -1)
				assertTrue(Id.bitsEqual(p1, p2, i));
			assertFalse(Id.bitsEqual(p1, p2, i + 1));
		}
	}

	@Test
	void testGetCommonPrefix() {
		for (var depth = -1; depth < Id.SIZE; depth++) {
			var id = Id.random();
			var p = new Prefix(id, depth);

			var ids = IntStream.range(0, 128 + Random.random().nextInt(128))
					.mapToObj(i -> p.createRandomId())
					.toList();

			var cp = Prefix.getCommonPrefix(ids);
			assertEquals(p, cp);
		}
	}

	@Test
	void testSplitAndSiblings() {
		// Root
		var root = Prefix.all();

		// Level 1
		var p1 = root.splitBranch(false);
		var p2 = root.splitBranch(true);

		assertTrue(p1.isSiblingOf(p2));
		assertTrue(p2.isSiblingOf(p1));

		assertTrue(root.isPrefixOf(p1));
		assertTrue(root.isPrefixOf(p2));

		// Level 2（1.x)
		var p11 = p1.splitBranch(false);
		var p12 = p1.splitBranch(true);

		assertTrue(p11.isSiblingOf(p12));
		assertTrue(p12.isSiblingOf(p11));

		assertFalse(p1.isSiblingOf(p11));
		assertFalse(p1.isSiblingOf(p12));

		assertFalse(p2.isSiblingOf(p11));
		assertFalse(p2.isSiblingOf(p12));

		assertTrue(p1.isPrefixOf(p11));
		assertTrue(p1.isPrefixOf(p12));

		assertFalse(p2.isPrefixOf(p11));
		assertFalse(p2.isPrefixOf(p12));

		// Level 2 (2.x)
		var p21 = p2.splitBranch(false);
		var p22 = p2.splitBranch(true);

		assertTrue(p21.isSiblingOf(p22));
		assertTrue(p22.isSiblingOf(p21));

		assertFalse(p1.isSiblingOf(p21));
		assertFalse(p1.isSiblingOf(p22));

		assertFalse(p2.isSiblingOf(p21));
		assertFalse(p2.isSiblingOf(p22));

		assertTrue(p2.isPrefixOf(p21));
		assertTrue(p2.isPrefixOf(p22));

		assertFalse(p1.isPrefixOf(p21));
		assertFalse(p1.isPrefixOf(p22));

		assertFalse(p12.isSiblingOf(p21));
		assertFalse(p21.isSiblingOf(p12));
	}
}