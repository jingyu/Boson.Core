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

package io.bosonnetwork.kademlia.routing;

import java.math.RoundingMode;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.text.DecimalFormat;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.Version;
import io.bosonnetwork.kademlia.rpc.RpcServer;
import io.bosonnetwork.kademlia.utils.ExponentialWeightedMovingAverage;

/**
 * Represents an entry in a Kademlia routing table bucket (KBucket).
 * <p>
 * Each KBucketEntry corresponds to a node in the Kademlia Distributed Hash Table (DHT),
 * encapsulating its network address, node ID, and metadata used for managing connectivity
 * and routing decisions.
 * </p>
 * <p>
 * This class extends {@link NodeInfo} by adding fields and logic to track:
 * <ul>
 *   <li><b>created:</b> Timestamp when this entry was first created.</li>
 *   <li><b>lastSeen:</b> Timestamp of the last successful interaction with the node.</li>
 *   <li><b>lastSend:</b> Timestamp of the last outgoing request sent to the node (0 if never sent).</li>
 *   <li><b>failedRequests:</b> Number of consecutive failed requests to the node, used for exponential backoff and eviction decisions.</li>
 *   <li><b>reachable:</b> Whether the node is currently considered reachable based on recent responses.</li>
 *   <li><b>avgRTT:</b> An exponential weighted moving average of the round-trip time (RTT) to this node, used to prioritize nodes with lower latency.</li>
 * </ul>
 * </p>
 * <p>
 * The class provides methods for managing node liveness, ping backoff, merging updated node information,
 * and serialization/deserialization for routing table persistence.
 * </p>
 */
public class KBucketEntry extends NodeInfo {
	// 5 failures or timeouts, used for exponential back-off as per Kademlia paper
	public static final	int MAX_FAILURES = 5;
	public static final int OLD_AND_STALE_FAILURES = 2;

	// haven't seen it for a long time + timeout == evict sooner than pure timeout
	// based threshold. e.g. for old entries that we haven't touched for a long time
	public static final int OLD_AND_STALE_TIME = 15 * 60 * 1000; // 15 minutes
	public static final int PING_BACKOFF_BASE_INTERVAL = 60 * 1000; // 1 minute

	private static final double RTT_EMA_WEIGHT = 0.3;

	private long created;
	private long lastSeen;
	/**
	 *  0 = never sent
	 * >0 = last send
	 */
	private long lastSend;

	private boolean reachable;

	/**
	 *  0 = last query was a success
	 * >0 = query failed
	 */
	private int failedRequests;

	private final ExponentialWeightedMovingAverage avgRTT = new ExponentialWeightedMovingAverage(RTT_EMA_WEIGHT);

	/**
	 * Constructs a new KBucketEntry with the specified node ID and socket address.
	 * Initializes timestamps and state for managing node reachability and request tracking.
	 *
	 * @param id   The unique identifier of the node.
	 * @param addr The socket address (IP and port) of the node.
	 */
	public KBucketEntry(Id id, InetSocketAddress addr) {
		super(id, addr);
		created = System.currentTimeMillis();
		lastSeen = created;
		lastSend = 0;
		reachable = false;
		failedRequests = 0;
	}

	/**
	 * Constructs a new KBucketEntry by copying the node ID and address from an existing NodeInfo.
	 *
	 * @param node The NodeInfo object to copy from.
	 */
	public KBucketEntry(NodeInfo node) {
		this(node.getId(), node.getAddress());
	}

	/**
	 * Constructs a new KBucketEntry by copying all relevant metadata from another KBucketEntry.
	 *
	 * @param entry The existing KBucketEntry to copy.
	 */
	public KBucketEntry(KBucketEntry entry) {
		super(entry.getId(), entry.getAddress());
		created = entry.creationTime();
		lastSeen = entry.lastSeen();
		lastSend = entry.lastSend();
		reachable = entry.isReachable();
		failedRequests = entry.failedRequests();
	}

	/**
	 * Returns the timestamp when this entry was created.
	 *
	 * @return Creation time in milliseconds since epoch.
	 */
	public long creationTime() {
		return created;
	}

