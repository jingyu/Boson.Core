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

package io.bosonnetwork.kademlia.rpc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramPacket;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.net.SocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.crypto.CryptoBox;
import io.bosonnetwork.crypto.CryptoException;
import io.bosonnetwork.kademlia.exceptions.MessageTooBigException;
import io.bosonnetwork.kademlia.impl.ErrorCode;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.impl.Network;
import io.bosonnetwork.kademlia.metrics.DHTMetrics;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.security.Blacklist;
import io.bosonnetwork.kademlia.security.SpamThrottle;
import io.bosonnetwork.kademlia.security.SuspiciousNodeDetector;
import io.bosonnetwork.kademlia.utils.TimeoutSampler;
import io.bosonnetwork.metrics.Measured;

/**
 * Manages RPC communication for a Kademlia Distributed Hash Table (DHT) system.
 * Handles sending and receiving messages, managing RPC calls, and enforcing throttling,
 * blacklisting, and timeout policies. This class operates in a single-threaded environment
 * and is designed for internal use within the DHT system. It is not thread-safe and does
 * not support serialization. Integrates with {@link RpcCall} for call lifecycle management
 * and uses Vert.x for asynchronous socket operations.
 */
public class RpcServer implements Measured {
	// Transport-layer parameters, owned by this class rather than by KadConstants: each one is
	// meaningful only inside the RPC layer's own machinery - the socket, the reachability detector,
	// the per-address throttles, the timeout sampler - and none is node-wide policy. The three
	// RPC_CALL_TIMEOUT_* values in particular are calibrated as a set and are the arguments to one
	// TimeoutSampler constructor call. RPC_CALL_TIMEOUT_MAX is the only one published outward: it is
	// this layer's promise about how long a call can take, which RpcCall and KBucketEntry both rely on.

	/** Socket send buffer size (1 MB). */
	private static final int SOCKET_SEND_BUFFER_SIZE = 1024 * 1024;
	/** Socket receive buffer size (1 MB). */
	private static final int SOCKET_RECEIVE_BUFFER_SIZE = 1024 * 1024;
	/** Interval for checking server reachability (5 seconds). */
	private static final int REACHABILITY_CHECK_INTERVAL = 5_000;
	/** Timeout for determining server unreachability (60 seconds). */
	private static final int REACHABILITY_TIMEOUT = 60_000;
	// The two throttles are sized separately, because they express opposite policies and one pair of
	// numbers cannot hold both.
	//
	// Inbound is a local choice: how much work this node accepts from a source it has not verified. A node
	// may raise it as far as its CPU and uplink allow, and nobody else is affected by the decision.
	//
	// Outbound is a promise about someone else's limit. Calls we send past a peer's inbound burst are
	// dropped at their end, and each one comes back to us as a timeout - failed-request counts, a demoted
	// entry, a skewed RTT sample - charged to a peer whose only offense was enforcing its own ceiling. So
	// the outbound numbers track what a *default* node accepts, not what this one does, and they move only
	// once the network is known to have moved.

	/** Inbound packets per second accepted from one source unit (IPv4 /32, IPv6 /64). */
	static final int INBOUND_LIMIT_PER_SECOND = 32;
	/**
	 * Inbound burst capacity per source unit.
	 * <p>
	 * Front-load only: the sustained cost to a sender is the rate above, and a saturated counter is
	 * accepting again after a single decay tick whatever this is set to. What it buys is room for
	 * correlated legitimate bursts - a NAT full of nodes restarting together, a lookup fanning in - which
	 * arrive all at once or not at all. What it costs is the one-off work, and the one-off reflection
	 * budget, that one source unit can draw before the ceiling engages.
	 * </p>
	 */
	static final int INBOUND_BURST_CAPACITY = 512;
	/** Outbound calls per second to one target unit. Held at the network default - see above. */
	static final int OUTBOUND_LIMIT_PER_SECOND = 32;
	/** Outbound burst capacity per target unit. Held at the network default - see above. */
	static final int OUTBOUND_BURST_CAPACITY = 128;
	/**
	 * Floor for the active-call table, and its size for any ordinary node.
	 * <p>
	 * The table is not sized from the task budget alone: the bootstrap fan-out, the periodic random ping
	 * and the unsolicited ping for an unknown id all take slots without being counted in it, and the last
	 * of those is driven by inbound traffic rather than by configuration. This floor is what leaves them
	 * room at the default settings, where the task budget is a small fraction of it.
	 * </p>
	 */
	private static final int MIN_ACTIVE_CALLS = 1024;
	/** Maximum timeout for RPC calls (10 seconds). */
	public static final int RPC_CALL_TIMEOUT_MAX = 10_000;
	/** Minimum baseline timeout for RPC calls (100 milliseconds). */
	private static final int RPC_CALL_TIMEOUT_BASELINE_MIN = 100;
	/** Bin size for timeout sampling (50 milliseconds). */
	private static final int RPC_CALL_TIMEOUT_BIN_SIZE = 50;
	/** Initial capacity for the pending calls map (256). */
	private static final int DEFAULT_PENDING_CALLS_CAPACITY = 256;

	/** Context providing access to Vert.x and DHT runtime information. */
	private final KadContext context;

	/** Local node identity for encryption and identification. */
	private final Identity identity;

	/** Network type for the server. */
	private final Network network;

	/** Host address for the server socket. */
	private final String host;

	/** Port for the server socket. */
	private final int port;

	/** Blacklist for banning malicious nodes. */
	private final Blacklist blacklist;

