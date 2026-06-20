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
public final class Functional {
	private Functional() {
	}

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
	 * Invokes {@code f} and returns its result; if {@code f} throws a checked exception, that
	 * exception is rethrown without being declared.
	 * <p>
	 * <strong>This uses the "sneaky-throw" idiom</strong> - the original exception is rethrown
	 * as-is using a generic-erasure trick, not wrapped in a {@code RuntimeException}. A caller's
	 * {@code catch (IOException e)} clause will still match an {@code IOException} thrown from
	 * {@code f}, even though this method's signature does not declare it. Use this only where
	 * declaring the checked type is impossible (e.g. inside a {@code Function} / {@code Supplier})
	 * and the caller is prepared for the actual exception type to surface.
	 *
	 * @param <T> the return type.
	 * @param f the supplier function, which may throw any {@link Throwable}.
	 * @return the result from the supplier.
	 */
	public static <T> T unchecked(ThrowingSupplier<? extends T, ?> f) {
		try {
			return f.get();
		} catch (Throwable e) {
			throw rethrow(e);
		}
	}

	@SuppressWarnings("unchecked")
	private static <E extends Throwable> RuntimeException rethrow(Throwable e) throws E {
		throw (E) e;
	}

	/**
	 * Rethrows the {@code e} without being declared.
	 * <p>
	 * <strong>This uses the "sneaky-throw" idiom</strong> - the original exception is rethrown
	 * as-is using a generic-erasure trick, not wrapped in a {@code RuntimeException}. A caller's
	 * {@code catch (IOException e)} clause will still match an {@code IOException} thrown from
	 * this method, even though this method's signature does not declare it. Use this only where
	 * declaring the checked type is impossible (e.g. inside a {@code Function} / {@code Supplier})
	 * and the caller is prepared for the actual exception type to surface.
	 *
	 * @param <E> the exception type
	 * @param e the exception to throw
	 * @throws E the exception
	 */
	@SuppressWarnings("unchecked")
	public static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
		throw (E) e;
	}
}