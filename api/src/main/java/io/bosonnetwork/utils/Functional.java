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

import java.util.function.Consumer;

/**
 * Some functional helper methods.
 */
public class Functional {
	/**
	 * Feeds the object to the {@code Consumer} and return the object.
	 *
	 * @param <T> the object type.
	 * @param obj the target object to feed to the {@code Consumer}
	 * @param c the {@code Consumer}
	 * @return the object
	 */
	public static <T> T tap(T obj, Consumer<T> c) {
		c.accept(obj);
		return obj;
	}

	/**
	 * Functional interface for the {@code Consumer} that throws Exception.
	 *
	 * @param <T> the parameter type
	 * @param <E> the exception type to throw
	 */
	public interface ThrowingConsumer<T, E extends Throwable> {
		/**
		 * Performs this operation on the given argument.
		 *
		 * @param arg the input argument
		 * @throws E if error occurred.
		 */
		void accept(T arg) throws E;
	}

	/**
	 * Functional interface for the {@code Supplier} that throws Exception.
	 *
	 * @param <R> the return type
	 * @param <E> the exception type to throw
	 */
	@FunctionalInterface
	public interface ThrowingSupplier<R, E extends Throwable> {
		/**
		 * Gets a result.
		 *
		 * @return a result.
		 * @throws E if error occurred.
		 */
		R get() throws E;
	}

	/**
	 * Functional interface for the {@code Function} that throws Exception.
	 *
	 * @param <R> the return type
	 * @param <T> the parameter type
	 * @param <E> the exception type to throw
	 */
	@FunctionalInterface
	public interface ThrowingFunction<R, T, E extends Throwable> {
		/**
		 * Applies this function to the given argument.
		 *
		 * @param arg the function argument.
		 * @return the function result.
		 * @throws E if error occurred.
		 */
		R apply(T arg) throws E;
	}

	/**
	 * Wrap the checked exception to unchecked exception.
	 *
	 * @param <T> the return type.
	 * @param f the supplier function with throw exceptions.
	 * @return the result from the supplier.
	 */
	public static <T> T unchecked(ThrowingSupplier<? extends T, ?> f) {
		try {
			return f.get();
		} catch (Throwable e) {
			throwAsUnchecked(e);
			return null;
		}
	}

	private static void throwAsUnchecked(Throwable t) {
		asUnchecked(t);
	}

	@SuppressWarnings("unchecked")
	private static <T extends Throwable> void asUnchecked(Throwable t) throws T {
		throw (T) t;
	}
}