	/** Tracker for suspicious nodes, maybe disabled with a disabled SuspiciousNodeTracker implementation. */
	private final SuspiciousNodeDetector suspiciousNodeDetector;

	/** Sampler for calculating RPC call timeouts. */
	private final TimeoutSampler timeoutSampler;

	/** Throttle for incoming messages, maybe disabled with a disabled SpamThrottle implementation. */
	private final SpamThrottle inboundThrottle;

	/** Throttle for outgoing messages, maybe disabled with a disabled SpamThrottle implementation. */
	private final SpamThrottle outboundThrottle;

	/** Metrics collector, null if metrics are disabled. */
	private final DHTMetrics metrics;

	/** Datagram socket for sending and receiving messages, null when stopped. */
	private DatagramSocket socket;

	/** Map of active RPC calls, keyed by transaction ID. */
	private final Map<Long, RpcCall> pendingCalls;

	/**
	 * Ceiling on {@link #pendingCalls}, derived from the configuration rather than fixed.
	 * <p>
	 * A running task holds up to {@code alpha} calls in flight and the task manager runs up to
	 * {@code concurrentTasks} of them, so anything below that product is a ceiling the node cannot reach
	 * its own configured concurrency under - and it does not fail politely: {@link #sendCall} rejects
	 * rather than queues, and the rejection reaches the task as an error, so a task retires a candidate it
	 * never asked. A super node configured for thousands of concurrent tasks would spend most of its call
	 * attempts that way against a fixed 1024.
	 * </p>
	 * <p>
	 * Package-private because the derivation is worth pinning and the enforcement is not reachable from a
	 * test without a thousand outstanding calls.
	 * </p>
	 */
	final int maxActiveCalls;

	/** Total number of received packets. */
	private long receivedPackets;
	/** Number of packets received at the last reachability check. */
	private long receivedPacketsAtLastReachableCheck;
	/**
	 * Timestamp of the last request we sent, or 0 if we have never sent one.
	 * <p>
	 * Requests only, never responses: this exists to answer "are we waiting for an answer that is not
	 * coming", and a response we send is not something we expect a reply to.
	 * </p>
	 */
	private long lastCallSent;
	/** Timestamp of the last reachability check. */
	private long lastReachableCheck;
	/** Indicates whether the server is reachable. */
	private boolean reachable;
	/** Timer ID for periodic reachability checks. */
	private long reachableCheckTimer;
	/** Handler for reachability state changes, null if not set. */
	private Consumer<Boolean> reachableHandler;

	/** Handler for incoming messages, null if not set. */
	private Consumer<Message> messageHandler;

	/** Handler for RPC call sent, null if not set. */
	private Consumer<RpcCall> callSentHandler;

	/** Handler for RPC call timeouts, null if not set. */
	private Consumer<RpcCall> callTimeoutHandler;

	/** Handler for identity churn at a known endpoint, null if not set. */
	private BiConsumer<NodeInfo, Boolean> churnHandler;

	/** Server start time in milliseconds, or -1 if not started. */
	private long startTime;

	/** Indicates whether the server is running. */
	private boolean running;

	/** Logger for debugging and error reporting. */
	private static final Logger log = LoggerFactory.getLogger(RpcServer.class);

	/**
	 * Constructs an RPC server with the specified configuration.
	 *
	 * @param context                     the DHT context providing Vert.x and configuration
	 * @param host                        the host address to bind the server
	 * @param port                        the port to bind the server
	 * @param blacklist                   the blacklist for banning malicious nodes
	 * @param suspiciousNodeDetector      the suspicious node tracker
	 * @param enableSpamThrottling        whether to enable spam throttling
	 * @param metrics                     the metrics collector, null to disable metrics
	 */
	public RpcServer(KadContext context, String host, int port, Blacklist blacklist,
					 SuspiciousNodeDetector suspiciousNodeDetector, boolean enableSpamThrottling,
					 DHTMetrics metrics) {
		this.context = context;
		this.network = context.getNetwork();
		this.identity = context.getIdentity();
		this.host = host;
		this.port = port;
		this.blacklist = blacklist;
		this.metrics = metrics;

		this.suspiciousNodeDetector = suspiciousNodeDetector;

		// Initialize timeout sampler for RTT calculations
		this.timeoutSampler = new TimeoutSampler(RPC_CALL_TIMEOUT_BIN_SIZE,
				0, RPC_CALL_TIMEOUT_MAX, RPC_CALL_TIMEOUT_BASELINE_MIN);

		// Initialize throttles for spam protection
		if (enableSpamThrottling && !context.isDeveloperMode()) {
			this.inboundThrottle = SpamThrottle.create(INBOUND_LIMIT_PER_SECOND, INBOUND_BURST_CAPACITY);
			this.outboundThrottle = SpamThrottle.create(OUTBOUND_LIMIT_PER_SECOND, OUTBOUND_BURST_CAPACITY);
		} else {
			this.inboundThrottle = SpamThrottle.disabled();
			this.outboundThrottle = SpamThrottle.disabled();
		}

		// Read once, like TaskManager reads concurrentTasks: both values are final on the DHT, so there is
		// nothing to keep fresh. The long is for the multiplication only - neither factor has an upper
		// bound in the configuration, and a table sized past what an int can hold is not a table anyway.
		long taskDemand = (long) context.getConcurrentTasks() * context.getAlpha();
		this.maxActiveCalls = (int) Math.max(MIN_ACTIVE_CALLS, Math.min(taskDemand, Integer.MAX_VALUE));

		// Initialize pending calls map
		this.pendingCalls = new HashMap<>(DEFAULT_PENDING_CALLS_CAPACITY);

		this.startTime = -1;
		this.running = false;
	}

