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
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

/**
 * A mutable single-slot cell, for state that has to be updated from inside a lambda.
 *
 * <p>Java requires a captured local to be effectively final, so a closure cannot assign to one. The
 * usual workarounds are a one-element array or an {@code AtomicInteger} / {@code AtomicReference};
 * this is the same thing without the array's noise or the atomic's synchronization overhead, for the
 * common case where the lambda and its enclosing code run on one thread. The typical value is a
 * counter or another primitive:
 * <pre>{@code
 * Variable<Integer> outstanding = Variable.of(calls.size());
 * calls.forEach(c -> c.onComplete(v -> {
 *     if (outstanding.updateAndGet(n -> n - 1) == 0)
 *         finish();
 * }));
 * }</pre>
 *
 * <p><b>Not a mutable {@link Optional}.</b> It carries the same vocabulary - {@link #isPresent},
 * {@link #map}, {@link #orElse} - because an empty cell has to be expressible, but the intent is a
 * writable slot rather than a value-returning pipeline. Two consequences follow. Absence is
 * represented by {@code null}, so for a nullable {@code T} "empty" and "holds null" cannot be told
 * apart. And the {@code Optional}-shaped methods that return a {@code Variable} - {@link #filter},
 * {@link #or} - may return {@code this}, so the result is not a snapshot: writing through it writes
 * through the original.
 *
 * <p><b>Not thread-safe</b>, deliberately - that is the whole point of preferring it to an atomic.
 * The field is not {@code volatile}, so a value written on one thread is not guaranteed visible on
 * another. Use {@code AtomicReference} and friends when the cell really is shared. Note also that a
 * {@code Variable<Integer>} boxes on every update where an {@code AtomicInteger} holds an {@code int};
 * that is the trade for being generic, and it is worth knowing before putting one in a hot loop.
 *
 * <p>The API is in two halves. {@link #get}, {@link #set}, {@link #isPresent}, {@link #clear} and the
 * update family ({@link #updateAndGet}, {@link #getAndUpdate}, {@link #getAndSet}) are the cell
 * vocabulary and cover the intended use. The rest -
 * {@link #map}, {@link #flatMap}, {@link #filter}, {@link #or}, {@link #stream}, {@link #toOptional}
 * and the {@code orElse} family - mirror {@code Optional} for interoperability and are secondary;
 * reach for them only when a value really is being passed along rather than held.
 *
 * @param <T> the type of value that may be contained
 */
public class Variable<T extends @Nullable Object> {
	private @Nullable T value;

	/**
	 * Constructs a {@code Variable} with the specified initial value.
	 *
	 * @param value the initial value, which may be {@code null}
	 */
	protected Variable(@Nullable T value) {
		this.value = value;
	}

