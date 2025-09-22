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
import io.bosonnetwork.Network;
import io.bosonnetwork.NodeConfiguration;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.kademlia.protocol.FindNodeRequest;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.rpc.RpcCall;
import io.bosonnetwork.kademlia.rpc.RpcCallListener;
import io.bosonnetwork.utils.AddressUtils;
import io.bosonnetwork.utils.Base58;
import io.bosonnetwork.utils.FileUtils;
import io.bosonnetwork.utils.vertx.VertxFuture;

public class SybilTests {
	private static final Path testDir = Path.of(System.getProperty("java.io.tmpdir"), "boson", "SybilTests");

	private Vertx vertx;
	private KadNode target;
	private NodeInfo targetInfo;

	private static final InetAddress localAddr = AddressUtils.getAllAddresses()
			.filter(Inet4Address.class::isInstance)
			.filter(AddressUtils::isAnyUnicast)
			.distinct()
			.findFirst()
			.orElse(null);

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
				.port(39001)
				.generatePrivateKey()
				.dataPath(testDir.resolve("nodes"  + File.separator + "node-target"))
				.storageURL("jdbc:sqlite:" + testDir.resolve("nodes"  + File.separator + "node-target" + File.separator + "storage.db"))
				.enableDeveloperMode()
				.build());
		target.run().get();

		targetInfo = target.getNodeInfo().getV4();
	}

	@AfterEach
	void tearDown() throws Exception {
		target.shutdown().get();

		VertxFuture.of(vertx.close()).get();

		FileUtils.deleteFile(testDir);
	}

	@Test
	void TestAddresses() throws Exception {
		final int SYBIL_NODES = 10;
		final int ALLOWED_ATTEMPTS = 8;

		String sybilKey = Base58.encode(Signature.KeyPair.random().privateKey().bytes());
		KadNode sybil;
		for (int i = 0; i < SYBIL_NODES; i++) {
			System.out.format("\n\n======== Testing request #%d ...\n\n", i);
			NodeConfiguration sybilConfig = NodeConfiguration.builder()
					.vertx(vertx)
					.address4(localAddr)
					.port(39002 + i)
					.privateKey(sybilKey)
					.dataPath(testDir.resolve("nodes"  + File.separator + "node-" + i))
					.enableDeveloperMode()
					.build();

			sybil = new KadNode(sybilConfig);
			sybil.run().get();

			Message<FindNodeRequest> request = Message.findNodeRequest(Id.random(), true, false);
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

			sybil.getDHT(Network.IPv4).getRpcServer().sendCall(call);

			synchronized(result) {
				result.wait();
			}

			if (i < ALLOWED_ATTEMPTS)
				assertTrue(result.get());
			else
				assertFalse(result.get());

			sybil.shutdown().get();

			TimeUnit.SECONDS.sleep(2);
		}
	}

	@Test
	void TestIds() throws Exception {
		final int SYBIL_NODES = 36;
		final int ALLOWED_ATTEMPTS = 32;

		KadNode sybil;
		for (int i = 0; i < SYBIL_NODES; i++) {
			System.out.format("\n\n======== Testing request #%d ...\n\n", i);

			NodeConfiguration sybilConfig = NodeConfiguration.builder()
					.vertx(vertx)
					.generatePrivateKey()
					.address4(localAddr)
					.port(39002)
					.dataPath(testDir.resolve("nodes"  + File.separator + "node-" + i))
					.enableDeveloperMode()
					.build();

			sybil = new KadNode(sybilConfig);
			sybil.run().get();

			Message<FindNodeRequest> request = Message.findNodeRequest(Id.random(), true, false);
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

			sybil.getDHT(Network.IPv4).getRpcServer().sendCall(call);

			synchronized(result) {
				result.wait();
			}

			if (i <= ALLOWED_ATTEMPTS)
				assertTrue(result.get());
			else
				assertFalse(result.get());

			sybil.shutdown().get();

			TimeUnit.SECONDS.sleep(2);
		}
	}
}