	/**
	 * Gets the ID of the local node.
	 *
	 * @return the local node ID
	 */
	public Id getId() {
		return identity.getId();
	}

	/**
	 * Gets the network type of the server.
	 *
	 * @return the network type
	 */
	public Network getType() {
		return network;
	}

	/**
	 * Gets the host address of the server.
	 *
	 * @return the host address
	 */
	public String getHost() {
		return host;
	}

	/**
	 * Gets the port of the server.
	 *
	 * @return the port
	 */
	public int getPort() {
		return port;
	}

	/**
	 * Checks if the metrics collection is enabled.
	 *
	 * @return true if metrics are enabled, false otherwise
	 */
	@Override
	public boolean isMetricsEnabled() {
		return metrics != null;
	}

	/**
	 * Periodically checks server reachability based on received packets.
	 * Sets the server as unreachable if a request has gone unanswered for
	 * {@link #REACHABILITY_TIMEOUT}.
	 *
	 * @param unusedTimerId the timer ID (unused)
	 */
	private void checkReachability(long unusedTimerId) {
		long now = System.currentTimeMillis();

		// Update reachability based on packet activity
		if (receivedPackets != receivedPacketsAtLastReachableCheck) {
			setReachable(true);
			lastReachableCheck = now;
			receivedPacketsAtLastReachableCheck = receivedPackets;
		} else if (unanswered(now, lastReachableCheck, lastCallSent)) {
			setReachable(false);
			// Reset timeout sampler to avoid stale RTT estimates for new connections
			timeoutSampler.reset();
		}
	}

	/**
	 * Whether we have asked a question and heard nothing back for long enough to call the socket deaf.
	 * <p>
	 * The verdict needs evidence, and the evidence is an unanswered request. {@code lastRequestSent >
	 * lastReceived} reads as "we sent a request after the last packet that arrived", so a node that
	 * sends nothing stays in whatever state it was in - no traffic, no verdict - and an idle node whose
	 * last request <em>was</em> answered is silent rather than deaf.
	 * </p>
	 * <p>
	 * The second condition used to be {@code receivedPackets != 0}, which meant a node whose network
	 * was broken from the very start - it had never received anything to compare against - could never
	 * be declared unreachable, and reported itself connected indefinitely. That is exactly the node
	 * whose background traffic is most futile, so it is the one the reachability gates most need to
	 * catch.
	 * </p>
	 *
	 * @param now the current time, in milliseconds
	 * @param lastReceived when a packet last arrived, in milliseconds
	 * @param lastRequestSent when we last sent a request, or 0 if we never have
	 * @return true if the socket should be considered unreachable
	 */
	static boolean unanswered(long now, long lastReceived, long lastRequestSent) {
		return now - lastReceived > REACHABILITY_TIMEOUT && lastRequestSent > lastReceived;
	}

	/**
	 * Updates the reachability state and notifies the handler.
	 *
	 * @param reachable the new reachability state
	 */
	private void setReachable(boolean reachable) {
		if (this.reachable == reachable) // nothing changed
			return;

		this.reachable = reachable;
		if (reachableHandler != null)
			reachableHandler.accept(reachable);
	}

	/**
	 * Sets the handler for reachability state changes.
	 *
	 * @param reachableHandler the handler to notify
	 */
	public void setReachableHandler(Consumer<Boolean> reachableHandler) {
		this.reachableHandler = reachableHandler;
	}

	/**
	 * Checks if the server is currently reachable.
	 *
	 * @return true if reachable, false otherwise
	 */
	public boolean isReachable() {
		return reachable;
	}

	/**
	 * Checks if there are any pending RPC calls.
	 *
	 * @return true if there are pending calls, false otherwise
	 */
	public boolean hasPendingCalls() {
		return !pendingCalls.isEmpty();
	}

	/**
	 * Checks if the server is running.
	 *
	 * @return true if the server is running, false otherwise
	 */
	public boolean isRunning() {
		return running;
	}

	/**
	 * Gets the duration since the server started.
	 *
	 * @return the uptime, or {@link Duration#ZERO} if not started
	 */
	public Duration age() {
		if(startTime == -1)
			return Duration.ZERO;

		return Duration.ofMillis(System.currentTimeMillis() - startTime);
	}

	/**
	 * Sets the handler for incoming messages.
	 *
	 * @param messageHandler the handler to process messages
	 */
	public void setMessageHandler(Consumer<Message> messageHandler) {
		this.messageHandler = messageHandler;
	}

	/**
	 * Sets the handler for the RPC call sent.
	 *
	 * @param callSentHandler the handler to process sent calls.
	 */
	public void setCallSentHandler(Consumer<RpcCall> callSentHandler) {
		this.callSentHandler = callSentHandler;
	}

	/**
	 * Sets the handler for RPC call timeouts.
	 *
	 * @param callTimeoutHandler the handler to process timeouts
	 */
	public void setCallTimeoutHandler(Consumer<RpcCall> callTimeoutHandler) {
		this.callTimeoutHandler = callTimeoutHandler;
	}

