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

package io.bosonnetwork.kademlia.impl;

/**
 * The Kademlia tuning constants for this module: the default parameter values, and the fixed
 * intervals and thresholds that are not configurable.
 * <p>
 * Centralized so the values that govern one node's behavior can be read and adjusted in one place
 * rather than being spread across the routing, task and RPC layers.
 * </p>
 */
public final class KadConstants {
	// Default values for Kademlia parameters. The effective values are owned by DHT, which receives
	// them from KadNode; these apply only where no configuration is supplied.
	public static final int ALPHA = 3;
	public static final int K = 16;
	public static final int REPLACEMENTS = 8;
	public static final int CONCURRENT_TASKS = 32;

	/**
	 * The concurrency ceiling for low-priority (background maintenance) tasks, applied whenever alpha
	 * allows it. Not configurable: maintenance should not become more parallel just because
	 * foreground lookups do. See {@link KadContext#getLowPriorityAlpha()}.
	 */
	public static final int LOW_PRIORITY_ALPHA = 2;

	public static final int DHT_UPDATE_INTERVAL = 30 * 1000;                        // 30 seconds
	public static final int BOOTSTRAP_MIN_INTERVAL = 4 * 60 * 1000;                 // 4 minutes
	public static final int SELF_LOOKUP_INTERVAL = 30 * 60 * 1000;                  // 30 minutes
	public static final int ROUTING_TABLE_PERSIST_INITIAL_DELAY = 2 * 60 * 1000;    // 2 minutes
	public static final int ROUTING_TABLE_PERSIST_INTERVAL = 10 * 60 * 1000;        // 10 minutes
	public static final int ROUTING_TABLE_MAINTENANCE_INTERVAL = 4 * 60 * 1000;     // 4 minutes
	/**
	 * The minimum interval (in milliseconds) between required bucket refreshes.
	 */
	public static final int BUCKET_REFRESH_INTERVAL = 15 * 60 * 1000; // 15 minutes in milliseconds
	/**
	 * The minimum interval (in milliseconds) between pings to replacement entries.
	 */
	public static final int BUCKET_REPLACEMENT_PING_MIN_INTERVAL = 30 * 1000; // 30 seconds in milliseconds
	public static final int RANDOM_LOOKUP_INTERVAL = 10 * 60 * 1000;                // 10 minutes
	public static final int RANDOM_PING_INTERVAL = 10 * 1000;                       // 10 seconds

	public static final int SUSPICIOUS_NODES_PURGE_INITIAL_DELAY = 60 * 1000;       // 60 seconds
	// Bans/observations expire lazily on read, so this only governs memory reclamation, not accuracy.
	public static final int SUSPICIOUS_NODES_PURGE_INTERVAL = 60 * 1000;            // 60 seconds
	public static final int BOOTSTRAP_IF_LESS_THAN_X_ENTRIES = 30;
	public static final int USE_BOOTSTRAP_NODES_IF_LESS_THAN_X_ENTRIES = 8;

	private KadConstants() {
	}
}