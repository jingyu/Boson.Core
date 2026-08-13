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

package io.bosonnetwork.kademlia;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeConfiguration;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.kademlia.impl.Network;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.rpc.RpcCall;
import io.bosonnetwork.kademlia.rpc.RpcCallListener;
import io.bosonnetwork.utils.AddressUtils;
import io.bosonnetwork.utils.Base58;
import io.bosonnetwork.utils.FileUtils;
import io.bosonnetwork.vertx.ContextualFuture;

public class SybilTests {
	private static final Path testDir = Path.of(System.getProperty("java.io.tmpdir"), "boson", "SybilTests");

	private Vertx vertx;
	private KadNode target;
	private NodeInfo targetInfo;

	private static final InetAddress localAddr = AddressUtils.getDefaultRouteAddress(Inet4Address.class);

	@BeforeEach
	void setUp() throws Exception {
		Files.createDirectories(testDir);

		vertx = Vertx.vertx(new VertxOptions()
				.setEventLoopPoolSize(32)
				.setWorkerPoolSize(8)
				.setBlockedThreadCheckIntervalUnit(TimeUnit.SECONDS)
				.setBlockedThreadCheckInterval(120));

		target = new KadNode(NodeConfiguration.builder()
				.vertx(vertx)
				.address4(localAddr)
				.port(39601)
				.generateKeyPair()
				.dataDir(testDir.resolve("nodes"  + File.separator + "node-target"))
				.databaseUri("jdbc:sqlite:" + testDir.resolve("nodes"  + File.separator + "node-target" + File.separator + "storage.db"))
				.developerMode(true)
				.build());
		target.start().get();

		targetInfo = target.getNodeInfo().orElseThrow();
	}

	@AfterEach
	void tearDown() throws Exception {
		target.stop().get();

		ContextualFuture.of(vertx.close()).get();

		FileUtils.deleteFile(testDir);
	}

	/**
	 * One identity moving between ports on one address is served, every time.
	 * <p>
	 * <b>This assertion is the reverse of what it used to be, on purpose.</b> The test previously required
	 * the target to stop answering after 8 attempts, because the detector counted each {@code ip:port} pair
	 * as a separate address and banned the host once 8 of them presented the same id. Two things were wrong
	 * with that. A port is not a resource anyone has to acquire, so counting ports let a sender multiply
	 * itself for free - and the same pattern is what an ordinary NAT rebinding produces, so the rule fired
	 * on honest peers at least as readily as on hostile ones.
	 * </p>
	 * <p>
	 * Nothing is given up by tolerating it: the routing table is keyed on id and rejects an entry colliding
	 * on id or address, so one identity occupying ten ports still occupies exactly one entry. There was no
	 * attack here to stop.
	 * </p>
	 * <p>
	 * The genuine version of this pattern - one identity answering from several addresses that each
	 * demonstrably receive our traffic - is still caught, and is covered by
	 * {@code SuspiciousNodeDetectorTests.testProvenObservationsTriggerTheSameIdMassBan}. It needs distinct
	 * addresses, which this fixture cannot produce from a single host.
	 * </p>
	 */
	@Test
	void TestAddresses() throws Exception {
		final int SYBIL_NODES = 10;

		String sybilKey = Base58.encode(Signature.KeyPair.random().privateKey().bytes());
		KadNode sybil;
		for (int i = 0; i < SYBIL_NODES; i++) {
			System.out.format("\n\n======== Testing request #%d ...\n\n", i);
			NodeConfiguration sybilConfig = NodeConfiguration.builder()
					.vertx(vertx)
					.address4(localAddr)
					.port(39602 + i)
					.privateKey(sybilKey)
					.dataDir(testDir.resolve("nodes"  + File.separator + "node-" + i))
					.developerMode(true)
					.build();

			sybil = new KadNode(sybilConfig);
			sybil.start().get();

			Message request = Message.findNodeRequest(Id.random(), true, false);
			RpcCall call = new RpcCall(targetInfo, request);

			AtomicBoolean result = new AtomicBoolean(false);
			call.addListener(new RpcCallListener() {
				@Override
				public void onStateChange(RpcCall call, RpcCall.State previous, RpcCall.State state) {}

				@Override
				public void onResponse(RpcCall c) {
					synchronized(result) {
						result.set(true);
						result.notifyAll();
					}
				}

				@Override
				public void onTimeout(RpcCall c) {
					synchronized(result) {
						result.set(false);
						result.notifyAll();
					}
				}
			});

			sybil.getDHT(Network.IPv4).sendCall(call);

			synchronized(result) {
				result.wait();
			}

			assertTrue(result.get(), "a port change must not cost a node its answer");

			sybil.stop().get();

			TimeUnit.SECONDS.sleep(2);
		}
	}

	/**
	 * A source may rotate through a bounded number of identities before it is told to slow down.
	 * <p>
	 * This is the Sybil ceiling, and the assertion is unchanged: ids are free to mint, so the only place to
	 * charge them is the address they are presented from. What changed underneath is the consequence -
	 * exceeding the budget now suppresses the source for a short, escalating interval rather than banning it
	 * for half an hour, because a request's source address is chosen by whoever sent it and a long ban is
	 * worth more to whoever aims it than it costs to produce.
	 * </p>
	 */
	@Test
	void TestIds() throws Exception {
		final int SYBIL_NODES = 36;
		final int ALLOWED_ATTEMPTS = 32;

		KadNode sybil;
		for (int i = 0; i < SYBIL_NODES; i++) {
			System.out.format("\n\n======== Testing request #%d ...\n\n", i);

			NodeConfiguration sybilConfig = NodeConfiguration.builder()
					.vertx(vertx)
					.generateKeyPair()
					.address4(localAddr)
					.port(39002)
					.dataDir(testDir.resolve("nodes"  + File.separator + "node-" + i))
					.developerMode(true)
					.build();

			sybil = new KadNode(sybilConfig);
			sybil.start().get();

			Message request = Message.findNodeRequest(Id.random(), true, false);
			RpcCall call = new RpcCall(targetInfo, request);

			AtomicBoolean result = new AtomicBoolean(false);
			call.addListener(new RpcCallListener() {
				@Override
				public void onStateChange(RpcCall call, RpcCall.State previous, RpcCall.State state) {}

				@Override
				public void onResponse(RpcCall c) {
					synchronized(result) {
						result.set(true);
						result.notifyAll();
					}
				}
				@Override
				public void onTimeout(RpcCall c) {
					synchronized(result) {
						result.set(false);
						result.notifyAll();
					}
				}
			});

			sybil.getDHT(Network.IPv4).sendCall(call);

			synchronized(result) {
				result.wait();
			}

			if (i <= ALLOWED_ATTEMPTS)
				assertTrue(result.get());
			else
				assertFalse(result.get());

			sybil.stop().get();

			TimeUnit.SECONDS.sleep(2);
		}
	}
}