	/**
	 * Returns an empty {@code Variable} instance with no value present.
	 *
	 * @param <T> the type of value
	 * @return an empty {@code Variable}
	 */
	public static <T extends @Nullable Object> Variable<T> empty() {
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
	public static <T extends @Nullable Object> Variable<T> of(T value) {
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
	public static <T extends @Nullable Object> Variable<T> ofNullable(@Nullable T value) {
		return new Variable<>(value);
	}

	/**
	 * Sets the value.
	 * <p>
	 * With {@link #get()}, the pair this class exists for: the write happens inside a lambda, the read
	 * after it returns. {@code null} is rejected because it is how absence is represented - use
	 * {@link #clear()} to empty the cell deliberately.
	 * </p>
	 *
	 * @param value the new value, which must be non-null
	 * @throws NullPointerException if the value is {@code null}
	 */
	public void set(T value) {
		this.value = Objects.requireNonNull(value, "Value cannot be null");
	}

	/**
	 * Replaces the present value with the result of applying the given operator to it, and returns the
	 * new value.
	 * <p>
	 * The counterpart of {@code AtomicInteger.updateAndGet} and {@code AtomicReference.updateAndGet},
	 * and the method to reach for when this is being used as an unsynchronized mutable cell: the
	 * operator sees the value and nothing else, so a counter reads as
	 * {@code counter.updateAndGet(v -> v - 1)} with no empty case to handle. Throwing on an empty cell
	 * is deliberate: a cell seeded with {@link #of} and updated from a callback always holds a value, so
	 * an empty one means the callback never ran. Where absence is genuine, test {@link #isPresent()}
	 * first, or seed the cell with {@link #setIfAbsent} before updating it.
	 * </p>
	 *
	 * @param updater the operator to apply to the present value
	 * @return the new value
	 * @throws NoSuchElementException if no value is present
	 * @throws NullPointerException if the operator is {@code null}
	 */
	public T updateAndGet(UnaryOperator<T> updater) {
		Objects.requireNonNull(updater);
		T updated = updater.apply(get());
		value = updated;
		return updated;
	}

	/**
	 * Replaces the present value with the result of applying the given operator to it, and returns the
	 * <em>previous</em> value.
	 * <p>
	 * As {@code AtomicInteger.getAndUpdate}. The counterpart of {@link #updateAndGet}, for when the
	 * decision is about the value going out rather than the one coming in - claiming a slot, or reading
	 * a counter as it is consumed.
	 * </p>
	 *
	 * @param updater the operator to apply to the present value
	 * @return the value held before the update
	 * @throws NoSuchElementException if no value is present
	 * @throws NullPointerException if the operator is {@code null}
	 */
	public T getAndUpdate(UnaryOperator<T> updater) {
		Objects.requireNonNull(updater);
		T previous = get();
		value = updater.apply(previous);
		return previous;
	}

	/**
	 * Sets the value and returns what was there before.
	 * <p>
	 * As {@code AtomicReference.getAndSet}. The single-threaded use is handing a value out of a lambda
	 * and resetting the slot in one step - {@code pending.getAndSet(next)} rather than a read followed
	 * by a write that a later reader could interleave with.
	 * </p>
	 *
	 * @param value the new value, which must be non-null
	 * @return the value held before the call, or {@code null} if the variable was empty
	 * @throws NullPointerException if the value is {@code null}
	 */
	public @Nullable T getAndSet(T value) {
		@Nullable T previous = this.value;
		this.value = Objects.requireNonNull(value, "Value cannot be null");
		return previous;
	}

	/**
	 * Empties the cell.
	 * <p>
	 * The counterpart to {@link #set}, which rejects {@code null} precisely so that emptying is an
	 * explicit act rather than something a stray null can do by accident. After this,
	 * {@link #isPresent()} is false and {@link #get()} throws.
	 * </p>
	 */
	public void clear() {
		this.value = null;
	}

	/**
	 * Sets the value to {@code value} only if no value is currently present. If a value is
	 * already held, this method does nothing - the existing value is not replaced.
	 * <p>
	 * Equivalent to {@code Map.putIfAbsent} semantics, but applied to a single slot. The
	 * argument may be {@code null}; passing {@code null} when the variable is already empty
	 * is a no-op as well (the variable remains empty).
	 *
	 * @param value the new value, which may be {@code null}
	 */
	public void setIfAbsent(@Nullable T value) {
		if (this.value == null)
			this.value = value;
	}

	/**
	 * Returns the value, throwing if the cell is empty.
	 * <p>
	 * The primary reader. Throwing rather than returning {@code null} is the right default for the way
	 * this class is used - a cell seeded with {@link #of} and written by a lambda always holds a value,
	 * so an empty one means the lambda did not run, which is a bug worth surfacing rather than
	 * propagating as a null. Use {@link #isPresent()} or {@link #orElse} where absence is expected.
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
	 * If a value is not present, returns {@code true}, otherwise {@code false}.
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
	 * <p>
	 * <b>Returns {@code this}, not a copy</b>, when the value matches - so the result is a live view of
	 * the same cell, and writing through it writes through the original. {@code Optional} cannot have
	 * this hazard because it is immutable. Secondary API: the cell vocabulary ({@link #get},
	 * {@link #set}, {@link #updateAndGet}) is what this class is for.
	 * </p>
	 *
	 * @param predicate the predicate to apply to a value, if present
	 * @return an {@code Variable} describing the value of this
	 *         {@code Variable}, if a value is present and the value matches the
	 *         given predicate, otherwise an empty {@code Variable}
	 * @throws NullPointerException if the predicate is {@code null}
	 */
	public Variable<T> filter(Predicate<? super T> predicate) {
		Objects.requireNonNull(predicate);
		if (value == null) {
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
		if (value == null) {
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
		if (value == null) {
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
	// Suppress NullAway: Stream's type parameter is non-null in the JSpecify model, which T's
	// @Nullable bound cannot satisfy. Unavoidable here, unlike a suppression over our own signature.
	@SuppressWarnings("NullAway")
	public Stream<T> stream() {
		return Stream.ofNullable(value);
	}

	/**
	 * If a value is present, returns an {@code Variable} describing the value,
	 * otherwise returns an {@code Variable} produced by the supplying function.
	 *
	 * <p>
	 * <b>Returns {@code this}, not a copy</b>, when a value is present - see the note on
	 * {@link #filter}. Secondary API.
	 * </p>
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
	public @Nullable T orElse(@Nullable T other) {
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
	// Suppress NullAway: Optional's type parameter is non-null in the JSpecify model, which T's
	// @Nullable bound cannot satisfy. Unavoidable here, unlike a suppression over our own signature.
	@SuppressWarnings("NullAway")
	public Optional<T> toOptional() {
		return Optional.ofNullable(value);
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
	public boolean equals(@Nullable Object obj) {
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