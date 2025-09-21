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

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * A mutable container object which may or may not contain a value.
 * Unlike {@link Optional}, {@code Variable} allows the contained value to be
 * changed after creation using {@link #set} or {@link #setNullable}.
 * If a value is present, it can be retrieved with {@link #get} or processed
 * using methods like {@link #ifPresent}, {@link #map}, or {@link #flatMap}.
 * If no value is present, certain methods provide default values or actions.
 *
 * <p>This class is particularly useful in lambda expressions or contexts where
 * a mutable reference to a value is needed, such as capturing state in closures.
 * For example:
 * <pre>{@code
 * Variable<String> var = Variable.of("initial");
 * someMethod(() -> var.set("updated"));
 * System.out.println(var.get()); // Prints "updated"
 * }</pre>
 *
 * <p><b>Note:</b> Unlike {@code Optional}, this class is mutable, so care must
 * be taken in multi-threaded environments to avoid race conditions.
 *
 * @param <T> the type of value that may be contained
 */
public class Variable<T> {
	private T value;

	/**
	 * Constructs a {@code Variable} with the specified initial value.
	 *
	 * @param value the initial value, which may be {@code null}
	 */
	protected Variable(T value) {
		this.value = value;
	}

	/**
	 * Returns an empty {@code Variable} instance with no value present.
	 *
	 * @param <T> the type of value
	 * @return an empty {@code Variable}
	 */
	public static <T> Variable<T> empty() {
		return new Variable<>(null);
	}

	/**
	 * Returns a {@code Variable} containing the specified non-null value.
	 *
	 * @param <T> the type of value
	 * @param value the value to be contained, which must be non-null
	 * @return a {@code Variable} containing the specified value
	 * @throws NullPointerException if the value is {@code null}
	 */
	public static <T> Variable<T> of(T value) {
		return new Variable<>(Objects.requireNonNull(value));
	}

	/**
	 * Returns a {@code Variable} containing the specified value, which may be
	 * {@code null}.
	 *
	 * @param <T> the type of value
	 * @param value the value to be contained, which may be {@code null}
	 * @return a {@code Variable} containing the specified value
	 */
	public static <T> Variable<T> ofNullable(T value) {
		return new Variable<>(value);
	}

	/**
	 * Sets the value contained by this {@code Variable} to the specified
	 * non-null value.
	 *
	 * @param value the new value, which must be non-null
	 * @throws NullPointerException if the value is {@code null}
	 */
	public void set(T value) {
		this.value = Objects.requireNonNull(value, "Value cannot be null");
	}

	/**
	 * Sets the value contained by this {@code Variable} to the specified value,
	 * which may be {@code null}, only if no value is currently present.
	 *
	 * <p>
	 * This method is useful for conditionally setting a value when the
	 * {@code Variable} is empty. To unconditionally set a nullable value, use
	 * the constructor or {@link #ofNullable}.
	 * </p>
	 *
	 * @param value the new value, which may be {@code null}
	 */
	public void setNullable(T value) {
		if (this.value == null)
			this.value = value;
	}

	/**
	 * Returns the value if present, otherwise throws {@code NoSuchElementException}.
	 *
	 * <p>
	 * This method is provided for compatibility with {@link Optional#get()}.
	 * The preferred alternative is {@link #orElseThrow()}.
	 * </p>
	 *
	 * @return the value held by this {@code Variable}
	 * @throws NoSuchElementException if no value is present
	 */
	public T get() {
		if (value == null)
			throw new NoSuchElementException("No value present");

		return value;
	}

	/**
	 * If a value is present, returns {@code true}, otherwise {@code false}.
	 *
	 * @return {@code true} if a value is present, otherwise {@code false}
	 */
	public boolean isPresent() {
		return value != null;
	}

	/**
	 * If a value is  not present, returns {@code true}, otherwise {@code false}.
	 *
	 * @return  {@code true} if a value is not present, otherwise {@code false}
	 */
	public boolean isEmpty() {
		return value == null;
	}

	/**
	 * If a value is present, performs the given action with the value,
	 * otherwise does nothing.
	 *
	 * @param action the action to be performed, if a value is present
	 * @throws NullPointerException if value is present and the given action is
	 *         {@code null}
	 */
	public void ifPresent(Consumer<? super T> action) {
		if (value != null) {
			action.accept(value);
		}
	}

	/**
	 * If a value is present, performs the given action with the value,
	 * otherwise performs the given empty-based action.
	 *
	 * @param action the action to be performed, if a value is present
	 * @param emptyAction the empty-based action to be performed, if no value is
	 *        present
	 * @throws NullPointerException if a value is present and the given action
	 *         is {@code null}, or no value is present and the given empty-based
	 *         action is {@code null}.
	 */
	public void ifPresentOrElse(Consumer<? super T> action, Runnable emptyAction) {
		if (value != null) {
			action.accept(value);
		} else {
			emptyAction.run();
		}
	}

	/**
	 * If a value is present, and the value matches the given predicate,
	 * returns an {@code Variable} describing the value, otherwise returns an
	 * empty {@code Variable}.
	 *
	 * @param predicate the predicate to apply to a value, if present
	 * @return an {@code Variable} describing the value of this
	 *         {@code Variable}, if a value is present and the value matches the
	 *         given predicate, otherwise an empty {@code Variable}
	 * @throws NullPointerException if the predicate is {@code null}
	 */
	public Variable<T> filter(Predicate<? super T> predicate) {
		Objects.requireNonNull(predicate);
		if (!isPresent()) {
			return this;
		} else {
			return predicate.test(value) ? this : empty();
		}
	}

	/**
	 * If a value is present, returns an {@code Variable} describing (as if by
	 * {@link #ofNullable}) the result of applying the given mapping function to
	 * the value, otherwise returns an empty {@code Variable}.
	 *
	 * <p>If the mapping function returns a {@code null} result then this method
	 * returns an empty {@code Variable}.
	 *
	 * <p>
	 * This method allows post-processing of the value without explicit null
	 * checks. For example:
	 * </p>
	 * <pre>{@code
	 *     Variable<String> var = Variable.of("hello");
	 *     Variable<Integer> length = var.map(String::length);
	 * }</pre>
	 *
	 * @param mapper the mapping function to apply to a value, if present
	 * @param <U> The type of the value returned from the mapping function
	 * @return an {@code Variable} describing the result of applying a mapping
	 *         function to the value of this {@code Variable}, if a value is
	 *         present, otherwise an empty {@code Variable}
	 * @throws NullPointerException if the mapping function is {@code null}
	 */
	public <U> Variable<U> map(Function<? super T, ? extends U> mapper) {
		Objects.requireNonNull(mapper);
		if (!isPresent()) {
			return empty();
		} else {
			return Variable.ofNullable(mapper.apply(value));
		}
	}

	/**
	 * If a value is present, returns the result of applying the given
	 * {@code Variable}-bearing mapping function to the value, otherwise returns
	 * an empty {@code Variable}.
	 *
	 * <p>
	 * This method is similar to {@link #map}, but the mapper returns a
	 * {@code Variable}, avoiding the need to wrap the result in an additional
	 * {@code Variable}.
	 * </p>
	 *
	 * @param <U> The type of value of the {@code Variable} returned by the
	 *            mapping function
	 * @param mapper the mapping function to apply to a value, if present
	 * @return the result of applying an {@code Variable}-bearing mapping
	 *         function to the value of this {@code Variable}, if a value is
	 *         present, otherwise an empty {@code Variable}
	 * @throws NullPointerException if the mapping function is {@code null} or
	 *         returns a {@code null} result
	 */
	public <U> Variable<U> flatMap(Function<? super T, ? extends Variable<? extends U>> mapper) {
		Objects.requireNonNull(mapper);
		if (!isPresent()) {
			return empty();
		} else {
			@SuppressWarnings("unchecked")
			Variable<U> r = (Variable<U>) mapper.apply(value);
			return Objects.requireNonNull(r);
		}
	}

	/**
	 * If a value is present, returns a sequential {@link Stream} containing
	 * only that value, otherwise returns an empty {@code Stream}.
	 *
	 * <p>
	 * This method can be used to transform a {@code Stream} of optional
	 * elements to a {@code Stream} of present value elements:
	 * </p>
	 * <pre>{@code
	 *     Stream<Variable<T>> vars = ..
	 *     Stream<T> s = vars.flatMap(Variable::stream)
	 * }</pre>
	 *
	 * @return the optional value as a {@code Stream}
	 */
	public Stream<T> stream() {
		if (!isPresent())
			return Stream.empty();
		else
			return Stream.of(value);
	}

	/**
	 * If a value is present, returns an {@code Variable} describing the value,
	 * otherwise returns an {@code Variable} produced by the supplying function.
	 *
	 * @param supplier the supplying function that produces an {@code Variable}
	 *        to be returned
	 * @return returns an {@code Variable} describing the value of this
	 *         {@code Variable}, if a value is present, otherwise an
	 *         {@code Variable} produced by the supplying function.
	 * @throws NullPointerException if the supplying function is {@code null} or
	 *         produces a {@code null} result
	 */
	public Variable<T> or(Supplier<? extends Variable<? extends T>> supplier) {
		Objects.requireNonNull(supplier);
		if (isPresent()) {
			return this;
		} else {
			@SuppressWarnings("unchecked")
			Variable<T> r = (Variable<T>) supplier.get();
			return Objects.requireNonNull(r);
		}
	}

	/**
	 * If a value is present, returns the value, otherwise returns
	 * {@code other}.
	 *
	 * @param other the value to be returned, if no value is present.
	 *        May be {@code null}.
	 * @return the value, if present, otherwise {@code other}
	 */
	public T orElse(T other) {
		return value != null ? value : other;
	}

	/**
	 * If a value is present, returns the value, otherwise returns the result
	 * produced by the supplying function.
	 *
	 * @param supplier the supplying function that produces a value to be returned
	 * @return the value, if present, otherwise the result produced by the
	 *         supplying function
	 * @throws NullPointerException if no value is present and the supplying
	 *         function is {@code null}
	 */
	public T orElseGet(Supplier<? extends T> supplier) {
		return value != null ? value : supplier.get();
	}

	/**
	 * If a value is present, returns the value, otherwise throws
	 * {@code NoSuchElementException}.
	 *
	 * @return the non-{@code null} value described by this {@code Variable}
	 * @throws NoSuchElementException if no value is present
	 */
	public T orElseThrow() {
		if (value == null)
			throw new NoSuchElementException("No value present");

		return value;
	}

	/**
	 * If a value is present, returns the value, otherwise throws an exception
	 * produced by the exception supplying function.
	 *
	 * <p>
	 * A method reference to the exception constructor with an empty argument
	 * list can be used as the supplier. For example,
	 * {@code IllegalStateException::new}
	 * </p>
	 *
	 * @param <X> Type of the exception to be thrown
	 * @param exceptionSupplier the supplying function that produces an
	 *        exception to be thrown
	 * @return the value, if present
	 * @throws X if no value is present
	 * @throws NullPointerException if no value is present and the exception
	 *          supplying function is {@code null}
	 */
	public <X extends Throwable> T orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
		if (value != null)
			return value;

		throw exceptionSupplier.get();
	}

	/**
	 * Converts this {@code Variable} to an {@link Optional} containing the same value.
	 * If no value is present, returns an empty {@code Optional}.
	 *
	 * <p>
	 * This method is useful for interoperability with APIs that expect an
	 * {@code Optional}. Since {@code Variable} is mutable and {@code Optional}
	 * is immutable, the returned {@code Optional} captures the current value
	 * at the time of invocation, and subsequent changes to this {@code Variable}
	 * do not affect the returned {@code Optional}.
	 * </p>
	 *
	 * @return an {@code Optional} containing the value of this {@code Variable}
	 *         if present, otherwise an empty {@code Optional}
	 */
	public Optional<T> toOptional() {
		if (value == null)
			return Optional.empty();

		return Optional.of(value);
	}

	/**
	 * Indicates whether some other object is "equal to" this {@code Variable}.
	 * The other object is considered equal if:
	 * <ul>
	 * <li>it is also an {@code Variable} and;
	 * <li>both instances have no value present or;
	 * <li>the present values are "equal to" each other via {@code equals()}.
	 * </ul>
	 *
	 * @param obj an object to be tested for equality
	 * @return {@code true} if the other object is "equal to" this object
	 *         otherwise {@code false}
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;

		return obj instanceof Variable<?> other
				&& Objects.equals(value, other.value);
	}

	/**
	 * Returns the hash code of the value, if present, otherwise {@code 0}
	 * (zero) if no value is present.
	 *
	 * @return hash code value of the present value or {@code 0} if no value is
	 *         present
	 */
	@Override
	public int hashCode() {
		return Objects.hashCode(value);
	}

	/**
	 * Returns a non-empty string representation of this {@code Variable}
	 * suitable for debugging.  The exact presentation format is unspecified and
	 * may vary between implementations and versions.
	 *
	 * @return the string representation of this instance
	 */
	@Override
	public String toString() {
		return value != null ? ("Variable[" + value + "]") : "Variable[]";
	}
}