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

package io.bosonnetwork.utils;

import java.util.function.Function;

/**
 * This class is a simple holder for a pair of values.
 *
 * @param <A> type for value a.
 * @param <B> type for value b.
 */
public class Pair<A, B> {
	private final A a;
	private final B b;

	/**
	 * Create a value pair object from the given values.
	 *
	 * @param <C> type for value a.
	 * @param <D>type for value b.
	 * @param a value a.
	 * @param b value b.
	 * @return the new Pair object.
	 */
	public static <C, D> Pair<C, D> of(C a, D b) {
		return new Pair<C, D>(a, b);
	}

	/**
	 * Create a value pair object from the given values.
	 *
	 * @param a value a.
	 * @param b value b.
	 */
	public Pair(A a, B b) {
		this.a = a;
		this.b = b;
	}

	/**
	 * Gets the value a from the pair object.
	 *
	 * @return the value a.
	 */
	public A a() {
		return a;
	}

	/**
	 * Gets the value b from the pair object.
	 *
	 * @return the value b.
	 */
	public B b() {
		return b;
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
			.append(">");

		return repr.toString();
	}
}