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
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.kademlia.Constants;
import io.bosonnetwork.kademlia.messages.deprecated.FindValueRequest;
import io.bosonnetwork.kademlia.messages.deprecated.FindValueResponse;
import io.bosonnetwork.kademlia.messages.deprecated.OldMessage;
import io.bosonnetwork.kademlia.messages.deprecated.OldMessage.Method;
import io.bosonnetwork.kademlia.messages.deprecated.OldMessage.Type;

public class FindValueTests extends MessageTests {
	@Deprecated
	@Test
	public void testFindValueRequestSize() throws Exception {
		FindValueRequest msg = new FindValueRequest(Id.random());
		msg.setId(Id.random());
		msg.setTxid(0x87654321);
		msg.setVersion(VERSION);
		msg.setWant4(true);
		msg.setWant6(true);
		msg.setSequenceNumber(Random.random().nextInt(0x7fffffff));
		byte[] bin = msg.serialize();
		printMessage(msg, bin);
		assertTrue(bin.length <= msg.estimateSize());
	}

	@Deprecated
	@Test
	public void testFindValueRequest4() throws Exception {
		Id id = Id.random();
		Id target = Id.random();
		int txid = Random.random().nextInt();
		int seq = Random.random().nextInt(0x7FFFFFFF);

		FindValueRequest msg = new FindValueRequest(target);
		msg.setId(id);
		msg.setTxid(txid);
		msg.setVersion(VERSION);
		msg.setWant4(true);
		msg.setWant6(false);
		msg.setSequenceNumber(seq);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		OldMessage pm = OldMessage.parse(bin);
		pm.setId(id);
		assertInstanceOf(FindValueRequest.class, pm);
		FindValueRequest m = (FindValueRequest)pm;

		assertEquals(Type.REQUEST, m.getType());
		assertEquals(Method.FIND_VALUE, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(VERSION_STR, m.getReadableVersion());
		assertEquals(target, m.getTarget());
		assertTrue(m.doesWant4());
		assertFalse(m.doesWant6());
		assertEquals(seq, m.getSequenceNumber());

		var msg2 = (Message<io.bosonnetwork.kademlia.messages.FindValueRequest>) Message.parse(bin);
		msg2.setId(id);
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getVersion(), msg2.getVersion());
		assertEquals(msg.getTarget(), msg2.getBody().getTarget());
		assertEquals(msg.doesWant4(), msg2.getBody().doesWant4());
		assertEquals(msg.doesWant6(), msg2.getBody().doesWant6());
		assertEquals(seq, msg2.getBody().getSequenceNumber());

		printMessage(msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testFindValueRequest6() throws Exception {
		Id id = Id.random();
		Id target = Id.random();
		int txid = Random.random().nextInt();
		int seq = Random.random().nextInt(0x7FFFFFFF);

		FindValueRequest msg = new FindValueRequest(target);
		msg.setId(id);
		msg.setTxid(txid);
		msg.setWant4(false);
		msg.setWant6(true);
		msg.setSequenceNumber(seq);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		OldMessage pm = OldMessage.parse(bin);
		pm.setId(id);
		assertInstanceOf(FindValueRequest.class, pm);
		FindValueRequest m = (FindValueRequest)pm;

		assertEquals(Type.REQUEST, m.getType());
		assertEquals(Method.FIND_VALUE, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(target, m.getTarget());
		assertFalse(m.doesWant4());
		assertTrue(m.doesWant6());
		assertEquals(seq, m.getSequenceNumber());

		var msg2 = (Message<io.bosonnetwork.kademlia.messages.FindValueRequest>) Message.parse(bin);
		msg2.setId(id);
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getVersion(), msg2.getVersion());
		assertEquals(msg.getTarget(), msg2.getBody().getTarget());
		assertEquals(msg.doesWant4(), msg2.getBody().doesWant4());
		assertEquals(msg.doesWant6(), msg2.getBody().doesWant6());
		assertEquals(seq, msg2.getBody().getSequenceNumber());

		printMessage(msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testFindValueRequest46() throws Exception {
		Id id = Id.random();
		Id target = Id.random();
		int txid = Random.random().nextInt();
		int seq = Random.random().nextInt(0x7FFFFFFF);

		FindValueRequest msg = new FindValueRequest(target);
		msg.setId(id);
		msg.setTxid(txid);
		msg.setWant4(true);
		msg.setWant6(true);
		msg.setSequenceNumber(seq);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		OldMessage pm = OldMessage.parse(bin);
		pm.setId(id);
		assertInstanceOf(FindValueRequest.class, pm);
		FindValueRequest m = (FindValueRequest)pm;

		assertEquals(Type.REQUEST, m.getType());
		assertEquals(Method.FIND_VALUE, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(target, m.getTarget());
		assertTrue(m.doesWant4());
		assertTrue(m.doesWant6());
		assertEquals(seq, m.getSequenceNumber());

		var msg2 = (Message<io.bosonnetwork.kademlia.messages.FindValueRequest>) Message.parse(bin);
		msg2.setId(id);
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getVersion(), msg2.getVersion());
		assertEquals(msg.getTarget(), msg2.getBody().getTarget());
		assertEquals(msg.doesWant4(), msg2.getBody().doesWant4());
		assertEquals(msg.doesWant6(), msg2.getBody().doesWant6());
		assertEquals(seq, msg2.getBody().getSequenceNumber());

		printMessage(msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testFindValueResponseSize() throws Exception {
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

		byte[] nonce = new byte[24];
		Arrays.fill(nonce, (byte)'N');
		byte[] sig = new byte[64];
		Arrays.fill(sig, (byte)'S');
		byte[] data = new byte[1025];
		Arrays.fill(data, (byte)'D');
		Value value = Value.of(Id.random(), Id.random(), nonce, 0x77654321, sig, data);

		FindValueResponse msg = new FindValueResponse(0xF7654321);
		msg.setId(Id.random());
		msg.setVersion(VERSION);
		msg.setNodes4(nodes4);
		msg.setNodes6(nodes6);
		msg.setToken(0xF8765432);
		msg.setValue(value);

		byte[] bin = msg.serialize();
		printMessage(msg, bin);
		assertTrue(bin.length <= msg.estimateSize());
	}

	@Deprecated
	@Test
	public void testFindValueResponse4() throws Exception {
		Id id = Id.random();
		int txid = Random.random().nextInt(0x7FFFFFFF);
		Id pk = Id.random();
		Id recipient = Id.random();
		byte[] nonce = new byte[24];
		Random.random().nextBytes(nonce);
		int cas = Random.random().nextInt(0x7FFFFFFF);
		int seq = cas + 1;
		byte[] sig = new byte[64];
		Random.random().nextBytes(sig);
		byte[] data = new byte[1025];
		Random.random().nextBytes(data);

		Value value = Value.of(pk, recipient, nonce, seq, sig, data);

		List<NodeInfo> nodes4 = new ArrayList<>();
		nodes4.add(new NodeInfo(Id.random(), "251.251.251.251", 65535));
		nodes4.add(new NodeInfo(Id.random(), "192.168.1.2", 1232));
		nodes4.add(new NodeInfo(Id.random(), "192.168.1.3", 1233));
		nodes4.add(new NodeInfo(Id.random(), "192.168.1.4", 1234));
		nodes4.add(new NodeInfo(Id.random(), "192.168.1.5", 1235));

		FindValueResponse msg = new FindValueResponse(txid);
		msg.setId(id);
		msg.setVersion(VERSION);
		msg.setNodes4(nodes4);
		msg.setValue(value);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		OldMessage pm = OldMessage.parse(bin);
		pm.setId(id);
		assertInstanceOf(FindValueResponse.class, pm);
		FindValueResponse m = (FindValueResponse)pm;

		assertEquals(Type.RESPONSE, m.getType());
		assertEquals(Method.FIND_VALUE, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(VERSION_STR, m.getReadableVersion());
		assertTrue(m.getNodes6().isEmpty());
		assertFalse(m.getNodes4().isEmpty());
		Value v = m.getValue();
		assertNotNull(v);
		assertEquals(value, v);

		List<NodeInfo> nodes = m.getNodes4();
		assertArrayEquals(nodes4.toArray(), nodes.toArray());

		var msg2 = (Message<io.bosonnetwork.kademlia.messages.FindValueResponse>) Message.parse(bin);
		msg2.setId(id);
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getVersion(), msg2.getVersion());
		assertEquals(msg.getNodes4(), msg2.getBody().getNodes4());
		assertEquals(msg.getNodes6(), msg2.getBody().getNodes6());
		assertEquals(msg.getToken(), msg2.getBody().getToken());
		assertEquals(msg.getValue(), msg2.getBody().getValue());

		printMessage(msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testFindValueResponse4Immutable() throws Exception {
		Id id = Id.random();
		int txid = Random.random().nextInt(0x7FFFFFFF);
		byte[] data = new byte[1025];
		Random.random().nextBytes(data);

		Value value = Value.of(data);

		List<NodeInfo> nodes4 = new ArrayList<>();
		nodes4.add(new NodeInfo(Id.random(), "251.251.251.251", 65535));
		nodes4.add(new NodeInfo(Id.random(), "192.168.1.2", 1232));
		nodes4.add(new NodeInfo(Id.random(), "192.168.1.3", 1233));
		nodes4.add(new NodeInfo(Id.random(), "192.168.1.4", 1234));
		nodes4.add(new NodeInfo(Id.random(), "192.168.1.5", 1235));

		FindValueResponse msg = new FindValueResponse(txid);
		msg.setId(id);
		msg.setVersion(VERSION);
		msg.setNodes4(nodes4);
		msg.setValue(value);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		OldMessage pm = OldMessage.parse(bin);
		pm.setId(id);
		assertInstanceOf(FindValueResponse.class, pm);
		FindValueResponse m = (FindValueResponse)pm;

		assertEquals(Type.RESPONSE, m.getType());
		assertEquals(Method.FIND_VALUE, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(VERSION_STR, m.getReadableVersion());
		assertTrue(m.getNodes6().isEmpty());
		assertFalse(m.getNodes4().isEmpty());
		Value v = m.getValue();
		assertNotNull(v);
		assertEquals(value, v);

		List<NodeInfo> nodes = m.getNodes4();
		assertArrayEquals(nodes4.toArray(), nodes.toArray());

		var msg2 = (Message<io.bosonnetwork.kademlia.messages.FindValueResponse>) Message.parse(bin);
		msg2.setId(id);
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getVersion(), msg2.getVersion());
		assertEquals(msg.getNodes4(), msg2.getBody().getNodes4());
		assertEquals(msg.getNodes6(), msg2.getBody().getNodes6());
		assertEquals(msg.getToken(), msg2.getBody().getToken());
		assertEquals(msg.getValue(), msg2.getBody().getValue());

		printMessage(msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testFindValueResponse6() throws Exception {
		Id id = Id.random();
		int txid = Random.random().nextInt(0x7FFFFFFF);
		Id pk = Id.random();
		Id recipient = Id.random();
		byte[] nonce = new byte[24];
		Random.random().nextBytes(nonce);
		int cas = Random.random().nextInt(0x7FFFFFFF);
		int seq = cas + 1;
		byte[] sig = new byte[64];
		Random.random().nextBytes(sig);
		byte[] data = new byte[1025];
		Random.random().nextBytes(data);

		Value value = Value.of(pk, recipient, nonce, seq, sig, data);

		List<NodeInfo> nodes6 = new ArrayList<>();
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:8070:6543:8a2e:0370:7334", 65535));
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:0000:0000:8a2e:0370:7332", 1232));
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:0000:0000:8a2e:0370:7333", 1233));
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:0000:0000:8a2e:0370:7334", 1234));
		nodes6.add(new NodeInfo(Id.random(), "2001:0db8:85a3:0000:0000:8a2e:0370:7335", 1235));

		FindValueResponse msg = new FindValueResponse(txid);
		msg.setId(id);
		msg.setVersion(VERSION);
		msg.setNodes6(nodes6);
		msg.setValue(value);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		OldMessage pm = OldMessage.parse(bin);
		pm.setId(id);
		assertInstanceOf(FindValueResponse.class, pm);
		FindValueResponse m = (FindValueResponse)pm;

		assertEquals(Type.RESPONSE, m.getType());
		assertEquals(Method.FIND_VALUE, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(VERSION_STR, m.getReadableVersion());
		assertTrue(m.getNodes4().isEmpty());
		assertFalse(m.getNodes6().isEmpty());
		Value v = m.getValue();
		assertNotNull(v);
		assertEquals(value, v);

		List<NodeInfo> nodes = m.getNodes6();
		assertArrayEquals(nodes6.toArray(), nodes.toArray());

		var msg2 = (Message<io.bosonnetwork.kademlia.messages.FindValueResponse>) Message.parse(bin);
		msg2.setId(id);
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getVersion(), msg2.getVersion());
		assertEquals(msg.getNodes4(), msg2.getBody().getNodes4());
		assertEquals(msg.getNodes6(), msg2.getBody().getNodes6());
		assertEquals(msg.getToken(), msg2.getBody().getToken());
		assertEquals(msg.getValue(), msg2.getBody().getValue());

		printMessage(msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@Test
	public void testFindValueResponse46() throws Exception {
		Id id = Id.random();
		int txid = Random.random().nextInt(0x7FFFFFFF);
		Id pk = Id.random();
		Id recipient = Id.random();
		byte[] nonce = new byte[24];
		Random.random().nextBytes(nonce);
		int cas = Random.random().nextInt(0x7FFFFFFF);
		int seq = cas + 1;
		byte[] sig = new byte[64];
		Random.random().nextBytes(sig);
		byte[] data = new byte[1025];
		Random.random().nextBytes(data);

		Value value = Value.of(pk, recipient, nonce, seq, sig, data);

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

		FindValueResponse msg = new FindValueResponse(txid);
		msg.setId(id);
		msg.setNodes4(nodes4);
		msg.setNodes6(nodes6);
		msg.setValue(value);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

		printMessage(msg, bin);

		OldMessage pm = OldMessage.parse(bin);
		pm.setId(id);
		assertInstanceOf(FindValueResponse.class, pm);
		FindValueResponse m = (FindValueResponse)pm;

		assertEquals(Type.RESPONSE, m.getType());
		assertEquals(Method.FIND_VALUE, m.getMethod());
		assertEquals(id, m.getId());
		assertEquals(txid, m.getTxid());
		assertEquals(0, m.getVersion());
		assertNotNull(m.getNodes4());
		assertNotNull(m.getNodes6());

		Value v = m.getValue();
		assertNotNull(v);
		assertEquals(value, v);

		List<NodeInfo> nodes = m.getNodes4();
		assertArrayEquals(nodes4.toArray(), nodes.toArray());

		nodes = m.getNodes6();
		assertArrayEquals(nodes6.toArray(), nodes.toArray());

		var msg2 = (Message<io.bosonnetwork.kademlia.messages.FindValueResponse>) Message.parse(bin);
		msg2.setId(id);
		assertEquals(msg.getId(), msg2.getId());
		assertEquals(msg.getTxid(), msg2.getTxid());
		assertEquals(msg.getVersion(), msg2.getVersion());
		assertEquals(msg.getNodes4(), msg2.getBody().getNodes4());
		assertEquals(msg.getNodes6(), msg2.getBody().getNodes6());
		assertEquals(msg.getToken(), msg2.getBody().getToken());
		assertEquals(msg.getValue(), msg2.getBody().getValue());

		printMessage(msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	@Deprecated
	@ParameterizedTest(name = "{0}")
	@MethodSource("responseParameters")
	void testFindValueResponse(String name, List<NodeInfo> nodes4, List<NodeInfo> nodes6, Value value, int expectedSize) throws Exception {
		var nodeId = Id.random();
		var txid = 0x76543210;

		var msg = new FindValueResponse(txid);
		msg.setId(nodeId);
		msg.setVersion(Constants.VERSION);
		msg.setNodes4(nodes4);
		msg.setNodes6(nodes6);
		if (value != null)
			msg.setValue(value);

		byte[] bin = msg.serialize();
		assertTrue(bin.length <= msg.estimateSize());

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
		var seq = 0x78654321;
		var msg = Message.findValueRequest(txid, target, want4, want6, seq);
		msg.setId(nodeId);
		byte[] bin = msg.toBytes();

		printMessage(msg);

		assertEquals(72, bin.length);

		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.FIND_VALUE, msg.getMethod());
		assertEquals(nodeId, msg.getId());
		assertEquals(txid, msg.getTxid());
		assertEquals(DEFAULT_VERSION_STR, msg.getReadableVersion());
		assertEquals(target, msg.getBody().getTarget());
		assertEquals(want4, msg.getBody().doesWant4());
		assertEquals(want6, msg.getBody().doesWant6());
		assertEquals(seq, msg.getBody().getSequenceNumber());

		var msg2 = Message.parse(bin);
		msg2.setId(nodeId);
		assertEquals(msg, msg2);
		assertArrayEquals(bin, msg2.toBytes());
	}

	private static Stream<Arguments> responseParameters() throws Exception {
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

		Value immutable = Value.createValue("This is a immutable value".getBytes());
		Value signedValue = Value.createSignedValue("This is a signed value".getBytes());
		Value encryptedValue = Value.createEncryptedValue(Id.of(Signature.KeyPair.random().publicKey().bytes()),
				"This is a encrypted value".getBytes());

		return Stream.of(
				Arguments.of("v4", nodes4, null, null, 380),
				Arguments.of("v4+immutable", nodes4, null, immutable, 409),
				Arguments.of("v4+signed", nodes4, null, signedValue, 539),
				Arguments.of("v4+encrypted", nodes4, null, encryptedValue, 597),
				Arguments.of("v6", null, nodes6, null, 476),
				Arguments.of("v6+immutable", null, nodes6, immutable, 505),
				Arguments.of("v6+signed", null, nodes6, signedValue, 635),
				Arguments.of("v6+encrypted", null, nodes6, encryptedValue, 693),
				Arguments.of("v4+v6", nodes4, nodes6, null, 832),
				Arguments.of("v4+v6+immutable", nodes4, nodes6, immutable, 861),
				Arguments.of("v4+v6+signed", nodes4, nodes6, signedValue, 991),
				Arguments.of("v4+v6+encrypted", nodes4, nodes6, encryptedValue, 1049),
				Arguments.of("immutable", null, null, immutable, 53),
				Arguments.of("signed", null, null, signedValue, 183),
				Arguments.of("encrypted", null, null, encryptedValue, 241));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("responseParameters")
	void testResponse(String name, List<NodeInfo> nodes4, List<NodeInfo> nodes6, Value value, int expectedSize) throws Exception {
		var nodeId = Id.random();
		var txid = 0x76543210;
		var msg = Message.findValueResponse(txid, nodes4, nodes6, value);
		msg.setId(nodeId);
		byte[] bin = msg.toBytes();

		printMessage(msg);

		//assertEquals(expectedSize, bin.length);

		assertEquals(Message.Type.RESPONSE, msg.getType());
		assertEquals(Message.Method.FIND_VALUE, msg.getMethod());
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

		assertEquals(value, msg.getBody().getValue());

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
		var seq = 0x78654321;

		{ // TODO: remove
			// warmup
			var msg = new FindValueRequest(target);
			msg.setId(nodeId);
			msg.setTxid(txid);
			msg.setWant4(true);
			msg.setVersion(Constants.VERSION);

			var bin = msg.serialize();
			OldMessage.parse(bin);

			var start = System.currentTimeMillis();
			for (var i = 0; i < TIMING_ITERATIONS; i++) {
				msg = new FindValueRequest(target);
				msg.setId(nodeId);
				msg.setTxid(txid);
				msg.setWant4(true);
				msg.setSequenceNumber(seq);
				msg.setVersion(Constants.VERSION);

				bin = msg.serialize();
				OldMessage.parse(bin);
			}
			var end = System.currentTimeMillis();
			System.out.printf(">>>>>>>> FindValueRequest: %dms\n", (end - start));
		}

		// warmup
		var msg = Message.findValueRequest(txid, target, true, false, seq);
		msg.setId(nodeId);
		var bin = msg.toBytes();
		Message.parse(bin);

		var start = System.currentTimeMillis();
		for (var i = 0; i < TIMING_ITERATIONS; i++) {
			msg = Message.findValueRequest(txid, target, true, false, seq);
			msg.setId(nodeId);
			bin = msg.toBytes();
			Message.parse(bin);
		}
		var end = System.currentTimeMillis();
		System.out.printf(">>>>>>>> FindValueRequest: %dms,estimated: streaming ~= 570ms, *mapping ~= 780ms @ MBP-13-m1pro\n", (end - start));
	}

	@Test
	void timingResponse() throws Exception {
		var nodeId = Id.random();
		var txid = 0x76543210;

		int port = 65535;

		Value value = Value.createEncryptedValue(Id.of(Signature.KeyPair.random().publicKey().bytes()), Random.randomBytes(512));

		{ // TODO: remove
			// warmup
			var msg = new FindValueResponse(txid);
			msg.setId(nodeId);
			msg.setValue(value);
			msg.setVersion(Constants.VERSION);
			var bin = msg.serialize();
			OldMessage.parse(bin);

			var start = System.currentTimeMillis();
			for (var i = 0; i < TIMING_ITERATIONS; i++) {
				msg = new FindValueResponse(txid);
				msg.setId(nodeId);
				msg.setValue(value);
				msg.setVersion(Constants.VERSION);
				bin = msg.serialize();
				OldMessage.parse(bin);
			}
			var end = System.currentTimeMillis();
			System.out.printf(">>>>>>>> FindValueResponse: %dms\n", (end - start));
		}

		// warmup
		var msg = Message.findValueResponse(txid, null, null, value);
		msg.setId(nodeId);
		var bin = msg.toBytes();
		Message.parse(bin);

		var start = System.currentTimeMillis();
		for (var i = 0; i < TIMING_ITERATIONS; i++) {
			msg = Message.findValueResponse(txid, null, null, value);
			msg.setId(nodeId);
			bin = msg.toBytes();
			Message.parse(bin);
		}
		var end = System.currentTimeMillis();
		System.out.printf(">>>>>>>> FindValueResponse: %dms, estimated: streaming ~= 700ms, *mapping ~= 1400ms @ MBP-13-m1pro\n", (end - start));
	}
}