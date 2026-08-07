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
	 * exist to contain it. Memory grows as k per bucket. The lookup convergence rule
	 * ({@code ClosestSet.isEligible}) needs about 2k insert attempts, so lookups get proportionally
	 * more expensive - see {@link #LOOKUP_CONVERGENCE_FACTOR}. The candidate queue would grow as 3k and
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
	// The real termination rule is convergence, in LookupTask#isDone(): the lookup stops when the
	// closest set is "eligible" (ClosestSet#isEligible) and no unqueried candidate is closer than its
	// tail. Everything below exists for the paths where that never happens - an unresponsive region
	// of the network, packet loss, or a peer feeding an endless stream of plausible-looking nodes.
	// ---------------------------------------------------------------------------------------------

	/**
	 * Multiplier on k giving the number of insert attempts a lookup needs before it can possibly
	 * converge, used to size {@code LookupTask.maxIterations}.
	 * <p>
	 * <b>Why 2.</b> {@link io.bosonnetwork.kademlia.tasks.ClosestSet#isEligible()} is
	 * {@code reachedCapacity() && insertAttemptsSinceTailModification > capacity}, and the capacity is
	 * k. So a lookup needs about k insert attempts to fill the closest set, then a further k+1 attempts
	 * that fail to improve the tail before it may declare convergence - roughly {@code 2 * k} attempts
	 * in total. Insert attempts track responses about one for one, and an iteration is driven by a
	 * response, so {@code 2 * k} is the floor below which the iteration cap would cut the lookup off
	 * before the convergence rule could ever fire.
	 * </p>
	 * <p>
	 * <b>Trade-off.</b> This term is not slack - it is the minimum. Setting the iteration cap below it
	 * does not merely make lookups cheaper, it makes every lookup terminate by exhaustion instead of by
	 * convergence, and (see {@code LookupTask#isDone()}) the caller cannot tell the difference: both
	 * report COMPLETED. A store built on a truncated lookup silently lands on fewer nodes than
	 * intended. This is why the cap is derived rather than configured.
	 * </p>
	 */
	public static final int LOOKUP_CONVERGENCE_FACTOR = 2;

	/**
	 * Number of lookup rounds' worth of extra iterations allowed on top of the convergence floor, in
	 * units of alpha.
	 * <p>
	 * <b>What it covers.</b> Two things the convergence floor does not: the depth ramp before the
	 * closest set starts filling, and iterations consumed without progress. Kademlia converges in
	 * O(log_k N) rounds rather than O(log2 N), because every response returns up to k nodes - for a
	 * 10^9-node network at k=16 that is about 7 rounds, or ~21 RPCs at alpha 3. Separately, an
	 * unanswered RPC currently consumes iterations without inserting anything (a call transitions
	 * STALLED and then TIMEOUT, and both drive an iteration), so a lossy path burns budget.
	 * </p>
	 * <p>
	 * <b>Why 8.</b> {@code alpha * 8} = 24 at the default alpha, which covers the ~7-round depth ramp
	 * for a network far larger than this one is likely to get, with room for a handful of dead peers.
	 * Erring high is cheap here: the budget is a ceiling, and a healthy lookup converges long before
	 * reaching it. Erring low is not cheap - see LOOKUP_CONVERGENCE_FACTOR.
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
	 * ({@link #BOOTSTRAP_MIN_INTERVAL}, {@link #SELF_LOOKUP_INTERVAL}). Most ticks do nothing. 30
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
	 */
	public static final int BOOTSTRAP_MIN_INTERVAL = 4 * 60 * 1000;                 // 4 minutes

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
	 * How many buckets' worth of contacts the node wants before it stops trying to bootstrap,
	 * expressed as a multiple of k rather than as an absolute count.
	 * <p>
	 * Above {@code BOOTSTRAP_THRESHOLD_BUCKETS * k} entries the table is considered self-sustaining
	 * and normal maintenance keeps it healthy; below it the node is at risk of partition and
	 * re-bootstraps, subject to {@link #BOOTSTRAP_MIN_INTERVAL}. Three buckets is enough for lookups
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
	 * {@link #BOOTSTRAP_MIN_INTERVAL} indefinitely - permanently, since the retry has no backoff.
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
	 * same futile self-bootstrap every {@link #BOOTSTRAP_MIN_INTERVAL}. Keeping the fallback threshold
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
	 * the bootstrap threshold re-runs the whole fan-out every {@link #BOOTSTRAP_MIN_INTERVAL} rather
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
	 * k: convergence needs roughly {@link #LOOKUP_CONVERGENCE_FACTOR} * k responses. So the real cost
	 * of a full budget scales with k even though the number does not, and the cap matters more on a
	 * super node, not less. A larger k is a reason to lower this, never to raise it.
	 * </p>
	 * <p>
	 * Implementation limit, not protocol: purely this node's maintenance budget.
	 * </p>
	 */
	public static final int MAX_BUCKET_FILLS_PER_BOOTSTRAP = 8;

	private KadConstants() {
	}
}