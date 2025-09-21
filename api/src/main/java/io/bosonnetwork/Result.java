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
	/** The value for the IPv4 network. */
	private T v4;
	/** The value for the IPv6 network. */
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
	 * Creates a new {@code Result} instance with the specified values for IPv4 and IPv6 networks.
	 *
	 * @param v4 the value for the IPv4 network.
	 * @param v6 the value for the IPv6 network.
	 * @param <T> the type of the values for the IPv4 and IPv6 networks.
	 * @return a new {@code Result} instance containing the specified values.
	 */
	public static <T> Result<T> of(T v4, T v6) {
		return new Result<>(v4, v6);
	}

	/**
	 * Creates a {@code Result} object containing values for IPv4 and IPv6 networks
	 * based on the specified {@code Network} type.
	 *
	 * @param <T> the type of the value for the network.
	 * @param network the network type (IPv4 or IPv6) used to determine which value to include.
	 * @param v the value to associate with the network type.
	 * @return a {@code Result} object containing the value for IPv4 if the network is IPv4,
	 *         the value for IPv6 if the network is IPv6, or {@code null} for unsupported networks.
	 */
	public static <T> Result<T> ofNetwork(Network network, T v) {
		return new Result<>(network.isIPv4() ? v : null, network.isIPv6() ? v : null);
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

	@Override
	public int hashCode() {
		return 0x6030A + Objects.hash(v4, v6);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;

		if (obj instanceof Result<?> other)
			return Objects.equals(v4, other.v4) && Objects.equals(v6, other.v6);

		return false;
	}
}