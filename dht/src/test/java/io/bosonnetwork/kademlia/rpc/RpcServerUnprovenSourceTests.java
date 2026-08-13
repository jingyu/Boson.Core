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

package io.bosonnetwork.kademlia.rpc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Inet4Address;
import java.util.concurrent.TimeUnit;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import io.bosonnetwork.Identity;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.impl.Network;
import io.bosonnetwork.kademlia.impl.TestKadContext;
import io.bosonnetwork.kademlia.security.Blacklist;
import io.bosonnetwork.kademlia.security.SuspiciousNodeDetector;
import io.bosonnetwork.utils.AddressUtils;
import io.bosonnetwork.vertx.BosonVerticle;

/**
 * Packets that arrive without proving where they came from must not be able to silence the address they
 * name for any length of time worth aiming.
 * <p>
 * A UDP source address is written by the sender, so the receive path cannot tell a genuine peer's traffic
 * from traffic forged to look like it. Both bursts below are the cheapest packets an attacker can produce -
 * one too short to hold an identity, one long enough to reach the decrypt step and fail it - and neither
 * carries a single authenticated bit. They may cost the source a brief suppression; they must not cost it
 * the ban duration, because whoever chose the address on them chose who pays.
 * </p>
 * <p>
 * The two cases are separate tests on separate ports so that each meets a detector with no history. Sharing
 * one would let the first burst's escalation decide the second's outcome, and the escalation is not what is
 * under test here.
 * </p>
 */
@ExtendWith(VertxExtension.class)
public class RpcServerUnprovenSourceTests {
	/** Over the 32-hit threshold, under the inbound throttle's 128-packet burst - throttled packets are
	 *  dropped before the detector ever sees them, so a bigger burst would test less, not more. */
	private static final int PACKETS = 40;

	private static final long OBSERVATION_PERIOD = 60 * 1000;
	private static final int HITS = 32;
	private static final long BAN_DURATION = 30 * 60 * 1000;
	private static final long SUPPRESSION_DURATION = 1000;

	@SuppressWarnings("ConstantConditions")
	private static final String localAddr = AddressUtils.getDefaultRouteAddress(Inet4Address.class).getHostAddress();

	static class VictimNode extends BosonVerticle {
		final Identity identity = new CryptoIdentity();
		final SuspiciousNodeDetector detector =
				SuspiciousNodeDetector.create(OBSERVATION_PERIOD, HITS, BAN_DURATION, SUPPRESSION_DURATION);

		private final int port;
		private KadContext kadContext;
		private RpcServer rpcServer;

		VictimNode(int port) {
			this.port = port;
		}

		@Override
		protected void prepare(Vertx vertx, Context context) {
			super.prepare(vertx, context);

			kadContext = new TestKadContext(context, identity, Network.IPv4).setDeveloperMode(false);
			rpcServer = new RpcServer(kadContext, localAddr, port, Blacklist.empty(), detector, true, null);
		}

		@Override
		protected Future<Void> deploy() {
			return rpcServer.start();
		}

		@Override
		protected Future<Void> undeploy() {
			if (rpcServer != null)
				return rpcServer.stop().andThen(ar -> rpcServer = null);
			else
				return Future.succeededFuture();
		}
	}

	/**
	 * Fires a burst of datagrams at the victim and settles once they are all on the wire.
	 */
	private static Future<Void> flood(Vertx vertx, int port, int size) {
		DatagramSocket socket = vertx.createDatagramSocket();
		Future<Void> sent = Future.succeededFuture();

		for (int i = 0; i < PACKETS; i++) {
			byte[] junk = new byte[size];
			Random.random().nextBytes(junk);
			sent = sent.compose(unused -> socket.send(Buffer.buffer(junk), port, localAddr));
		}

		return sent.eventually(socket::close);
	}

	private void assertSuppressedButNotBanned(Vertx vertx, VertxTestContext context, int port, int size) {
		VictimNode victim = new VictimNode(port);

		vertx.deployVerticle(victim)
				.compose(unused -> flood(vertx, port, size))
				// Give the receive path a moment to drain the burst.
				.compose(unused -> vertx.timer(500))
				.compose(unused -> {
					context.verify(() -> assertTrue(victim.detector.isBanned(localAddr),
							"unattributable traffic should still cost the source something"));
					// Long enough for the suppression to lapse, nowhere near the ban duration. If an
					// unproven packet can ever reach the ban path again, this is what catches it.
					return vertx.timer(SUPPRESSION_DURATION * 2);
				})
				.compose(unused -> {
					context.verify(() -> assertFalse(victim.detector.isBanned(localAddr),
							"an unproven source must not be held for the ban duration"));
					return vertx.undeploy(victim.deploymentID());
				})
				.onComplete(context.succeedingThenComplete());
	}

	@Test
	@Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
	public void testUndersizedDatagramsCannotBanTheSource(Vertx vertx, VertxTestContext context) {
		// Below the minimum accepted length, so it is rejected before anything is decrypted.
		assertSuppressedButNotBanned(vertx, context, 8890, 16);
	}

	@Test
	@Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
	public void testUndecryptableDatagramsCannotBanTheSource(Vertx vertx, VertxTestContext context) {
		// Long enough to pass the length check and reach the decrypt step, which it then fails. The first
		// 32 bytes read as a node id, but nothing proves the sender holds that key - or sent from here.
		assertSuppressedButNotBanned(vertx, context, 8891, 128);
	}
}
