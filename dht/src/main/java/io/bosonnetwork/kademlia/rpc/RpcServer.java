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
import java.util.HashMap;
import java.util.Map;
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
import io.bosonnetwork.Network;
import io.bosonnetwork.crypto.CryptoBox;
import io.bosonnetwork.crypto.CryptoException;
import io.bosonnetwork.kademlia.impl.KadContext;
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
	/** Socket send buffer size (1 MB). */
	private static final int SOCKET_SEND_BUFFER_SIZE = 1024 * 1024;
	/** Socket receive buffer size (1 MB). */
	private static final int SOCKET_RECEIVE_BUFFER_SIZE = 1024 * 1024;
	/** Interval for checking server reachability (5 seconds). */
	private static final int REACHABILITY_CHECK_INTERVAL = 5_000;
	/** Timeout for determining server unreachability (60 seconds). */
	private static final int REACHABILITY_TIMEOUT = 60_000;
	/** Maximum RPC calls per second (32). */
	private static final int RPC_CALL_LIMIT_PER_SECOND = 32;
	/** Burst capacity for RPC calls (128). */
	private static final int RPC_CALL_BURST_CAPACITY = 128;
	/** Maximum number of active RPC calls. */
	private static final int MAX_ACTIVE_CALLS = 1024;
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

	/** Total number of received packets. */
	private long receivedPackets;
	/** Number of packets received at the last reachability check. */
	private long receivedPacketsAtLastReachableCheck;
	/** Timestamp of the last reachability check. */
	private long lastReachableCheck;
	/** Indicates whether the server is reachable. */
	private boolean reachable;
	/** Timer ID for periodic reachability checks. */
	private long reachableCheckTimer;
	/** Handler for reachability state changes, null if not set. */
	private Consumer<Boolean> reachableHandler;

	/** Handler for incoming messages, null if not set. */
	private Consumer<Message<?>> messageHandler;

	/** Handler for RPC call sent, null if not set. */
	private Consumer<RpcCall> callSentHandler;

	/** Handler for RPC call timeouts, null if not set. */
	private Consumer<RpcCall> callTimeoutHandler;

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
			this.inboundThrottle = SpamThrottle.create(RPC_CALL_LIMIT_PER_SECOND, RPC_CALL_BURST_CAPACITY);
			this.outboundThrottle = SpamThrottle.create(RPC_CALL_LIMIT_PER_SECOND, RPC_CALL_BURST_CAPACITY);
		} else {
			this.inboundThrottle = SpamThrottle.disabled();
			this.outboundThrottle = SpamThrottle.disabled();
		}

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
	 * Sets the server as unreachable if no packets are received within
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
		} else if (now - lastReachableCheck > REACHABILITY_TIMEOUT &&
				receivedPackets != 0 && receivedPacketsAtLastReachableCheck != 0) {
			setReachable(false);
			// Reset timeout sampler to avoid stale RTT estimates for new connections
			timeoutSampler.reset();
		}
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
	public void setMessageHandler(Consumer<Message<?>> messageHandler) {
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
	 * Starts the RPC server, binding to the configured host and port.
	 *
	 * @return a Future that completes when the server is started
	 * @throws IllegalStateException if the server is already running
	 */
	public Future<Void> start() {
		if (running)
			throw new IllegalStateException("Server is already running");

		socket = context.getVertx().createDatagramSocket(new DatagramSocketOptions()
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
			pendingCalls.clear();

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
		if (buffer.length() < Id.BYTES + CryptoBox.MAC_BYTES + Message.MIN_BYTES) {
			log.warn("Ignored invalid packet(too short) from {}", remoteAddress);
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

		// Decrypt and parse message
		Message<?> message;
		try {
			byte[] encryptedMsg = buffer.getBytes(Id.BYTES, buffer.length());
			byte[] decryptedMsg = identity.decrypt(remoteId, encryptedMsg);
			message = Message.parse(decryptedMsg, remoteId);
			message.setId(remoteId);
			message.setRemote(remoteId, remoteAddress);
		} catch (Exception e) {
			if (e instanceof CryptoException) {
				log.warn("Decrypt packet error from {}, ignored", remoteAddress);
			} else if (e instanceof IllegalArgumentException) {
				if (log.isTraceEnabled()) // log the parse error for debugging
					log.trace("Parse message error from {}@{}, ignored", remoteId, remoteAddress, e.getCause());

				log.warn("Invalid message from {}@{}, ignored", remoteId, remoteAddress);
			} else {
				log.warn("Invalid message from {}@{}, ignored", remoteId, remoteAddress, e);
			}

			suspiciousNodeDetector.malformedMessage(remoteAddress);
			if (metrics != null) {
				metrics.bytesDropped(remoteAddress, buffer.length());
				metrics.messageDropped(remoteAddress, DHTMetrics.Reason.INVALID);
			}
			return;
		}

		log.trace("Received {}:{} from {}@{} : {}", message.getMethod(), message.getType(),
				remoteId, remoteAddress, message);

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
				if (message.getMethod() != call.getRequest().getMethod()) {
					log.warn("Got response with wrong method {} from {}@{} for {}",
							message.getMethod(), remoteId, remoteAddress, call.getRequest().getMethod());
					call.respondWrongMethod(message);
					suspiciousNodeDetector.malformedMessage(remoteAddress);
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
			suspiciousNodeDetector.inconsistent(remoteAddress, remoteId);

			if (metrics != null) {
				metrics.bytesDropped(remoteAddress, buffer.length());
				metrics.messageDropped(remoteAddress, DHTMetrics.Reason.INCONSISTENT);
			}

			// but expect an upcoming timeout if it's really just a misbehaving node
			call.respondInconsistentSocket(message);
			return;
		}

		suspiciousNodeDetector.observe(remoteAddress, remoteId);

		// No matched call
		// - call already timed out
		// - stray response, uptime is high enough that it's a stray from a restart
		log.warn("Cannot find RPC call for {}[txid:{}]", message.getType(), message.getTxid());
		if (metrics != null) {
			metrics.bytesDropped(remoteAddress, buffer.length());
			metrics.messageDropped(remoteAddress, DHTMetrics.Reason.NO_MATCHED_CALL);
		}
	}

	/**
	 * Sends an RPC call to a remote node, applying throttling and timeouts.
	 *
	 * @param call the RPC call to send
	 * @return a Future resolving to the sent RpcCall
	 */
	public Future<RpcCall> sendCall(RpcCall call) {
		if (pendingCalls.size() >= MAX_ACTIVE_CALLS)
			return Future.failedFuture("Maximum active calls exceeded");

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

				if (callSentHandler != null)
					callSentHandler.accept(call);

				// Clear inbound throttle to allow responses
				log.debug("Reset inbound throttle for {}", call.getTarget());
				inboundThrottle.clear(call.getTarget().getIpAddress());
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
	public Future<Void> sendMessage(Message<?> message) {
		Buffer buffer;

		message.setId(identity.getId());

		try {
			byte[] encryptedMsg = identity.encrypt(message.getRemoteId(), message.toBytes());
			buffer = Buffer.buffer(encryptedMsg.length + Id.BYTES);
			buffer.appendBytes(message.getId().bytes());
			buffer.appendBytes(encryptedMsg);
		} catch (CryptoException e) {
			log.error("!!!INTERNAL ERROR: Failed to encrypt message", e);
			return Future.failedFuture(e);
		}

		SocketAddress remote = message.getRemoteAddress();
		return socket.send(buffer, remote.port(), remote.host()).andThen(ar -> {
			if (ar.succeeded()) {
				log.trace("Sent {}/{} to {}@{}: {}", message.getMethod(), message.getType(),
						message.getRemoteId(), remote, message);

				if (metrics != null) {
					metrics.bytesWritten(remote, buffer.length());
					metrics.messageSent(remote);
					metrics.requestSent(message);
				}
			} else {
				if (log.isDebugEnabled())
					log.error("Failed to send {}/{} to {}@{}: {}", message.getMethod(), message.getType(),
							message.getRemoteId(), remote, message, ar.cause());
				else
					log.error("Failed to send {}/{} to {}@{}", message.getMethod(), message.getType(),
							message.getRemoteId(), remote, ar.cause());

				if (metrics != null)
					metrics.messageSendFailed(remote, ar.cause());

				// TODO: how to check the ENOBUFS error?
				/*/
				// Checking for specific errors by inspecting a generic IOException and its message is not ideal
				if (ar.cause() != null && Objects.equals(ar.cause().getMessage(), "No buffer space available")) {
					log.debug("Awaiting the socket available, set a timer to resend the messages.");
					context.owner().setTimer(1000, unused -> sendMessage(message));
				}
				*/
			}
		});
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