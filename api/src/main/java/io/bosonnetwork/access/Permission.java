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

package io.bosonnetwork.access;

import java.util.Map;

/**
 * The interface that representing the access to the specified service.
 */
public interface Permission {
	/**
	 * The access permissions: allow and deny.
	 */
	enum Access {
		/**
		 * Allow access.
		 */
		Allow,

		/**
		 * Deny access.
		 */
		Deny;

		/**
		 * Returns the enum constant of the specified enum type with the specified name.
		 * The name could be upper-case or low-case.
		 *
		 * @param name the name of the constant to return.
		 * @return the enum constant of the specified enum type with the specified name.
		 */
		public static Access of(String name) {
			return switch (name.toLowerCase()) {
				case "allow" -> Allow;
				case "deny" -> Deny;
				default -> throw new IllegalArgumentException("Unknown: " + name);
			};
		}
	}

	/**
	 * Gets the id of the target service that this permission described.
	 *
	 * @return the service id string.
	 */
	String getTargetServiceId();

	/**
	 * Gets the access type.
	 *
	 * @return the access type.
	 */
	Access getAccess();

	/**
	 * Checks if the access is allowed to the target service.
	 *
	 * @return true if allowed, false otherwise.
	 */
	default boolean isAllow() {
		return getAccess() == Access.Allow;
	}

	/**
	 * Checks if the access is denied to the target service.
	 *
	 * @return true if denied, false otherwise.
	 */
	default boolean isDeny() {
		return getAccess() == Access.Deny;
	}

	/**
	 * Gets the extra properties that related with the permission.
	 *
	 * @return the properties in {@code Map} object.
	 */
	Map<String, Object> getProperties();
}