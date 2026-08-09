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

package io.bosonnetwork.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link Variable}, weighted towards what it is for: a mutable cell written from inside a
 * lambda, standing in for a one-element array or an {@code AtomicInteger} on a single thread.
 * <p>
 * The {@code Optional}-shaped half of the API is covered more lightly, but its two surprises are
 * pinned deliberately - {@code filter} and {@code or} return {@code this} rather than a snapshot, so
 * the result aliases the original.
 * </p>
 */
public class VariableTests {
	/**
	 * The reason this class exists: a local cannot be assigned from a closure, so the state lives in a
	 * cell that is captured instead.
	 */
	@Nested
	class CaptureInLambda {
		@Test
		void testCounterReachesZero() {
			Variable<Integer> outstanding = Variable.of(3);
			List<Runnable> callbacks = new ArrayList<>();
			Variable<Boolean> finished = Variable.of(false);

			for (int i = 0; i < 3; i++)
				callbacks.add(() -> {
					if (outstanding.updateAndGet(v -> v - 1) == 0)
						finished.set(true);
				});

			callbacks.get(0).run();
			assertEquals(2, outstanding.get());
			assertFalse(finished.get());

			callbacks.get(1).run();
			callbacks.get(2).run();
			assertEquals(0, outstanding.get());
			assertTrue(finished.get(), "the last callback should have seen the counter hit zero");
		}

		@Test
		void testValueEscapesTheLambda() {
			Variable<String> captured = Variable.empty();
			Consumer<String> handler = captured::set;

			assertTrue(captured.isEmpty());
			handler.accept("from inside");

			assertTrue(captured.isPresent());
			assertEquals("from inside", captured.get());
		}

		@Test
		void testEmptyCellMeansTheLambdaNeverRan() {
			// Throwing rather than returning null is the documented behaviour, and the reason for it:
			// a cell that is still empty means the callback that should have written it did not run.
			Variable<String> captured = Variable.empty();
			assertThrows(NoSuchElementException.class, captured::get);
		}
	}

	@Nested
	class Factories {
		@Test
		void testOfHoldsTheValue() {
			Variable<String> v = Variable.of("value");
			assertTrue(v.isPresent());
			assertEquals("value", v.get());
		}

		@Test
		void testOfRejectsNull() {
			assertThrows(NullPointerException.class, () -> Variable.of(null));
		}

		@Test
		void testOfNullableAcceptsNull() {
			Variable<String> v = Variable.ofNullable(null);
			assertTrue(v.isEmpty());
			assertEquals("fallback", v.orElse("fallback"));
		}

		@Test
		void testEmptyIsEmpty() {
			Variable<String> v = Variable.empty();
			assertTrue(v.isEmpty());
			assertFalse(v.isPresent());
		}
	}

	@Nested
	class SetAndClear {
		@Test
		void testSetReplacesTheValue() {
			Variable<String> v = Variable.of("first");
			v.set("second");
			assertEquals("second", v.get());
		}

		@Test
		void testSetRejectsNull() {
			// null is how absence is represented, so emptying has to go through clear().
			Variable<String> v = Variable.of("value");
			assertThrows(NullPointerException.class, () -> v.set(null));
			assertEquals("value", v.get(), "a rejected set must not have disturbed the value");
		}

		@Test
		void testClearEmptiesTheCell() {
			Variable<String> v = Variable.of("value");
			v.clear();

			assertTrue(v.isEmpty());
			assertThrows(NoSuchElementException.class, v::get);
		}

		@Test
		void testSetIfAbsentOnlyFillsAnEmptyCell() {
			Variable<String> empty = Variable.empty();
			empty.setIfAbsent("filled");
			assertEquals("filled", empty.get());

			Variable<String> occupied = Variable.of("original");
			occupied.setIfAbsent("ignored");
			assertEquals("original", occupied.get());
		}

		@Test
		void testSetIfAbsentWithNullIsANoOp() {
			Variable<String> v = Variable.empty();
			v.setIfAbsent(null);
			assertTrue(v.isEmpty());
		}
	}

	/**
	 * The atomic-parity family, which is what makes this usable as an unsynchronized counter.
	 */
	@Nested
	class UpdateFamily {
		@Test
		void testUpdateAndGetReturnsTheNewValue() {
			Variable<Integer> v = Variable.of(10);

			assertEquals(9, v.updateAndGet(n -> n - 1));
			assertEquals(9, v.get());
		}

		@Test
		void testGetAndUpdateReturnsThePreviousValue() {
			Variable<Integer> v = Variable.of(10);

			assertEquals(10, v.getAndUpdate(n -> n - 1));
			assertEquals(9, v.get());
		}

		@Test
		void testGetAndSetReturnsThePreviousValue() {
			Variable<String> v = Variable.of("old");

			assertEquals("old", v.getAndSet("new"));
			assertEquals("new", v.get());
		}

		@Test
		void testGetAndSetOnAnEmptyCellReturnsNull() {
			Variable<String> v = Variable.empty();

			assertNull(v.getAndSet("first"));
			assertEquals("first", v.get());
		}

		@Test
		void testGetAndSetRejectsNull() {
			Variable<String> v = Variable.of("value");
			assertThrows(NullPointerException.class, () -> v.getAndSet(null));
			assertEquals("value", v.get());
		}

		@Test
		void testUpdatingAnEmptyCellThrows() {
			Variable<Integer> v = Variable.empty();

			assertThrows(NoSuchElementException.class, () -> v.updateAndGet(n -> n - 1));
			assertThrows(NoSuchElementException.class, () -> v.getAndUpdate(n -> n - 1));
		}