	/**
	 * Returns the timestamp of the last successful interaction with this node.
	 *
	 * @return Last seen time in milliseconds since epoch.
	 */
	public long lastSeen() {
		return lastSeen;
	}

	// for testing
	void setLastSeen(long lastSeen) {
		this.lastSeen = lastSeen;
	}

	/**
	 * Returns the timestamp of the last outgoing request sent to this node.
	 *
	 * @return Last send time in milliseconds since epoch, or 0 if never sent.
	 */
	public long lastSend() {
		return lastSend;
	}

	/**
	 * Returns the number of consecutive failed requests to this node.
	 *
	 * @return Number of failed requests.
	 */
	public int failedRequests() {
		return failedRequests;
	}

	/**
	 * Returns whether this node is currently considered reachable.
	 *
	 * @return {@code true} if reachable; {@code false} otherwise.
	 */
	public boolean isReachable() {
		return reachable;
	}

	@SuppressWarnings("SameParameterValue")
	protected void setReachable(boolean reachable) {
		this.reachable = reachable;
	}

	/**
	 * Returns whether this node has never been contacted (no requests sent).
	 *
	 * @return {@code true} if never contacted; {@code false} otherwise.
	 */
	public boolean isNeverContacted() {
		return lastSend == 0;
	}

	/**
	 * Determines if this node is eligible to be included in nodes lists returned by lookup queries.
	 * Nodes with fewer than 3 failed requests and currently reachable are considered eligible.
	 *
	 * @return {@code true} if eligible for nodes list; {@code false} otherwise.
	 */
	public boolean eligibleForNodesList() {
		// 2 timeout can occasionally happen. should be fine to hand it out as long as
		// we've verified it at least once
		return isReachable() && failedRequests < 3;
	}

	/**
	 * Determines if this node is eligible for local lookup operations.
	 * Nodes that are reachable with up to 3 failed requests or nodes with no failures are eligible.
	 *
	 * @return {@code true} if eligible for local lookup; {@code false} otherwise.
	 */
	public boolean eligibleForLocalLookup() {
		return (isReachable() && failedRequests <= 3) || failedRequests <= 0;
	}

	/**
	 * Calculates the exponential backoff interval for pinging a node based on the number of failed requests.
	 *
	 * <p>This method uses exponential backoff to reduce the frequency of ping attempts after consecutive failures.
	 * The backoff interval is calculated as:</p>
	 * <pre>
	 * backoff = PING_BACKOFF_BASE_INTERVAL * 2^(min(MAX_FAILURES, max(0, failedRequests - 1)))
	 * </pre>
	 *
	 * <p>Where:
	 * <ul>
	 *     <li>{@code PING_BACKOFF_BASE_INTERVAL} is the base interval in milliseconds (1 minute).</li>
	 *     <li>{@code failedRequests} is the number of consecutive failed requests to the node.</li>
	 *     <li>{@code MAX_FAILURES} caps the maximum exponent to prevent overflow and excessively long intervals.</li>
	 * </ul>
	 * </p>
	 *
	 * @return the backoff interval in milliseconds, capped at {@link Integer#MAX_VALUE}.
	 */
	private int backoff() {
		// Assertion in test case will guard the MAX_FAILURES not causing overflow
		return PING_BACKOFF_BASE_INTERVAL << Math.min(MAX_FAILURES, Math.max(0, failedRequests - 1));

		/*
		// Calculate the exponent for the backoff interval:
		// It is the number of failed requests minus one, bounded between 0 and MAX_FAILURES.
		int shift = Math.min(MAX_FAILURES, Math.max(0, failedRequests - 1));

		// Calculate the backoff interval by left-shifting the base interval by 'shift' bits,
		// effectively multiplying by 2^shift.
		long value = (long) PING_BACKOFF_BASE_INTERVAL << shift;

		// Cap the value to Integer.MAX_VALUE to avoid overflow when casting to int.
		return (int) Math.min(Integer.MAX_VALUE, value);
		*/
	}

	/**
	 * Determines if the current time is within the backoff window after the last sent request.
	 *
	 * @param now Current time in milliseconds.
	 * @return {@code true} if within backoff window; {@code false} otherwise.
	 */
	private boolean withinBackoffWindow(long now) {
		return failedRequests != 0 && now - lastSend < backoff();
	}

