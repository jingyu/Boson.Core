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
 * <p>
 * <b>What belongs here.</b> A value belongs here when it is node-wide policy: it decides how much
 * work the node does or how much traffic it emits, more than one component cares about it, or it is
 * calibrated against another value in this file. Everything here should be readable as an answer to
 * "what does a node of this implementation do, and how often".
 * </p>
 * <p>
 * <b>What deliberately does not.</b> A value that is only meaningful inside one component stays with
 * that component, because moving it would separate it from the code that gives it meaning and imply a
 * generality it does not have. Three groups are deliberately left where they are:
 * </p>
 * <ul>
 *   <li>{@code RpcServer}'s socket buffers, reachability detector, throttle rates and timeout-sampler
 *       bounds - transport and abuse-control parameters of one layer. The sampler bounds in particular
 *       are calibrated as a set and are arguments to a single constructor call.</li>
 *   <li>{@code KBucketEntry}'s failure counts, ping backoff base and RTT smoothing weight - the
 *       machinery of a single entry's liveness state, meaningless outside it. Its one value that does
 *       have a partner here, {@code OLD_AND_STALE_TIME}, is derived from
 *       {@link #BUCKET_REFRESH_INTERVAL} rather than repeated.</li>
 *   <li>{@code KadNode}'s {@code NAME} / {@code SHORT_NAME} / {@code VERSION} - this implementation's
 *       identity on the wire, not a tuning dial; and {@code TokenManager.TOKEN_TIMEOUT}, which is the
 *       token's own lifetime and is referenced by whoever needs to match it.</li>
 * </ul>
 * <p>
 * The age limits that bound stored data, {@code Node.MAX_VALUE_AGE} and {@code Node.MAX_PEER_AGE},
 * are not here either: they are part of the public API contract in the {@code api} module, and
 * {@link #RE_ANNOUNCE_INTERVAL} is calibrated against them from this side.
 * </p>
 */
public final class KadConstants {
	// ---------------------------------------------------------------------------------------------
	// Configurable parameter defaults
	//
	// These are only defaults. The effective values are owned by DHT, which receives them from
	// KadNode, which reads them from NodeConfiguration.KademliaOptions; the values below apply where
	// no configuration is supplied. None of them is a protocol rule - two nodes running different
	// values interoperate fine, they simply spend different amounts of effort.
	// ---------------------------------------------------------------------------------------------

	/**
	 * Lookup concurrency: how many RPCs a single task keeps in flight.
	 * <p>
	 * <b>Why 3.</b> The value from the Kademlia paper, and what BitTorrent's mainline DHT and
	 * Ethereum's discv4/discv5 use. It is a latency-versus-traffic dial: higher alpha overlaps more
	 * round trips so a lookup finishes sooner, but wastes queries on nodes a lower alpha would never
	 * have needed to ask, and multiplies the load one node can place on the network.
	 * </p>
	 * <p>
	 * <b>Trade-off at the extremes.</b> alpha=1 makes a lookup a strict serial chain - minimal traffic,
	 * but one slow peer stalls the whole thing for a full timeout. Large alpha buys progressively less
	 * because Kademlia converges in O(log_k N) rounds regardless; past a handful the extra queries are
	 * mostly redundant. This module previously ran alpha=16, which cost roughly five times the paper's
	 * traffic per lookup for no measurable gain.
	 * </p>
	 */
	public static final int ALPHA = 3;

	/**
	 * Bucket size (k): how many contacts a routing-table bucket keeps, and the size of the closest set
	 * a lookup converges on.
	 * <p>
	 * <b>Why 16.</b> Implementations disagree and all are defensible: the Kademlia paper uses 20,
	 * BitTorrent's mainline DHT 8, Ethereum's discv4/discv5 16, libp2p 20. Larger k means the routing
	 * table survives more simultaneous node failures before a bucket empties, and makes eclipse
	 * attacks costlier because an attacker must own more of the k closest slots. 16 sits between
	 * mainline's minimum and the paper's value.
	 * </p>
	 * <p>
	 * <b>What raising it costs.</b> More than it first appears, which is why several other limits
	 * exist to contain it. Memory grows as k per bucket. Lookups cost more, though no longer in
	 * proportion: filling the closest set takes k responses, but the stability margin on top of it is
	 * capped at {@link #LOOKUP_STABILITY_ATTEMPTS} rather than being k as well - which is what it was
	 * until that cap was introduced, and it doubled the cost of every lookup at k=16. The candidate
	 * queue would grow as 3k and
	 * is re-sorted per insertion, hence {@link #MAX_LOOKUP_CANDIDATES}. Response size would grow as k
	 * and overrun the MTU, hence {@link #MAX_NODES_PER_RESPONSE}. A super node raising k gets the
	 * routing robustness it wants without those consequences only because those caps are in place.
	 * </p>
	 */
	public static final int K = 16;

	/**
	 * Replacement cache size: how many spare contacts a bucket holds for entries that go bad.
	 * <p>
	 * <b>What it is for.</b> A node learned from an unsolicited request is not trusted enough to enter
	 * the bucket proper - it has not completed a request/response round trip - so it waits here. This
	 * is the mechanism that stops an attacker from displacing verified routing state just by sending
	 * packets: the cache is bounded, so flooding it evicts only other unverified nodes.
	 * </p>
	 * <p>
	 * <b>Why 8, and why it is not k.</b> These were a single constant until recently, so the cache
	 * implicitly inherited the bucket size. They serve different purposes: k sizes the set of contacts
	 * the node routes through, while this sizes a holding area for unverified ones. A cache of 8 is
	 * ample to refill a bucket after a churn event; making it as large as a large k would only give an
	 * attacker more cheap slots to occupy.
	 * </p>
	 */
	public static final int REPLACEMENTS = 8;

	/**
	 * Ceiling on tasks running concurrently in one {@code TaskManager}; further tasks queue.
	 * <p>
	 * <b>What it bounds.</b> Total outstanding work for one DHT instance. Combined with alpha it caps
	 * in-flight RPCs at roughly {@code CONCURRENT_TASKS * ALPHA} (96 at the defaults), which is the
	 * real bound on this node's outbound query traffic. {@code RpcServer} enforces its own separate
	 * caps on active calls and per-second rate.
	 * </p>
	 * <p>
	 * <b>Trade-off.</b> Too low and lookups queue behind maintenance, adding latency to user-visible
	 * operations. Too high and a burst of lookups floods the node's own socket buffers and the
	 * network, and memory grows with the per-task candidate queues (up to
	 * {@link #MAX_LOOKUP_CANDIDATES} entries each). 32 keeps the worst case at a few megabytes.
	 * </p>
	 */
	public static final int CONCURRENT_TASKS = 32;

	/**
	 * The concurrency ceiling for low-priority (background maintenance) tasks, applied whenever alpha
	 * allows it. Not configurable: maintenance should not become more parallel just because
	 * foreground lookups do. See {@link KadContext#getLowPriorityAlpha()}.
	 */
	public static final int LOW_PRIORITY_ALPHA = 2;

	// ---------------------------------------------------------------------------------------------
	// Lookup budget
	//
	// These bound the work a single iterative lookup may do. They are implementation limits, not
	// protocol rules: a peer cannot observe them, and changing them cannot make this node
	// incompatible with any other. What they protect is this node's own CPU, memory and outbound
	// bandwidth, and they act as a backstop for the case where the real termination rule cannot fire.
	//
	// The real termination rule is convergence, in LookupTask#isDone(): the lookup stops when no
	// unqueried candidate is closer than the closest set's tail - nothing left to ask could enter the
	// set - and the set has additionally held still for LOOKUP_STABILITY_ATTEMPTS more responses, which
	// is the allowance for a farther node knowing a closer one. Everything below exists for the paths
	// where that never happens - an unresponsive region of the network, packet loss, or a peer feeding
	// an endless stream of plausible-looking nodes.
	// ---------------------------------------------------------------------------------------------

	/**
	 * How many consecutive non-improving responses a lookup collects before it may call its closest set
	 * stable - the exploration margin in
	 * {@link io.bosonnetwork.kademlia.tasks.ClosestSet#isEligible()}.
	 * <p>
	 * <b>What it buys.</b> Not termination - that is decided by {@code LookupTask#isDone()}, which
	 * requires in addition that no unqueried candidate is closer than the set's tail, at which point
	 * nothing left to ask can enter the set. This is the allowance spent <em>past</em> that point,
	 * because a node farther from the target may still know a closer one that no response has mentioned
	 * yet. Without it a lookup stops at the first plateau; with it, a plateau must hold for this many
	 * consecutive probes before the lookup believes it.
	 * </p>
	 * <p>
	 * <b>Why it is capped rather than being k.</b> The rule used to read
	 * {@code insertAttemptsSinceTailModification > capacity}, where the capacity is k, so raising k from
	 * 8 to 16 silently doubled the margin - 17 non-improving responses instead of 9, on every lookup.
	 * That was inherited from mldht, where the same expression sits at that project's own k of 8; it was
	 * never a decision that the margin should scale. Escaping a false plateau is a property of the
	 * graph, not of how many contacts we choose to keep, and if anything a larger k makes plateaus
	 * <em>less</em> likely, since each response carries up to {@link #MAX_NODES_PER_RESPONSE} nodes.
	 * </p>
	 * <p>
	 * <b>Why 8.</b> It restores mldht's effective margin, so a node running k=8 behaves exactly as
	 * before and only larger k changes. The saving is real: at k=16 a converging lookup needs about 25
	 * responses instead of 33.
	 * </p>
	 * <p>
	 * <b>Why it is applied as {@code min(k, 8)} rather than flat, given the argument above.</b> Taken
	 * literally, "the margin is a property of the graph" would also say it should not <em>shrink</em>
	 * below 8 for a node configured under that - k may go as low as
	 * {@code NodeConfiguration.KademliaOptions.MIN_K}. It is capped by k anyway, for reasons that are
	 * about the small-k case specifically rather than about plateaus. A node configured with a tiny k
	 * is buying cheapness, and holding it to a margin twice its whole closest set spends its budget
	 * against that intent. It is also not the safety net - {@code LookupTask.isDone()} still requires
	 * that no unqueried candidate is closer than the set's tail, which is the sound termination rule on
	 * its own - and alpha puts a hard floor underneath it regardless, since a task cannot be done while
	 * any call is in flight, so no lookup converges on fewer than one full round of settled responses.
	 * The practical effect of the cap's lower half is nil: it leaves every k below 8 behaving exactly as
	 * it did before this constant existed.
	 * </p>
	 * <p>
	 * Implementation policy, invisible to peers: it changes only how long this node keeps asking.
	 * </p>
	 */
	public static final int LOOKUP_STABILITY_ATTEMPTS = 8;

	/**
	 * Number of lookup rounds' worth of extra iterations allowed on top of the convergence floor, in
	 * units of alpha.
	 * <p>
	 * <b>What it covers.</b> Two things the convergence floor does not: the depth ramp before the
	 * closest set starts filling, and iterations consumed without progress. Kademlia converges in
	 * O(log_k N) rounds rather than O(log2 N), because every response returns up to k nodes - for a
	 * 10^9-node network at k=16 that is about 7 rounds, or ~21 RPCs at alpha 3. Separately, an
	 * iteration is not an RPC: a call that answers slowly costs two (STALLED, then RESPONDED) and one
	 * that is lost costs two plus the retry it re-queues, so a slow or lossy path burns budget at up to
	 * twice the rate a healthy one does. That factor, not the depth ramp, is what sizes this term in
	 * practice.
	 * </p>
	 * <p>
	 * <b>Why 8.</b> {@code alpha * 8} = 24 at the default alpha, which covers the ~7-round depth ramp
	 * for a network far larger than this one is likely to get, with room for a handful of dead peers.
	 * Erring high is cheap here: the budget is a ceiling, and a healthy lookup converges long before
	 * reaching it. Erring low is not cheap - a lookup truncated by the budget still reports COMPLETED,
	 * so a store built on it silently lands on fewer nodes than intended.
	 * </p>
	 * <p>
	 * <b>Why it does not scale with k.</b> It used to be wrapped in {@code max(k, ...)}, which grew the
	 * slack as k grew - backwards, since convergence is O(log_k N) and a larger k therefore needs
	 * <em>fewer</em> rounds, not more. The wrapper bound only from k=25 up, so its only effect was to
	 * inflate super-node budgets.
	 * </p>
	 */
	public static final int LOOKUP_DEPTH_ALLOWANCE = 8;

	/**
	 * Hard ceiling on the candidate queue of a single lookup, independent of k.
	 * <p>
	 * <b>What it controls.</b> How many not-yet-queried nodes a lookup will remember. The queue is
	 * normally {@code 3 * k}; this caps that product, and so binds only from k=43 upward. At the
	 * default k it is inert - {@code 3 * 16 = 48} is the value that actually applies.
	 * </p>
	 * <p>
	 * <b>Where the {@code 3 * k} comes from - read this before retuning either number.</b> It is
	 * inherited from mldht, which this lookup code derives from, but with a changed meaning. In mldht
	 * {@code 3 * MAX_ENTRIES_PER_BUCKET} is the <em>seed</em> size: how many nodes to pull out of the
	 * local routing table to start a lookup. Its candidate set proper is an unbounded map that retains
	 * everything the lookup subsequently learns. Here the same expression became the <em>capacity</em>
	 * of the candidate queue, so this implementation discards distant candidates that mldht would
	 * keep. That is a deliberate memory bound rather than a derived optimum: an earlier version of
	 * this comment described 3k as "the k closest plus 2k spares", which was a rationalization written
	 * after the fact, not the reason the number is 3.
	 * </p>
	 * <p>
	 * <b>Why bound it at all, and why the cost is smaller than it looks.</b> Pruning is not free but
	 * it is not quadratic either: {@code ClosestCandidates.add} sorts once per call, and
	 * {@code LookupTask} calls it once per response with the whole batch of nodes - not once per node.
	 * So a response costs one O(n log n) pass, about 900 comparisons at n=128. Memory is similarly
	 * modest: 128 entries across {@link #CONCURRENT_TASKS} concurrent lookups is well under a
	 * megabyte. Neither number justifies a tight cap; what the ceiling really buys is a predictable
	 * worst case at large k, which matters on constrained devices.
	 * </p>
	 * <p>
	 * <b>How 128 compares.</b> Implementations that bound the candidate pool at all do so with a fixed
	 * constant unrelated to k: OpenDHT caps at 14 ({@code SEARCH_NODES}) and libtorrent at 100
	 * ({@code m_results.resize(100)}). mldht and go-libp2p-kad-dht do not bound it, tracking only the
	 * closest k for decisions. The effective 48 at the default k therefore sits between the two
	 * bounding implementations, and this ceiling is near libtorrent's - so it is not a tight limit by
	 * the standards of the field, and there is no case for lowering it.
	 * </p>
	 * <p>
	 * <b>What it does not bound.</b> Only the candidate map. {@code ClosestCandidates.dedup} also
	 * retains the id of any node whose id was accepted but whose address then collided, and those are
	 * reclaimed only for nodes that reached the candidate map - so the structure that can actually
	 * grow unchecked during a lookup is not the one this caps.
	 * </p>
	 * <p>
	 * Implementation limit, not protocol: purely this node's resource budget.
	 * </p>
	 */
	public static final int MAX_LOOKUP_CANDIDATES = 128;

	// ---------------------------------------------------------------------------------------------
	// Response size
	//
	// Unlike the lookup budget above, these are observable by peers - they decide how many nodes a
	// FIND_NODE / FIND_VALUE / FIND_PEER response carries. They are still not protocol rules: the
	// protocol places no minimum on the node count, and a requester must cope with fewer nodes than
	// it asked for (it always could - a small routing table returns fewer). What they exist for is
	// keeping a response inside one UDP datagram.
	//
	// Why that matters: a fragmented UDP datagram is lost entirely if any single fragment is lost,
	// and middleboxes commonly drop fragments outright. So exceeding the path MTU does not degrade
	// gracefully - it turns a working lookup into a silent black hole on some paths. Every UDP-based
	// Kademlia bounds responses this way; the ones that return a full k=20 (libp2p, IPFS) run over
	// TCP/QUIC streams where MTU is not a concern. Ethereum's discv4/discv5 are the closest analogue
	// to this design - same 256-bit ID space, same UDP transport - and they cap at the 1280-byte IPv6
	// minimum MTU, splitting a response across several messages when k does not fit.
	// ---------------------------------------------------------------------------------------------

	/**
	 * The declared ceiling on how many nodes one response may carry per address family.
	 * <p>
	 * <b>Why it is separate from k.</b> k is a routing-table property: it says how many contacts a
	 * bucket keeps, and raising it makes routing more robust. The number of nodes that fits in a
	 * datagram is a transport property and has nothing to do with routing robustness. Tying the two
	 * together means a node that raises k for better routing silently starts emitting oversized
	 * packets. The effective count is {@code min(k, MAX_NODES_PER_RESPONSE, whatever fits)}.
	 * </p>
	 * <p>
	 * <b>Why 16.</b> It matches the bucket size that Ethereum discv4/discv5 use over the same
	 * transport, and it is comfortably more than the 8 that BitTorrent's mainline DHT returns. A
	 * requester needs enough closest nodes to make progress and to tolerate some being dead; beyond
	 * about 16 the extra entries mostly duplicate what the next round would find anyway.
	 * </p>
	 * <p>
	 * <b>Trade-off.</b> Fewer nodes per response means more lookup rounds (convergence is O(log_k N)
	 * in the count actually returned, not in the configured k), so latency rises. More nodes per
	 * response means larger datagrams, and past the MTU, catastrophic rather than gradual loss. 16 is
	 * chosen to sit on the safe side of that cliff for a single address family; when both families are
	 * requested the byte budget below reduces it further.
	 * </p>
	 */
	public static final int MAX_NODES_PER_RESPONSE = 16;

	/**
	 * Estimated wire cost, in bytes, of one IPv4 node entry in a response.
	 * <p>
	 * Derived from the golden vectors in {@code FindNodeTests}: a FIND_NODE response with 8 IPv4 nodes
	 * is 380 bytes and with 8 IPv6 nodes 476, which solves to a ~24-byte message base, ~44.5 bytes per
	 * IPv4 entry and ~56.5 per IPv6 entry. Rounded up here so the estimate errs toward smaller
	 * responses. The bulk of an entry is the 32-byte node id, which this design cannot shrink; the
	 * remainder is CBOR field framing, which a compact fixed-layout encoding could reduce by roughly a
	 * third if more nodes per packet were ever needed.
	 * </p>
	 * <p>
	 * If the node entry encoding changes, these estimates and the test vectors move together - the
	 * vectors are the source of truth and will fail first.
	 * </p>
	 */
	public static final int NODE_ENTRY_SIZE_V4 = 48;

	/**
	 * Estimated wire cost, in bytes, of one IPv6 node entry in a response.
	 * See {@link #NODE_ENTRY_SIZE_V4} for how this was derived.
	 */
	public static final int NODE_ENTRY_SIZE_V6 = 60;

	/**
	 * Bytes reserved in the packet budget for everything in a response that is not a node entry.
	 * <p>
	 * Covers the ~24-byte message base (type, method, txid, version), the optional token, the
	 * 32-byte sender id and 16-byte MAC that {@code RpcServer} prepends when it encrypts, and
	 * headroom so that a small growth in the message header cannot silently push a response over the
	 * MTU. Deliberately generous: the cost of over-reserving is one or two fewer nodes per response,
	 * while the cost of under-reserving is a fragmented datagram.
	 * </p>
	 */
	public static final int RESPONSE_OVERHEAD = 128;

	// ---------------------------------------------------------------------------------------------
	// Maintenance cadence
	//
	// All implementation policy, none of it protocol: a peer sees only the resulting traffic. Every
	// one of these is the same trade - a shorter interval keeps the routing table fresher and detects
	// dead peers sooner, at the cost of background traffic that every node in the network pays
	// simultaneously. Because the cost is paid network-wide, erring long is the safer direction.
	//
	// Two layers are at work and it is easy to confuse them. DHT_UPDATE_INTERVAL is only how often
	// the node *looks* at whether anything needs doing; what actually happens on a given tick is
	// decided by the coarser intervals below. Changing the tick rate does not change how much
	// maintenance traffic the node emits.
	//
	// CAUTION: these are all compared against System.currentTimeMillis(), which is not monotonic. A
	// backwards clock step suppresses maintenance for the duration of the jump. See the review notes
	// on moving elapsed-time decisions to System.nanoTime().
	// ---------------------------------------------------------------------------------------------

	/**
	 * How often {@code DHT.update()} runs - the maintenance polling granularity, not a work rate.
	 * <p>
	 * Each tick re-evaluates whether the routing table needs maintenance
	 * ({@link #ROUTING_TABLE_MAINTENANCE_INTERVAL}) and whether a bootstrap is due
	 * ({@link #BOOTSTRAP_INTERVAL}, {@link #SELF_LOOKUP_INTERVAL}). Most ticks do nothing. 30
	 * seconds is short enough that the node reacts promptly when its table drains - the condition that
	 * matters most, since a node with an empty table is effectively offline - and the cost of a tick
	 * that finds nothing to do is a few comparisons.
	 * </p>
	 */
	public static final int DHT_UPDATE_INTERVAL = 30 * 1000;                        // 30 seconds

	/**
	 * Minimum time between bootstrap attempts - a rate limiter, not a schedule.
	 * <p>
	 * Without it the 30-second tick would re-bootstrap continuously whenever the routing table sat
	 * below the {@link #BOOTSTRAP_THRESHOLD_BUCKETS} threshold, which is exactly the situation where the node
	 * is least able to afford the traffic and most likely to hammer the configured bootstrap servers.
	 * Those servers are a shared, centralized resource for the whole network, so this interval
	 * protects them as much as it protects this node.
	 * </p>
	 * <p>
	 * The nominal interval rather than a hard floor, despite the name: an individual attempt lands
	 * within {@link #BOOTSTRAP_INTERVAL_JITTER_PERCENT} either side of this, so nodes that started
	 * together do not stay synchronised. The band is symmetric, so this remains the long-run average
	 * and the true floor is that percentage below it. That constant also records why the interval stays
	 * flat instead of backing off exponentially.
	 * </p>
	 */
	public static final int BOOTSTRAP_INTERVAL = 4 * 60 * 1000;                 // 4 minutes

	/**
	 * How far an individual bootstrap attempt may fall either side of {@link #BOOTSTRAP_INTERVAL},
	 * as a percentage of that interval.
	 * <p>
	 * <b>Why any jitter.</b> A fixed retry period leaves nodes that started together in lockstep
	 * forever - a fleet rolled out at once, or an entire population reconnecting after the same
	 * outage - so their attempts arrive at the shared bootstrap servers in synchronised waves rather
	 * than spread out. Drawing a fresh offset per attempt lets each node's phase drift away from the
	 * others within a few cycles.
	 * </p>
	 * <p>
	 * <b>Why symmetric.</b> A one-sided offset would decorrelate just as well, but it would also raise
	 * the mean interval by half its width and keep it there - the node would settle at a permanently
	 * slower cadence than the one this file documents. Centring the band on the interval leaves the
	 * long-run average exactly where it is meant to be and only randomises the individual wait.
	 * </p>
	 * <p>
	 * <b>Why 10, and why it cannot be much smaller.</b> The jitter has to survive
	 * {@link #DHT_UPDATE_INTERVAL}. Bootstrap is only ever reached from a tick of that timer, so the
	 * wait is effectively rounded up to the node's next tick and any offset shorter than one tick is
	 * absorbed without changing when the attempt actually fires - a jitter of a second or two would be
	 * very nearly a no-op. At 10% the band is 48 seconds wide, more than one tick, so an attempt lands
	 * on one of several distinct ticks instead of always the same one. The other direction bounds it
	 * too: the wait must stay short enough for a node with a thin routing table, since that table is
	 * what every lookup depends on.
	 * </p>
	 * <p>
	 * <b>Why not exponential backoff.</b> Considered and rejected. What a permanently failing node
	 * costs shared infrastructure is one {@code findNode} packet per configured server per interval:
	 * the expensive part of a bootstrap, {@link #MAX_BUCKET_FILLS_PER_BOOTSTRAP} bucket-filling
	 * lookups, runs against ordinary peers and is skipped entirely while the RPC server reports itself
	 * unreachable. Against that, backoff would do its most damage in the scenario that motivates it -
	 * after a bootstrap-server outage the whole stranded population would sit at its longest interval
	 * exactly when service returns, turning a 4-minute recovery into a much longer one. Churn also
	 * undercuts the premise backoff rests on: in a network where a large share of peers turn over
	 * within one interval, a failed attempt says little about the next one.
	 * </p>
	 * <p>
	 * Implementation detail, not protocol: peers see only the resulting arrival times.
	 * </p>
	 */
	public static final int BOOTSTRAP_INTERVAL_JITTER_PERCENT = 10;

	/**
	 * The most bootstrap nodes one periodic attempt will contact.
	 * <p>
	 * <b>A ceiling, not a quota.</b> At 8 this leaves any ordinary configuration alone - an operator
	 * listing a handful of bootstrap nodes has all of them contacted, exactly as before this existed.
	 * What it bounds is the pathological list, where a node's load on shared infrastructure would
	 * otherwise scale with however many entries someone pasted in. That is backwards: listing more
	 * bootstrap nodes for redundancy should buy resilience, not cost traffic on every attempt, forever.
	 * </p>
	 * <p>
	 * <b>Why it can afford to be this generous.</b> Above about three answers the extra nodes are not
	 * even kept: each response carries up to {@link #MAX_NODES_PER_RESPONSE} nodes and a lookup's
	 * candidate queue holds {@code min(3k, }{@link #MAX_LOOKUP_CANDIDATES}{@code )}, so the surplus is
	 * pruned on insertion. What the extra contacts buy is not seeds but the probability that at least
	 * one node answers at all - and the only nodes that reach this path are deaf or thin-tabled, which
	 * is precisely when that probability is what matters and when frugality is the wrong instinct. The
	 * cost of erring high is a few UDP packets per {@link #BOOTSTRAP_INTERVAL}, paid by a node that is
	 * already in trouble.
	 * </p>
	 * <p>
	 * <b>Periodic attempts only.</b> The startup bootstrap contacts every configured node - first
	 * contact is where latency matters most and there is no repetition to economise on - and an
	 * application-supplied bootstrap contacts exactly what it was given. When the ceiling does bind,
	 * the draw is fresh each attempt rather than a set chosen once, so no node is permanently unlucky
	 * and a recovered one is picked up without any health tracking.
	 * </p>
	 */
	public static final int BOOTSTRAP_NODES_PER_ATTEMPT = 8;

	/**
	 * How long a bootstrap keeps collecting responses after the first one arrives.
	 * <p>
	 * <b>Not a timeout.</b> The calls it stops waiting for are still outstanding and are still answered
	 * normally - a late responder still enters the routing table like any other peer that answers us.
	 * This only bounds how long the bootstrap holds off on the work that follows.
	 * </p>
	 * <p>
	 * <b>What it replaced.</b> Waiting for every bootstrap node to settle means waiting at the pace of
	 * the worst one, and a dead one only settles when its RPC times out. That put a ten-second delay in
	 * front of the startup bootstrap - which gates the node reporting itself connected - whenever one
	 * configured bootstrap node was down, however fast the others answered.
	 * </p>
	 * <p>
	 * <b>Why one second.</b> Sized against the spread between healthy bootstrap nodes rather than
	 * against the RPC timeout: hosts that are up answer within tens of milliseconds of each other even
	 * across continents, so a second captures the stragglers and keeps the merged seed set, while
	 * bounding what a dead one costs to the first response time plus this. It is also what makes it
	 * safe to start the clock on a bootstrap node that answers from an empty table - that answer seeds
	 * nothing, but a slower one with nodes still lands well inside the window.
	 * </p>
	 */
	public static final int BOOTSTRAP_NODE_GRACE = 1000;                          // 1 second

	/**
	 * How often a node with a healthy routing table still performs a lookup for its own id.
	 * <p>
	 * <b>Why do this at all when the table is fine.</b> The point is not to improve this node's table
	 * but to refresh this node's presence in *other* nodes' tables. A self-lookup contacts the peers
	 * closest to our id, which are precisely the ones that should hold us in their buckets; without it
	 * a quiet node gradually ages out of the network's collective routing state and becomes
	 * unreachable while still believing it is connected.
	 * </p>
	 * <p>
	 * 30 minutes is well inside the bucket refresh window ({@link #BUCKET_REFRESH_INTERVAL}), so peers
	 * hear from us before they would consider our entry stale.
	 * </p>
	 */
	public static final int SELF_LOOKUP_INTERVAL = 30 * 60 * 1000;                  // 30 minutes

	/**
	 * Delay before the first routing-table snapshot is written after startup.
	 * <p>
	 * Deliberately later than the first bootstrap so the first snapshot captures a table worth
	 * reloading, rather than overwriting a good cached table with the near-empty one that exists
	 * seconds after launch.
	 * </p>
	 */
	public static final int ROUTING_TABLE_PERSIST_INITIAL_DELAY = 2 * 60 * 1000;    // 2 minutes

	/**
	 * How often the routing table is written to disk.
	 * <p>
	 * <b>What it buys.</b> A warm start: on restart the node pings the cached contacts instead of
	 * going back to the bootstrap servers, which is faster for the node and much cheaper for the
	 * network. The cost of losing a snapshot is bounded by this interval and is never worse than a
	 * cold bootstrap.
	 * </p>
	 * <p>
	 * Kept coarse because the write is blocking file I/O performed on the event loop, so each one is a
	 * latency spike proportional to table size.
	 * </p>
	 */
	public static final int ROUTING_TABLE_PERSIST_INTERVAL = 10 * 60 * 1000;        // 10 minutes

	/**
	 * Minimum time between routing-table maintenance passes.
	 * <p>
	 * A pass walks the buckets and schedules a refresh for any that need one, so this is the rate at
	 * which stale buckets are noticed - the per-bucket decision is
	 * {@link #BUCKET_REFRESH_INTERVAL}. Set well below that interval so a bucket becoming stale is
	 * acted on promptly rather than waiting most of another refresh window.
	 * </p>
	 */
	public static final int ROUTING_TABLE_MAINTENANCE_INTERVAL = 4 * 60 * 1000;     // 4 minutes

	/**
	 * How long a bucket may go without a refresh before it is eligible to be refreshed.
	 * <p>
	 * <b>What triggers a refresh.</b> The bucket is stale by this interval <em>and</em> holds at least
	 * one entry due for a ping. A refresh looks up a random id inside the bucket's prefix, which both
	 * revalidates its entries and discovers replacements for any that died.
	 * </p>
	 * <p>
	 * <b>Why 15 minutes.</b> More aggressive than the Kademlia paper, which refreshes a bucket after
	 * an hour without a lookup in its range. Churn on an open peer-to-peer network is far higher than
	 * the paper assumed, and a stale bucket is not merely unhelpful - it actively misroutes lookups
	 * toward nodes that no longer exist, so every lookup that traverses it pays a timeout. Shortening
	 * it further would raise background traffic for every node at once with diminishing returns.
	 * </p>
	 * <p>
	 * Note that {@code KBucket.put} resets a full bucket's refresh timestamp to 0 when a reachable
	 * node cannot be admitted, deliberately forcing a refresh so the bucket's dead entries are
	 * revalidated and the newcomer gets a chance to replace one.
	 * </p>
	 * <p>
	 * <b>Also the entry-level staleness horizon.</b> {@code KBucketEntry.OLD_AND_STALE_TIME} is defined
	 * as this value rather than repeating it, because the two are halves of one rule - a bucket refresh
	 * needs both clocks to have run out - and were previously two independent literals that nothing kept
	 * in step. Retuning here therefore also moves when an individual contact is considered stale, which
	 * is the intent: they answer the same question at different granularities.
	 * </p>
	 */
	public static final int BUCKET_REFRESH_INTERVAL = 15 * 60 * 1000; // 15 minutes in milliseconds

	/**
	 * Minimum time between probes of a bucket's never-contacted replacement entries.
	 * <p>
	 * <b>Why this is 30x shorter than {@link #BUCKET_REFRESH_INTERVAL}.</b> The two do different work
	 * at very different prices. A refresh is a full iterative lookup; probing a replacement is a
	 * single ping. And the probe is what promotes an unverified contact into a usable one, so a bucket
	 * with dead entries and unprobed replacements is one cheap packet away from being healthy again.
	 * Doing that quickly is what keeps buckets full during churn.
	 * </p>
	 */
	public static final int BUCKET_REPLACEMENT_PING_MIN_INTERVAL = 30 * 1000; // 30 seconds in milliseconds

	/**
	 * How often the node performs a lookup for a random id.
	 * <p>
	 * Complements {@link #SELF_LOOKUP_INTERVAL}: a self-lookup announces us to the region of the
	 * keyspace near our own id, while a random lookup reaches a region we would otherwise never
	 * contact. That both fills distant buckets and spreads knowledge of this node across the keyspace,
	 * which is what makes us findable by peers whose lookups pass through those regions.
	 * </p>
	 * <p>
	 * Skipped entirely while the RPC server reports itself unreachable - there is no point advertising
	 * to a network that cannot answer us.
	 * </p>
	 */
	public static final int RANDOM_LOOKUP_INTERVAL = 10 * 60 * 1000;                // 10 minutes

	/**
	 * How often the node pings one random routing-table entry to prove its own socket still works.
	 * <p>
	 * <b>Why so much shorter than everything else here.</b> This is not routing maintenance; it is a
	 * liveness probe for the local socket, feeding the reachability state that gates lookups and
	 * connection status. Detecting that we have gone deaf - NAT rebinding, interface change - matters
	 * on a scale of seconds, not minutes.
	 * </p>
	 * <p>
	 * <b>Why it costs almost nothing.</b> It is skipped whenever any RPC is already in flight. On a
	 * busy node it therefore rarely fires: ordinary traffic already proves the socket works. It only
	 * really runs when the node is idle, which is exactly when it is needed and when the network can
	 * most afford it.
	 * </p>
	 */
	public static final int RANDOM_PING_INTERVAL = 10 * 1000;                       // 10 seconds

	/**
	 * Delay before the first purge of the suspicious-node tracker after startup.
	 * See {@link #SUSPICIOUS_NODES_PURGE_INTERVAL}.
	 */
	public static final int SUSPICIOUS_NODES_PURGE_INITIAL_DELAY = 60 * 1000;       // 60 seconds

	/**
	 * How often expired entries are dropped from the suspicious-node tracker.
	 * <p>
	 * Bans and observations expire lazily on read, so this governs memory reclamation only, never
	 * accuracy - a ban does not outlive its deadline because a purge was late. The interval is
	 * therefore a pure memory-versus-CPU dial, and the purge walks the whole map, whose size an
	 * attacker can influence by sending from many source addresses.
	 * </p>
	 */
	public static final int SUSPICIOUS_NODES_PURGE_INTERVAL = 60 * 1000;            // 60 seconds

	/**
	 * Delay before the first purge of expired local storage after startup.
	 * See {@link #STORAGE_EXPIRE_INTERVAL}.
	 */
	public static final int STORAGE_EXPIRE_INITIAL_DELAY = 30 * 1000;               // 30 seconds

	/**
	 * How often values and peers that have outlived their age limit are dropped from local storage.
	 * <p>
	 * Purely local: no traffic, no protocol effect, and nothing observable by peers. The age limits
	 * themselves are the public contract ({@code Node.MAX_VALUE_AGE} and {@code Node.MAX_PEER_AGE}),
	 * so this interval only decides how long an already-expired row lingers on disk - it can never
	 * keep a value alive past its limit, because reads filter by age.
	 * </p>
	 */
	public static final int STORAGE_EXPIRE_INTERVAL = 10 * 60 * 1000;               // 10 minutes

	/**
	 * Delay before the first re-announce pass after startup.
	 * See {@link #RE_ANNOUNCE_INTERVAL}.
	 */
	public static final int RE_ANNOUNCE_INITIAL_DELAY = 60 * 1000;                  // 60 seconds

	/**
	 * How often the node re-publishes the values and peers it is persistently announcing.
	 * <p>
	 * <b>The most expensive periodic work here.</b> Unlike the rest of this section, one pass is not a
	 * bounded amount of traffic: it runs a full iterative store-or-announce lookup for every item due,
	 * so its cost scales with what the application has asked the node to keep published. Skipped
	 * entirely while the RPC server reports itself unreachable.
	 * </p>
	 * <p>
	 * <b>Why 5 minutes against a 2-hour limit.</b> An item must be refreshed on its remote holders
	 * before they expire it at {@code Node.MAX_VALUE_AGE} / {@code Node.MAX_PEER_AGE}. The selection
	 * query allows two intervals of slack ({@code MAX_VALUE_AGE - 2 * RE_ANNOUNCE_INTERVAL}), so a
	 * missed pass - the node was unreachable, or the lookup failed - still leaves many further
	 * attempts before anything is actually dropped. Raising this interval eats into that margin;
	 * raising it past roughly half the age limit removes the margin altogether.
	 * </p>
	 */
	public static final int RE_ANNOUNCE_INTERVAL = 5 * 60 * 1000;                   // 5 minutes

	/**
	 * How many buckets' worth of contacts the node wants before it stops trying to bootstrap,
	 * expressed as a multiple of k rather than as an absolute count.
	 * <p>
	 * Above {@code BOOTSTRAP_THRESHOLD_BUCKETS * k} entries the table is considered self-sustaining
	 * and normal maintenance keeps it healthy; below it the node is at risk of partition and
	 * re-bootstraps, subject to {@link #BOOTSTRAP_INTERVAL}. Three buckets is enough for lookups
	 * to route in every direction, and low enough to catch a table that is collapsing rather than
	 * waiting for it to empty. The product is capped by {@link #BOOTSTRAP_THRESHOLD_ENTRIES}.
	 * </p>
	 * <p>
	 * <b>Why a multiple of k and not a constant.</b> "Enough contacts to operate" is inherently
	 * relative to how many contacts a bucket holds. This was previously the literal 30, which was
	 * about four buckets when k was 8 - but when k became 16 the same literal silently became under
	 * two buckets, making the node markedly more reluctant to repair a thinning table without anyone
	 * choosing that. Deriving it from k keeps the intent stable across any k.
	 * </p>
	 */
	public static final int BOOTSTRAP_THRESHOLD_BUCKETS = 3;

	/**
	 * Absolute ceiling on the bootstrap threshold, whatever k is.
	 * <p>
	 * <b>Why scaling with k has to stop.</b> "Enough contacts to operate" tracks k only up to a point.
	 * Past some absolute number of contacts a node can route in every direction regardless of how
	 * large its buckets are, and continuing to scale turns the threshold into a target the node may
	 * never reach: a super node at k=64 would want 192 entries before it stopped bootstrapping, and
	 * in a network that never offers it that many it would re-bootstrap every
	 * {@link #BOOTSTRAP_INTERVAL} indefinitely - permanently, since the retry interval is flat by
	 * design and never lengthens (see {@link #BOOTSTRAP_INTERVAL_JITTER_PERCENT}). This ceiling is
	 * what stops that state from being reachable in the first place.
	 * </p>
	 * <p>
	 * <b>Why 64.</b> Roughly the point past which more contacts stop making a node meaningfully more
	 * able to route. It is inert at the default k (3 * 16 = 48 is already below it) and only binds
	 * from k=22 upward, so this is purely a super-node guard rather than a change to normal behavior.
	 * </p>
	 */
	public static final int BOOTSTRAP_THRESHOLD_ENTRIES = 64;

	/**
	 * How many buckets' worth of contacts the node must drop below before bootstrapping falls back to
	 * the configured bootstrap servers, expressed as a multiple of k.
	 * <p>
	 * <b>Why there are two thresholds.</b> Between {@code USE_BOOTSTRAP_NODES_THRESHOLD_BUCKETS * k}
	 * and {@link #BOOTSTRAP_THRESHOLD_BUCKETS} times k, the node bootstraps using only peers it
	 * already knows - a self-lookup seeded from its own routing table. Only below one bucket's worth
	 * of contacts, where it genuinely may not be able to reach the network unaided, does it contact
	 * the configured servers.
	 * </p>
	 * <p>
	 * That split matters because bootstrap servers are a shared, centralized dependency: every node
	 * that leans on them does so at the same moments (startup, and network-wide disruption, which is
	 * precisely when they are least able to cope). Keeping the fallback rare is what stops routine
	 * table churn from turning into a thundering herd against a handful of hosts.
	 * </p>
	 * <p>
	 * One bucket is the natural unit here: a node that cannot fill a single bucket has no useful
	 * routing state in any direction. This was previously the literal 8, which was exactly one bucket
	 * at the old k and became half a bucket when k doubled - narrowing the band in which the node
	 * self-repairs, and so pushing load onto the bootstrap servers that the two-tier scheme exists to
	 * protect. The product is capped by {@link #USE_BOOTSTRAP_NODES_THRESHOLD_ENTRIES}.
	 * </p>
	 */
	public static final int USE_BOOTSTRAP_NODES_THRESHOLD_BUCKETS = 1;

	/**
	 * Absolute ceiling on the bootstrap-server fallback threshold, whatever k is.
	 * <p>
	 * <b>Why this one needs a cap even more than {@link #BOOTSTRAP_THRESHOLD_ENTRIES} does.</b> That
	 * threshold governs whether the node spends its <em>own</em> effort; this one governs whether it
	 * involves the shared bootstrap servers. Leaving it uncapped while capping the other is the wrong
	 * way round: it lets the tier with externalized cost grow without bound.
	 * </p>
	 * <p>
	 * <b>What goes wrong without it.</b> "One bucket's worth" stops being a sensible measure of
	 * minimum viable routing state at large k - a node holding 100 contacts can route perfectly well
	 * whether k is 16 or 128. Uncapped, the two thresholds also collide: at k=64 both land on 64, so
	 * the self-bootstrap band vanishes and every bootstrap contacts the servers; above that they
	 * invert, and the routine {@link #SELF_LOOKUP_INTERVAL} self-lookup starts contacting them on
	 * every fire. That is precisely the thundering herd the two-tier split exists to prevent, and it
	 * appears only at large k, which is the case least likely to be exercised in testing.
	 * </p>
	 * <p>
	 * <b>Invariant, and why this is expressed as a division.</b> The effective value must stay strictly
	 * below the effective {@link #BOOTSTRAP_THRESHOLD_ENTRIES}-capped threshold, or the self-bootstrap
	 * band is empty and the scheme degrades to one tier. Defining this as half the other ceiling makes
	 * that structural rather than coincidental: once both caps bind, the relationship is 32 &lt; 64 by
	 * construction, and it cannot drift if someone retunes the ceiling. Below the caps the invariant
	 * holds trivially, since {@code k < 3k}. Writing the two as independent literals is the same shape
	 * of mistake as the k-derived literals this file already had to correct once.
	 * </p>
	 * <p>
	 * <b>Why half, rather than lower.</b> The self-bootstrap tier can only help when the contacts the
	 * node still holds are alive - and a table that shrank because its contacts died is exactly the
	 * case where a self-lookup cannot recover. Nothing escalates on repeated failure: the tier is
	 * chosen purely on entry count, so a node stuck with a table of stale contacts would retry the
	 * same futile self-bootstrap every {@link #BOOTSTRAP_INTERVAL}. Keeping the fallback threshold
	 * reasonably high bounds how long that can go on before the servers are consulted.
	 * </p>
	 * <p>
	 * Inert at the default k, where {@code 1 * 16} already sits below it, so normal behavior is
	 * unchanged; it binds only from k=32 upward.
	 * </p>
	 */
	public static final int USE_BOOTSTRAP_NODES_THRESHOLD_ENTRIES = BOOTSTRAP_THRESHOLD_ENTRIES / 2;

	/**
	 * How many buckets a single bootstrap may fill with an iterative lookup.
	 * <p>
	 * <b>What it controls.</b> Bootstrap ends by topping up partially populated buckets, one full
	 * iterative {@code NodeLookupTask} on a random id per bucket. This bounds how many of those a
	 * single bootstrap dispatches. Buckets over the budget are not dropped - they are simply not
	 * refreshed this time, stay stale, and therefore sort to the front of the next bootstrap's
	 * selection. The cap costs latency in filling the table, never completeness.
	 * </p>
	 * <p>
	 * <b>Why it needs a bound at all.</b> The fan-out is one lookup per eligible bucket, so its cost
	 * scales with the size of the routing table - the nodes doing the most work fan out the widest.
	 * Frequency compounds it: bootstrap is not only a startup step, and a node whose table sits below
	 * the bootstrap threshold re-runs the whole fan-out every {@link #BOOTSTRAP_INTERVAL} rather
	 * than every {@link #SELF_LOOKUP_INTERVAL}. Below that threshold the "skip full buckets" rule also
	 * stops firing, so every non-empty bucket is filled every time. The burst is largest exactly when
	 * the table is weakest, which is when the node can least afford it.
	 * </p>
	 * <p>
	 * <b>Why 8.</b> A quarter of the default {@link #CONCURRENT_TASKS} and half the smallest value
	 * that may be configured, so bucket-filling can never own the task manager and starve the
	 * application lookups and ping refreshes queued alongside it. The absolute number matters less
	 * than that ratio; what an implementation must not do is let a maintenance fan-out size itself
	 * from the routing table while the queue it shares is fixed. For reference mldht runs the same
	 * unbounded loop but caps concurrent tasks at 7, so its fan-out is throttled by the queue instead.
	 * </p>
	 * <p>
	 * <b>Trade-off.</b> Lower fills the table more slowly after a restart from a cached routing table;
	 * higher lets a burst of maintenance delay user-visible lookups. Note the budget interacts with the
	 * per-bucket rate limiter - a bucket may be lookup-filled at most once per
	 * {@link #BUCKET_REFRESH_INTERVAL} - so raising this does not make a node fill the same buckets
	 * more often, only more distinct buckets at once.
	 * </p>
	 * <p>
	 * <b>Behavior as k grows.</b> This budget counts lookups, and each lookup gets more expensive with
	 * k: convergence needs about {@code k + min(k, }{@link #LOOKUP_STABILITY_ATTEMPTS}{@code ) + 1}
	 * responses. So the real cost of a full budget scales with k even though the number does not, and
	 * the cap matters more on a super node, not less. A larger k is a reason to lower this, never to
	 * raise it.
	 * </p>
	 * <p>
	 * Implementation limit, not protocol: purely this node's maintenance budget.
	 * </p>
	 */
	public static final int MAX_BUCKET_FILLS_PER_BOOTSTRAP = 8;

	private KadConstants() {
	}
}