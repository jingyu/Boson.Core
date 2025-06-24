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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.kademlia.Constants;
import io.bosonnetwork.kademlia.messages.Message.Method;
import io.bosonnetwork.kademlia.messages.Message.Type;
import io.bosonnetwork.kademlia.messages2.Message2;

public class FindNodeTests extends MessageTests {
	@Deprecated
	@Test
	public void testFindNodeRequestSize() throws Exception {
		FindNodeRequest msg = new FindNodeRequest(Id.random());
		msg.setId(Id.random());
		msg.setTxid(0x87654321);
		msg.setVersion(VERSION);
		msg.setWant4(true);
		msg.setWant6(true);
		msg.setWantToken(true);
		byte[] bin = msg.serialize();
		printMessage(msg, bin);
		assertTrue(bin.length <= msg.estimateSize());
	}

	@Deprecated
	@Test
	public void testFindNodeRequest4() throws Exception {
		Id id = Id.random();
		Id target = Id.random();
		int txid = Random.random().nextInt();

		FindNodeRequest msg = new FindNodeRequest(target);
		msg.setId(id);
		msg.setTxid(txid);
		msg.setVersion(VERSION);
		msg.setWant4(true);
		msg.setWant6(false);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		Message pm = Message.parse(bin);
		pm.setId(id);
		assertInstanceOf(FindNodeRequest.class, pm);
		FindNodeRequest m = (FindNodeRequest)pm;

		assertEquals(Type.REQUEST, m.getType());
		assertEquals(Method.FIND_NODE, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(VERSION_STR, m.getReadableVersion());
		assertEquals(target, m.getTarget());
		assertTrue(m.doesWant4());
		assertFalse(m.doesWant6());
		assertFalse(m.doesWantToken());

		// Compatibility
		var msg2 = (Message2<io.bosonnetwork.kademlia.messages2.FindNodeRequest>) Message2.parse(bin);
		msg2.setId(msg.getId());
		assertEquals(msg.getType().value(), msg2.getType().value());
		assertEquals(msg.getMethod().value(), msg2.getMethod().value());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		var body = msg2.getBody();
		assertNotNull(body);
		assertEquals(msg.getTarget(), body.getTarget());
		assertEquals(msg.doesWant4(), body.doesWant4());
		assertEquals(msg.doesWant6(), body.doesWant6());
		assertEquals(msg.doesWantToken(), body.doesWantToken());
		assertEquals(msg.getVersion(), msg2.getVersion());
		printMessage(msg2);

		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testFindNodeRequest4WithToken() throws Exception {
		Id id = Id.random();
		Id target = Id.random();
		int txid = Random.random().nextInt();

		FindNodeRequest msg = new FindNodeRequest(target);
		msg.setId(id);
		msg.setTxid(txid);
		msg.setVersion(VERSION);
		msg.setWant4(true);
		msg.setWant6(false);
		msg.setWantToken(true);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		Message pm = Message.parse(bin);
		pm.setId(id);
		assertInstanceOf(FindNodeRequest.class, pm);
		FindNodeRequest m = (FindNodeRequest)pm;

		assertEquals(Type.REQUEST, m.getType());
		assertEquals(Method.FIND_NODE, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(VERSION_STR, m.getReadableVersion());
		assertEquals(target, m.getTarget());
		assertTrue(m.doesWant4());
		assertFalse(m.doesWant6());
		assertTrue(m.doesWantToken());

		// Compatibility
		var msg2 = (Message2<io.bosonnetwork.kademlia.messages2.FindNodeRequest>) Message2.parse(bin);
		msg2.setId(msg.getId());
		assertEquals(msg.getType().value(), msg2.getType().value());
		assertEquals(msg.getMethod().value(), msg2.getMethod().value());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		var body = msg2.getBody();
		assertNotNull(body);
		assertEquals(msg.getTarget(), body.getTarget());
		assertEquals(msg.doesWant4(), body.doesWant4());
		assertEquals(msg.doesWant6(), body.doesWant6());
		assertEquals(msg.doesWantToken(), body.doesWantToken());
		assertEquals(msg.getVersion(), msg2.getVersion());
		printMessage(msg2);

		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testFindNodeRequest6() throws Exception {
		Id id = Id.random();
		Id target = Id.random();
		int txid = Random.random().nextInt();

		FindNodeRequest msg = new FindNodeRequest(target);
		msg.setId(id);
		msg.setTxid(txid);
		msg.setWant4(false);
		msg.setWant6(true);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		Message pm = Message.parse(bin);
		pm.setId(id);
		assertInstanceOf(FindNodeRequest.class, pm);
		FindNodeRequest m = (FindNodeRequest)pm;

		assertEquals(Type.REQUEST, m.getType());
		assertEquals(Method.FIND_NODE, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(target, m.getTarget());
		assertFalse(m.doesWant4());
		assertTrue(m.doesWant6());
		assertFalse(m.doesWantToken());

		// Compatibility
		var msg2 = (Message2<io.bosonnetwork.kademlia.messages2.FindNodeRequest>) Message2.parse(bin);
		msg2.setId(msg.getId());
		assertEquals(msg.getType().value(), msg2.getType().value());
		assertEquals(msg.getMethod().value(), msg2.getMethod().value());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		var body = msg2.getBody();
		assertNotNull(body);
		assertEquals(msg.getTarget(), body.getTarget());
		assertEquals(msg.doesWant4(), body.doesWant4());
		assertEquals(msg.doesWant6(), body.doesWant6());
		assertEquals(msg.doesWantToken(), body.doesWantToken());
		assertEquals(msg.getVersion(), msg2.getVersion());
		printMessage(msg2);

		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testFindNodeRequest6WithToken() throws Exception {
		Id id = Id.random();
		Id target = Id.random();
		int txid = Random.random().nextInt();

		FindNodeRequest msg = new FindNodeRequest(target);
		msg.setId(id);
		msg.setTxid(txid);
		msg.setWant4(false);
		msg.setWant6(true);
		msg.setWantToken(true);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		Message pm = Message.parse(bin);
		pm.setId(id);
		assertInstanceOf(FindNodeRequest.class, pm);
		FindNodeRequest m = (FindNodeRequest)pm;

		assertEquals(Type.REQUEST, m.getType());
		assertEquals(Method.FIND_NODE, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(target, m.getTarget());
		assertFalse(m.doesWant4());
		assertTrue(m.doesWant6());
		assertTrue(m.doesWantToken());

		// Compatibility
		var msg2 = (Message2<io.bosonnetwork.kademlia.messages2.FindNodeRequest>) Message2.parse(bin);
		msg2.setId(msg.getId());
		assertEquals(msg.getType().value(), msg2.getType().value());
		assertEquals(msg.getMethod().value(), msg2.getMethod().value());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		var body = msg2.getBody();
		assertNotNull(body);
		assertEquals(msg.getTarget(), body.getTarget());
		assertEquals(msg.doesWant4(), body.doesWant4());
		assertEquals(msg.doesWant6(), body.doesWant6());
		assertEquals(msg.doesWantToken(), body.doesWantToken());
		assertEquals(msg.getVersion(), msg2.getVersion());
		printMessage(msg2);

		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testFindNodeRequest46() throws Exception {
		Id id = Id.random();
		Id target = Id.random();
		int txid = Random.random().nextInt();

		FindNodeRequest msg = new FindNodeRequest(target);
		msg.setId(id);
		msg.setTxid(txid);
		msg.setWant4(true);
		msg.setWant6(true);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		Message pm = Message.parse(bin);
		pm.setId(id);
		assertInstanceOf(FindNodeRequest.class, pm);
		FindNodeRequest m = (FindNodeRequest)pm;

		assertEquals(Type.REQUEST, m.getType());
		assertEquals(Method.FIND_NODE, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(target, m.getTarget());
		assertTrue(m.doesWant4());
		assertTrue(m.doesWant6());
		assertFalse(m.doesWantToken());

		// Compatibility
		var msg2 = (Message2<io.bosonnetwork.kademlia.messages2.FindNodeRequest>) Message2.parse(bin);
		msg2.setId(msg.getId());
		assertEquals(msg.getType().value(), msg2.getType().value());
		assertEquals(msg.getMethod().value(), msg2.getMethod().value());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		var body = msg2.getBody();
		assertNotNull(body);
		assertEquals(msg.getTarget(), body.getTarget());
		assertEquals(msg.doesWant4(), body.doesWant4());
		assertEquals(msg.doesWant6(), body.doesWant6());
		assertEquals(msg.doesWantToken(), body.doesWantToken());
		assertEquals(msg.getVersion(), msg2.getVersion());
		printMessage(msg2);

		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testFindNodeRequest46WithToken() throws Exception {
		Id id = Id.random();
		Id target = Id.random();
		int txid = Random.random().nextInt();

		FindNodeRequest msg = new FindNodeRequest(target);
		msg.setId(id);
		msg.setTxid(txid);
		msg.setWant4(true);
		msg.setWant6(true);
		msg.setWantToken(true);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		Message pm = Message.parse(bin);
		pm.setId(id);
		assertInstanceOf(FindNodeRequest.class, pm);
		FindNodeRequest m = (FindNodeRequest)pm;

		assertEquals(Type.REQUEST, m.getType());
		assertEquals(Method.FIND_NODE, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(target, m.getTarget());
		assertTrue(m.doesWant4());
		assertTrue(m.doesWant6());
		assertTrue(m.doesWantToken());

		// Compatibility
		var msg2 = (Message2<io.bosonnetwork.kademlia.messages2.FindNodeRequest>) Message2.parse(bin);
		msg2.setId(msg.getId());
		assertEquals(msg.getType().value(), msg2.getType().value());
		assertEquals(msg.getMethod().value(), msg2.getMethod().value());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		var body = msg2.getBody();
		assertNotNull(body);
		assertEquals(msg.getTarget(), body.getTarget());
		assertEquals(msg.doesWant4(), body.doesWant4());
		assertEquals(msg.doesWant6(), body.doesWant6());
		assertEquals(msg.doesWantToken(), body.doesWantToken());
		assertEquals(msg.getVersion(), msg2.getVersion());
		printMessage(msg2);

		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testFindNodeResponseSize() throws Exception {
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

		FindNodeResponse msg = new FindNodeResponse(0xF7654321);
		msg.setId(Id.random());
		msg.setVersion(VERSION);
		msg.setNodes4(nodes4);
		msg.setNodes6(nodes6);
		msg.setToken(0x78901234);

		byte[] bin = msg.serialize();
		printMessage(msg, bin);
		assertTrue(bin.length <= msg.estimateSize());
	}

	@Deprecated
	@Test
	public void testFindNodeResponse4() throws Exception {
		Id id = Id.random();
		int txid = Random.random().nextInt();

		List<NodeInfo> nodes4 = new ArrayList<>();
		nodes4.add(new NodeInfo(Id.random(), "251.251.251.251", 65535));
		nodes4.add(new NodeInfo(Id.random(), "192.168.1.2", 1232));
		nodes4.add(new NodeInfo(Id.random(), "192.168.1.3", 1233));
		nodes4.add(new NodeInfo(Id.random(), "192.168.1.4", 1234));
		nodes4.add(new NodeInfo(Id.random(), "192.168.1.5", 1235));

		FindNodeResponse msg = new FindNodeResponse(txid);
		msg.setId(id);
		msg.setVersion(VERSION);
		msg.setNodes4(nodes4);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		Message pm = Message.parse(bin);
		pm.setId(id);
		assertInstanceOf(FindNodeResponse.class, pm);
		FindNodeResponse m = (FindNodeResponse)pm;

		assertEquals(Type.RESPONSE, m.getType());
		assertEquals(Method.FIND_NODE, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(VERSION_STR, m.getReadableVersion());
		assertTrue(m.getNodes6().isEmpty());
		assertFalse(m.getNodes4().isEmpty());
		assertEquals(0, m.getToken());

		List<NodeInfo> nodes = m.getNodes4();
		assertArrayEquals(nodes4.toArray(), nodes.toArray());

		// Compatibility
		var msg2 = (Message2<io.bosonnetwork.kademlia.messages2.FindNodeResponse>) Message2.parse(bin);
		msg2.setId(msg.getId());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getVersion(), msg2.getVersion());
		var body = msg2.getBody();
		assertNotNull(body);
		assertEquals(msg.getNodes4(), body.getNodes4());
		assertEquals(msg.getNodes6(), body.getNodes6());
		assertEquals(msg.getToken(), body.getToken());

		printMessage(msg2);

		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testFindNodeResponse4WithToken() throws Exception {
		Id id = Id.random();
		int txid = Random.random().nextInt();

		List<NodeInfo> nodes4 = new ArrayList<>();
		nodes4.add(new NodeInfo(Id.random(), "251.251.251.251", 65535));
		nodes4.add(new NodeInfo(Id.random(), "192.168.1.2", 1232));
		nodes4.add(new NodeInfo(Id.random(), "192.168.1.3", 1233));
		nodes4.add(new NodeInfo(Id.random(), "192.168.1.4", 1234));
		nodes4.add(new NodeInfo(Id.random(), "192.168.1.5", 1235));

		FindNodeResponse msg = new FindNodeResponse(txid);
		msg.setId(id);
		msg.setVersion(VERSION);
		msg.setNodes4(nodes4);
		msg.setToken(0x12345678);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		Message pm = Message.parse(bin);
		pm.setId(id);
		assertInstanceOf(FindNodeResponse.class, pm);
		FindNodeResponse m = (FindNodeResponse)pm;

		assertEquals(Type.RESPONSE, m.getType());
		assertEquals(Method.FIND_NODE, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(VERSION_STR, m.getReadableVersion());
		assertTrue(m.getNodes6().isEmpty());
		assertFalse(m.getNodes4().isEmpty());
		assertEquals(0x12345678, m.getToken());

		List<NodeInfo> nodes = m.getNodes4();
		assertArrayEquals(nodes4.toArray(), nodes.toArray());

		// Compatibility
		var msg2 = (Message2<io.bosonnetwork.kademlia.messages2.FindNodeResponse>) Message2.parse(bin);
		msg2.setId(msg.getId());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getVersion(), msg2.getVersion());
		var body = msg2.getBody();
		assertNotNull(body);
		assertEquals(msg.getNodes4(), body.getNodes4());
		assertEquals(msg.getNodes6(), body.getNodes6());
		assertEquals(msg.getToken(), body.getToken());

		printMessage(msg2);

		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testFindNodeResponse6() throws Exception {
		Id id = Id.random();
		int txid = Random.random().nextInt();

		List<NodeInfo> nodes6 = new ArrayList<>();
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:8070:6543:8a2e:0370:7334", 65535));
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:0000:0000:8a2e:0370:7332", 1232));
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:0000:0000:8a2e:0370:7333", 1233));
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:0000:0000:8a2e:0370:7334", 1234));
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:0000:0000:8a2e:0370:7335", 1235));

		FindNodeResponse msg = new FindNodeResponse(txid);
		msg.setId(id);
		msg.setVersion(VERSION);
		msg.setNodes6(nodes6);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		Message pm = Message.parse(bin);
		pm.setId(id);
		assertInstanceOf(FindNodeResponse.class, pm);
		FindNodeResponse m = (FindNodeResponse)pm;

		assertEquals(Type.RESPONSE, m.getType());
		assertEquals(Method.FIND_NODE, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(VERSION_STR, m.getReadableVersion());
		assertTrue(m.getNodes4().isEmpty());
		assertFalse(m.getNodes6().isEmpty());
		assertEquals(0, m.getToken());

		List<NodeInfo> nodes = m.getNodes6();
		assertArrayEquals(nodes6.toArray(), nodes.toArray());

		// Compatibility
		var msg2 = (Message2<io.bosonnetwork.kademlia.messages2.FindNodeResponse>) Message2.parse(bin);
		msg2.setId(msg.getId());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getVersion(), msg2.getVersion());
		var body = msg2.getBody();
		assertNotNull(body);
		assertEquals(msg.getNodes4(), body.getNodes4());
		assertEquals(msg.getNodes6(), body.getNodes6());
		assertEquals(msg.getToken(), body.getToken());

		printMessage(msg2);

		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testFindNodeResponse6WithToken() throws Exception {
		Id id = Id.random();
		int txid = Random.random().nextInt();

		List<NodeInfo> nodes6 = new ArrayList<>();
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:8070:6543:8a2e:0370:7334", 65535));
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:0000:0000:8a2e:0370:7332", 1232));
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:0000:0000:8a2e:0370:7333", 1233));
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:0000:0000:8a2e:0370:7334", 1234));
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:0000:0000:8a2e:0370:7335", 1235));

		FindNodeResponse msg = new FindNodeResponse(txid);
		msg.setId(id);
		msg.setVersion(VERSION);
		msg.setNodes6(nodes6);
		msg.setToken(0x43218765);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		Message pm = Message.parse(bin);
		pm.setId(id);
		assertInstanceOf(FindNodeResponse.class, pm);
		FindNodeResponse m = (FindNodeResponse)pm;

		assertEquals(Type.RESPONSE, m.getType());
		assertEquals(Method.FIND_NODE, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(VERSION_STR, m.getReadableVersion());
		assertTrue(m.getNodes4().isEmpty());
		assertFalse(m.getNodes6().isEmpty());
		assertEquals(0x43218765, m.getToken());

		List<NodeInfo> nodes = m.getNodes6();
		assertArrayEquals(nodes6.toArray(), nodes.toArray());

		// Compatibility
		var msg2 = (Message2<io.bosonnetwork.kademlia.messages2.FindNodeResponse>) Message2.parse(bin);
		msg2.setId(msg.getId());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getVersion(), msg2.getVersion());
		var body = msg2.getBody();
		assertNotNull(body);
		assertEquals(msg.getNodes4(), body.getNodes4());
		assertEquals(msg.getNodes6(), body.getNodes6());
		assertEquals(msg.getToken(), body.getToken());

		printMessage(msg2);

		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testFindNodeResponse46() throws Exception {
		Id id = Id.random();
		int txid = Random.random().nextInt();

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

		FindNodeResponse msg = new FindNodeResponse(txid);
		msg.setId(id);
		msg.setNodes4(nodes4);
		msg.setNodes6(nodes6);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		Message pm = Message.parse(bin);
		pm.setId(id);
		assertInstanceOf(FindNodeResponse.class, pm);
		FindNodeResponse m = (FindNodeResponse)pm;

		assertEquals(Type.RESPONSE, m.getType());
		assertEquals(Method.FIND_NODE, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(0, m.getVersion());
		assertFalse(m.getNodes4().isEmpty());
		assertFalse(m.getNodes6().isEmpty());
		assertEquals(0, m.getToken());

		List<NodeInfo> nodes = m.getNodes4();
		assertArrayEquals(nodes4.toArray(), nodes.toArray());

		nodes = m.getNodes6();
		assertArrayEquals(nodes6.toArray(), nodes.toArray());

		// Compatibility
		var msg2 = (Message2<io.bosonnetwork.kademlia.messages2.FindNodeResponse>) Message2.parse(bin);
		msg2.setId(msg.getId());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getVersion(), msg2.getVersion());
		var body = msg2.getBody();
		assertNotNull(body);
		assertEquals(msg.getNodes4(), body.getNodes4());
		assertEquals(msg.getNodes6(), body.getNodes6());
		assertEquals(msg.getToken(), body.getToken());

		printMessage(msg2);

		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testFindNodeResponse46WithToken() throws Exception {
		Id id = Id.random();
		int txid = Random.random().nextInt();

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

		FindNodeResponse msg = new FindNodeResponse(txid);
		msg.setId(id);
		msg.setNodes4(nodes4);
		msg.setNodes6(nodes6);
		msg.setToken(0x87654321);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		Message pm = Message.parse(bin);
		pm.setId(id);
		assertInstanceOf(FindNodeResponse.class, pm);
		FindNodeResponse m = (FindNodeResponse)pm;

		assertEquals(Type.RESPONSE, m.getType());
		assertEquals(Method.FIND_NODE, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(0, m.getVersion());
		assertFalse(m.getNodes4().isEmpty());
		assertFalse(m.getNodes6().isEmpty());
		assertEquals(0x87654321, m.getToken());

		List<NodeInfo> nodes = m.getNodes4();
		assertArrayEquals(nodes4.toArray(), nodes.toArray());

		nodes = m.getNodes6();
		assertArrayEquals(nodes6.toArray(), nodes.toArray());

		// Compatibility
		var msg2 = (Message2<io.bosonnetwork.kademlia.messages2.FindNodeResponse>) Message2.parse(bin);
		msg2.setId(msg.getId());
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getVersion(), msg2.getVersion());
		var body = msg2.getBody();
		assertNotNull(body);
		assertEquals(msg.getNodes4(), body.getNodes4());
		assertEquals(msg.getNodes6(), body.getNodes6());
		assertEquals(msg.getToken(), body.getToken());

		printMessage(msg2);

		assertArrayEquals(bin, msg2.toBytes());
	}

	private static Stream<Arguments> requestParameters() {
		return Stream.of(
				Arguments.of("v4", true, false, false),
				Arguments.of("v4+token", true, false, true),
				Arguments.of("v6", false, true, false),
				Arguments.of("v6+token", false, true, true),
				Arguments.of("v4+v6", true, true, false),
				Arguments.of("v4+v6+token", true, true, true)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("requestParameters")
	void testRequest(String name, boolean want4, boolean want6, boolean wantToken) throws Exception {
		var nodeId = Id.random();
		var target = Id.random();
		var txid = 0x76543210;
		var msg = Message2.findNodeRequest(txid, target, want4, want6, wantToken);
		msg.setId(nodeId);
		byte[] bin = msg.toBytes();

		printMessage(msg);

		assertEquals(63, bin.length);

		assertEquals(Message2.Type.REQUEST, msg.getType());
		assertEquals(Message2.Method.FIND_NODE, msg.getMethod());
		assertEquals(nodeId, msg.getId());
		assertEquals(txid, msg.getTxid());
		assertEquals(DEFAULT_VERSION_STR, msg.getReadableVersion());
		assertEquals(target, msg.getBody().getTarget());
		assertEquals(want4, msg.getBody().doesWant4());
		assertEquals(want6, msg.getBody().doesWant6());
		assertEquals(wantToken, msg.getBody().doesWantToken());

		var msg2 = Message2.parse(bin);
		msg2.setId(nodeId);
		assertEquals(msg, msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	private static Stream<Arguments> responseParameters() {
		var ip4 = "192.168.1.1";
		int port = 65535;

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
		nodes6.add(new NodeInfo(Id.random(), ip6, port));

		var token = 0x87654321;

		return Stream.of(
				Arguments.of("v4", nodes4, null, 0, 380),
				Arguments.of("v4+token", nodes4, null, token, 389),
				Arguments.of("v6", null, nodes6, 0, 476),
				Arguments.of("v6+token", null, nodes6, token, 485),
				Arguments.of("v4+v6", nodes4, nodes6, 0, 832),
				Arguments.of("v4+v6+token", nodes4, nodes6, token, 841)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("responseParameters")
	void testResponse(String name, List<NodeInfo> nodes4, List<NodeInfo> nodes6, int token, int expectedSize) throws Exception {
		var nodeId = Id.random();
		var txid = 0x76543210;

		var msg = Message2.findNodeResponse(txid, nodes4, nodes6, token);
		msg.setId(nodeId);
		byte[] bin = msg.toBytes();

		printMessage(msg);

		assertEquals(expectedSize, bin.length);

		assertEquals(Message2.Type.RESPONSE, msg.getType());
		assertEquals(Message2.Method.FIND_NODE, msg.getMethod());
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

		assertEquals(token, msg.getBody().getToken());

		var msg2 = Message2.parse(bin);
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
			var msg = new FindNodeRequest(target);
			msg.setId(nodeId);
			msg.setTxid(txid);
			msg.setWant4(true);
			msg.setWantToken(true);
			msg.setVersion(Constants.VERSION);

			var bin = msg.serialize();
			Message.parse(bin);

			var start = System.currentTimeMillis();
			for (var i = 0; i < TIMING_ITERATIONS; i++) {
				msg = new FindNodeRequest(target);
				msg.setId(nodeId);
				msg.setTxid(txid);
				msg.setWant4(true);
				msg.setWantToken(true);
				msg.setVersion(Constants.VERSION);

				bin = msg.serialize();
				Message.parse(bin);
			}
			var end = System.currentTimeMillis();
			System.out.printf(">>>>>>>> FindNodeRequest: %dms\n", (end - start));
		}

		// warmup
		var msg = Message2.findNodeRequest(txid, target, true, false, true);
		msg.setId(nodeId);
		var bin = msg.toBytes();
		Message2.parse(bin);

		var start = System.currentTimeMillis();
		for (var i = 0; i < TIMING_ITERATIONS; i++) {
			msg = Message2.findNodeRequest(txid, target, true, false, true);
			msg.setId(nodeId);
			bin = msg.toBytes();
			Message2.parse(bin);
		}
		var end = System.currentTimeMillis();
		System.out.printf(">>>>>>>> FindNodeRequest: %dms, estimated: streaming ~= 560ms, *mapping ~= 900ms @ MBP-13-m1pro\n", (end - start));
	}

	@Test
	void timingResponse() throws Exception {
		var nodeId = Id.random();
		var txid = 0x76543210;
		var token = 0x87654321;

		String ip4 = "251.251.251.251";
		String ip6 = "f1ee:f1ee:f1ee:f1ee:f1ee:f1ee:f1ee:f1ee";
		int port = 65535;

		List<NodeInfo> nodes4 = new ArrayList<>();
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));
		nodes4.add(new NodeInfo(Id.random(), ip4, port--));

		List<NodeInfo> nodes6 = new ArrayList<>();
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port--));
		nodes6.add(new NodeInfo(Id.random(), ip6, port));

		{ // TODO: remove
			// warmup
			var msg = new FindNodeResponse(txid);
			msg.setId(nodeId);
			msg.setNodes4(nodes4);
			msg.setNodes6(nodes6);
			msg.setToken(token);
			msg.setVersion(Constants.VERSION);
			var bin = msg.serialize();
			Message.parse(bin);

			var start = System.currentTimeMillis();
			for (var i = 0; i < TIMING_ITERATIONS; i++) {
				msg = new FindNodeResponse(txid);
				msg.setId(nodeId);
				msg.setNodes4(nodes4);
				msg.setNodes6(nodes6);
				msg.setToken(token);
				msg.setVersion(Constants.VERSION);
				bin = msg.serialize();
				Message.parse(bin);
			}
			var end = System.currentTimeMillis();
			System.out.printf(">>>>>>>> FindNodeResponse: %dms\n", (end - start));
		}

		// warmup
		var msg = Message2.findNodeResponse(txid, nodes4, nodes6, token);
		msg.setId(nodeId);
		var bin = msg.toBytes();
		Message2.parse(bin);

		var start = System.currentTimeMillis();
		for (var i = 0; i < TIMING_ITERATIONS; i++) {
			msg = Message2.findNodeResponse(txid, nodes4, nodes6, token);
			msg.setId(nodeId);
			bin = msg.toBytes();
			Message2.parse(bin);
		}
		var end = System.currentTimeMillis();
		System.out.printf(">>>>>>>> FindNodeResponse: %dms, estimated: streaming ~= 2500ms, *mapping ~= 2600ms @ MBP-13-m1pro\n", (end - start));
	}
}