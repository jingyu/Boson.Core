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

package io.bosonnetwork;

import java.util.Objects;

/**
 * A generic class representing a result with values for IPv4 and IPv6 networks.
 *
 * @param <T> The type of values stored in the result.
 */
public class Result<T> {
	private T v4;
	private T v6;

	/**
	 * Constructs a Result object with values for IPv4 and IPv6 networks.
	 *
	 * @param v4 the value for the IPv4 network.
	 * @param v6 the value for the IPv6 network.
	 */
	public Result(T v4, T v6) {
		this.v4 = v4;
		this.v6 = v6;
	}

	/**
	 * Gets the value for the IPv4 network.
	 *
	 * @return the value for the IPv4 network.
	 */
	public T getV4() {
		return v4;
	}

	/**
	 * Gets the value for the IPv6 network.
	 *
	 * @return the value for the IPv6 network.
	 */
	public T getV6() {
		return v6;
	}

	/**
	 * Gets the value with preferred network, or null fallback to the other network.
	 *
	 * @param preferred the preferred network type (IPv4 or IPv6).
	 * @return the corresponding value for the specified network type.
	 */
	public T get(Network preferred) {
		Objects.requireNonNull(preferred, "preferred");

		T value = getValue(preferred);
		if (value != null)
			return value;

		Network another = preferred == Network.IPv4 ? Network.IPv6 : Network.IPv4;
		return getValue(another);
	}

	/**
	 * Gets the value based on the specified network type.
	 *
	 * @param network the network type (IPv4 or IPv6).
	 * @return the corresponding value for the specified network type.
	 */
	public T getValue(Network network) {
		Objects.requireNonNull(network, "network");

		return switch (network) {
			case IPv4 -> v4;
			case IPv6 -> v6;
		};
	}

	/**
	 * Sets the value for the specified network type.
	 *
	 * @param network the network type (IPv4 or IPv6).
	 * @param value   the value to be set.
	 */
	protected void setValue(Network network, T value) {
		switch (network) {
		case IPv4:
			v4 = value;

		case IPv6:
			v6 = value;
		}
	}

	/**
	 * Checks if both IPv4 and IPv6 values are unset.
	 *
	 * @return {@code true} if both values are unset, {@code false} otherwise.
	 */
	public boolean isEmpty() {
		return v4 == null && v6 == null;
	}

	/**
	 * Checks if at least one of the values is set.
	 *
	 * @return {@code true} if at least one value is set, {@code false} otherwise.
	 */
	public boolean hasValue() {
		return v4 != null || v6 != null;
	}

	/**
	 * Checks if both IPv4 and IPv6 values are set.
	 *
	 * @return {@code true} if both values are set, {@code false} otherwise.
	 */
	public boolean isComplete() {
		return v4 != null && v6 != null;
	}
}