	/**
	 * Determines if the current time is within the backoff window after the last sent request.
	 *
	 * @return {@code true} if within backoff window; {@code false} otherwise.
	 */
	public boolean withinBackoffWindow() {
		return withinBackoffWindow(System.currentTimeMillis());
	}

	/**
	 * Returns the timestamp when the backoff window ends.
	 *
	 * @return Timestamp in milliseconds when backoff window ends, or -1 if no backoff is active.
	 */
	public long backoffWindowEnd() {
		if (failedRequests == 0 || lastSend <= 0)
			return -1L;

		return lastSend + backoff();
	}

	/**
	 * Determines whether this node needs to be pinged to verify its reachability.
	 * <p>
	 * The node is not pinged if it was seen recently (within 30 seconds) to allow NAT entries to expire naturally.
	 * Also respects an exponential backoff window after failed requests to reduce network traffic.
	 * </p>
	 * <p>
	 * Nodes with failed requests or those not seen for a long time (older than {@code OLD_AND_STALE_TIME}) will be pinged.
	 * </p>
	 *
	 * @return {@code true} if the node needs a ping; {@code false} otherwise.
	 */
	public boolean needsPing() {
		long now = System.currentTimeMillis();

		// don't ping if recently seen to allow NAT entries to time out
		// see https://arxiv.org/pdf/1605.05606v1.pdf for numbers
		// and do exponential backoff after failures to reduce traffic
		if (now - lastSeen < 30 * 1000 || withinBackoffWindow(now))
			return false;

		// ping if there have been failures or if the node is old and stale
		return failedRequests != 0 || now - lastSeen > OLD_AND_STALE_TIME;
	}

	/**
	 * Determines if this entry is old and stale, meaning it has had multiple failures
	 * and has not been seen for a long time.
	 *
	 * @return {@code true} if the entry is old and stale; {@code false} otherwise.
	 */
	public boolean oldAndStale() {
		return failedRequests > OLD_AND_STALE_FAILURES &&
				System.currentTimeMillis() - lastSeen > OLD_AND_STALE_TIME;
	}

	/**
	 * Determines if this entry can be removed from the routing table without needing replacement.
	 * <p>
	 * Entries with too many failed requests and which have not been seen since the last request sent
	 * are considered removable.
	 * </p>
	 *
	 * @return {@code true} if removable without replacement; {@code false} otherwise.
	 */
	public boolean removableWithoutReplacement() {
		// some non-reachable nodes may contact us repeatedly, bumping the last seen
		// counter. they might be interesting to keep around so we can keep track of the
		// backoff interval to not waste pings on them
		// but things we haven't heard from in a while can be discarded
		boolean seenSinceLastSend = lastSeen > lastSend;
		return failedRequests > MAX_FAILURES && !seenSinceLastSend;
	}

	/**
	 * Determines if this entry needs to be replaced in the routing table.
	 * <p>
	 * Replacement is needed if the node is unreachable with more than one failed request,
	 * if it exceeds maximum allowed timeouts, or if it is old and stale.
	 * </p>
	 *
	 * @return {@code true} if replacement is needed; {@code false} otherwise.
	 */
	protected boolean needsReplacement() {
		return (failedRequests > 1 && !isReachable()) ||
				failedRequests > MAX_FAILURES ||
				oldAndStale();
	}

	/**
	 * Merges information from another KBucketEntry into this one.
	 * <p>
	 * If the entries represent the same node, this method updates the failed request count,
	 * reachability status, RTT average, and timestamps to the freshest values.
	 * </p>
	 *
	 * @param entry The other KBucketEntry to merge from.
	 */
	protected void merge(KBucketEntry entry) {
		if (this == entry || !this.equals(entry) )
			return;

		// use the failedRequests value from the fresher entry
		if (entry.lastSeen > lastSeen)
			failedRequests = entry.failedRequests;

		if (entry.isReachable())
			setReachable(true);

		// update RTT average if the other entry has a valid average
		if (!Double.isNaN(entry.avgRTT.getAverage()))
			avgRTT.update(entry.avgRTT.getAverage());

		// maintain the earliest creation time and the latest lastSeen and lastSend times
		created = Math.min(created, entry.created);
		lastSeen = Math.max(lastSeen, entry.lastSeen);
		lastSend = Math.max(lastSend, entry.lastSend);
	}

