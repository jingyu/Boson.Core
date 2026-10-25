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

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * How a node decides which other nodes it federates with.
 */
public enum FederationMode {
	/**
	 * Federates with any node that proves its identity, as email does: nodes are refused only by being
	 * blocked, suspended, or over their limits. The default.
	 */
	OPEN,
	/**
	 * Federates only with nodes that both sides have agreed to, through a proposal and its acceptance.
	 */
	MUTUAL,
	/**
	 * Federates only with the nodes its operator has listed.
	 */
	ALLOWLIST;

	/**
	 * Returns the name used on the wire and in configuration: the constant's name in lowercase.
	 *
	 * @return the wire name
	 */
	public String wireName() {
		return name().toLowerCase(Locale.ROOT);
	}

	/**
	 * Returns the mode with the given wire name, ignoring case.
	 *
	 * @param name the wire name
	 * @return the mode, or {@code null} if no mode has that name
	 */
	public static @Nullable FederationMode ofWireName(@Nullable String name) {
		if (name == null)
			return null;

		for (FederationMode m : values()) {
			if (m.name().equalsIgnoreCase(name.trim()))
				return m;
		}

		return null;
	}
}