	/**
	 * Sets the handler for identity churn: an endpoint presented a different node id than the last one seen
	 * at that {@code ip:port}.
	 * <p>
	 * The handler is given the <b>stale</b> binding - the id that used to be at that address, not the one
	 * that just arrived - because that is what identifies the routing table entry the change invalidates.
	 * It runs before the message itself is dispatched, so a listener can act on the old binding while the
	 * new one is still unlearned.
	 * </p>
	 * <p>
	 * The second argument is what the report is <b>worth</b>, and a listener that ignores it is wrong in the
	 * direction this whole area exists to prevent:
	 * </p>
	 * <ul>
	 *   <li>{@code false} - the change was merely observed, on a message that decrypted. That authenticates
	 *       the id, not the source: a UDP source address is written by its sender, so anyone able to spoof
	 *       one reports churn at any endpoint they can name - and the endpoints we hold are what we hand out
	 *       when asked who is nearby. Nothing done here may be worth aiming at a third party.</li>
	 *   <li>{@code true} - a response proved it: it matched a call we had outstanding and came back from the
	 *       address that call was sent to, carrying an id other than the one we addressed. The address
	 *       demonstrably receives our traffic, since it answered a transaction id we chose and never
	 *       published, so this cannot have been aimed by a bystander.</li>
	 * </ul>
	 *
	 * @param churnHandler the handler to process identity churn, taking the stale binding and whether the
	 *        report is proven
	 */
	public void setChurnHandler(BiConsumer<NodeInfo, Boolean> churnHandler) {
		this.churnHandler = churnHandler;
	}

	/**
	 * Starts the RPC server, binding to the configured host and port.
	 *
	 * @return a Future that completes when the server is started
	 * @throws IllegalStateException if the server is already running
	 */
	public Future<Void> start() {
		if (running)
			throw new IllegalStateException("Server is already running");

		socket = context.getVertx().createDatagramSocket(new DatagramSocketOptions()
				// Defaults to IPv4-only; without this an IPv6 host fails to bind with
				// UnsupportedAddressTypeException.
				.setIpV6(context.getNetwork() == Network.IPv6)
				.setSendBufferSize(SOCKET_SEND_BUFFER_SIZE)
				.setReceiveBufferSize(SOCKET_RECEIVE_BUFFER_SIZE)
				.setTrafficClass(0x10));

		// Set up packet and exception handlers
		socket.handler(this::handlePacket);
		socket.exceptionHandler(e -> {
			log.error("DHT RPC server datagram socket error", e);
			if (metrics != null)
				metrics.exceptionOccurred(e);
		});

		// Bind the socket and start reachability checks
		return socket.listen(port, host).andThen(ar -> {
			if (ar.succeeded()) {
				startTime = System.currentTimeMillis();
				running = true;

				reachable = true;
				lastCallSent = 0;
				lastReachableCheck = startTime;
				// Schedule periodic reachability checks
				reachableCheckTimer = context.setPeriodic(REACHABILITY_CHECK_INTERVAL * 2,
						REACHABILITY_CHECK_INTERVAL, this::checkReachability);

				log.info("RPC server started at {}:{}", host, port);
			} else {
				log.error("RPC server start failed at {}:{} ", host, port, ar.cause());
			}
		}).mapEmpty();
	}

	/**
	 * Stops the RPC server, closing the socket and clearing resources.
	 *
	 * @return a Future that completes when the server is stopped
	 */
	public Future<Void> stop() {
		if (socket == null)
			return Future.succeededFuture();

		return socket.close().andThen(ar -> {
			socket = null;

			startTime = -1;
			running = false;

			context.cancelTimer(reachableCheckTimer);

			inboundThrottle.clear();
			outboundThrottle.clear();

			// Cancel rather than drop. A call outstanding when the socket closes can never be answered,
			// and its timeout timer belongs to the verticle context, which Vert.x tears down on undeploy
			// - so simply clearing the map leaves every caller waiting on a future that will never
			// settle. DHT.doBootstrap is one such caller, and a bootstrap that never completes leaves
			// its in-progress flag set, blocking every later attempt for the life of the instance.
			// Snapshot first: cancelling notifies listeners, which may reach back into this map.
			List<RpcCall> outstanding = new ArrayList<>(pendingCalls.values());
			pendingCalls.clear();
			for (RpcCall call : outstanding)
				call.cancel();

			if (ar.succeeded())
				log.info("RPC server at {}:{} stopped", host, port);
			else
				log.error("RPC server at {}:{} stop failed", host, port, ar.cause());
		});
	}

