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

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * This class is a simple holder for a triple of values.
 * <p>
 * It follows the same conventions as {@link Pair}, but stores three values instead of two.
 * Each element may be {@code null}; {@link #empty()} holds {@code null} for all three.
 *
 * @param <A> type for value a.
 * @param <B> type for value b.
 * @param <C> type for value c.
 */
public class Triple<A extends @Nullable Object, B extends @Nullable Object, C extends @Nullable Object> {
	private static final Triple<?, ?, ?> EMPTY = new Triple<>(null, null, null);

	private final @Nullable A a;
	private final @Nullable B b;
	private final @Nullable C c;

	/**
	 * Create a value triple object from the given values.
	 *
	 * @param a value a.
	 * @param b value b.
	 * @param c value c.
	 */
	public Triple(@Nullable A a, @Nullable B b, @Nullable C c) {
		this.a = a;
		this.b = b;
		this.c = c;
	}

	/**
	 * Create a value triple object from the given values.
	 *
	 * @param <A1> type for value a.
	 * @param <B1> type for value b.
	 * @param <C1> type for value c.
	 * @param a value a.
	 * @param b value b.
	 * @param c value c.
	 * @return the new Triple object.
	 */
	public static <A1 extends @Nullable Object, B1 extends @Nullable Object, C1 extends @Nullable Object>
			Triple<A1, B1, C1> of(@Nullable A1 a, @Nullable B1 b, @Nullable C1 c) {
		return new Triple<>(a, b, c);
	}

	/**
	 * Returns an immutable, empty Triple instance in which all three values are null.
	 *
	 * @param <A1> the type of the first value in the triple.
	 * @param <B1> the type of the second value in the triple.
	 * @param <C1> the type of the third value in the triple.
	 * @return an empty Triple instance with null values.
	 */
	@SuppressWarnings("unchecked")
	public static <A1 extends @Nullable Object, B1 extends @Nullable Object, C1 extends @Nullable Object>
			Triple<A1, B1, C1> empty() {
		return (Triple<A1, B1, C1>) EMPTY;
	}

	/**
	 * Gets the value a from the triple object.
	 *
	 * @return the value a.
	 */
	public @Nullable A a() {
		return a;
	}

	/**
	 * Gets the value b from the triple object.
	 *
	 * @return the value b.
	 */
	public @Nullable B b() {
		return b;
	}

	/**
	 * Gets the value c from the triple object.
	 *
	 * @return the value c.
	 */
	public @Nullable C c() {
		return c;
	}

	@Override
	public int hashCode() {
		return Objects.hash(a, b, c);
	}

	@Override
	public boolean equals(@Nullable Object obj) {
		if (this == obj)
			return true;

		if (obj instanceof Triple<?, ?, ?> that)
			return Objects.equals(this.a, that.a) && Objects.equals(this.b, that.b) && Objects.equals(this.c, that.c);

		return false;
	}

	private static String valueOf(@Nullable Object o) {
		return (o instanceof String) ? "\"" + o + "\"" : String.valueOf(o);
	}

	@Override
	public String toString() {
		return "<" + valueOf(a) + ", " + valueOf(b) + ", " + valueOf(c) + ">";
	}
}