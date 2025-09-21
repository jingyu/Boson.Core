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

/**
 * Enumeration of DHT lookup strategies used to specify how lookup operations
 * should be performed within the distributed hash table (DHT) network.
 * <p>
 * Each option represents a different approach to querying data:
 * <ul>
 *   <li>{@link #LOCAL}: Restricts the lookup to local data only, suitable when remote queries are unnecessary or to reduce network overhead.</li>
 *   <li>{@link #ARBITRARY}: Attempts a local lookup first, then queries the DHT network, returning results as soon as they are found to optimize responsiveness.</li>
 *   <li>{@link #OPTIMISTIC}: Queries the DHT network and returns immediately upon the first successful result, prioritizing speed over completeness.</li>
 *   <li>{@link #CONSERVATIVE}: Performs a full iteration of the DHT network lookup to obtain the most accurate and comprehensive result, suitable when accuracy is critical.</li>
 * </ul>
 */
public enum LookupOption {
	/**
	 * Perform lookup using only local data.
	 * <p>
	 * This option is reserved for future use and currently does not query the DHT network.
	 */
	LOCAL,

	/**
	 * Perform a lookup that first queries the local data, then the DHT network.
	 * <p>
	 * The lookup stops and returns as soon as a result is found locally or from the network,
	 * aiming to balance speed and coverage.
	 */
	ARBITRARY,

	/**
	 * Perform a lookup by querying the DHT network and return immediately upon
	 * receiving the first result.
	 * <p>
	 * This approach favors speed, potentially at the cost of completeness.
	 */
	OPTIMISTIC,

	/**
	 * Perform a thorough lookup by querying the entire DHT network.
	 * <p>
	 * The lookup completes a full iteration to gather the most accurate and comprehensive result,
	 * prioritizing accuracy over speed.
	 */
	CONSERVATIVE
}