	/**
	 * Handles incoming datagram packets, processing messages and routing responses.
	 *
	 * @param packet the received datagram packet
	 */
	private void handlePacket(DatagramPacket packet) {
		receivedPackets++;

		Buffer buffer = packet.data();
		SocketAddress remoteAddress = packet.sender();

		if (metrics != null) {
			metrics.bytesRead(remoteAddress, buffer.length());
			metrics.messageReceived(remoteAddress);
		}

		// Check inbound throttle
		if (inboundThrottle.incrementAndCheck(remoteAddress.host())) {
			log.warn("Throttled a packet from {}", remoteAddress);
			if (metrics != null) {
				metrics.bytesDropped(remoteAddress, buffer.length());
				metrics.messageDropped(remoteAddress, DHTMetrics.Reason.THROTTLED);
			}
			return;
		}

		// Validate packet size
		if (buffer.length() < Id.BYTES + CryptoBox.MAC_BYTES + CryptoBox.MAC_BYTES + Message.MIN_BYTES) {
			log.warn("Ignored invalid packet(too short) from {}", remoteAddress);
			// Unproven source - a packet this short carries no identity at all, so the address it names is
			// the only thing to go on, and the sender chose that. Reported as an unproven observation, which
			// can suppress the source for a short while but can never ban it.
			suspiciousNodeDetector.malformedMessage(remoteAddress);
			if (metrics != null) {
				metrics.bytesDropped(remoteAddress, buffer.length());
				metrics.messageDropped(remoteAddress, DHTMetrics.Reason.INVALID);
			}
			return;
		}

		// Extract and validate remote ID
		Id remoteId = Id.of(buffer.getBytes(0, Id.BYTES));
		if (blacklist.isBanned(remoteId, remoteAddress.host())) {
			log.warn("Ignored packet from blacklisted node {}@{}", remoteId, remoteAddress);
			if (metrics != null) {
				metrics.bytesDropped(remoteAddress, buffer.length());
				metrics.messageDropped(remoteAddress, DHTMetrics.Reason.BANNED);
			}
			return;
		}
		if (suspiciousNodeDetector.isBanned(remoteAddress.host())) {
			log.warn("Ignored packet from suspicious node {}@{}", remoteId, remoteAddress);
			if (metrics != null) {
				metrics.bytesDropped(remoteAddress, buffer.length());
				metrics.messageDropped(remoteAddress, DHTMetrics.Reason.SUSPICIOUS);
			}
			return;
		}

		// Decryption and parsing run on a worker, and the extra context switch is worth paying for.
		//
		// The event loop is the funnel for everything: it reads every datagram and runs every
		// continuation below. So the figure that sets the ceiling is not total CPU but what the loop
		// itself spends per packet, and measured on a 10-core machine (JDK 17, the pure-Java Bouncy
		// Castle provider, shared key already cached) that comparison is:
		//
		//     inline, decrypt + parse      1.04 us for a PING, 5.19 us for a full-size response
		//     offloaded, loop-side cost    0.27 us per packet with a deep queue
		//
		// So the loop does roughly 4x to 19x less work per packet, and the crypto runs in parallel
		// across the pool instead of one packet at a time. The handoff looks expensive when measured
		// alone at low load - 5.95 us round trip with a single request in flight - but that is latency
		// on an idle node, not throughput, and Vert.x amortizes it once completions batch: 1.26 us at
		// eight in flight, 0.27 us at sixty-four. Against RTTs measured in milliseconds, the idle-node
		// latency does not matter.
		//
		// It also covers the case that hurts most. The shared key is cached per peer, so the numbers
		// above are the warm path; a first contact costs about 82 us for the Ed25519-to-Curve25519
		// conversion and the X25519 agreement. Inline, a lookup fanning out to eight unseen nodes would
		// stall the loop for most of a millisecond.
		//
		// Unordered on purpose: packets are independent, UDP promises no ordering, and responses are
		// matched by transaction id rather than by arrival. Ordered would serialize the pool behind one
		// queue and leave this strictly worse than the inline version it replaced, since the event loop
		// was already serial.
		context.executeBlocking(() -> {
			// Decrypt and parse message
			byte[] encryptedMsg = buffer.getBytes(Id.BYTES, buffer.length());
			byte[] decryptedMsg = identity.decrypt(remoteId, encryptedMsg);
			Message message = Message.parse(decryptedMsg, remoteId);
			message.setId(remoteId);
			message.setRemote(remoteId, remoteAddress);
			return message;
		}, false).andThen(ar -> {
			if (ar.succeeded()) {
				Message message = ar.result();
				log.trace("Received {}:{} from {}@{} : {}", message.getMethod(), message.getType(),
						remoteId, remoteAddress, message);

				// Identity accounting, before the message is dispatched. The detector answers with the id
				// this endpoint used to present, and a non-null answer means the binding a listener may be
				// holding for that address is no longer the one that is there.
				//
				// The stale binding is what goes to the handler, not the new one: it names the entry the
				// change invalidates, and the listener has no other way to find it - a routing table is
				// keyed on id, not on address.
				Id previousId = suspiciousNodeDetector.observed(remoteAddress, remoteId);
				if (previousId != null && churnHandler != null)
					// Unproven: this message authenticates the id that sent it and nothing about where it
					// came from.
					churnHandler.accept(NodeInfo.of(previousId, remoteAddress.hostAddress(), remoteAddress.port()), false);

				// Handle request messages
				if (message.isRequest()) {
					if (metrics != null)
						metrics.requestReceived(message);

					// Incoming requests, no need to match them to pending requests
					if (messageHandler != null)
						messageHandler.accept(message);

					return;
				}

				// Handle response or error messages
				// check if this is a response to an outstanding request
				RpcCall call = pendingCalls.get(message.getTxid());
				if (call != null) {
					// the message matches transaction ID and origin == destination
					// we only check the IP address here. the routing table applies more strict checks to also verify a stable port
					if (remoteAddress.equals(call.getRequest().getRemoteAddress())) {
						// Solicited traffic is not charged to the unsolicited budget. This packet answers a
						// call we made, from the address we sent it to, so it is work we asked for - and how
						// much of it we can ask for is bounded by the outbound throttle, not by this one.
						// Refund the charge handlePacket levied on arrival: one packet, for the one packet
						// that proved itself.
						//
						// A refund rather than an exemption, because the budget is spent before the packet
						// can be identified - the throttle check runs ahead of the decrypt that reveals the
						// transaction id. So a response arriving while its source is already at the ceiling
						// is still dropped; what this prevents is a conversation we started from consuming
						// that source's budget at all.
						//
						// All three outcomes below are refunded, including the two that are misbehavior:
						// they are still traffic we solicited, the detector punishes them on the channel
						// built for it, and a rate penalty is the wrong instrument for an offense that is
						// not about rate.
						//
						// The call's target address rather than the packet's source: the test above has
						// already established they are the same, and this one is an InetAddress we hold,
						// so the hot path parses no host string.
						inboundThrottle.decrement(call.getTarget().getIpAddress());

						if (message.getMethod() != call.getRequest().getMethod()) {
							log.warn("Got response with wrong method {} from {}@{} for {}",
									message.getMethod(), remoteId, remoteAddress, call.getRequest().getMethod());
							// This is a terminal error for the call: remove it from the pending map
							// (race-safe, mirroring the normal response path) so it is not leaked.
							if (pendingCalls.remove(message.getTxid(), call))
								call.respondWrongMethod(message);
							// Proven source: this matched an outstanding call and came back from the address
							// that call was sent to, so the address receives our traffic and the evidence
							// cannot have been aimed at a bystander.
							suspiciousNodeDetector.misbehaved(remoteAddress, remoteId);
							return;
						}

						// Proven identity churn
						if (!remoteId.equals(call.getTargetId())) {
							log.warn("Got response with churning id {} -> {} from {}",
									call.getTargetId(), remoteId, remoteAddress);
							// This is a terminal error for the call: remove it from the pending map
							// Still feed the response to the call to finish the call?!
							if (pendingCalls.remove(message.getTxid(), call))
								call.respondChurningId(message);

							suspiciousNodeDetector.misbehaved(remoteAddress, remoteId);

							// Proven: this arrived from the address we sent the call to, answering a
							// transaction id we chose, so no third party could have aimed it.
							if (churnHandler != null)
								churnHandler.accept(NodeInfo.of(call.getTargetId(), remoteAddress.hostAddress(), remoteAddress.port()), true);

							return;
						}

						// Remove call to prevent timeout race, defense against timeout race
						if (pendingCalls.remove(message.getTxid(), call)) {
							call.respond(message);

							if (messageHandler != null)
								messageHandler.accept(message);

							// Update the timeout sampler for non-known nodes to avoid skewing RTT estimates
							if(!call.isReachableAtCreationTime())
								timeoutSampler.updateAndRecalc(call.getRTT());

							if (metrics != null) {
								metrics.responseReceived(message);

								// Update loss rate: 0f for successful response, 1f for timeout
								if (call.isReachableAtCreationTime())
									metrics.verifiedLossRateUpdate(0f);
								else
									metrics.unverifiedLossRateUpdate(0f);
							}
						}

						return;
					}

					// Handle inconsistent socket (e.g., NAT issues or attack)
					// - the message is not a request
					// - the transaction ID matched
					// - response source did not match request destination
					// this happening by chance is exceedingly unlikely indicates either port-mangling NAT,
					// a multihomed host listening on any-local address or some kind of attack
					log.warn("Node address not consistent, ignored. request: {} <- response: {}@{}",
							call.getTarget(), remoteId, remoteAddress);
					// Unproven source, and unproven for the very reason this branch exists: the packet came
					// from somewhere other than where the call was sent, so nothing here says the sender
					// receives traffic at the address it used.
					suspiciousNodeDetector.inconsistent(remoteAddress, remoteId);

					if (metrics != null) {
						metrics.bytesDropped(remoteAddress, buffer.length());
						metrics.messageDropped(remoteAddress, DHTMetrics.Reason.INCONSISTENT);
					}

					// but expect an upcoming timeout if it's really just a misbehaving node
					call.respondInconsistentSocket(message);
					return;
				}

				// No matched call
				// - call already timed out
				// - stray response, uptime is high enough that it's a stray from a restart
				//
				// Deliberately not reported to the suspicious-node detector. Both causes above are normal
				// operation rather than misbehavior, and the source is unproven either way, so counting it
				// would charge an address for a race it did not cause.
				log.warn("Cannot find RPC call for {}[txid:{}]", message.getType(), message.getTxid());
				if (metrics != null) {
					metrics.bytesDropped(remoteAddress, buffer.length());
					metrics.messageDropped(remoteAddress, DHTMetrics.Reason.NO_MATCHED_CALL);
				}
			} else {
				Throwable e = ar.cause();
				if (e instanceof CryptoException) {
					log.warn("Decrypt packet error from {}, ignored", remoteAddress);
				} else if (e instanceof IllegalArgumentException) {
					if (log.isTraceEnabled()) // log the parse error for debugging
						log.trace("Parse message error from {}@{}, ignored", remoteId, remoteAddress, e.getCause());

					log.warn("Invalid message from {}@{}, ignored", remoteId, remoteAddress);
				} else {
					log.warn("Invalid message from {}@{}, ignored", remoteId, remoteAddress, e);
				}

				// Unproven source. A decryption failure says nothing about the sender at all - the id in
				// the first 32 bytes is whatever it chose to write there - and a parse failure identifies
				// the sender's key without saying anything about where the packet came from.
				suspiciousNodeDetector.malformedMessage(remoteAddress);
				if (metrics != null) {
					metrics.bytesDropped(remoteAddress, buffer.length());
					metrics.messageDropped(remoteAddress, DHTMetrics.Reason.INVALID);
				}
			}
		});
	}

