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

package io.bosonnetwork.service;

/**
 * Enum representing different access scopes for API permissions.
 * <p>
 * Each access scope is associated with a specific string value that identifies
 * the level or type of API access.
 */
public enum AccessScope {
	/**
	 * Access scope representing administrative privileges.
	 * This scope grants the highest level of permissions, typically reserved for
	 * system administrators or users who require full control of the API.
	 */
	ADMIN("api:admin"),
	/**
	 * Access scope representing client-level privileges.
	 * This scope is intended for users or applications that interact with the API
	 * as standard clients, providing access to non-administrative features and data.
	 */
	CLIENT("api:client"),
	/**
	 * Access scope representing federation-level privileges.
	 * This scope is intended for accessing APIs related to inter-node or
	 * inter-system communication, enabling the management and integration
	 * of federated resources or services.
	 */
	FEDERATION("api:federation");

	private final String value;

	AccessScope(String value) {
		this.value = value;
	}

	@Override
	public String toString() {
		return value;
	}

	/**
	 * Converts a string representation of an access scope into the corresponding {@link AccessScope} enum value.
	 *
	 * @param value the string value representing the access scope
	 * @return the corresponding {@link AccessScope} enum value
	 * @throws IllegalArgumentException if the provided string does not match any valid access scope
	 */
	public static AccessScope fromString(String value) {
		return switch (value) {
			case "api:admin" -> ADMIN;
			case "api:client" -> CLIENT;
			case "api:federation" -> FEDERATION;
			default -> throw new IllegalArgumentException("Invalid access scope: " + value);
		};
	}
}