	/**
	 * Returns the current average round-trip time (RTT) to this node, capped at the maximum RPC call timeout.
	 *
	 * @return Average RTT in milliseconds.
	 */
	public int getRTT() {
		return (int) avgRTT.getAverage(RpcServer.RPC_CALL_TIMEOUT_MAX);
	}

	/**
	 * Returns the current average round-trip time (RTT) to this node, capped at the specified default value.
	 *
	 * @param defaultRTT The default RTT to use as upper bound.
	 * @return Average RTT in milliseconds.
	 */
	public int getRTT(int defaultRTT) {
		return (int) avgRTT.getAverage(defaultRTT);
	}

	/**
	 * Updates the lastSeen timestamp to the current time upon receiving an incoming request from this node.
	 * <p>
	 * Note: This does not mark the node as reachable until a full query/response cycle is completed.
	 * </p>
	 */
	/*
	public void onIncomingRequest() {
		lastSeen = System.currentTimeMillis();

		// need full proper query/response cycle to determine reachability
		// if (!reachable)
		// 	reachable = true;
	}
	*/

	/**
	 * Updates the lastSend timestamp to the current time when a request is sent to this node.
	 */
	public void onRequestSent() {
		lastSend = System.currentTimeMillis();
	}

	/**
	 * Updates the lastSend timestamp to the maximum of the current value and the provided timestamp.
	 *
	 * @param lastSent The timestamp to update lastSend with.
	 */
	public void updateLastSent(long lastSent) {
		this.lastSend = Math.max(this.lastSend, lastSent);
	}

	/**
	 * Updates state upon receiving a response from this node.
	 * <p>
	 * Resets failed request count, marks the node as reachable, updates lastSeen timestamp,
	 * and updates RTT average if provided.
	 * </p>
	 *
	 * @param rtt Round-trip time in milliseconds; -1 if unknown.
	 */
	public void onResponded(long rtt) {
		lastSeen = System.currentTimeMillis();
		failedRequests = 0;
		reachable = true;
		if (rtt > 0)
			avgRTT.update(rtt);
	}

	/**
	 * Should be called to signal that a request to this peer has timed out;
	 * increments the failed request count.
	 */
	protected void onTimeout() {
		failedRequests++;
	}

	/**
	 * Determines if this entry matches another KBucketEntry based on node ID and address.
	 *
	 * @param entry The other KBucketEntry to compare.
	 * @return {@code true} if they match; {@code false} otherwise.
	 */
	public boolean matches(KBucketEntry entry) {
		if (entry == null)
			return false;

		return super.matches(entry);
	}

	/**
	 * Serializes this KBucketEntry to a map for routing table persistence.
	 *
	 * @return A map containing the serialized fields of this entry.
	 */
	Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<>();

		map.put("id", getId().bytes());
		map.put("addr", getIpAddress().getAddress());
		map.put("port", getPort());
		if (created > 0)
			map.put("created", created);
		if (lastSeen > 0)
			map.put("lastSeen", lastSeen);
		if (lastSend > 0)
			map.put("lastSend", lastSend);
		if (failedRequests > 0)
			map.put("failedRequests", failedRequests);
		if (reachable)
			map.put("reachable", reachable);
		if (avgRTT.isInitialized())
			map.put("avgRtt", getRTT());
		if (getVersion() != 0)
			map.put("version", getVersion());