	/**
	 * Sends an RPC call to a remote node, applying throttling and timeouts.
	 *
	 * @param call the RPC call to send
	 * @return a Future resolving to the sent RpcCall
	 */
	public Future<RpcCall> sendCall(RpcCall call) {
		if (pendingCalls.size() >= maxActiveCalls) {
			call.fail(new IllegalStateException("Maximum active calls exceeded"));
			return Future.failedFuture("Maximum active calls exceeded");
		}

		int delay = outboundThrottle.incrementAndEstimateDelay(call.getTarget().getIpAddress());
		if (delay > 0) {
			log.info("Throttled (delay {}ms) the RPC call to remote peer {}@{}, {}",
					delay, call.getTargetId(), call.getTarget().getHost(), call.getRequest());

			context.setTimer(delay, unused -> {
				outboundThrottle.decrement(call.getTarget().getIpAddress());
				sendCall(call);
			});

			if (metrics != null)
				metrics.throttledOutbound(call.getTarget().getHost(), delay);

			return Future.succeededFuture(call);
		}

		// setup call
		call.setExpectedRttIfAbsent(timeoutSampler::getStallTimeout)
				.setTimer(context)
				.setTimeoutHandler(c -> {
					// Remove call and skip if already processed
					boolean exists = pendingCalls.remove(call.getTxid(), call);
					if (!exists)
						return;

					// Notify timeout handler
					if (callTimeoutHandler != null)
						callTimeoutHandler.accept(c);

					if (metrics != null) {
						// Update loss rate: 0f for successful response, 1f for timeout
						if (call.isReachableAtCreationTime())
							metrics.verifiedLossRateUpdate(1f);
						else
							metrics.unverifiedLossRateUpdate(1f);
					}
				});

		pendingCalls.put(call.getTxid(), call);
		return sendMessage(call.getRequest()).andThen(ar -> {
			if (ar.succeeded()) {
				call.sent();
				// Feeds checkReachability: an unanswered request is the only evidence we have that the
				// socket has gone deaf, as opposed to the network simply being quiet.
				lastCallSent = System.currentTimeMillis();

				if (callSentHandler != null)
					callSentHandler.accept(call);

				// Nothing is credited to the inbound throttle here, on purpose. Sending a call used to
				// clear the target's inbound counter outright, which was the right intent - the answer we
				// asked for should not be dropped by the budget for traffic we did not - carried out three
				// ways wrong. It refunded up to a full burst for one expected response; it granted that
				// credit when we decided to speak rather than when an answer arrived, so anything the
				// address sent next could spend it; and since the counter is keyed per source unit rather
				// than per node, it credited everything sharing that unit.
				//
				// It was also reachable from outside: an unsolicited request bearing an unknown id makes
				// the DHT ping the sender, so a sender could clear its own counter roughly once per packet
				// and buy the burst back each time.
				//
				// The exemption now lives in handlePacket, where a packet can actually be shown to be an
				// answer to one of our calls - see the refund there.
			} else {
				pendingCalls.remove(call.getTxid());
				call.fail(ar.cause());
			}
		}).map(call);
	}

