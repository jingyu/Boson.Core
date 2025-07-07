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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.bosonnetwork.Configuration;
import io.bosonnetwork.Id;
import io.bosonnetwork.Network;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.kademlia.messages.deprecated.FindNodeRequest;
import io.bosonnetwork.kademlia.messages.deprecated.Message;
import io.bosonnetwork.utils.AddressUtils;
import io.bosonnetwork.utils.FileUtils;

@EnabledIfSystemProperty(named = "io.bosonnetwork.environment", matches = "development")
public class SybilTests {
	private InetAddress getIPv4Address() {
		return AddressUtils.getAllAddresses()
				.filter(Inet4Address.class::isInstance)
				.filter((a) -> AddressUtils.isAnyUnicast(a))
				.distinct()
				.findFirst()
				.orElse(null);
	}

	@Test
	public void TestAddresses() throws Exception {
		Configuration targetNodeConfig = new Configuration() {
			@Override
			public Inet4Address address4() {
				 return (Inet4Address)getIPv4Address();
			}
		};

		Node target = new Node(targetNodeConfig);
		target.start();

		NodeInfo targetInfo = new NodeInfo(target.getId(), targetNodeConfig.address4(), targetNodeConfig.port());

		Node sybil;
		Path sybilDir = Path.of(System.getProperty("java.io.tmpdir"), "boson", "sybil");
		if (Files.notExists(sybilDir))
			Files.createDirectories(sybilDir);

		for (int i = 0; i < 36; i++) {
			System.out.format("\n\n======== Testing request #%d ...\n\n", i);

			int port = 39002 + i;
			Configuration sybilConfig = new Configuration() {
				@Override
				public Inet4Address address4() {
					 return (Inet4Address)getIPv4Address();
				}

				@Override
				public int port() {
					return port;
				}

				@Override
				public Path dataPath() {
					return sybilDir;
				}
			};

			sybil = new Node(sybilConfig);
			sybil.start();

			FindNodeRequest fnr = new FindNodeRequest(Id.random(), false);
			RPCCall call = new RPCCall(targetInfo, fnr);

			AtomicBoolean result = new AtomicBoolean(false);
			call.addListener(new RPCCallListener() {
				@Override
				public void onResponse(RPCCall c, Message response) {
					synchronized(result) {
						result.set(true);
						result.notifyAll();
					}
				}
				@Override
				public void onTimeout(RPCCall c) {
					synchronized(result) {
						result.set(false);
						result.notifyAll();
					}
				}
			});

			sybil.getDHT(Network.IPv4).getServer().sendCall(call);

			synchronized(result) {
				result.wait();
			}

			if (i <= 31)
				assertTrue(result.get());
			else
				assertFalse(result.get());

			sybil.stop();

			TimeUnit.SECONDS.sleep(2);
		}

		target.stop();

		FileUtils.deleteFile(sybilDir);
	}

	@Test
	public void TestIds() throws Exception {
		Configuration targetNodeConfig = new Configuration() {
			@Override
			public Inet4Address address4() {
				 return (Inet4Address)getIPv4Address();
			}
		};

		Node target = new Node(targetNodeConfig);
		target.start();

		NodeInfo targetInfo = new NodeInfo(target.getId(), targetNodeConfig.address4(), targetNodeConfig.port());

		Node sybil;

		for (int i = 0; i < 36; i++) {
			System.out.format("\n\n======== Testing request #%d ...\n\n", i);

			Configuration sybilConfig = new Configuration() {
				@Override
				public Inet4Address address4() {
					 return (Inet4Address)getIPv4Address();
				}

				@Override
				public int port() {
					return 39002;
				}
			};

			sybil = new Node(sybilConfig);
			sybil.start();

			FindNodeRequest fnr = new FindNodeRequest(Id.random(), false);
			RPCCall call = new RPCCall(targetInfo, fnr);

			AtomicBoolean result = new AtomicBoolean(false);
			call.addListener(new RPCCallListener() {
				@Override
				public void onResponse(RPCCall c, Message response) {
					synchronized(result) {
						result.set(true);
						result.notifyAll();
					}
				}
				@Override
				public void onTimeout(RPCCall c) {
					synchronized(result) {
						result.set(false);
						result.notifyAll();
					}
				}
			});

			sybil.getDHT(Network.IPv4).getServer().sendCall(call);

			synchronized(result) {
				result.wait();
			}

			if (i <= 31)
				assertTrue(result.get());
			else
				assertFalse(result.get());

			sybil.stop();

			TimeUnit.SECONDS.sleep(2);
		}

		target.stop();
	}
}