		@Test
		void testUpdaterMustNotBeNull() {
			Variable<Integer> v = Variable.of(1);

			assertThrows(NullPointerException.class, () -> v.updateAndGet(null));
			assertThrows(NullPointerException.class, () -> v.getAndUpdate(null));
		}
	}

	@Nested
	class Reads {
		@Test
		void testOrElseAndOrElseGet() {
			Variable<String> present = Variable.of("value");
			Variable<String> absent = Variable.empty();

			assertEquals("value", present.orElse("fallback"));
			assertEquals("fallback", absent.orElse("fallback"));

			assertEquals("value", present.orElseGet(() -> "computed"));
			assertEquals("computed", absent.orElseGet(() -> "computed"));
		}

		@Test
		void testOrElseGetIsNotInvokedWhenPresent() {
			Variable<String> present = Variable.of("value");
			Variable<Boolean> invoked = Variable.of(false);

			present.orElseGet(() -> {
				invoked.set(true);
				return "computed";
			});

			assertFalse(invoked.get(), "the supplier must not run when a value is present");
		}

		@Test
		void testOrElseThrow() {
			assertEquals("value", Variable.of("value").orElseThrow());
			assertThrows(NoSuchElementException.class, () -> Variable.empty().orElseThrow());
			assertThrows(IllegalStateException.class,
					() -> Variable.empty().orElseThrow(IllegalStateException::new));
		}

		@Test
		void testIfPresentAndIfPresentOrElse() {
			Variable<String> seen = Variable.empty();
			Variable.of("value").ifPresent(seen::set);
			assertEquals("value", seen.get());

			seen.clear();
			Variable.<String>empty().ifPresent(seen::set);
			assertTrue(seen.isEmpty(), "the action must not run on an empty cell");

			Variable<String> outcome = Variable.empty();
			Variable.<String>empty().ifPresentOrElse(outcome::set, () -> outcome.set("was empty"));
			assertEquals("was empty", outcome.get());
		}
	}

	/**
	 * The {@code Optional} mirror. Secondary API, but its aliasing behaviour is worth pinning so that
	 * changing it later is a deliberate act rather than an accident.
	 */
	@Nested
	class OptionalMirror {
		@Test
		void testMapAndFlatMap() {
			assertEquals(5, Variable.of("hello").map(String::length).get());
			assertTrue(Variable.<String>empty().map(String::length).isEmpty());

			assertEquals(5, Variable.of("hello").flatMap(s -> Variable.of(s.length())).get());
			assertTrue(Variable.<String>empty().flatMap(s -> Variable.of(s.length())).isEmpty());
		}

		@Test
		void testMapToNullYieldsAnEmptyVariable() {
			assertTrue(Variable.of("hello").map(s -> null).isEmpty());
		}

		@Test
		void testFilter() {
			Variable<String> v = Variable.of("hello");

			assertTrue(v.filter(s -> s.startsWith("h")).isPresent());
			assertTrue(v.filter(s -> s.startsWith("x")).isEmpty());
			assertTrue(Variable.<String>empty().filter(s -> true).isEmpty());
		}

		@Test
		void testFilterAndOrReturnTheSameCellNotASnapshot() {
			// Documented hazard: unlike Optional, this container is mutable, so a "derived" Variable can
			// be the original. Writing through the result writes through the source.
			Variable<String> v = Variable.of("hello");

			assertSame(v, v.filter(s -> s.startsWith("h")));
			assertSame(v, v.or(() -> Variable.of("other")));

			v.filter(s -> true).set("mutated");
			assertEquals("mutated", v.get());
		}

		@Test
		void testOrSuppliesOnlyWhenEmpty() {
			assertEquals("other", Variable.<String>empty().or(() -> Variable.of("other")).get());
			assertEquals("value", Variable.of("value").or(() -> Variable.of("other")).get());
		}

		@Test
		void testStreamAndToOptional() {
			assertEquals(List.of("value"), Variable.of("value").stream().toList());
			assertEquals(List.of(), Variable.empty().stream().toList());

			assertEquals(Optional.of("value"), Variable.of("value").toOptional());
			assertEquals(Optional.empty(), Variable.empty().toOptional());
		}

		@Test
		void testToOptionalIsASnapshot() {
			Variable<String> v = Variable.of("first");
			Optional<String> snapshot = v.toOptional();

			v.set("second");
			assertEquals(Optional.of("first"), snapshot, "the Optional must not track later writes");
		}
	}

	@Nested
	class ObjectMethods {
		@Test
		void testEqualsAndHashCode() {
			assertEquals(Variable.of("value"), Variable.of("value"));
			assertEquals(Variable.of("value").hashCode(), Variable.of("value").hashCode());

			assertEquals(Variable.empty(), Variable.empty());
			assertEquals(0, Variable.empty().hashCode());

			assertNotEquals(Variable.of("value"), Variable.of("other"));
			assertNotEquals(Variable.of("value"), Variable.empty());
		}

		@Test
		void testEqualsHandlesNullAndForeignTypes() {
			Variable<String> v = Variable.of("value");

			assertNotEquals(null, v);
			assertNotEquals("value", v);
			assertEquals(v, v);
		}

		@Test
		void testEqualityFollowsMutation() {
			// A consequence of being mutable, and the reason a Variable is a poor map key.
			Variable<String> a = Variable.of("value");
			Variable<String> b = Variable.of("value");
			assertEquals(a, b);

			a.set("changed");
			assertNotEquals(a, b);
		}

		@Test
		void testToString() {
			assertEquals("Variable[value]", Variable.of("value").toString());
			assertEquals("Variable[]", Variable.empty().toString());
		}
	}
}