	/**
	 * Sends a message to a remote node, encrypting the content.
	 *
	 * @param message the message to send
	 * @return a Future that completes when the message is sent
	 */
	public Future<Void> sendMessage(Message message) {
		message.setId(identity.getId());

		// Serialization and encryption go to a worker for the same reason the receive side does - see
		// handlePacket for the measurements and the reasoning behind the extra context switch. The send
		// side is cheaper than the receive side but still well above the handoff: 0.86 us inline for a
		// PING and 2.85 us for a full-size response, against 0.27 us of loop time to offload it.
		//
		// Only the encoding moves. The socket write below runs on the event loop, where it belongs: the
		// datagram socket is not ours to touch from a pool thread.
		return context.executeBlocking(() -> encode(message), false).compose(datagram -> {
			// Not necessarily the message that was passed in: an oversized response is replaced by an
			// error, and everything below reports what actually went on the wire.
			Message sent = datagram.message();
			Buffer buffer = datagram.buffer();
			SocketAddress remote = sent.getRemoteAddress();
			return socket.send(buffer, remote.port(), remote.host()).andThen(ar -> {
				if (ar.succeeded()) {
					log.trace("Sent {}/{} to {}@{}: {}", sent.getMethod(), sent.getType(),
							sent.getRemoteId(), remote, sent);

					if (metrics != null) {
						metrics.bytesWritten(remote, buffer.length());
						metrics.messageSent(remote);
						metrics.requestSent(sent);
					}
				} else {
					if (log.isDebugEnabled())
						log.error("Failed to send {}/{} to {}@{}: {}", sent.getMethod(), sent.getType(),
								sent.getRemoteId(), remote, sent, ar.cause());
					else
						log.error("Failed to send {}/{} to {}@{}", sent.getMethod(), sent.getType(),
								sent.getRemoteId(), remote, ar.cause());

					if (metrics != null)
						metrics.messageSendFailed(remote, ar.cause());

					// A send failure (incl. transient socket-buffer exhaustion / ENOBUFS) drops this datagram
					// and fails the associated RpcCall; the iterative tasks tolerate individual losses via their
					// alpha-concurrency, so no retransmit is attempted here (UDP best-effort, matching Kademlia).
					/*/
					// Checking for specific errors by inspecting a generic IOException and its message is not ideal
					if (ar.cause() != null && Objects.equals(ar.cause().getMessage(), "No buffer space available")) {
						log.debug("Awaiting the socket available, set a timer to resend the messages.");
						context.owner().setTimer(1000, unused -> sendMessage(message));
					}
					*/
				}
			});
		});
	}