		return map;
	}

	/**
	 * Deserializes a KBucketEntry from a map.
	 *
	 * @param map The map containing serialized entry data.
	 * @return A new KBucketEntry instance or {@code null} if deserialization fails.
	 */
	static public KBucketEntry fromMap(Map<String, Object> map) {
		try {
			Id id = Id.of((byte[])map.get("id"));
			InetAddress addr = InetAddress.getByAddress((byte[])map.get("addr"));
			int port = (int)map.get("port");

			KBucketEntry entry = new KBucketEntry(id, new InetSocketAddress(addr, port));

			entry.created = (long)map.getOrDefault("created", System.currentTimeMillis());
			entry.lastSeen = (long)map.getOrDefault("lastSeen", entry.created);
			entry.lastSend = (long)map.getOrDefault("lastSend", 0L);
			entry.failedRequests = (int)map.getOrDefault("failedRequests", 0);
			entry.reachable = (boolean)map.getOrDefault("reachable", false);

			int avgRtt = (int) map.getOrDefault("avgRtt", -1);
			if (avgRtt > 0)
				entry.avgRTT.reset(avgRtt); // set the EMA average directly

			entry.setVersion((int)map.getOrDefault("version", 0));

			return entry;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Comparator for sorting entries by ascending creation time.
	 * The oldest entry will be first.
	 *
	 * @param entry1 First entry to compare.
	 * @param entry2 Second entry to compare.
	 * @return Negative if entry1 is older, positive if newer, zero if equal.
	 */
	protected static int ageOrder(KBucketEntry entry1, KBucketEntry entry2) {
		return Long.compare(entry1.created, entry2.created);
	}

	/**
	 * Comparator for sorting entries by ascending last seen time.
	 * The least seen entry will be first.
	 *
	 * @param entry1 First entry to compare.
	 * @param entry2 Second entry to compare.
	 * @return Negative if entry1 is older, positive if newer, zero if equal.
	 */
	protected static int lastSeenOrder(KBucketEntry entry1, KBucketEntry entry2) {
		return Long.compare(entry1.lastSeen, entry2.lastSeen);
	}

	/**
	 * Comparator for sorting entries to determine replacement priority.
	 * <p>
	 * Prioritizes entries with shorter RTT, then more recent lastSeen, then older creation time.
	 * </p>
	 *
	 * @param entry1 First entry to compare.
	 * @param entry2 Second entry to compare.
	 * @return Negative if entry1 has higher priority, positive if lower, zero if equal.
	 */
	protected static int replacementOrder(KBucketEntry entry1, KBucketEntry entry2) {
		// reachable is more important
		int diff = -Boolean.compare(entry1.isReachable(), entry2.isReachable());
		if (diff != 0)
			return diff;

		// shorter RTT is more important
		diff = Integer.compare(entry1.getRTT(-1), entry2.getRTT(-1));
		if (diff != 0)
			return diff;

		// seen more recently is more important
		diff = -Long.compare(entry1.lastSeen, entry2.lastSeen);
		if (diff != 0)
			return diff;

		// older is more important
		return Long.compare(entry1.creationTime(), entry2.creationTime());
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;

		if (o instanceof KBucketEntry that)
			return super.equals(that);

		return false;
	}

	/*
	@Override
	public int hashCode() {
		return super.hashCode() + 0x006b6265; //kbe
	}
	*/

	/**
	 * Returns a string representation of this KBucketEntry including node ID, address,
	 * timestamps, failure count, reachability, RTT, and version.
	 *
	 * @return A human-readable string describing this entry.
	 */
	@Override
	public String toString() {
		StringBuilder repr = new StringBuilder(128);
		long now = System.currentTimeMillis();

		repr.append(getId().toHexString()).append('/').append(getId())
				.append('@').append(getHost()).append(':').append(getPort())
				.append("; seen: ").append(Duration.ofMillis(now - lastSeen))
				.append("; age: ").append(Duration.ofMillis(now - created));

		if (lastSend > 0)
			repr.append("; sent: ").append(Duration.ofMillis(now - lastSend));
		if (failedRequests != 0)
			repr.append("; fail: ").append(failedRequests);
		if (reachable)
			repr.append("; reachable");

		double rtt = avgRTT.getAverage();
		if (!Double.isNaN(rtt)) {
			DecimalFormat df = new DecimalFormat();
			df.setMaximumFractionDigits(2);
			df.setRoundingMode(RoundingMode.HALF_UP);
			repr.append("; rtt: ").append(df.format(rtt));
		}

		if (getVersion() != 0)
			repr.append("; ver: ").append(Version.toString(getVersion()));

		return repr.toString();
	}
}