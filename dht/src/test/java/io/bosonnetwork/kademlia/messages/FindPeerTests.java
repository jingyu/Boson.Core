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

package io.bosonnetwork.kademlia.messages;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.kademlia.Constants;
import io.bosonnetwork.kademlia.messages.deprecated.FindPeerRequest;
import io.bosonnetwork.kademlia.messages.deprecated.FindPeerResponse;
import io.bosonnetwork.kademlia.messages.deprecated.OldMessage;
import io.bosonnetwork.kademlia.messages.deprecated.OldMessage.Method;
import io.bosonnetwork.kademlia.messages.deprecated.OldMessage.Type;

public class FindPeerTests extends MessageTests {
	@Deprecated
	@Test
	public void testFindPeerRequestSize() throws Exception {
		FindPeerRequest msg = new FindPeerRequest(Id.random());
		msg.setId(Id.random());
		msg.setTxid(0x87654321);
		msg.setVersion(VERSION);
		msg.setWant4(true);
		msg.setWant6(true);
		byte[] bin = msg.serialize();
		printMessage(msg, bin);
		assertTrue(bin.length <= msg.estimateSize());
	}

	@Deprecated
	@Test
	public void testFindPeerRequest4() throws Exception {
		Id id = Id.random();
		Id target = Id.random();
		int txid = Random.random().nextInt();

		FindPeerRequest msg = new FindPeerRequest(target);
		msg.setId(id);
		msg.setTxid(txid);
		msg.setVersion(VERSION);
		msg.setWant4(true);
		msg.setWant6(false);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		OldMessage pm = OldMessage.parse(bin);
		pm.setId(id);
		assertInstanceOf(FindPeerRequest.class, pm);
		FindPeerRequest m = (FindPeerRequest)pm;

		assertEquals(Type.REQUEST, m.getType());
		assertEquals(Method.FIND_PEER, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(VERSION_STR, m.getReadableVersion());
		assertEquals(target, m.getTarget());
		assertTrue(m.doesWant4());
		assertFalse(m.doesWant6());

		var msg2 = (Message<io.bosonnetwork.kademlia.messages.FindPeerRequest>) Message.parse(bin);
		msg2.setId(id);
		printMessage(msg2);
		assertEquals(msg.getType().value(), msg2.getType().value());
		assertEquals(msg.getMethod().value(), msg2.getMethod().value());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getReadableVersion(), msg2.getReadableVersion());
		var body = msg2.getBody();
		assertEquals(msg.getTarget(), body.getTarget());
		assertEquals(msg.doesWant4(), body.doesWant4());
		assertEquals(msg.doesWant6(), body.doesWant6());

		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testFindPeerRequest6() throws Exception {
		Id id = Id.random();
		Id target = Id.random();
		int txid = Random.random().nextInt();

		FindPeerRequest msg = new FindPeerRequest(target);
		msg.setId(id);
		msg.setTxid(txid);
		msg.setWant4(false);
		msg.setWant6(true);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		OldMessage pm = OldMessage.parse(bin);
		assertInstanceOf(FindPeerRequest.class, pm);
		pm.setId(id);
		FindPeerRequest m = (FindPeerRequest)pm;

		assertEquals(Type.REQUEST, m.getType());
		assertEquals(Method.FIND_PEER, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(target, m.getTarget());
		assertFalse(m.doesWant4());
		assertTrue(m.doesWant6());


		var msg2 = (Message<io.bosonnetwork.kademlia.messages.FindPeerRequest>) Message.parse(bin);
		msg2.setId(id);
		printMessage(msg2);
		assertEquals(msg.getType().value(), msg2.getType().value());
		assertEquals(msg.getMethod().value(), msg2.getMethod().value());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getReadableVersion(), msg2.getReadableVersion());
		var body = msg2.getBody();
		assertEquals(msg.getTarget(), body.getTarget());
		assertEquals(msg.doesWant4(), body.doesWant4());
		assertEquals(msg.doesWant6(), body.doesWant6());

		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testFindPeerRequest46() throws Exception {
		Id id = Id.random();
		Id target = Id.random();
		int txid = Random.random().nextInt();

		FindPeerRequest msg = new FindPeerRequest(target);
		msg.setId(id);
		msg.setTxid(txid);
		msg.setWant4(true);
		msg.setWant6(true);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		OldMessage pm = OldMessage.parse(bin);
		pm.setId(id);
		assertInstanceOf(FindPeerRequest.class, pm);
		FindPeerRequest m = (FindPeerRequest)pm;

		assertEquals(Type.REQUEST, m.getType());
		assertEquals(Method.FIND_PEER, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(target, m.getTarget());
		assertTrue(m.doesWant4());
		assertTrue(m.doesWant6());

		var msg2 = (Message<io.bosonnetwork.kademlia.messages.FindPeerRequest>) Message.parse(bin);
		msg2.setId(id);
		printMessage(msg2);
		assertEquals(msg.getType().value(), msg2.getType().value());
		assertEquals(msg.getMethod().value(), msg2.getMethod().value());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getReadableVersion(), msg2.getReadableVersion());
		var body = msg2.getBody();
		assertEquals(msg.getTarget(), body.getTarget());
		assertEquals(msg.doesWant4(), body.doesWant4());
		assertEquals(msg.doesWant6(), body.doesWant6());

		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testFindPeerResponseSize() throws Exception {
		List<NodeInfo> nodes4 = new ArrayList<>();
		nodes4.add(new NodeInfo(Id.random(), "251.251.251.251", 65535));
		nodes4.add(new NodeInfo(Id.random(), "251.251.251.251", 65534));
		nodes4.add(new NodeInfo(Id.random(), "251.251.251.251", 65533));
		nodes4.add(new NodeInfo(Id.random(), "251.251.251.251", 65532));
		nodes4.add(new NodeInfo(Id.random(), "251.251.251.251", 65531));
		nodes4.add(new NodeInfo(Id.random(), "251.251.251.251", 65530));
		nodes4.add(new NodeInfo(Id.random(), "251.251.251.251", 65529));
		nodes4.add(new NodeInfo(Id.random(), "251.251.251.251", 65528));

		List<NodeInfo> nodes6 = new ArrayList<>();
		nodes6.add(new NodeInfo(Id.random(), "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 65535));
		nodes6.add(new NodeInfo(Id.random(), "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 65534));
		nodes6.add(new NodeInfo(Id.random(), "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 65533));
		nodes6.add(new NodeInfo(Id.random(), "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 65532));
		nodes6.add(new NodeInfo(Id.random(), "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 65531));
		nodes6.add(new NodeInfo(Id.random(), "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 65530));
		nodes6.add(new NodeInfo(Id.random(), "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 65529));
		nodes6.add(new NodeInfo(Id.random(), "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 65528));

		List<PeerInfo> peers = new ArrayList<>();
		byte[] sig = new byte[64];
		Id pid = Id.random();
		for (int i = 0; i < 8; i++) {
			Random.random().nextBytes(sig);
			peers.add(PeerInfo.of(pid, Id.random(), 65535 - i, sig));
		}

		FindPeerResponse msg = new FindPeerResponse(0xF7654321);
		msg.setId(Id.random());
		msg.setVersion(VERSION);
		msg.setNodes4(nodes4);
		msg.setNodes6(nodes6);
		msg.setToken(0x87654321);
		msg.setPeers(peers);

		byte[] bin = msg.serialize();
		printMessage(msg, bin);
		assertTrue(bin.length <= msg.estimateSize());
	}

	@Deprecated
	@Test
	public void testFindPeerResponseSize2() throws Exception {
		List<NodeInfo> nodes4 = new ArrayList<>();
		nodes4.add(new NodeInfo(Id.random(), "251.251.251.251", 65535));
		nodes4.add(new NodeInfo(Id.random(), "251.251.251.251", 65534));
		nodes4.add(new NodeInfo(Id.random(), "251.251.251.251", 65533));
		nodes4.add(new NodeInfo(Id.random(), "251.251.251.251", 65532));
		nodes4.add(new NodeInfo(Id.random(), "251.251.251.251", 65531));
		nodes4.add(new NodeInfo(Id.random(), "251.251.251.251", 65530));
		nodes4.add(new NodeInfo(Id.random(), "251.251.251.251", 65529));
		nodes4.add(new NodeInfo(Id.random(), "251.251.251.251", 65528));

		List<NodeInfo> nodes6 = new ArrayList<>();
		nodes6.add(new NodeInfo(Id.random(), "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 65535));
		nodes6.add(new NodeInfo(Id.random(), "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 65534));
		nodes6.add(new NodeInfo(Id.random(), "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 65533));
		nodes6.add(new NodeInfo(Id.random(), "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 65532));
		nodes6.add(new NodeInfo(Id.random(), "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 65531));
		nodes6.add(new NodeInfo(Id.random(), "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 65530));
		nodes6.add(new NodeInfo(Id.random(), "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 65529));
		nodes6.add(new NodeInfo(Id.random(), "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 65528));

		List<PeerInfo> peers = new ArrayList<>();
		byte[] sig = new byte[64];
		Id pid = Id.random();
		for (int i = 0; i < 8; i++) {
			Random.random().nextBytes(sig);
			peers.add(PeerInfo.of(pid, Id.random(), Id.random(), 65535 - i, "http://abc.pc2.net", sig));
		}

		FindPeerResponse msg = new FindPeerResponse(0xF7654321);
		msg.setId(Id.random());
		msg.setVersion(VERSION);
		msg.setNodes4(nodes4);
		msg.setNodes6(nodes6);
		msg.setToken(0x87654321);
		msg.setPeers(peers);

		byte[] bin = msg.serialize();
		printMessage(msg, bin);
		assertTrue(bin.length <= msg.estimateSize());
	}

	@Deprecated
	@Test
	public void testFindPeerResponse4() throws Exception {
		Id id = Id.random();
		int txid = Random.random().nextInt();
		int token = Random.random().nextInt();

		List<NodeInfo> nodes4 = new ArrayList<>();
		nodes4.add(new NodeInfo(Id.random(), "251.251.251.251", 65535));
		nodes4.add(new NodeInfo(Id.random(), "192.168.1.2", 1232));
		nodes4.add(new NodeInfo(Id.random(), "192.168.1.3", 1233));
		nodes4.add(new NodeInfo(Id.random(), "192.168.1.4", 1234));
		nodes4.add(new NodeInfo(Id.random(), "192.168.1.5", 1235));

		List<PeerInfo> peers = new ArrayList<>();
		byte[] sig = new byte[64];
		Id pid = Id.random();
		for (int i = 0; i < 8; i++) {
			Random.random().nextBytes(sig);
			peers.add(PeerInfo.of(pid, Id.random(), 65535 - i, sig));
		}

		FindPeerResponse msg = new FindPeerResponse(txid);
		msg.setId(id);
		msg.setVersion(VERSION);
		msg.setNodes4(nodes4);
		msg.setToken(token);
		msg.setPeers(peers);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		OldMessage pm = OldMessage.parse(bin);
		pm.setId(id);
		assertInstanceOf(FindPeerResponse.class, pm);
		FindPeerResponse m = (FindPeerResponse)pm;

		assertEquals(Type.RESPONSE, m.getType());
		assertEquals(Method.FIND_PEER, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(VERSION_STR, m.getReadableVersion());
		assertEquals(token, m.getToken());
		assertTrue(m.getNodes6().isEmpty());
		assertFalse(m.getNodes4().isEmpty());
		assertFalse(m.getPeers().isEmpty());

		List<NodeInfo> rNodes = m.getNodes4();
		assertArrayEquals(nodes4.toArray(), rNodes.toArray());

		List<PeerInfo> rPeers = m.getPeers();
		assertArrayEquals(peers.toArray(), rPeers.toArray());

		var msg2 = (Message<io.bosonnetwork.kademlia.messages.FindPeerResponse>) Message.parse(bin);
		msg2.setId(id);
		printMessage(msg2);
		assertEquals(msg.getType().value(), msg2.getType().value());
		assertEquals(msg.getMethod().value(), msg2.getMethod().value());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getReadableVersion(), msg2.getReadableVersion());
		var body = msg2.getBody();
		assertEquals(msg.getNodes4(), body.getNodes4());
		assertEquals(msg.getNodes6(), body.getNodes6());
		assertEquals(msg.getToken(), body.getToken());
		assertEquals(msg.getPeers(), body.getPeers());

		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testFindPeerResponse6() throws Exception {
		Id id = Id.random();
		int txid = Random.random().nextInt();
		int token = Random.random().nextInt();

		List<NodeInfo> nodes6 = new ArrayList<>();
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:8070:6543:8a2e:0370:7334", 65535));
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:0000:0000:8a2e:0370:7332", 1232));
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:0000:0000:8a2e:0370:7333", 1233));
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:0000:0000:8a2e:0370:7334", 1234));
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:0000:0000:8a2e:0370:7335", 1235));

		List<PeerInfo> peers = new ArrayList<>();
		byte[] sig = new byte[64];
		Id pid = Id.random();
		for (int i = 0; i < 8; i++) {
			Random.random().nextBytes(sig);
			peers.add(PeerInfo.of(pid, Id.random(), Id.random(), 65535 - i, "http://abc.pc2.net", sig));
		}

		FindPeerResponse msg = new FindPeerResponse(txid);
		msg.setId(id);
		msg.setVersion(VERSION);
		msg.setNodes6(nodes6);
		msg.setToken(token);
		msg.setPeers(peers);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		OldMessage pm = OldMessage.parse(bin);
		pm.setId(id);
		assertInstanceOf(FindPeerResponse.class, pm);
		FindPeerResponse m = (FindPeerResponse)pm;

		assertEquals(Type.RESPONSE, m.getType());
		assertEquals(Method.FIND_PEER, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(VERSION_STR, m.getReadableVersion());
		assertEquals(token, m.getToken());
		assertTrue(m.getNodes4().isEmpty());
		assertFalse(m.getNodes6().isEmpty());
		assertFalse(m.getPeers().isEmpty());

		List<NodeInfo> rNodes = m.getNodes6();
		assertArrayEquals(nodes6.toArray(), rNodes.toArray());

		List<PeerInfo> rPeers = m.getPeers();
		assertArrayEquals(peers.toArray(), rPeers.toArray());

		var msg2 = (Message<io.bosonnetwork.kademlia.messages.FindPeerResponse>) Message.parse(bin);
		msg2.setId(id);
		printMessage(msg2);
		assertEquals(msg.getType().value(), msg2.getType().value());
		assertEquals(msg.getMethod().value(), msg2.getMethod().value());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getReadableVersion(), msg2.getReadableVersion());
		var body = msg2.getBody();
		assertEquals(msg.getNodes4(), body.getNodes4());
		assertEquals(msg.getNodes6(), body.getNodes6());
		assertEquals(msg.getToken(), body.getToken());
		assertEquals(msg.getPeers(), body.getPeers());

		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testFindPeerResponse46() throws Exception {
		Id id = Id.random();
		int txid = Random.random().nextInt();
		int token = Random.random().nextInt();

		List<NodeInfo> nodes4 = new ArrayList<>();
		nodes4.add(new NodeInfo(Id.random(), "251.251.251.251", 65535));
		nodes4.add(new NodeInfo(Id.random(), "192.168.1.2", 1232));
		nodes4.add(new NodeInfo(Id.random(), "192.168.1.3", 1233));
		nodes4.add(new NodeInfo(Id.random(), "192.168.1.4", 1234));
		nodes4.add(new NodeInfo(Id.random(), "192.168.1.5", 1235));

		List<NodeInfo> nodes6 = new ArrayList<>();
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:8070:6543:8a2e:0370:7334", 65535));
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:0000:0000:8a2e:0370:7332", 1232));
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:0000:0000:8a2e:0370:7333", 1233));
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:0000:0000:8a2e:0370:7334", 1234));
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:0000:0000:8a2e:0370:7335", 1235));

		List<PeerInfo> peers = new ArrayList<>();
		byte[] sig = new byte[64];
		Id pid = Id.random();

		for (int i = 0; i < 4; i++) {
			Random.random().nextBytes(sig);
			peers.add(PeerInfo.of(pid, Id.random(), 65535 - i, sig));
		}

		for (int i = 0; i < 4; i++) {
			Random.random().nextBytes(sig);
			peers.add(PeerInfo.of(pid, Id.random(), Id.random(), 65535 - i, "http://abc.pc2.net", sig));
		}

		FindPeerResponse msg = new FindPeerResponse(txid);
		msg.setId(id);
		msg.setNodes4(nodes4);
		msg.setNodes6(nodes6);
		msg.setToken(token);
		msg.setPeers(peers);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		OldMessage pm = OldMessage.parse(bin);
		pm.setId(id);
		assertInstanceOf(FindPeerResponse.class, pm);
		FindPeerResponse m = (FindPeerResponse)pm;

		assertEquals(Type.RESPONSE, m.getType());
		assertEquals(Method.FIND_PEER, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(0, m.getVersion());
		assertEquals(token, m.getToken());
		assertFalse(m.getNodes4().isEmpty());
		assertFalse(m.getNodes6().isEmpty());
		assertFalse(m.getPeers().isEmpty());

		List<NodeInfo> rNodes = m.getNodes4();
		assertArrayEquals(nodes4.toArray(), rNodes.toArray());

		rNodes = m.getNodes6();
		assertArrayEquals(nodes6.toArray(), rNodes.toArray());

		List<PeerInfo> rPeers = m.getPeers();
		assertArrayEquals(peers.toArray(), rPeers.toArray());

		var msg2 = (Message<io.bosonnetwork.kademlia.messages.FindPeerResponse>) Message.parse(bin);
		msg2.setId(id);
		printMessage(msg2);
		assertEquals(msg.getType().value(), msg2.getType().value());
		assertEquals(msg.getMethod().value(), msg2.getMethod().value());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getReadableVersion(), msg2.getReadableVersion());
		var body = msg2.getBody();
		assertEquals(msg.getNodes4(), body.getNodes4());
		assertEquals(msg.getNodes6(), body.getNodes6());
		assertEquals(msg.getToken(), body.getToken());
		assertEquals(msg.getPeers(), body.getPeers());

		assertArrayEquals(bin, msg2.toBytes());
	}

	/*				old		delta	new
		v4			381		-1		380
		v4+p		1091	+2		1093
		v6			477		-1		476
		v6+p		1187	+2		1189
		v4+v6		834		-2		832
		v4+v6+p		1544	+1		1545
		p			734		+3		737
	 */
	@Deprecated
	@ParameterizedTest(name = "{0}")
	@MethodSource("responseParameters")
	void testFindPeerResponse(String name, List<NodeInfo> nodes4, List<NodeInfo> nodes6, List<PeerInfo> peers, int expectedSize) throws Exception {
		var nodeId = Id.random();
		var txid = 0x76543210;

		var msg = new FindPeerResponse(txid);
		msg.setId(nodeId);
		msg.setNodes4(nodes4);
		msg.setNodes6(nodes6);
		msg.setPeers(peers);
		msg.setVersion(Constants.VERSION);
		byte[] bin = msg.serialize();

		printMessage(msg, bin);
	}

	private static Stream<Arguments> requestParameters() {
		return Stream.of(
				Arguments.of("v4", true, false),
				Arguments.of("v6", false, true),
				Arguments.of("v4+v6", true, true)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("requestParameters")
	void testRequest(String name, boolean want4, boolean want6) throws Exception {
		var nodeId = Id.random();
		var target = Id.random();
		var txid = 0x76543210;
		var msg = Message.findPeerRequest(txid, target, want4, want6);
		msg.setId(nodeId);
		byte[] bin = msg.toBytes();

		printMessage(msg);

		assertEquals(63, bin.length);

		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.FIND_PEER, msg.getMethod());
		assertEquals(nodeId, msg.getId());
		assertEquals(txid, msg.getTxid());
		assertEquals(DEFAULT_VERSION_STR, msg.getReadableVersion());
		assertEquals(target, msg.getBody().getTarget());
		assertEquals(want4, msg.getBody().doesWant4());
		assertEquals(want6, msg.getBody().doesWant6());

		var msg2 = Message.parse(bin);
		msg2.setId(nodeId);
		assertEquals(msg, msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	private static Stream<Arguments> responseParameters() {
		var ip4 = "192.168.1.1";
		var port = 65535;

		var nodes4 = new ArrayList<NodeInfo>();
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));

		var ip6 = "2001:0db8:85a3:8070:6543:8a2e:0370:7386";

		var nodes6 = new ArrayList<NodeInfo>();
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));

		var peerId = Id.random();
		var sig = Random.randomBytes(64);
		var peers = new ArrayList<PeerInfo>();
		peers.add(PeerInfo.of(peerId, Id.random(), port--, sig));
		peers.add(PeerInfo.of(peerId, Id.random(), Id.random(), port--, sig));
		peers.add(PeerInfo.of(peerId, Id.random(), Id.random(), port--, "http://abc.example.com/", sig));
		peers.add(PeerInfo.of(peerId, Id.random(), port--, "https://foo.example.com/", sig));
		peers.add(PeerInfo.of(peerId, Id.random(), port, "http://bar.example.com/", sig));

		return Stream.of(
				Arguments.of("v4", nodes4, null, null, 380),
				Arguments.of("v4+peers", nodes4, null, peers, 1093),
				Arguments.of("v6", null, nodes6, null, 476),
				Arguments.of("v6+peers", null, nodes6, peers, 1189),
				Arguments.of("v4+v6", nodes4, nodes6, null, 832),
				Arguments.of("v4+v6+peers", nodes4, nodes6, peers, 1545),
				Arguments.of("peers", null, null, peers, 737)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("responseParameters")
	void testResponse(String name, List<NodeInfo> nodes4, List<NodeInfo> nodes6, List<PeerInfo> peers, int expectedSize) throws Exception {
		var nodeId = Id.random();
		var txid = 0x76543210;

		var msg = Message.findPeerResponse(txid, nodes4, nodes6, peers);
		msg.setId(nodeId);
		byte[] bin = msg.toBytes();

		printMessage(msg);

		assertEquals(expectedSize, bin.length);

		assertEquals(Message.Type.RESPONSE, msg.getType());
		assertEquals(Message.Method.FIND_PEER, msg.getMethod());
		assertEquals(nodeId, msg.getId());
		assertEquals(txid, msg.getTxid());
		assertEquals(DEFAULT_VERSION_STR, msg.getReadableVersion());

		if (nodes4 != null)
			assertEquals(nodes4, msg.getBody().getNodes4());
		else
			assertTrue(msg.getBody().getNodes4().isEmpty());

		if (nodes6 != null)
			assertEquals(nodes6, msg.getBody().getNodes6());
		else
			assertTrue(msg.getBody().getNodes6().isEmpty());

		if (peers != null)
			assertEquals(peers, msg.getBody().getPeers());
		else
			assertTrue(msg.getBody().getPeers().isEmpty());

		var msg2 = Message.parse(bin);
		msg2.setId(nodeId);
		assertEquals(msg, msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@Test
	void timingRequest() throws Exception {
		var nodeId = Id.random();
		var target = Id.random();
		var txid = 0x76543210;

		{ // TODO: remove
			// warmup
			var msg = new FindPeerRequest(target);
			msg.setId(nodeId);
			msg.setTxid(txid);
			msg.setWant4(true);
			msg.setVersion(Constants.VERSION);

			var bin = msg.serialize();
			OldMessage.parse(bin);

			var start = System.currentTimeMillis();
			for (var i = 0; i < TIMING_ITERATIONS; i++) {
				msg = new FindPeerRequest(target);
				msg.setId(nodeId);
				msg.setTxid(txid);
				msg.setWant4(true);
				msg.setVersion(Constants.VERSION);

				bin = msg.serialize();
				OldMessage.parse(bin);
			}
			var end = System.currentTimeMillis();
			System.out.printf(">>>>>>>> FindPeerRequest: %dms\n", (end - start));
		}

		// warmup
		var msg = Message.findPeerRequest(txid, target, true, false);
		msg.setId(nodeId);
		var bin = msg.toBytes();
		Message.parse(bin);

		var start = System.currentTimeMillis();
		for (var i = 0; i < TIMING_ITERATIONS; i++) {
			msg = Message.findPeerRequest(txid, target, true, false);
			msg.setId(nodeId);
			bin = msg.toBytes();
			Message.parse(bin);
		}
		var end = System.currentTimeMillis();
		System.out.printf(">>>>>>>> FindPeerRequest: %dms, estimated: streaming ~= 540ms, *mapping ~= 700ms @ MBP-13-m1pro\n", (end - start));
	}

	@Test
	void timingResponse() throws Exception {
		var nodeId = Id.random();
		var txid = 0x76543210;

		int port = 65535;

		var peerId = Id.random();
		var sig = Random.randomBytes(64);
		var peers = new ArrayList<PeerInfo>();
		peers.add(PeerInfo.of(peerId, Id.random(), port--, sig));
		peers.add(PeerInfo.of(peerId, Id.random(), Id.random(), port--, sig));
		peers.add(PeerInfo.of(peerId, Id.random(), Id.random(), port--, "http://abc.example.com/", sig));
		peers.add(PeerInfo.of(peerId, Id.random(), port--, "https://foo.example.com/", sig));
		peers.add(PeerInfo.of(peerId, Id.random(), port, "http://bar.example.com/", sig));

		{ // TODO: remove
			// warmup
			var msg = new FindPeerResponse(txid);
			msg.setId(nodeId);
			msg.setPeers(peers);
			msg.setVersion(Constants.VERSION);
			var bin = msg.serialize();
			OldMessage.parse(bin);

			var start = System.currentTimeMillis();
			for (var i = 0; i < TIMING_ITERATIONS; i++) {
				msg = new FindPeerResponse(txid);
				msg.setId(nodeId);
				msg.setPeers(peers);
				msg.setVersion(Constants.VERSION);
				bin = msg.serialize();
				OldMessage.parse(bin);
			}
			var end = System.currentTimeMillis();
			System.out.printf(">>>>>>>> FindPeerResponse: %dms\n", (end - start));
		}

		// warmup
		var msg = Message.findPeerResponse(txid, null, null, peers);
		msg.setId(nodeId);
		var bin = msg.toBytes();
		Message.parse(bin);

		var start = System.currentTimeMillis();
		for (var i = 0; i < TIMING_ITERATIONS; i++) {
			msg = Message.findPeerResponse(txid, null, null, peers);
			msg.setId(nodeId);
			bin = msg.toBytes();
			Message.parse(bin);
		}
		var end = System.currentTimeMillis();
		System.out.printf(">>>>>>>> FindPeerResponse: %dms, estimated: streaming ~= 1360ms, *mapping ~= 2850ms @ MBP-13-m1pro\n", (end - start));
	}
}