	/**
	 * An encrypted message and the message it actually carries.
	 * <p>
	 * The two are worth keeping together because they can disagree: an oversized response is sent as an
	 * error instead, and logging or counting the message that was handed in would then describe a
	 * datagram that never existed.
	 * </p>
	 *
	 * @param message the message this datagram carries.
	 * @param buffer  the bytes to put on the wire.
	 */
	private record Datagram(Message message, Buffer buffer) { }

	/**
	 * Serializes, size-checks and encrypts a message into the datagram that will be sent.
	 * <p>
	 * The size check is the last line of defense on the size of a datagram: every other bound in the
	 * module is a per-field limit or a per-entry estimate applied while a message is being built, and
	 * none of them has seen the message as a whole. What this catches is a message whose size was
	 * derived wrongly, or one built from a record stored before the limits that derive it existed.
	 * </p>
	 * <p>
	 * It runs before encryption rather than on the finished buffer, so a message that cannot be sent
	 * costs nothing to reject. That makes the checked size a <em>derivation</em> rather than a
	 * measurement - sender id, nonce, MAC and the serialized message, which is exactly what
	 * {@link Identity#encrypt} produces - so it is only as correct as that envelope is stable. The
	 * arithmetic is pinned by a test for that reason.
	 * </p>
	 * <p>
	 * Recursion is bounded at one step: what comes back from {@link #tooBigToSend} is a fixed-text
	 * error with no payload, which cannot itself exceed the budget.
	 * </p>
	 *
	 * @param message the message to encode.
	 * @return the datagram to send, which carries the substituted error if the message did not fit.
	 * @throws MessageTooBigException if the message does not fit and there is no one to tell.
	 * @throws CryptoException        if the message cannot be encrypted for its recipient.
	 */
	private Datagram encode(Message message) throws MessageTooBigException, CryptoException {
		byte[] plainMsg = message.toBytes();
		// The datagram this becomes: sender id || nonce || MAC || ciphertext, per CryptoIdentity.encrypt.
		int datagramSize = Id.BYTES + CryptoBox.Nonce.BYTES + CryptoBox.MAC_BYTES + plainMsg.length;
		if (datagramSize > network.maxPacketSize())
			return encode(tooBigToSend(message, datagramSize));

		try {
			byte[] encryptedMsg = identity.encrypt(message.getRemoteId(), plainMsg);
			Buffer buffer = Buffer.buffer(encryptedMsg.length + Id.BYTES);
			buffer.appendBytes(message.getId().bytesUnsafe());
			buffer.appendBytes(encryptedMsg);
			return new Datagram(message, buffer);
		} catch (CryptoException e) {
			log.error("!!!INTERNAL ERROR: Failed to encrypt message", e);
			throw e;
		}
	}

	/**
	 * Handles a message that will not fit one datagram on this socket.
	 * <p>
	 * Sending it anyway is the worst of the options: an oversized datagram is fragmented, a fragmented
	 * UDP datagram is lost entirely if any one fragment is lost, and middleboxes drop fragments
	 * outright - so it would work on the paths that need it least and fail silently on the rest.
	 * </p>
	 * <p>
	 * A <b>response</b> is replaced by an {@link ErrorCode#MessageTooBig} error, so the requester
	 * learns immediately instead of waiting out a timeout it would read as this node being
	 * unreachable. The substitute carries a fixed message and no payload, so it cannot itself be
	 * oversized; were it somehow refused as well it would arrive back here as an error, which is not
	 * substituted again, so the recursion is bounded at one step.
	 * </p>
	 * <p>
	 * Anything else - a request this node originated, or an error - simply fails. There is no remote
	 * party waiting on a request that was never sent, and the caller learns through the returned
	 * future: {@link #sendCall} drops the pending call and fails it with the same cause.
	 * </p>
	 * <p>
	 * Reaching here is a defect in whatever built the message, not an expected condition, which is why
	 * it is logged at error even though it is handled.
	 * </p>
	 *
	 * @param message the message that does not fit.
	 * @param size    the size of the datagram it would have produced, in bytes.
	 * @return the error response to send in its place.
	 * @throws MessageTooBigException if there is no remote party to inform.
	 */
	private Message tooBigToSend(Message message, int size) throws MessageTooBigException {
		log.error("Message {}/{} to {}@{} needs {} bytes, more than the {}-byte {} packet budget, not sent",
				message.getMethod(), message.getType(), message.getRemoteId(), message.getRemoteAddress(),
				size, network.maxPacketSize(), network);

		MessageTooBigException cause = new MessageTooBigException("Message too big to send in one datagram");

		if (metrics != null)
			metrics.messageSendFailed(message.getRemoteAddress(), cause);

		if (!message.isResponse())
			throw cause;

		Message error = Message.error(message.getMethod(), message.getTxid(), cause.getCode(), cause.getMessage());
		error.setRemote(message.getRemoteId(), message.getRemoteAddress());
		error.setId(identity.getId());
		return error;
	}

	/**
	 * Returns a string representation of the server, including its network, ID, address, and uptime.
	 *
	 * @return the string representation
	 */
	@Override
	public String toString() {
		// noinspection StringBufferReplaceableByString
		StringBuilder repr = new StringBuilder(160);

		repr.append("RPC Server[").append(network).append("]: ")
			.append(identity.getId()).append('@').append(host).append(':').append(port)
			.append(", uptime: ").append(age());

		return repr.toString();
	}
}
