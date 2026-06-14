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
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * This class is a simple holder for a quadruple of values.
 * <p>
 * It follows the same conventions as {@link Pair} and {@link Triple}, but stores four values instead of two/three.
 * Each element may be {@code null}; {@link #empty()} holds {@code null} for all four.
 *
 * @param <A> type for value a.
 * @param <B> type for value b.
 * @param <C> type for value c.
 * @param <D> type for value d.
 */
public class Quadruple<A, B, C, D> {
	private static final Quadruple<?, ?, ?, ?> EMPTY = new Quadruple<>(null, null, null, null);

	private final @Nullable A a;
	private final @Nullable B b;
	private final @Nullable C c;
	private final @Nullable D d;

	/**
	 * Create a value quadruple object from the given values.
	 *
	 * @param a value a.
	 * @param b value b.
	 * @param c value c.
	 * @param d value d.
	 */
	public Quadruple(@Nullable A a, @Nullable B b, @Nullable C c, @Nullable D d) {
		this.a = a;
		this.b = b;
		this.c = c;
		this.d = d;
	}

	/**
	 * Create a value quadruple object from the given values.
	 *
	 * @param <A1> type for value a.
	 * @param <B1> type for value b.
	 * @param <C1> type for value c.
	 * @param <D1> type for value d.
	 * @param a value a.
	 * @param b value b.
	 * @param c value c.
	 * @param d value d.
	 * @return the new Quadruple object.
	 */
	public static <A1, B1, C1, D1> Quadruple<A1, B1, C1, D1> of(@Nullable A1 a, @Nullable B1 b, @Nullable C1 c, @Nullable D1 d) {
		return new Quadruple<>(a, b, c, d);
	}

	/**
	 * Returns an immutable, empty Quadruple instance in which all four values are null.
	 *
	 * @param <A1> the type of the first value in the quadruple.
	 * @param <B1> the type of the second value in the quadruple.
	 * @param <C1> the type of the third value in the quadruple.
	 * @param <D1> the type of the fourth value in the quadruple.
	 * @return an empty Quadruple instance with null values.
	 */
	@SuppressWarnings("unchecked")
	public static <A1, B1, C1, D1> Quadruple<A1, B1, C1, D1> empty() {
		return (Quadruple<A1, B1, C1, D1>) EMPTY;
	}

	/**
	 * Gets the value a from the quadruple object.
	 *
	 * @return the value a.
	 */
	public @Nullable A a() {
		return a;
	}

	/**
	 * Gets the value b from the quadruple object.
	 *
	 * @return the value b.
	 */
	public @Nullable B b() {
		return b;
	}

	/**
	 * Gets the value c from the quadruple object.
	 *
	 * @return the value c.
	 */
	public @Nullable C c() {
		return c;
	}

	/**
	 * Gets the value d from the quadruple object.
	 *
	 * @return the value d.
	 */
	public @Nullable D d() {
		return d;
	}

	@Override
	public int hashCode() {
		return Objects.hash(a, b, c, d);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;

		if (obj instanceof Quadruple<?, ?, ?, ?> that)
			return Objects.equals(this.a, that.a)
					&& Objects.equals(this.b, that.b)
					&& Objects.equals(this.c, that.c)
					&& Objects.equals(this.d, that.d);

		return false;
	}

	@Override
	public String toString() {
		StringBuilder repr = new StringBuilder();

		Function<Object, String> valueOf = (v) ->
				(v instanceof String) ? "\"" + v + "\"" : String.valueOf(v);

		repr.append("<")
				.append(valueOf.apply(a))
				.append(", ")
				.append(valueOf.apply(b))
				.append(", ")
				.append(valueOf.apply(c))
				.append(", ")
				.append(valueOf.apply(d))
				.append(">");

		return repr.toString();
	}
}