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

/**
 * This class is a simple holder for a triple of values.
 * <p>
 * It follows the same conventions as {@link Pair}, but stores three values instead of two.
 *
 * @param <A> type for value a.
 * @param <B> type for value b.
 * @param <C> type for value c.
 */
public class Triple<A, B, C> {
	private final A a;
	private final B b;
	private final C c;

	/**
	 * Create a value triple object from the given values.
	 *
	 * @param a value a.
	 * @param b value b.
	 * @param c value c.
	 */
	public Triple(A a, B b, C c) {
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
	public static <A1, B1, C1> Triple<A1, B1, C1> of(A1 a, B1 b, C1 c) {
		return new Triple<>(a, b, c);
	}

	/**
	 * Gets the value a from the triple object.
	 *
	 * @return the value a.
	 */
	public A a() {
		return a;
	}

	/**
	 * Gets the value b from the triple object.
	 *
	 * @return the value b.
	 */
	public B b() {
		return b;
	}

	/**
	 * Gets the value c from the triple object.
	 *
	 * @return the value c.
	 */
	public C c() {
		return c;
	}

	@Override
	public int hashCode() {
		return Objects.hash(a, b, c);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;

		if (obj instanceof Triple<?, ?, ?> that)
			return Objects.equals(this.a, that.a) && Objects.equals(this.b, that.b) && Objects.equals(this.c, that.c);

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
				.append(">");

		return repr.toString();
	}
}