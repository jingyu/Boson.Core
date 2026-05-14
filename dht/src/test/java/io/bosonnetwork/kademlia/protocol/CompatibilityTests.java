package io.bosonnetwork.kademlia.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.bosonnetwork.Value;
import io.bosonnetwork.json.Json;
import io.bosonnetwork.utils.Hex;

public class CompatibilityTests {
	@Test
	void testPingRequest() throws Exception {
		String json = """
				{"y":33,"t":10,"v":1330774017}
				""";
		byte[] cbor = Hex.decode("bf6179182161740a61761a4f520001ff");

		Message ref = new Message(Message.Type.REQUEST, Message.Method.PING, 10, null);

		Message msg = Message.parse(json);
		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.PING, msg.getMethod());
		assertThat(msg)
				.usingRecursiveComparison()
				.withComparatorForType(Id::compare, Id.class)
				.isEqualTo(ref);

		msg = Message.parse(cbor);
		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.PING, msg.getMethod());
		assertThat(msg)
				.usingRecursiveComparison()
				.withComparatorForType(Id::compare, Id.class)
				.isEqualTo(ref);

		assertEquals(json.trim(), ref.toJson());
		assertArrayEquals(cbor, ref.toBytes());
	}

	@Test
	void testPingResponse() throws Exception {
		String json = """
				{"y":65,"t":10,"v":1330774017}
				""";
		byte[] cbor = Hex.decode("bf6179184161740a61761a4f520001ff");

		Message ref = new Message(Message.Type.RESPONSE, Message.Method.PING, 10, null);

		Message msg = Message.parse(json);
		assertEquals(Message.Type.RESPONSE, msg.getType());
		assertEquals(Message.Method.PING, msg.getMethod());
		assertThat(msg)
				.usingRecursiveComparison()
				.withComparatorForType(Id::compare, Id.class)
				.isEqualTo(ref);

		msg = Message.parse(cbor);
		assertEquals(Message.Type.RESPONSE, msg.getType());
		assertEquals(Message.Method.PING, msg.getMethod());
		assertThat(msg)
				.usingRecursiveComparison()
				.withComparatorForType(Id::compare, Id.class)
				.isEqualTo(ref);

		assertEquals(json.trim(), ref.toJson());
		assertArrayEquals(cbor, ref.toBytes());
	}

	static Stream<Arguments> findNodeRequests() {
		Id target = Id.of("HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoA5");

		String jsonV4 = """
				{"y":34,"t":1234,"q":{"t":"HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoA5","w":1},"v":1330774017}
				""";
		byte[] cborV4 = Hex.decode("bf6179182261741904d26171bf61745820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462ced6617701ff61761a4f520001ff");
		Message refV4 = new Message(Message.Type.REQUEST, Message.Method.FIND_NODE, 1234,
				new FindNodeRequest(target, true, false, false));

		String jsonV6 = """
				{"y":34,"t":1234,"q":{"t":"HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoA5","w":2},"v":1330774017}
				""";
		byte[] cborV6 = Hex.decode("bf6179182261741904d26171bf61745820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462ced6617702ff61761a4f520001ff");
		Message refV6 = new Message(Message.Type.REQUEST, Message.Method.FIND_NODE, 1234,
				new FindNodeRequest(target, false, true, false));

		String jsonV4Token = """
				{"y":34,"t":1234,"q":{"t":"HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoA5","w":5},"v":1330774017}
				""";
		byte[] cborV4Token = Hex.decode("bf6179182261741904d26171bf61745820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462ced6617705ff61761a4f520001ff");
		Message refV4Token = new Message(Message.Type.REQUEST, Message.Method.FIND_NODE, 1234,
				new FindNodeRequest(target, true, false, true));

		String jsonV6Token = """
				{"y":34,"t":1234,"q":{"t":"HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoA5","w":6},"v":1330774017}
				""";
		byte[] cborV6Token = Hex.decode("bf6179182261741904d26171bf61745820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462ced6617706ff61761a4f520001ff");
		Message refV6Token = new Message(Message.Type.REQUEST, Message.Method.FIND_NODE, 1234,
				new FindNodeRequest(target, false, true, true));

		String jsonV46 = """
				{"y":34,"t":1234,"q":{"t":"HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoA5","w":3},"v":1330774017}
				""";
		byte[] cborV46 = Hex.decode("bf6179182261741904d26171bf61745820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462ced6617703ff61761a4f520001ff");
		Message refV46 = new Message(Message.Type.REQUEST, Message.Method.FIND_NODE, 1234,
				new FindNodeRequest(target, true, true, false));

		String jsonV46Token = """
				{"y":34,"t":1234,"q":{"t":"HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoA5","w":7},"v":1330774017}
				""";
		byte[] cborV46Token = Hex.decode("bf6179182261741904d26171bf61745820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462ced6617707ff61761a4f520001ff");
		Message refV46Token = new Message(Message.Type.REQUEST, Message.Method.FIND_NODE, 1234,
				new FindNodeRequest(target, true, true, true));

		return Stream.of(
				Arguments.of("v4", jsonV4, cborV4, refV4),
				Arguments.of("v4+token", jsonV4Token, cborV4Token, refV4Token),
				Arguments.of("v6", jsonV6, cborV6, refV6),
				Arguments.of("v6+token", jsonV6Token, cborV6Token, refV6Token),
				Arguments.of("v4+v6", jsonV46, cborV46, refV46),
				Arguments.of("v4+v6+token", jsonV46Token, cborV46Token, refV46Token)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("findNodeRequests")
	void testFindNodeRequest(String name, String json, byte[] cbor, Message ref) {
		Message msg = Message.parse(json);
		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.FIND_NODE, msg.getMethod());
		assertThat(msg)
				.usingRecursiveComparison()
				.withComparatorForType(Id::compare, Id.class)
				.isEqualTo(ref);

		msg = Message.parse(cbor);
		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.FIND_NODE, msg.getMethod());
		assertThat(msg)
				.usingRecursiveComparison()
				.withComparatorForType(Id::compare, Id.class)
				.isEqualTo(ref);

		assertEquals(json.trim(), ref.toJson());
		assertArrayEquals(cbor, ref.toBytes());
	}

	static Stream<Arguments> findNodeResponses() {
		Id target = Id.of("HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoA5");
		int token = 1234567890;
		String ip4 = "192.168.1.";
		int port = 65535;

		var nodes4 = new ArrayList<NodeInfo>();
		nodes4.add(new NodeInfo(target.add(Id.ofBit(255)), ip4 + 1, port--));
		nodes4.add(new NodeInfo(target.add(Id.ofBit(254)), ip4 + 2, port--));
		nodes4.add(new NodeInfo(target.add(Id.ofBit(253)), ip4 + 3, port--));
		nodes4.add(new NodeInfo(target.add(Id.ofBit(252)), ip4 + 4, port--));
		nodes4.add(new NodeInfo(target.add(Id.ofBit(251)), ip4 + 5, port--));
		nodes4.add(new NodeInfo(target.add(Id.ofBit(250)), ip4 + 6, port--));
		nodes4.add(new NodeInfo(target.add(Id.ofBit(249)), ip4 + 7, port--));
		nodes4.add(new NodeInfo(target.add(Id.ofBit(248)), ip4 + 8, port--));

		String ip6 = "2001:0db8:85a3:8070:6543:8a2e:0370:738";

		var nodes6 = new ArrayList<NodeInfo>();
		nodes6.add(new NodeInfo(target.add(Id.ofBit(247)), ip6 + 1, port--));
		nodes6.add(new NodeInfo(target.add(Id.ofBit(246)), ip6 + 2, port--));
		nodes6.add(new NodeInfo(target.add(Id.ofBit(245)), ip6 + 3, port--));
		nodes6.add(new NodeInfo(target.add(Id.ofBit(243)), ip6 + 4, port--));
		nodes6.add(new NodeInfo(target.add(Id.ofBit(242)), ip6 + 5, port--));
		nodes6.add(new NodeInfo(target.add(Id.ofBit(241)), ip6 + 6, port--));
		nodes6.add(new NodeInfo(target.add(Id.ofBit(240)), ip6 + 7, port--));
		nodes6.add(new NodeInfo(target.add(Id.ofBit(239)), ip6 + 8, port));

		String jsonV4 = """
				{"y":66,"t":4321,"r":{"n4":[["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoA6","192.168.1.1",65535],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoA7","192.168.1.2",65534],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoA9","192.168.1.3",65533],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoAD","192.168.1.4",65532],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoAM","192.168.1.5",65531],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoAd","192.168.1.6",65530],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoBB","192.168.1.7",65529],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoCH","192.168.1.8",65528]]},"v":1330774017}
				""";
		byte[] cborV4 = Hex.decode("bf6179184261741910e16172bf626e34889f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462ced744c0a8010119ffffff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462ced844c0a8010219fffeff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462ceda44c0a8010319fffdff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462cede44c0a8010419fffcff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462cee644c0a8010519fffbff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462cef644c0a8010619fffaff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462cf1644c0a8010719fff9ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462cf5644c0a8010819fff8ffff61761a4f520001ff");
		Message refV4 = new Message(Message.Type.RESPONSE, Message.Method.FIND_NODE, 4321,
				new FindNodeResponse(nodes4, null, 0));

		String jsonV4Token = """
				{"y":66,"t":4321,"r":{"n4":[["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoA6","192.168.1.1",65535],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoA7","192.168.1.2",65534],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoA9","192.168.1.3",65533],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoAD","192.168.1.4",65532],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoAM","192.168.1.5",65531],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoAd","192.168.1.6",65530],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoBB","192.168.1.7",65529],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoCH","192.168.1.8",65528]],"tok":1234567890},"v":1330774017}
				""";
		byte[] cborV4Token = Hex.decode("bf6179184261741910e16172bf626e34889f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462ced744c0a8010119ffffff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462ced844c0a8010219fffeff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462ceda44c0a8010319fffdff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462cede44c0a8010419fffcff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462cee644c0a8010519fffbff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462cef644c0a8010619fffaff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462cf1644c0a8010719fff9ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462cf5644c0a8010819fff8ff63746f6b1a499602d2ff61761a4f520001ff");
		Message refV4Token = new Message(Message.Type.RESPONSE, Message.Method.FIND_NODE, 4321,
				new FindNodeResponse(nodes4, null, token));

		String jsonV6 = """
				{"y":66,"t":4321,"r":{"n6":[["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoEV","2001:db8:85a3:8070:6543:8a2e:370:7381",65527],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoJu","2001:db8:85a3:8070:6543:8a2e:370:7382",65526],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoTj","2001:db8:85a3:8070:6543:8a2e:370:7383",65525],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKpNh","2001:db8:85a3:8070:6543:8a2e:370:7384",65524],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKqbK","2001:db8:85a3:8070:6543:8a2e:370:7385",65523],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKt2Z","2001:db8:85a3:8070:6543:8a2e:370:7386",65522],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKxu3","2001:db8:85a3:8070:6543:8a2e:370:7387",65521],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdL8e1","2001:db8:85a3:8070:6543:8a2e:370:7388",65520]]},"v":1330774017}
				""";
		byte[] cborV6 = Hex.decode("bf6179184261741910e16172bf626e36889f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462cfd65020010db885a3807065438a2e0370738119fff7ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462d0d65020010db885a3807065438a2e0370738219fff6ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462d2d65020010db885a3807065438a2e0370738319fff5ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462ded65020010db885a3807065438a2e0370738419fff4ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462eed65020010db885a3807065438a2e0370738519fff3ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f4630ed65020010db885a3807065438a2e0370738619fff2ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f4634ed65020010db885a3807065438a2e0370738719fff1ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f463ced65020010db885a3807065438a2e0370738819fff0ffff61761a4f520001ff");
		Message refV6 = new Message(Message.Type.RESPONSE, Message.Method.FIND_NODE, 4321,
				new FindNodeResponse(null, nodes6, 0));

		String jsonV6Token = """
				{"y":66,"t":4321,"r":{"n6":[["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoEV","2001:db8:85a3:8070:6543:8a2e:370:7381",65527],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoJu","2001:db8:85a3:8070:6543:8a2e:370:7382",65526],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoTj","2001:db8:85a3:8070:6543:8a2e:370:7383",65525],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKpNh","2001:db8:85a3:8070:6543:8a2e:370:7384",65524],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKqbK","2001:db8:85a3:8070:6543:8a2e:370:7385",65523],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKt2Z","2001:db8:85a3:8070:6543:8a2e:370:7386",65522],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKxu3","2001:db8:85a3:8070:6543:8a2e:370:7387",65521],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdL8e1","2001:db8:85a3:8070:6543:8a2e:370:7388",65520]],"tok":1234567890},"v":1330774017}
				""";
		byte[] cborV6Token = Hex.decode("bf6179184261741910e16172bf626e36889f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462cfd65020010db885a3807065438a2e0370738119fff7ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462d0d65020010db885a3807065438a2e0370738219fff6ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462d2d65020010db885a3807065438a2e0370738319fff5ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462ded65020010db885a3807065438a2e0370738419fff4ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462eed65020010db885a3807065438a2e0370738519fff3ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f4630ed65020010db885a3807065438a2e0370738619fff2ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f4634ed65020010db885a3807065438a2e0370738719fff1ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f463ced65020010db885a3807065438a2e0370738819fff0ff63746f6b1a499602d2ff61761a4f520001ff");
		Message refV6Token = new Message(Message.Type.RESPONSE, Message.Method.FIND_NODE, 4321,
				new FindNodeResponse(null, nodes6, token));

		String jsonV46 = """
				{"y":66,"t":4321,"r":{"n4":[["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoA6","192.168.1.1",65535],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoA7","192.168.1.2",65534],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoA9","192.168.1.3",65533],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoAD","192.168.1.4",65532],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoAM","192.168.1.5",65531],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoAd","192.168.1.6",65530],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoBB","192.168.1.7",65529],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoCH","192.168.1.8",65528]],"n6":[["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoEV","2001:db8:85a3:8070:6543:8a2e:370:7381",65527],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoJu","2001:db8:85a3:8070:6543:8a2e:370:7382",65526],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoTj","2001:db8:85a3:8070:6543:8a2e:370:7383",65525],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKpNh","2001:db8:85a3:8070:6543:8a2e:370:7384",65524],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKqbK","2001:db8:85a3:8070:6543:8a2e:370:7385",65523],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKt2Z","2001:db8:85a3:8070:6543:8a2e:370:7386",65522],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKxu3","2001:db8:85a3:8070:6543:8a2e:370:7387",65521],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdL8e1","2001:db8:85a3:8070:6543:8a2e:370:7388",65520]]},"v":1330774017}
				""";
		byte[] cborV46 = Hex.decode("bf6179184261741910e16172bf626e34889f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462ced744c0a8010119ffffff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462ced844c0a8010219fffeff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462ceda44c0a8010319fffdff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462cede44c0a8010419fffcff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462cee644c0a8010519fffbff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462cef644c0a8010619fffaff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462cf1644c0a8010719fff9ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462cf5644c0a8010819fff8ff626e36889f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462cfd65020010db885a3807065438a2e0370738119fff7ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462d0d65020010db885a3807065438a2e0370738219fff6ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462d2d65020010db885a3807065438a2e0370738319fff5ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462ded65020010db885a3807065438a2e0370738419fff4ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462eed65020010db885a3807065438a2e0370738519fff3ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f4630ed65020010db885a3807065438a2e0370738619fff2ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f4634ed65020010db885a3807065438a2e0370738719fff1ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f463ced65020010db885a3807065438a2e0370738819fff0ffff61761a4f520001ff");
		Message refV46 = new Message(Message.Type.RESPONSE, Message.Method.FIND_NODE, 4321,
				new FindNodeResponse(nodes4, nodes6, 0));

		String jsonV46Token = """
				{"y":66,"t":4321,"r":{"n4":[["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoA6","192.168.1.1",65535],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoA7","192.168.1.2",65534],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoA9","192.168.1.3",65533],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoAD","192.168.1.4",65532],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoAM","192.168.1.5",65531],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoAd","192.168.1.6",65530],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoBB","192.168.1.7",65529],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoCH","192.168.1.8",65528]],"n6":[["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoEV","2001:db8:85a3:8070:6543:8a2e:370:7381",65527],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoJu","2001:db8:85a3:8070:6543:8a2e:370:7382",65526],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKoTj","2001:db8:85a3:8070:6543:8a2e:370:7383",65525],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKpNh","2001:db8:85a3:8070:6543:8a2e:370:7384",65524],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKqbK","2001:db8:85a3:8070:6543:8a2e:370:7385",65523],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKt2Z","2001:db8:85a3:8070:6543:8a2e:370:7386",65522],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdKxu3","2001:db8:85a3:8070:6543:8a2e:370:7387",65521],["HVG7zCqPqKVyxwwwKtC43EasxA3U7uVDLDU2EkRdL8e1","2001:db8:85a3:8070:6543:8a2e:370:7388",65520]],"tok":1234567890},"v":1330774017}
				""";
		byte[] cborV46Token = Hex.decode("bf6179184261741910e16172bf626e34889f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462ced744c0a8010119ffffff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462ced844c0a8010219fffeff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462ceda44c0a8010319fffdff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462cede44c0a8010419fffcff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462cee644c0a8010519fffbff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462cef644c0a8010619fffaff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462cf1644c0a8010719fff9ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462cf5644c0a8010819fff8ff626e36889f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462cfd65020010db885a3807065438a2e0370738119fff7ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462d0d65020010db885a3807065438a2e0370738219fff6ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462d2d65020010db885a3807065438a2e0370738319fff5ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462ded65020010db885a3807065438a2e0370738419fff4ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f462eed65020010db885a3807065438a2e0370738519fff3ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f4630ed65020010db885a3807065438a2e0370738619fff2ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f4634ed65020010db885a3807065438a2e0370738719fff1ff9f5820f4f859a871aac9a88f23ff8c95a59efb94f35b7d0781c3b84322caf6f463ced65020010db885a3807065438a2e0370738819fff0ff63746f6b1a499602d2ff61761a4f520001ff");
		Message refV46Token = new Message(Message.Type.RESPONSE, Message.Method.FIND_NODE, 4321,
				new FindNodeResponse(nodes4, nodes6, token));

		return Stream.of(
				Arguments.of("v4", jsonV4, cborV4, refV4),
				Arguments.of("v4+token", jsonV4Token, cborV4Token, refV4Token),
				Arguments.of("v6", jsonV6, cborV6, refV6),
				Arguments.of("v6+token", jsonV6Token, cborV6Token, refV6Token),
				Arguments.of("v4+v6", jsonV46, cborV46, refV46),
				Arguments.of("v4+v6+token", jsonV46Token, cborV46Token, refV46Token)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("findNodeResponses")
	void testFindNodeResponse(String name, String json, byte[] cbor, Message ref) {
		Message msg = Message.parse(json);
		assertEquals(Message.Type.RESPONSE, msg.getType());
		assertEquals(Message.Method.FIND_NODE, msg.getMethod());
		assertThat(msg)
				.usingRecursiveComparison()
				.withComparatorForType(Id::compare, Id.class)
				.isEqualTo(ref);

		msg = Message.parse(cbor);
		assertEquals(Message.Type.RESPONSE, msg.getType());
		assertEquals(Message.Method.FIND_NODE, msg.getMethod());
		assertThat(msg)
				.usingRecursiveComparison()
				.withComparatorForType(Id::compare, Id.class)
				.isEqualTo(ref);

		assertEquals(json.trim(), ref.toJson());
		assertArrayEquals(cbor, ref.toBytes());
	}

	static Stream<Arguments> findValueRequests() {
		Id target = Id.of("CA2GZdWvehqS6zH16xpUzNRgHpqAayo1asd9xUAJwRmB");

		String jsonV4 = """
				{"y":38,"t":99876543210,"q":{"t":"CA2GZdWvehqS6zH16xpUzNRgHpqAayo1asd9xUAJwRmB","w":1},"v":1330774017}
				""";
		byte[] cborV4 = Hex.decode("bf6179182661741b00000017411b1aea6171bf61745820a5c037b2666ac6ce9f92e11c5434d237bd361e36be0b374c058a7f263191b4c2617701ff61761a4f520001ff");
		Message refV4 = new Message(Message.Type.REQUEST, Message.Method.FIND_VALUE, 99876543210L,
				new FindValueRequest(target, true, false, -1));

		String jsonV4Cas = """
				{"y":38,"t":99876543210,"q":{"t":"CA2GZdWvehqS6zH16xpUzNRgHpqAayo1asd9xUAJwRmB","w":1,"cas":0},"v":1330774017}
				""";
		byte[] cborV4Cas = Hex.decode("bf6179182661741b00000017411b1aea6171bf61745820a5c037b2666ac6ce9f92e11c5434d237bd361e36be0b374c058a7f263191b4c26177016363617300ff61761a4f520001ff");
		Message refV4Cas = new Message(Message.Type.REQUEST, Message.Method.FIND_VALUE, 99876543210L,
				new FindValueRequest(target, true, false, 0));

		String jsonV6 = """
				{"y":38,"t":99876543210,"q":{"t":"CA2GZdWvehqS6zH16xpUzNRgHpqAayo1asd9xUAJwRmB","w":2},"v":1330774017}
				""";
		byte[] cborV6 = Hex.decode("bf6179182661741b00000017411b1aea6171bf61745820a5c037b2666ac6ce9f92e11c5434d237bd361e36be0b374c058a7f263191b4c2617702ff61761a4f520001ff");
		Message refV6 = new Message(Message.Type.REQUEST, Message.Method.FIND_VALUE, 99876543210L,
				new FindValueRequest(target, false, true, -1));

		String jsonV6Cas = """
				{"y":38,"t":99876543210,"q":{"t":"CA2GZdWvehqS6zH16xpUzNRgHpqAayo1asd9xUAJwRmB","w":2,"cas":1},"v":1330774017}
				""";
		byte[] cborV6Cas = Hex.decode("bf6179182661741b00000017411b1aea6171bf61745820a5c037b2666ac6ce9f92e11c5434d237bd361e36be0b374c058a7f263191b4c26177026363617301ff61761a4f520001ff");
		Message refV6Cas = new Message(Message.Type.REQUEST, Message.Method.FIND_VALUE, 99876543210L,
				new FindValueRequest(target, false, true, 1));

		String jsonV46 = """
				{"y":38,"t":99876543210,"q":{"t":"CA2GZdWvehqS6zH16xpUzNRgHpqAayo1asd9xUAJwRmB","w":3},"v":1330774017}
				""";
		byte[] cborV46 = Hex.decode("bf6179182661741b00000017411b1aea6171bf61745820a5c037b2666ac6ce9f92e11c5434d237bd361e36be0b374c058a7f263191b4c2617703ff61761a4f520001ff");
		Message refV46 = new Message(Message.Type.REQUEST, Message.Method.FIND_VALUE, 99876543210L,
				new FindValueRequest(target, true, true, -1));

		String jsonV46Cas = """
				{"y":38,"t":99876543210,"q":{"t":"CA2GZdWvehqS6zH16xpUzNRgHpqAayo1asd9xUAJwRmB","w":3,"cas":2},"v":1330774017}
				""";
		byte[] cborV46Cas = Hex.decode("bf6179182661741b00000017411b1aea6171bf61745820a5c037b2666ac6ce9f92e11c5434d237bd361e36be0b374c058a7f263191b4c26177036363617302ff61761a4f520001ff");
		Message refV46Cas = new Message(Message.Type.REQUEST, Message.Method.FIND_VALUE, 99876543210L,
				new FindValueRequest(target, true, true, 2));

		return Stream.of(
				Arguments.of("v4", jsonV4, cborV4, refV4),
				Arguments.of("v4+cas", jsonV4Cas, cborV4Cas, refV4Cas),
				Arguments.of("v6", jsonV6, cborV6, refV6),
				Arguments.of("v6+cas", jsonV6Cas, cborV6Cas, refV6Cas),
				Arguments.of("v4+v6", jsonV46, cborV46, refV46),
				Arguments.of("v4+v6+cas", jsonV46Cas, cborV46Cas, refV46Cas)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("findValueRequests")
	void testFindValueRequest(String name, String json, byte[] cbor, Message ref) {
		Message msg = Message.parse(json);
		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.FIND_VALUE, msg.getMethod());
		assertThat(msg)
				.usingRecursiveComparison()
				.withComparatorForType(Id::compare, Id.class)
				.isEqualTo(ref);

		msg = Message.parse(cbor);
		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.FIND_VALUE, msg.getMethod());
		assertThat(msg)
				.usingRecursiveComparison()
				.withComparatorForType(Id::compare, Id.class)
				.isEqualTo(ref);

		assertEquals(json.trim(), ref.toJson());
		assertArrayEquals(cbor, ref.toBytes());
	}

	static Stream<Arguments> findValueResponses() {
		Id target = Id.of("A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTP1p");

		String ip4 = "192.168.1.";
		int port = 65535;

		var nodes4 = new ArrayList<NodeInfo>();
		nodes4.add(new NodeInfo(target.add(Id.ofBit(255)), ip4 + 1, port--));
		nodes4.add(new NodeInfo(target.add(Id.ofBit(254)), ip4 + 2, port--));
		nodes4.add(new NodeInfo(target.add(Id.ofBit(253)), ip4 + 3, port--));
		nodes4.add(new NodeInfo(target.add(Id.ofBit(252)), ip4 + 4, port--));
		nodes4.add(new NodeInfo(target.add(Id.ofBit(251)), ip4 + 5, port--));
		nodes4.add(new NodeInfo(target.add(Id.ofBit(250)), ip4 + 6, port--));
		nodes4.add(new NodeInfo(target.add(Id.ofBit(249)), ip4 + 7, port--));
		nodes4.add(new NodeInfo(target.add(Id.ofBit(248)), ip4 + 8, port--));

		String ip6 = "2001:0db8:85a3:8070:6543:8a2e:0370:738";

		var nodes6 = new ArrayList<NodeInfo>();
		nodes6.add(new NodeInfo(target.add(Id.ofBit(247)), ip6 + 1, port--));
		nodes6.add(new NodeInfo(target.add(Id.ofBit(246)), ip6 + 2, port--));
		nodes6.add(new NodeInfo(target.add(Id.ofBit(245)), ip6 + 3, port--));
		nodes6.add(new NodeInfo(target.add(Id.ofBit(243)), ip6 + 4, port--));
		nodes6.add(new NodeInfo(target.add(Id.ofBit(242)), ip6 + 5, port--));
		nodes6.add(new NodeInfo(target.add(Id.ofBit(241)), ip6 + 6, port--));
		nodes6.add(new NodeInfo(target.add(Id.ofBit(240)), ip6 + 7, port--));
		nodes6.add(new NodeInfo(target.add(Id.ofBit(239)), ip6 + 8, port));

		Value immutable = Value.immutableBuilder().data("This is a immutable value".getBytes()).build();

		String j0 = """
				{"k":"A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTP1p","n":"x4KBlHfwKpcapKpzh4FT7YDd1elK68pI","sig":"8ypH1thDa6QPmTdrpy-dHOw2f5AAK7Et8zLsuAMe9btIXfy-_szPCDZJj8x6YzQ5CqBwExbRqP0HoejZTHPgBg","v":"VGhpcyBpcyBhIHNpZ25lZCB2YWx1ZQ"}
				""";
		Value signedValue = Json.parse(j0, Value.class);

		String j1 = """
				{"k":"A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTP1p","rec":"GBqLq7mKXLm9D9fckNBfFSHYoNyHk6GgFHN2iF72ktL5","n":"4TmM2CELCsSPkpeWAQtIaVSi3915nQgy","sig":"GPX7HzJcSIvbykFTqWSzgcAdr-oCjO8mzHlIeJsorTo_9EFBPtBN3vqVPHkokH30feC_pu_m460klQRwz5ADDQ","v":"Vdep0buvZMN4QwbCBQ09ogIyqpQCYu4azar8Jh5DgObo-PH11pPAU0Y"}
				""";
		Value encryptedValue = Json.parse(j1, Value.class);

		String jsonV4 = """
				{"y":70,"t":18,"r":{"n4":[["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTP1q","192.168.1.1",65535],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTP1r","192.168.1.2",65534],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTP1t","192.168.1.3",65533],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTP1x","192.168.1.4",65532],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTP26","192.168.1.5",65531],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTP2N","192.168.1.6",65530],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTP2v","192.168.1.7",65529],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTP42","192.168.1.8",65528]]},"v":1330774017}
				""";
		byte[] cborV4 = Hex.decode("bf617918466174126172bf626e34889f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad0003844c0a8010119ffffff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad0003944c0a8010219fffeff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad0003b44c0a8010319fffdff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad0003f44c0a8010419fffcff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad0004744c0a8010519fffbff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad0005744c0a8010619fffaff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad0007744c0a8010719fff9ff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad000b744c0a8010819fff8ffff61761a4f520001ff");
		Message refV4 = new Message(Message.Type.RESPONSE, Message.Method.FIND_VALUE, 18,
				new FindValueResponse(nodes4, null, null));

		String jsonV6 = """
				{"y":70,"t":18,"r":{"n6":[["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTP6E","2001:db8:85a3:8070:6543:8a2e:370:7381",65527],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTPAe","2001:db8:85a3:8070:6543:8a2e:370:7382",65526],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTPKU","2001:db8:85a3:8070:6543:8a2e:370:7383",65525],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTQES","2001:db8:85a3:8070:6543:8a2e:370:7384",65524],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTRT4","2001:db8:85a3:8070:6543:8a2e:370:7385",65523],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTTtJ","2001:db8:85a3:8070:6543:8a2e:370:7386",65522],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTYkn","2001:db8:85a3:8070:6543:8a2e:370:7387",65521],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTiVk","2001:db8:85a3:8070:6543:8a2e:370:7388",65520]]},"v":1330774017}
				""";
		byte[] cborV6 = Hex.decode("bf617918466174126172bf626e36889f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad001375020010db885a3807065438a2e0370738119fff7ff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad002375020010db885a3807065438a2e0370738219fff6ff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad004375020010db885a3807065438a2e0370738319fff5ff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad010375020010db885a3807065438a2e0370738419fff4ff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad020375020010db885a3807065438a2e0370738519fff3ff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad040375020010db885a3807065438a2e0370738619fff2ff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad080375020010db885a3807065438a2e0370738719fff1ff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad100375020010db885a3807065438a2e0370738819fff0ffff61761a4f520001ff");
		Message refV6 = new Message(Message.Type.RESPONSE, Message.Method.FIND_VALUE, 18,
				new FindValueResponse(null, nodes6, null));

		String jsonV46 = """
				{"y":70,"t":18,"r":{"n4":[["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTP1q","192.168.1.1",65535],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTP1r","192.168.1.2",65534],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTP1t","192.168.1.3",65533],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTP1x","192.168.1.4",65532],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTP26","192.168.1.5",65531],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTP2N","192.168.1.6",65530],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTP2v","192.168.1.7",65529],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTP42","192.168.1.8",65528]],"n6":[["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTP6E","2001:db8:85a3:8070:6543:8a2e:370:7381",65527],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTPAe","2001:db8:85a3:8070:6543:8a2e:370:7382",65526],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTPKU","2001:db8:85a3:8070:6543:8a2e:370:7383",65525],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTQES","2001:db8:85a3:8070:6543:8a2e:370:7384",65524],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTRT4","2001:db8:85a3:8070:6543:8a2e:370:7385",65523],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTTtJ","2001:db8:85a3:8070:6543:8a2e:370:7386",65522],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTYkn","2001:db8:85a3:8070:6543:8a2e:370:7387",65521],["A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTiVk","2001:db8:85a3:8070:6543:8a2e:370:7388",65520]]},"v":1330774017}
				""";
		byte[] cborV46 = Hex.decode("bf617918466174126172bf626e34889f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad0003844c0a8010119ffffff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad0003944c0a8010219fffeff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad0003b44c0a8010319fffdff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad0003f44c0a8010419fffcff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad0004744c0a8010519fffbff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad0005744c0a8010619fffaff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad0007744c0a8010719fff9ff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad000b744c0a8010819fff8ff626e36889f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad001375020010db885a3807065438a2e0370738119fff7ff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad002375020010db885a3807065438a2e0370738219fff6ff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad004375020010db885a3807065438a2e0370738319fff5ff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad010375020010db885a3807065438a2e0370738419fff4ff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad020375020010db885a3807065438a2e0370738519fff3ff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad040375020010db885a3807065438a2e0370738619fff2ff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad080375020010db885a3807065438a2e0370738719fff1ff9f58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad100375020010db885a3807065438a2e0370738819fff0ffff61761a4f520001ff");
		Message refV46 = new Message(Message.Type.RESPONSE, Message.Method.FIND_VALUE, 18,
				new FindValueResponse(nodes4, nodes6, null));

		String jsonImmutable = """
				{"y":70,"t":18,"r":{"v":"VGhpcyBpcyBhIGltbXV0YWJsZSB2YWx1ZQ"},"v":1330774017}
				""";
		byte[] cborImmutable = Hex.decode("bf617918466174126172bf6176581954686973206973206120696d6d757461626c652076616c7565ff61761a4f520001ff");
		Message refImmutable = new Message(Message.Type.RESPONSE, Message.Method.FIND_VALUE, 18,
				new FindValueResponse(null, null, immutable));

		String jsonSigned = """
				{"y":70,"t":18,"r":{"k":"A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTP1p","n":"x4KBlHfwKpcapKpzh4FT7YDd1elK68pI","sig":"8ypH1thDa6QPmTdrpy-dHOw2f5AAK7Et8zLsuAMe9btIXfy-_szPCDZJj8x6YzQ5CqBwExbRqP0HoejZTHPgBg","v":"VGhpcyBpcyBhIHNpZ25lZCB2YWx1ZQ"},"v":1330774017}
				""";
		byte[] cborSigned = Hex.decode("bf617918466174126172bf616b58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad00037616e5818c782819477f02a971aa4aa73878153ed80ddd5e94aebca48637369675840f32a47d6d8436ba40f99376ba72f9d1cec367f90002bb12df332ecb8031ef5bb485dfcbefecccf0836498fcc7a6334390aa0701316d1a8fd07a1e8d94c73e006617656546869732069732061207369676e65642076616c7565ff61761a4f520001ff");
		Message refSigned = new Message(Message.Type.RESPONSE, Message.Method.FIND_VALUE, 18,
				new FindValueResponse(null, null, signedValue));

		String jsonEncrypted = """
				{"y":70,"t":18,"r":{"k":"A2LHHwWJVwvpsaPxsPkjPSLCsq8paiKiR95XSPtZTP1p","rec":"GBqLq7mKXLm9D9fckNBfFSHYoNyHk6GgFHN2iF72ktL5","n":"4TmM2CELCsSPkpeWAQtIaVSi3915nQgy","sig":"GPX7HzJcSIvbykFTqWSzgcAdr-oCjO8mzHlIeJsorTo_9EFBPtBN3vqVPHkokH30feC_pu_m460klQRwz5ADDQ","v":"Vdep0buvZMN4QwbCBQ09ogIyqpQCYu4azar8Jh5DgObo-PH11pPAU0Y"},"v":1330774017}
				""";
		byte[] cborEncrypted = Hex.decode("bf617918466174126172bf616b58208610906a9c2619631caca5f3df3fc21f91440d6da22a95bd94a19d9d1ad00037637265635820e1a5c5b7bceec2c4a853922eeec040aa02490c25f10930d9da692858ad119f06616e5818e1398cd8210b0ac48f929796010b486954a2dfdd799d083263736967584018f5fb1f325c488bdbca4153a964b381c01dafea028cef26cc7948789b28ad3a3ff441413ed04ddefa953c7928907df47de0bfa6efe6e3ad24950470cf90030d6176582955d7a9d1bbaf64c3784306c2050d3da20232aa940262ee1acdaafc261e4380e6e8f8f1f5d693c05346ff61761a4f520001ff");
		Message refEncrypted = new Message(Message.Type.RESPONSE, Message.Method.FIND_VALUE, 18,
				new FindValueResponse(null, null, encryptedValue));

		return Stream.of(
				Arguments.of("v4", jsonV4, cborV4, refV4),
				Arguments.of("v6", jsonV6, cborV6, refV6),
				Arguments.of("v46", jsonV46, cborV46, refV46),
				Arguments.of("immutable", jsonImmutable, cborImmutable, refImmutable),
				Arguments.of("signed", jsonSigned, cborSigned, refSigned),
				Arguments.of("encrypted", jsonEncrypted, cborEncrypted, refEncrypted)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("findValueResponses")
	void testFindValueResponse(String name, String json, byte[] cbor, Message ref) {
		Message msg = Message.parse(json);
		assertEquals(Message.Type.RESPONSE, msg.getType());
		assertEquals(Message.Method.FIND_VALUE, msg.getMethod());
		assertThat(msg)
				.usingRecursiveComparison()
				.withComparatorForType(Id::compare, Id.class)
				.isEqualTo(ref);

		msg = Message.parse(cbor);
		assertEquals(Message.Type.RESPONSE, msg.getType());
		assertEquals(Message.Method.FIND_VALUE, msg.getMethod());
		assertThat(msg)
				.usingRecursiveComparison()
				.withComparatorForType(Id::compare, Id.class)
				.isEqualTo(ref);

		assertEquals(json.trim(), ref.toJson());
		assertArrayEquals(cbor, ref.toBytes());
	}

	static Stream<Arguments> storeValueRequests() {
		Id target = Id.of("84jivoRPZUX5jfpASKGrQ7JJT6Aj79s9Que6KBLdg6Pp");
		int token = 87654321;

		String j0 = """
				{"v":"VGhpcyBpcyBhIGltbXV0YWJsZSB2YWx1ZQ"}
				""";
		Value immutable = Json.parse(j0, Value.class);
		String j1 = """
				{"k":"84jivoRPZUX5jfpASKGrQ7JJT6Aj79s9Que6KBLdg6Pp","n":"24S9pmbnhjb_CM0JSdnA4oou7CGJ96KC","seq":3,"sig":"FHlAurKTVSmzywQc34hni20n1loC-JXDpUk1OVVGl7akpkXs6DYNM-Y3udGsSV32qlIy0McjF_TVXaqYrJSCCA","v":"VGhpcyBpcyBhIHNpZ25lZCB2YWx1ZQ"}
				""";
		Value signed = Json.parse(j1, Value.class);
		String j2 = """
				{"k":"84jivoRPZUX5jfpASKGrQ7JJT6Aj79s9Que6KBLdg6Pp","rec":"EXuir6x2NZYAgt3JR8Nimf674GhJzaT3KaWWoLD1PyeV","n":"LM41AaUnxehDDel-JLnwtQ-xw2h3TdL9","seq":9,"sig":"C05RE5a8AN1Lawe_6ninr7B0SVxvTmBr1vjEv0DApNGGWpKhMy5MiVF7FS4mAHAQM-6tTTnuFM2l_oKjMNzgAA","v":"5yeP6N8m58HfdXDU_NN47NqyZEeeegFbdav-XxfJNaN4dwOoChnCGw4"}
				""";
		Value encrypted = Json.parse(j2, Value.class);

		String jsonImmutable = """
				{"y":37,"t":33,"q":{"tok":87654321,"v":"VGhpcyBpcyBhIGltbXV0YWJsZSB2YWx1ZQ"},"v":1330774017}
				""";
		byte[] cborImmutable = Hex.decode("bf61791825617418216171bf63746f6b1a05397fb16176581954686973206973206120696d6d757461626c652076616c7565ff61761a4f520001ff");
		Message refImmutable = new Message(Message.Type.REQUEST, Message.Method.STORE_VALUE, 33,
				new StoreValueRequest(immutable, token, -1));

		String jsonSigned = """
				{"y":37,"t":33,"q":{"tok":87654321,"k":"84jivoRPZUX5jfpASKGrQ7JJT6Aj79s9Que6KBLdg6Pp","n":"24S9pmbnhjb_CM0JSdnA4oou7CGJ96KC","seq":3,"sig":"FHlAurKTVSmzywQc34hni20n1loC-JXDpUk1OVVGl7akpkXs6DYNM-Y3udGsSV32qlIy0McjF_TVXaqYrJSCCA","v":"VGhpcyBpcyBhIHNpZ25lZCB2YWx1ZQ"},"v":1330774017}
				""";
		byte[] cborSigned = Hex.decode("bf61791825617418216171bf63746f6b1a05397fb1616b582068f6db9b329fdd3e9cbb07a515ab578ebf6292bc6ff8fee1bcd494ab9a1a9e97616e5818db84bda666e78636ff08cd0949d9c0e28a2eec2189f7a2826373657103637369675840147940bab2935529b3cb041cdf88678b6d27d65a02f895c3a5493539554697b6a4a645ece8360d33e637b9d1ac495df6aa5232d0c72317f4d55daa98ac948208617656546869732069732061207369676e65642076616c7565ff61761a4f520001ff");
		Message refSigned = new Message(Message.Type.REQUEST, Message.Method.STORE_VALUE, 33,
				new StoreValueRequest(signed, token, -1));

		String jsonSignedCas = """
				{"y":37,"t":33,"q":{"tok":87654321,"cas":0,"k":"84jivoRPZUX5jfpASKGrQ7JJT6Aj79s9Que6KBLdg6Pp","n":"24S9pmbnhjb_CM0JSdnA4oou7CGJ96KC","seq":3,"sig":"FHlAurKTVSmzywQc34hni20n1loC-JXDpUk1OVVGl7akpkXs6DYNM-Y3udGsSV32qlIy0McjF_TVXaqYrJSCCA","v":"VGhpcyBpcyBhIHNpZ25lZCB2YWx1ZQ"},"v":1330774017}
				""";
		byte[] cborSignedCas = Hex.decode("bf61791825617418216171bf63746f6b1a05397fb16363617300616b582068f6db9b329fdd3e9cbb07a515ab578ebf6292bc6ff8fee1bcd494ab9a1a9e97616e5818db84bda666e78636ff08cd0949d9c0e28a2eec2189f7a2826373657103637369675840147940bab2935529b3cb041cdf88678b6d27d65a02f895c3a5493539554697b6a4a645ece8360d33e637b9d1ac495df6aa5232d0c72317f4d55daa98ac948208617656546869732069732061207369676e65642076616c7565ff61761a4f520001ff");
		Message refSignedCas = new Message(Message.Type.REQUEST, Message.Method.STORE_VALUE, 33,
				new StoreValueRequest(signed, token, 0));

		String jsonEncrypted = """
				{"y":37,"t":33,"q":{"tok":87654321,"k":"84jivoRPZUX5jfpASKGrQ7JJT6Aj79s9Que6KBLdg6Pp","rec":"EXuir6x2NZYAgt3JR8Nimf674GhJzaT3KaWWoLD1PyeV","n":"LM41AaUnxehDDel-JLnwtQ-xw2h3TdL9","seq":9,"sig":"C05RE5a8AN1Lawe_6ninr7B0SVxvTmBr1vjEv0DApNGGWpKhMy5MiVF7FS4mAHAQM-6tTTnuFM2l_oKjMNzgAA","v":"5yeP6N8m58HfdXDU_NN47NqyZEeeegFbdav-XxfJNaN4dwOoChnCGw4"},"v":1330774017}
				""";
		byte[] cborEncrypted = Hex.decode("bf61791825617418216171bf63746f6b1a05397fb1616b582068f6db9b329fdd3e9cbb07a515ab578ebf6292bc6ff8fee1bcd494ab9a1a9e97637265635820c912f779753fd48e3a116c5438e82af78f083d60feeb63a5ddeeaf7e5f53880e616e58182cce3501a527c5e8430de97e24b9f0b50fb1c368774dd2fd63736571096373696758400b4e511396bc00dd4b6b07bfea78a7afb074495c6f4e606bd6f8c4bf40c0a4d1865a92a1332e4c89517b152e2600701033eead4d39ee14cda5fe82a330dce00061765829e7278fe8df26e7c1df7570d4fcd378ecdab264479e7a015b75abfe5f17c935a3787703a80a19c21b0eff61761a4f520001ff");
		Message refEncrypted = new Message(Message.Type.REQUEST, Message.Method.STORE_VALUE, 33,
				new StoreValueRequest(encrypted, token, -1));

		String jsonEncryptedCas = """
				{"y":37,"t":33,"q":{"tok":87654321,"cas":1,"k":"84jivoRPZUX5jfpASKGrQ7JJT6Aj79s9Que6KBLdg6Pp","rec":"EXuir6x2NZYAgt3JR8Nimf674GhJzaT3KaWWoLD1PyeV","n":"LM41AaUnxehDDel-JLnwtQ-xw2h3TdL9","seq":9,"sig":"C05RE5a8AN1Lawe_6ninr7B0SVxvTmBr1vjEv0DApNGGWpKhMy5MiVF7FS4mAHAQM-6tTTnuFM2l_oKjMNzgAA","v":"5yeP6N8m58HfdXDU_NN47NqyZEeeegFbdav-XxfJNaN4dwOoChnCGw4"},"v":1330774017}
				""";
		byte[] cborEncryptedCas = Hex.decode("bf61791825617418216171bf63746f6b1a05397fb16363617301616b582068f6db9b329fdd3e9cbb07a515ab578ebf6292bc6ff8fee1bcd494ab9a1a9e97637265635820c912f779753fd48e3a116c5438e82af78f083d60feeb63a5ddeeaf7e5f53880e616e58182cce3501a527c5e8430de97e24b9f0b50fb1c368774dd2fd63736571096373696758400b4e511396bc00dd4b6b07bfea78a7afb074495c6f4e606bd6f8c4bf40c0a4d1865a92a1332e4c89517b152e2600701033eead4d39ee14cda5fe82a330dce00061765829e7278fe8df26e7c1df7570d4fcd378ecdab264479e7a015b75abfe5f17c935a3787703a80a19c21b0eff61761a4f520001ff");
		Message refEncryptedCas = new Message(Message.Type.REQUEST, Message.Method.STORE_VALUE, 33,
				new StoreValueRequest(encrypted, token, 1));

		return Stream.of(
				Arguments.of("immutable", jsonImmutable, cborImmutable, refImmutable),
				Arguments.of("signed", jsonSigned, cborSigned, refSigned),
				Arguments.of("signedCas", jsonSignedCas, cborSignedCas, refSignedCas),
				Arguments.of("encrypted", jsonEncrypted, cborEncrypted, refEncrypted),
				Arguments.of("encryptedCas", jsonEncryptedCas, cborEncryptedCas, refEncryptedCas)

		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("storeValueRequests")
	void testStoreValueRequest(String name, String json, byte[] cbor, Message ref) {
		Message msg = Message.parse(json);
		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.STORE_VALUE, msg.getMethod());
		assertThat(msg)
				.usingRecursiveComparison()
				.withComparatorForType(Id::compare, Id.class)
				.isEqualTo(ref);

		msg = Message.parse(cbor);
		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.STORE_VALUE, msg.getMethod());
		assertThat(msg)
				.usingRecursiveComparison()
				.withComparatorForType(Id::compare, Id.class)
				.isEqualTo(ref);

		assertEquals(json.trim(), ref.toJson());
		assertArrayEquals(cbor, ref.toBytes());
	}

	@Test
	void testStoreValueResponse() throws Exception {
		String json = """
				{"y":69,"t":2,"v":1330774017}
				""";
		byte[] cbor = Hex.decode("bf6179184561740261761a4f520001ff");

		Message ref = new Message(Message.Type.RESPONSE, Message.Method.STORE_VALUE, 2, null);

		Message msg = Message.parse(json);
		assertEquals(Message.Type.RESPONSE, msg.getType());
		assertEquals(Message.Method.STORE_VALUE, msg.getMethod());
		assertThat(msg)
				.usingRecursiveComparison()
				.withComparatorForType(Id::compare, Id.class)
				.isEqualTo(ref);

		msg = Message.parse(cbor);
		assertEquals(Message.Type.RESPONSE, msg.getType());
		assertEquals(Message.Method.STORE_VALUE, msg.getMethod());
		assertThat(msg)
				.usingRecursiveComparison()
				.withComparatorForType(Id::compare, Id.class)
				.isEqualTo(ref);

		assertEquals(json.trim(), ref.toJson());
		assertArrayEquals(cbor, ref.toBytes());
	}

	static Stream<Arguments> findPeerRequests() {
		Id target = Id.of("DP6Cn2Rs6nKQTdjN6yNZSaiyUT84HmcwRn1iLZFWpAWm");

		String jsonV4 = """
				{"y":36,"t":9876,"q":{"t":"DP6Cn2Rs6nKQTdjN6yNZSaiyUT84HmcwRn1iLZFWpAWm","w":1},"v":1330774017}
				""";
		byte[] cborV4 = Hex.decode("bf6179182461741926946171bf61745820b7f4e76d4919daa3ff0de1616394c41d519cf4f38cb659fa21eb53ae3ff8006a617701ff61761a4f520001ff");
		Message refV4 = new Message(Message.Type.REQUEST, Message.Method.FIND_PEER, 9876,
				new FindPeerRequest(target, true, false, -1, 0));

		String jsonV4Cas = """
				{"y":36,"t":9876,"q":{"t":"DP6Cn2Rs6nKQTdjN6yNZSaiyUT84HmcwRn1iLZFWpAWm","w":1,"cas":0},"v":1330774017}
				""";
		byte[] cborV4Cas = Hex.decode("bf6179182461741926946171bf61745820b7f4e76d4919daa3ff0de1616394c41d519cf4f38cb659fa21eb53ae3ff8006a6177016363617300ff61761a4f520001ff");
		Message refV4Cas = new Message(Message.Type.REQUEST, Message.Method.FIND_PEER, 9876,
				new FindPeerRequest(target, true, false, 0, 0));

		String jsonV4Expected = """
				{"y":36,"t":9876,"q":{"t":"DP6Cn2Rs6nKQTdjN6yNZSaiyUT84HmcwRn1iLZFWpAWm","w":1,"e":8},"v":1330774017}
				""";
		byte[] cborV4Expected = Hex.decode("bf6179182461741926946171bf61745820b7f4e76d4919daa3ff0de1616394c41d519cf4f38cb659fa21eb53ae3ff8006a617701616508ff61761a4f520001ff");
		Message refV4Expected = new Message(Message.Type.REQUEST, Message.Method.FIND_PEER, 9876,
				new FindPeerRequest(target, true, false, -1, 8));

		String jsonV4CasExpected = """
				{"y":36,"t":9876,"q":{"t":"DP6Cn2Rs6nKQTdjN6yNZSaiyUT84HmcwRn1iLZFWpAWm","w":1,"cas":1,"e":8},"v":1330774017}
				""";
		byte[] cborV4CasExpected = Hex.decode("bf6179182461741926946171bf61745820b7f4e76d4919daa3ff0de1616394c41d519cf4f38cb659fa21eb53ae3ff8006a6177016363617301616508ff61761a4f520001ff");
		Message refV4CasExpected = new Message(Message.Type.REQUEST, Message.Method.FIND_PEER, 9876,
				new FindPeerRequest(target, true, false, 1, 8));

		String jsonV6 = """
				{"y":36,"t":9876,"q":{"t":"DP6Cn2Rs6nKQTdjN6yNZSaiyUT84HmcwRn1iLZFWpAWm","w":2},"v":1330774017}
				""";
		byte[] cborV6 = Hex.decode("bf6179182461741926946171bf61745820b7f4e76d4919daa3ff0de1616394c41d519cf4f38cb659fa21eb53ae3ff8006a617702ff61761a4f520001ff");
		Message refV6 = new Message(Message.Type.REQUEST, Message.Method.FIND_PEER, 9876,
				new FindPeerRequest(target, false, true, -1, 0));

		String jsonV6Cas = """
				{"y":36,"t":9876,"q":{"t":"DP6Cn2Rs6nKQTdjN6yNZSaiyUT84HmcwRn1iLZFWpAWm","w":2,"cas":2},"v":1330774017}
				""";
		byte[] cborV6Cas = Hex.decode("bf6179182461741926946171bf61745820b7f4e76d4919daa3ff0de1616394c41d519cf4f38cb659fa21eb53ae3ff8006a6177026363617302ff61761a4f520001ff");
		Message refV6Cas = new Message(Message.Type.REQUEST, Message.Method.FIND_PEER, 9876,
				new FindPeerRequest(target, false, true, 2, 0));

		String jsonV6Expected = """
				{"y":36,"t":9876,"q":{"t":"DP6Cn2Rs6nKQTdjN6yNZSaiyUT84HmcwRn1iLZFWpAWm","w":2,"e":8},"v":1330774017}
				""";
		byte[] cborV6Expected = Hex.decode("bf6179182461741926946171bf61745820b7f4e76d4919daa3ff0de1616394c41d519cf4f38cb659fa21eb53ae3ff8006a617702616508ff61761a4f520001ff");
		Message refV6Expected = new Message(Message.Type.REQUEST, Message.Method.FIND_PEER, 9876,
				new FindPeerRequest(target, false, true, -1, 8));

		String jsonV6CasExpected = """
				{"y":36,"t":9876,"q":{"t":"DP6Cn2Rs6nKQTdjN6yNZSaiyUT84HmcwRn1iLZFWpAWm","w":2,"cas":3,"e":8},"v":1330774017}
				""";
		byte[] cborV6CasExpected = Hex.decode("bf6179182461741926946171bf61745820b7f4e76d4919daa3ff0de1616394c41d519cf4f38cb659fa21eb53ae3ff8006a6177026363617303616508ff61761a4f520001ff");
		Message refV6CasExpected = new Message(Message.Type.REQUEST, Message.Method.FIND_PEER, 9876,
				new FindPeerRequest(target, false, true, 3, 8));

		String jsonV46 = """
				{"y":36,"t":9876,"q":{"t":"DP6Cn2Rs6nKQTdjN6yNZSaiyUT84HmcwRn1iLZFWpAWm","w":3},"v":1330774017}
				""";
		byte[] cborV46 = Hex.decode("bf6179182461741926946171bf61745820b7f4e76d4919daa3ff0de1616394c41d519cf4f38cb659fa21eb53ae3ff8006a617703ff61761a4f520001ff");
		Message refV46 = new Message(Message.Type.REQUEST, Message.Method.FIND_PEER, 9876,
				new FindPeerRequest(target, true, true, -1, 0));

		String jsonV46Cas = """
				{"y":36,"t":9876,"q":{"t":"DP6Cn2Rs6nKQTdjN6yNZSaiyUT84HmcwRn1iLZFWpAWm","w":3,"cas":4},"v":1330774017}
				""";
		byte[] cborV46Cas = Hex.decode("bf6179182461741926946171bf61745820b7f4e76d4919daa3ff0de1616394c41d519cf4f38cb659fa21eb53ae3ff8006a6177036363617304ff61761a4f520001ff");
		Message refV46Cas = new Message(Message.Type.REQUEST, Message.Method.FIND_PEER, 9876,
				new FindPeerRequest(target, true, true, 4, 0));

		String jsonV46Expected = """
				{"y":36,"t":9876,"q":{"t":"DP6Cn2Rs6nKQTdjN6yNZSaiyUT84HmcwRn1iLZFWpAWm","w":3,"e":8},"v":1330774017}
				""";
		byte[] cborV46Expected = Hex.decode("bf6179182461741926946171bf61745820b7f4e76d4919daa3ff0de1616394c41d519cf4f38cb659fa21eb53ae3ff8006a617703616508ff61761a4f520001ff");
		Message refV46Expected = new Message(Message.Type.REQUEST, Message.Method.FIND_PEER, 9876,
				new FindPeerRequest(target, true, true, -1, 8));

		String jsonV46CasExpected = """
				{"y":36,"t":9876,"q":{"t":"DP6Cn2Rs6nKQTdjN6yNZSaiyUT84HmcwRn1iLZFWpAWm","w":3,"cas":5,"e":8},"v":1330774017}
				""";
		byte[] cborV46CasExpected = Hex.decode("bf6179182461741926946171bf61745820b7f4e76d4919daa3ff0de1616394c41d519cf4f38cb659fa21eb53ae3ff8006a6177036363617305616508ff61761a4f520001ff");
		Message refV46CasExpected = new Message(Message.Type.REQUEST, Message.Method.FIND_PEER, 9876,
				new FindPeerRequest(target, true, true, 5, 8));

		return Stream.of(
				Arguments.of("v4", jsonV4, cborV4, refV4),
				Arguments.of("v4Cas", jsonV4Cas, cborV4Cas, refV4Cas),
				Arguments.of("v4Expected", jsonV4Expected, cborV4Expected, refV4Expected),
				Arguments.of("v4CasExpected", jsonV4CasExpected, cborV4CasExpected, refV4CasExpected),
				Arguments.of("v6", jsonV6, cborV6, refV6),
				Arguments.of("v6Cas", jsonV6Cas, cborV6Cas, refV6Cas),
				Arguments.of("v6Expected", jsonV6Expected, cborV6Expected, refV6Expected),
				Arguments.of("v6CasExpected", jsonV6CasExpected, cborV6CasExpected, refV6CasExpected),
				Arguments.of("v46", jsonV46, cborV46, refV46),
				Arguments.of("v46Cas", jsonV46Cas, cborV46Cas, refV46Cas),
				Arguments.of("v46Expected", jsonV46Expected, cborV46Expected, refV46Expected),
				Arguments.of("v46CasExpected", jsonV46CasExpected, cborV46CasExpected, refV46CasExpected)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("findPeerRequests")
	void testFindPeerRequest(String name, String json, byte[] cbor, Message ref) {
		Message msg = Message.parse(json);
		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.FIND_PEER, msg.getMethod());
		assertThat(msg)
				.usingRecursiveComparison()
				.withComparatorForType(Id::compare, Id.class)
				.isEqualTo(ref);

		msg = Message.parse(cbor);
		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.FIND_PEER, msg.getMethod());
		assertThat(msg)
				.usingRecursiveComparison()
				.withComparatorForType(Id::compare, Id.class)
				.isEqualTo(ref);

		assertEquals(json.trim(), ref.toJson());
		assertArrayEquals(cbor, ref.toBytes());
	}

	static Stream<Arguments> findPeerResponses() {
		Id target = Id.of("2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqAe");
		String ip4 = "192.168.1.";
		int port = 65535;

		var nodes4 = new ArrayList<NodeInfo>();
		nodes4.add(new NodeInfo(target.add(Id.ofBit(255)), ip4 + 1, port--));
		nodes4.add(new NodeInfo(target.add(Id.ofBit(254)), ip4 + 2, port--));
		nodes4.add(new NodeInfo(target.add(Id.ofBit(253)), ip4 + 3, port--));
		nodes4.add(new NodeInfo(target.add(Id.ofBit(252)), ip4 + 4, port--));
		nodes4.add(new NodeInfo(target.add(Id.ofBit(251)), ip4 + 5, port--));
		nodes4.add(new NodeInfo(target.add(Id.ofBit(250)), ip4 + 6, port--));
		nodes4.add(new NodeInfo(target.add(Id.ofBit(249)), ip4 + 7, port--));
		nodes4.add(new NodeInfo(target.add(Id.ofBit(248)), ip4 + 8, port--));

		String ip6 = "2001:0db8:85a3:8070:6543:8a2e:0370:738";

		var nodes6 = new ArrayList<NodeInfo>();
		nodes6.add(new NodeInfo(target.add(Id.ofBit(247)), ip6 + 1, port--));
		nodes6.add(new NodeInfo(target.add(Id.ofBit(246)), ip6 + 2, port--));
		nodes6.add(new NodeInfo(target.add(Id.ofBit(245)), ip6 + 3, port--));
		nodes6.add(new NodeInfo(target.add(Id.ofBit(243)), ip6 + 4, port--));
		nodes6.add(new NodeInfo(target.add(Id.ofBit(242)), ip6 + 5, port--));
		nodes6.add(new NodeInfo(target.add(Id.ofBit(241)), ip6 + 6, port--));
		nodes6.add(new NodeInfo(target.add(Id.ofBit(240)), ip6 + 7, port--));
		nodes6.add(new NodeInfo(target.add(Id.ofBit(239)), ip6 + 8, port));

		String j0 = """
				{"id":"2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqAe","n":"cQundi2gNo-43AcJwmjCsw64e2VH_8WP","sig":"EiltluuYTrQkQQ1GG9x2rAnBps4T6dC4kurFBVtnd8bjBOJoVqEz0ZG4lTa3RZVg8AMuQnglGTcHP1wiGZp9Ag","f":4660,"e":"tcp://203.0.113.10:65520"}
				""";
		String j1 = """
				{"id":"2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqAe","n":"hQgJcy9DRO7ttNnw3xAJP5QCjUPDKpUq","sig":"2nzxFvONxY_Tpt8mIrCF8VtwAsQAbtn4-MUO_cnQBTmj0hgiePEmn7nONSMt7PvY29Dd3nwzg0R9txvWSCiUAg","f":4661,"e":"tcp://203.0.113.11:65519"}
				""";
		String j2 = """
				{"id":"2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqAe","n":"vrLrDwEO7lSRXhbCnfKzzfuskzqDYe6B","sig":"L4wpYXK7zb9x-moPsc663WhPdFB7Smq7iy4OwYTklljbl5PyGeOOawWy0Z1GY4BqsSHFqAv1O-Fd6DLbI505AA","f":4662,"e":"http://abc.example.com/"}
				""";
		String j3 = """
				{"id":"2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqAe","n":"lj1ddvT2PGFQSna-0IJ4clqWRSbp1Ex5","sig":"EzYSo8GdAzxdSs5ooNdrIpVuXv8Yp0uijp8jGd5lo26A1eDpqWel2osm9L3l0BBzmyQU2wzuUZUpyIX3qhoHAg","f":4663,"e":"http://foo.example.com/"}
				""";
		String j4 = """
				{"id":"2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqAe","n":"_MB0UeVX5g1lbdfH0kxcAzgCV4KyKKb_","o":"8755tgy7ENtdHfjZPLYbgHWPSwVdZDxvuuh4KkVhDsdE","os":"8VXGlZfwVq-NfJZ5P9-iBkLzhSifygjKxA-q00E4GlyHdgf--LCtnK0-C8uleKthIIKW6g6kj-NHeO8wqRgwAg","sig":"d-wlJ2GUqUHgLwXxBaVVdC68vVObFWfvmzuFEfz30BM2Zfcp6py04eKkgxE9qw7tMLaMn19137Kk8-WfDzglBw","f":4664,"e":"http://bar.example.com/"}
				""";
		List<PeerInfo> peers = Stream.of(j0, j1, j2, j3, j4).map(j -> Json.parse(j, PeerInfo.class)).toList();

		String jsonV4 = """
				{"y":68,"t":18,"r":{"n4":[["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqAf","192.168.1.1",65535],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqAg","192.168.1.2",65534],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqAi","192.168.1.3",65533],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqAn","192.168.1.4",65532],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqAv","192.168.1.5",65531],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqBC","192.168.1.6",65530],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqBk","192.168.1.7",65529],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqCr","192.168.1.8",65528]]},"v":1330774017}
				""";
		byte[] cborV4 = Hex.decode("bf617918446174126172bf626e34889f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7091ef044c0a8010119ffffff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7091ef144c0a8010219fffeff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7091ef344c0a8010319fffdff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7091ef744c0a8010419fffcff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7091eff44c0a8010519fffbff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7091f0f44c0a8010619fffaff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7091f2f44c0a8010719fff9ff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7091f6f44c0a8010819fff8ffff61761a4f520001ff");
		Message refV4 = new Message(Message.Type.RESPONSE, Message.Method.FIND_PEER, 18,
				new FindPeerResponse(nodes4, null, null));

		String jsonV6 = """
				{"y":68,"t":18,"r":{"n6":[["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqF4","2001:db8:85a3:8070:6543:8a2e:370:7381",65527],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqKU","2001:db8:85a3:8070:6543:8a2e:370:7382",65526],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqUJ","2001:db8:85a3:8070:6543:8a2e:370:7383",65525],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6frPG","2001:db8:85a3:8070:6543:8a2e:370:7384",65524],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fsbt","2001:db8:85a3:8070:6543:8a2e:370:7385",65523],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fv38","2001:db8:85a3:8070:6543:8a2e:370:7386",65522],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fzuc","2001:db8:85a3:8070:6543:8a2e:370:7387",65521],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6gAea","2001:db8:85a3:8070:6543:8a2e:370:7388",65520]]},"v":1330774017}
				""";
		byte[] cborV6 = Hex.decode("bf617918446174126172bf626e36889f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7091fef5020010db885a3807065438a2e0370738119fff7ff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f70920ef5020010db885a3807065438a2e0370738219fff6ff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f70922ef5020010db885a3807065438a2e0370738319fff5ff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7092eef5020010db885a3807065438a2e0370738419fff4ff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7093eef5020010db885a3807065438a2e0370738519fff3ff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7095eef5020010db885a3807065438a2e0370738619fff2ff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7099eef5020010db885a3807065438a2e0370738719fff1ff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f70a1eef5020010db885a3807065438a2e0370738819fff0ffff61761a4f520001ff");
		Message refV6 = new Message(Message.Type.RESPONSE, Message.Method.FIND_PEER, 18,
				new FindPeerResponse(null, nodes6, null));

		String jsonV46 = """
				{"y":68,"t":18,"r":{"n4":[["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqAf","192.168.1.1",65535],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqAg","192.168.1.2",65534],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqAi","192.168.1.3",65533],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqAn","192.168.1.4",65532],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqAv","192.168.1.5",65531],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqBC","192.168.1.6",65530],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqBk","192.168.1.7",65529],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqCr","192.168.1.8",65528]],"n6":[["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqF4","2001:db8:85a3:8070:6543:8a2e:370:7381",65527],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqKU","2001:db8:85a3:8070:6543:8a2e:370:7382",65526],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqUJ","2001:db8:85a3:8070:6543:8a2e:370:7383",65525],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6frPG","2001:db8:85a3:8070:6543:8a2e:370:7384",65524],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fsbt","2001:db8:85a3:8070:6543:8a2e:370:7385",65523],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fv38","2001:db8:85a3:8070:6543:8a2e:370:7386",65522],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fzuc","2001:db8:85a3:8070:6543:8a2e:370:7387",65521],["2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6gAea","2001:db8:85a3:8070:6543:8a2e:370:7388",65520]]},"v":1330774017}
				""";
		byte[] cborV46 = Hex.decode("bf617918446174126172bf626e34889f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7091ef044c0a8010119ffffff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7091ef144c0a8010219fffeff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7091ef344c0a8010319fffdff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7091ef744c0a8010419fffcff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7091eff44c0a8010519fffbff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7091f0f44c0a8010619fffaff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7091f2f44c0a8010719fff9ff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7091f6f44c0a8010819fff8ff626e36889f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7091fef5020010db885a3807065438a2e0370738119fff7ff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f70920ef5020010db885a3807065438a2e0370738219fff6ff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f70922ef5020010db885a3807065438a2e0370738319fff5ff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7092eef5020010db885a3807065438a2e0370738419fff4ff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7093eef5020010db885a3807065438a2e0370738519fff3ff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7095eef5020010db885a3807065438a2e0370738619fff2ff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7099eef5020010db885a3807065438a2e0370738719fff1ff9f582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f70a1eef5020010db885a3807065438a2e0370738819fff0ffff61761a4f520001ff");
		Message refV46 = new Message(Message.Type.RESPONSE, Message.Method.FIND_PEER, 18,
				new FindPeerResponse(nodes4, nodes6, null));

		String jsonPeers = """
				{"y":68,"t":18,"r":{"p":[{"id":"2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqAe","n":"cQundi2gNo-43AcJwmjCsw64e2VH_8WP","sig":"EiltluuYTrQkQQ1GG9x2rAnBps4T6dC4kurFBVtnd8bjBOJoVqEz0ZG4lTa3RZVg8AMuQnglGTcHP1wiGZp9Ag","f":4660,"e":"tcp://203.0.113.10:65520"},{"id":"2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqAe","n":"hQgJcy9DRO7ttNnw3xAJP5QCjUPDKpUq","sig":"2nzxFvONxY_Tpt8mIrCF8VtwAsQAbtn4-MUO_cnQBTmj0hgiePEmn7nONSMt7PvY29Dd3nwzg0R9txvWSCiUAg","f":4661,"e":"tcp://203.0.113.11:65519"},{"id":"2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqAe","n":"vrLrDwEO7lSRXhbCnfKzzfuskzqDYe6B","sig":"L4wpYXK7zb9x-moPsc663WhPdFB7Smq7iy4OwYTklljbl5PyGeOOawWy0Z1GY4BqsSHFqAv1O-Fd6DLbI505AA","f":4662,"e":"http://abc.example.com/"},{"id":"2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqAe","n":"lj1ddvT2PGFQSna-0IJ4clqWRSbp1Ex5","sig":"EzYSo8GdAzxdSs5ooNdrIpVuXv8Yp0uijp8jGd5lo26A1eDpqWel2osm9L3l0BBzmyQU2wzuUZUpyIX3qhoHAg","f":4663,"e":"http://foo.example.com/"},{"id":"2WssfJZmWESjfU1LrKsYNZQTyj9obAkn5mSqfyP6fqAe","n":"_MB0UeVX5g1lbdfH0kxcAzgCV4KyKKb_","o":"8755tgy7ENtdHfjZPLYbgHWPSwVdZDxvuuh4KkVhDsdE","os":"8VXGlZfwVq-NfJZ5P9-iBkLzhSifygjKxA-q00E4GlyHdgf--LCtnK0-C8uleKthIIKW6g6kj-NHeO8wqRgwAg","sig":"d-wlJ2GUqUHgLwXxBaVVdC68vVObFWfvmzuFEfz30BM2Zfcp6py04eKkgxE9qw7tMLaMn19137Kk8-WfDzglBw","f":4664,"e":"http://bar.example.com/"}]},"v":1330774017}
				""";
		byte[] cborPeers = Hex.decode("bf617918446174126172bf617085bf626964582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7091eef616e5818710ba7762da0368fb8dc0709c268c2b30eb87b6547ffc58f63736967584012296d96eb984eb424410d461bdc76ac09c1a6ce13e9d0b892eac5055b6777c6e304e26856a133d191b89536b7459560f0032e4278251937073f5c22199a7d026166191234616578187463703a2f2f3230332e302e3131332e31303a3635353230ffbf626964582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7091eef616e5818850809732f4344eeedb4d9f0df10093f94028d43c32a952a637369675840da7cf116f38dc58fd3a6df2622b085f15b7002c4006ed9f8f8c50efdc9d00539a3d2182278f1269fb9ce35232decfbd8dbd0ddde7c3383447db71bd6482894026166191235616578187463703a2f2f3230332e302e3131332e31313a3635353139ffbf626964582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7091eef616e5818beb2eb0f010eee54915e16c29df2b3cdfbac933a8361ee816373696758402f8c296172bbcdbf71fa6a0fb1cebadd684f74507b4a6abb8b2e0ec184e49658db9793f219e38e6b05b2d19d4663806ab121c5a80bf53be15de832db239d39006166191236616577687474703a2f2f6162632e6578616d706c652e636f6d2fffbf626964582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7091eef616e5818963d5d76f4f63c61504a76bed08278725a964526e9d44c79637369675840133612a3c19d033c5d4ace68a0d76b22956e5eff18a74ba28e9f2319de65a36e80d5e0e9a967a5da8b26f4bde5d010739b2414db0cee519529c885f7aa1a07026166191237616577687474703a2f2f666f6f2e6578616d706c652e636f6d2fffbf626964582016830d9dc3ed56a5b1eb7a548ca41a60b479e0685e81c58d28f326c8f7091eef616e5818fcc07451e557e60d656dd7c7d24c5c0338025782b228a6ff616f5820698fe9587e9c1e99886d45233b0138377b91a037c34315a648ede49c456ab7dd626f735840f155c69597f056af8d7c96793fdfa20642f385289fca08cac40faad341381a5c877607fef8b0ad9cad3e0bcba578ab61208296ea0ea48fe34778ef30a918300263736967584077ec25276194a941e02f05f105a555742ebcbd539b1567ef9b3b8511fcf7d0133665f729ea9cb4e1e2a483113dab0eed30b68c9f5f75dfb2a4f3e59f0f3825076166191238616577687474703a2f2f6261722e6578616d706c652e636f6d2fffff61761a4f520001ff");
		Message refPeers = new Message(Message.Type.RESPONSE, Message.Method.FIND_PEER, 18,
				new FindPeerResponse(null, null, peers));

		return Stream.of(
				Arguments.of("v4", jsonV4, cborV4, refV4),
				Arguments.of("v6", jsonV6, cborV6, refV6),
				Arguments.of("v46", jsonV46, cborV46, refV46),
				Arguments.of("peers", jsonPeers, cborPeers, refPeers)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("findPeerResponses")
	void testFindPeerResponse(String name, String json, byte[] cbor, Message ref) {
		Message msg = Message.parse(json);
		assertEquals(Message.Type.RESPONSE, msg.getType());
		assertEquals(Message.Method.FIND_PEER, msg.getMethod());
		assertThat(msg)
				.usingRecursiveComparison()
				.withComparatorForType(Id::compare, Id.class)
				.isEqualTo(ref);

		msg = Message.parse(cbor);
		assertEquals(Message.Type.RESPONSE, msg.getType());
		assertEquals(Message.Method.FIND_PEER, msg.getMethod());
		assertThat(msg)
				.usingRecursiveComparison()
				.withComparatorForType(Id::compare, Id.class)
				.isEqualTo(ref);

		assertEquals(json.trim(), ref.toJson());
		assertArrayEquals(cbor, ref.toBytes());
	}

	static Stream<Arguments> announcePeerRequests() {
		int token = 0x12345678;

		String p0 = """
				{"id":"7n2GPcHPPjuJzD25rbTUkAMGqVWEB2rcFso2wfGYfbAo","n":"g1ETrs4degCLKMCZaEojYPXwAO-hSvMm","sig":"xGizD8UzUOEM2xBvSaH6Ni5I7h34_29CNffKejaQvocvk9hZFbJ5UocaObqapFQtNUwAevypqB8T2HUHHRpFCA","f":-1040584351410612015,"e":"https://192.168.8.1/test/service"}
				""";
		PeerInfo peer = Json.parse(p0, PeerInfo.class);

		String p1 = """
				{"id":"7n2GPcHPPjuJzD25rbTUkAMGqVWEB2rcFso2wfGYfbAo","n":"RdbN9ZeR31gWrO4P_aT8VmjW_stQtDf7","seq":1,"sig":"SQumwHr43Kal35gfw_QG4h6V9-xvwbQ6Qv1tZO67UiEpw8CcPG5WvAvjltmJ2vOgRxk7kKBcJgKfacWr_pMiDQ","f":207660901230922110,"e":"https://10.0.0.8/test/service","ex":"WLQKoxeRWQd4jikgIJs3kET4BJfblNST1E2jGt6qOH7UGNNv9RLmzR2TlJtyydPoi7Y"}
				""";
		PeerInfo peerExt = Json.parse(p1, PeerInfo.class);

		String p2 = """
				{"id":"7n2GPcHPPjuJzD25rbTUkAMGqVWEB2rcFso2wfGYfbAo","n":"qVLmbtDSiim0HmKfxWr8K8LVBekeDdqM","seq":2,"o":"F2rDetsGPpXU3U9ukFtXFAZ7zF65AEVw38r1jQkJ1mGF","os":"mfpreg0alYKaFxefFW59rVKXSZsShu1UOi-LEdYnbZA_Mj63cZhDwUXvjOJ60eb9Pj2e-drxJURT8IJh-FaMBw","sig":"UpYDoCFE2IS8TQy-BFMe2Lb2iAv0KWa1a3Zm8OHjrXiG8F86HmWCjGVD0MmSjB-oQHvxR-7AUf2kHSJoS6HyBg","f":1519981037643232008,"e":"https://example.com/test/service"}
				""";
		PeerInfo peerAuth = Json.parse(p2, PeerInfo.class);

		String p3 = """
				{"id":"7n2GPcHPPjuJzD25rbTUkAMGqVWEB2rcFso2wfGYfbAo","n":"Jky5gu4VyxsVrzuvfMVnk36KHY71M1je","seq":3,"o":"EzYDtuvC7Fzb584uPJaAsFGRwxBsdLbJbQGsBn3uVnfb","os":"MIp2HWYPc-UskP6uGB9kehm-it4Jjx9zrku68_WM42ZSxtX8GHbWhf_EDxQPoEO7SUrt-_0HKHvJ4wf-RICADw","sig":"zdlcUMVCFT7Z6kwtx0guZ0lqTv2L0VvCR0wbM7DIMI7M89fCHpYFrBuskhFon_oiUNg41cfmILXoeSxzxRx4CQ","f":6004588448283323695,"e":"https://example.com/test/service","ex":"FBvBhGcQVECipquOGmmC205fo_bGHLnaHbsmniZYAIbtbfVR"}
				""";
		PeerInfo peerAuthExt = Json.parse(p3, PeerInfo.class);

		String jsonPeer = """
				{"y":35,"t":321,"q":{"tok":305419896,"k":"7n2GPcHPPjuJzD25rbTUkAMGqVWEB2rcFso2wfGYfbAo","n":"g1ETrs4degCLKMCZaEojYPXwAO-hSvMm","sig":"xGizD8UzUOEM2xBvSaH6Ni5I7h34_29CNffKejaQvocvk9hZFbJ5UocaObqapFQtNUwAevypqB8T2HUHHRpFCA","f":-1040584351410612015,"e":"https://192.168.8.1/test/service"},"v":1330774017}
				""";
		byte[] cborPeer = Hex.decode("bf6179182361741901416171bf63746f6b1a12345678616b582064aeb04e27365babee15f412be8eac992c115bf39d2d0e2a99d24b9fd59975c0616e5818835113aece1d7a008b28c099684a2360f5f000efa14af326637369675840c468b30fc53350e10cdb106f49a1fa362e48ee1df8ff6f4235f7ca7a3690be872f93d85915b27952871a39ba9aa4542d354c007afca9a81f13d875071d1a450861663b0e70e5f450a8e72e6165782068747470733a2f2f3139322e3136382e382e312f746573742f73657276696365ff61761a4f520001ff");
		Message refPeer = new Message(Message.Type.REQUEST, Message.Method.ANNOUNCE_PEER, 321,
				new AnnouncePeerRequest(peer, token, -1));

		String jsonPeerCas = """
				{"y":35,"t":321,"q":{"tok":305419896,"cas":0,"k":"7n2GPcHPPjuJzD25rbTUkAMGqVWEB2rcFso2wfGYfbAo","n":"g1ETrs4degCLKMCZaEojYPXwAO-hSvMm","sig":"xGizD8UzUOEM2xBvSaH6Ni5I7h34_29CNffKejaQvocvk9hZFbJ5UocaObqapFQtNUwAevypqB8T2HUHHRpFCA","f":-1040584351410612015,"e":"https://192.168.8.1/test/service"},"v":1330774017}
				""";
		byte[] cborPeerCas = Hex.decode("bf6179182361741901416171bf63746f6b1a123456786363617300616b582064aeb04e27365babee15f412be8eac992c115bf39d2d0e2a99d24b9fd59975c0616e5818835113aece1d7a008b28c099684a2360f5f000efa14af326637369675840c468b30fc53350e10cdb106f49a1fa362e48ee1df8ff6f4235f7ca7a3690be872f93d85915b27952871a39ba9aa4542d354c007afca9a81f13d875071d1a450861663b0e70e5f450a8e72e6165782068747470733a2f2f3139322e3136382e382e312f746573742f73657276696365ff61761a4f520001ff");
		Message refPeerCas = new Message(Message.Type.REQUEST, Message.Method.ANNOUNCE_PEER, 321,
				new AnnouncePeerRequest(peer, token, 0));

		String jsonPeerExt = """
				{"y":35,"t":321,"q":{"tok":305419896,"k":"7n2GPcHPPjuJzD25rbTUkAMGqVWEB2rcFso2wfGYfbAo","n":"RdbN9ZeR31gWrO4P_aT8VmjW_stQtDf7","seq":1,"sig":"SQumwHr43Kal35gfw_QG4h6V9-xvwbQ6Qv1tZO67UiEpw8CcPG5WvAvjltmJ2vOgRxk7kKBcJgKfacWr_pMiDQ","f":207660901230922110,"e":"https://10.0.0.8/test/service","ex":"WLQKoxeRWQd4jikgIJs3kET4BJfblNST1E2jGt6qOH7UGNNv9RLmzR2TlJtyydPoi7Y"},"v":1330774017}
				""";
		byte[] cborPeerExt = Hex.decode("bf6179182361741901416171bf63746f6b1a12345678616b582064aeb04e27365babee15f412be8eac992c115bf39d2d0e2a99d24b9fd59975c0616e581845d6cdf59791df5816acee0ffda4fc5668d6fecb50b437fb6373657101637369675840490ba6c07af8dca6a5df981fc3f406e21e95f7ec6fc1b43a42fd6d64eebb522129c3c09c3c6e56bc0be396d989daf3a047193b90a05c26029f69c5abfe93220d61661b02e1c27d4b9b497e6165781d68747470733a2f2f31302e302e302e382f746573742f73657276696365626578583258b40aa317915907788e2920209b379044f80497db94d493d44da31adeaa387ed418d36ff512e6cd1d93949b72c9d3e88bb6ff61761a4f520001ff");
		Message refPeerExt = new Message(Message.Type.REQUEST, Message.Method.ANNOUNCE_PEER, 321,
				new AnnouncePeerRequest(peerExt, token, -1));

		String jsonPeerExtCas = """
				{"y":35,"t":321,"q":{"tok":305419896,"cas":1,"k":"7n2GPcHPPjuJzD25rbTUkAMGqVWEB2rcFso2wfGYfbAo","n":"RdbN9ZeR31gWrO4P_aT8VmjW_stQtDf7","seq":1,"sig":"SQumwHr43Kal35gfw_QG4h6V9-xvwbQ6Qv1tZO67UiEpw8CcPG5WvAvjltmJ2vOgRxk7kKBcJgKfacWr_pMiDQ","f":207660901230922110,"e":"https://10.0.0.8/test/service","ex":"WLQKoxeRWQd4jikgIJs3kET4BJfblNST1E2jGt6qOH7UGNNv9RLmzR2TlJtyydPoi7Y"},"v":1330774017}
				""";
		byte[] cborPeerExtCas = Hex.decode("bf6179182361741901416171bf63746f6b1a123456786363617301616b582064aeb04e27365babee15f412be8eac992c115bf39d2d0e2a99d24b9fd59975c0616e581845d6cdf59791df5816acee0ffda4fc5668d6fecb50b437fb6373657101637369675840490ba6c07af8dca6a5df981fc3f406e21e95f7ec6fc1b43a42fd6d64eebb522129c3c09c3c6e56bc0be396d989daf3a047193b90a05c26029f69c5abfe93220d61661b02e1c27d4b9b497e6165781d68747470733a2f2f31302e302e302e382f746573742f73657276696365626578583258b40aa317915907788e2920209b379044f80497db94d493d44da31adeaa387ed418d36ff512e6cd1d93949b72c9d3e88bb6ff61761a4f520001ff");
		Message refPeerExtCas = new Message(Message.Type.REQUEST, Message.Method.ANNOUNCE_PEER, 321,
				new AnnouncePeerRequest(peerExt, token, 1));

		String jsonPeerAuth = """
				{"y":35,"t":321,"q":{"tok":305419896,"k":"7n2GPcHPPjuJzD25rbTUkAMGqVWEB2rcFso2wfGYfbAo","n":"qVLmbtDSiim0HmKfxWr8K8LVBekeDdqM","seq":2,"o":"F2rDetsGPpXU3U9ukFtXFAZ7zF65AEVw38r1jQkJ1mGF","os":"mfpreg0alYKaFxefFW59rVKXSZsShu1UOi-LEdYnbZA_Mj63cZhDwUXvjOJ60eb9Pj2e-drxJURT8IJh-FaMBw","sig":"UpYDoCFE2IS8TQy-BFMe2Lb2iAv0KWa1a3Zm8OHjrXiG8F86HmWCjGVD0MmSjB-oQHvxR-7AUf2kHSJoS6HyBg","f":1519981037643232008,"e":"https://example.com/test/service"},"v":1330774017}
				""";
		byte[] cborPeerAuth = Hex.decode("bf6179182361741901416171bf63746f6b1a12345678616b582064aeb04e27365babee15f412be8eac992c115bf39d2d0e2a99d24b9fd59975c0616e5818a952e66ed0d28a29b41e629fc56afc2bc2d505e91e0dda8c6373657102616f5820d07cd9537ece7b7bfeef479a8ec0c47514f09773e9611030ef99b6a85014f454626f73584099fa6b7a0d1a95829a17179f156e7dad5297499b1286ed543a2f8b11d6276d903f323eb7719843c145ef8ce27ad1e6fd3e3d9ef9daf1254453f08261f8568c07637369675840529603a02144d884bc4d0cbe04531ed8b6f6880bf42966b56b7666f0e1e3ad7886f05f3a1e65828c6543d0c9928c1fa8407bf147eec051fda41d22684ba1f20661661b15180eb3560df3086165782068747470733a2f2f6578616d706c652e636f6d2f746573742f73657276696365ff61761a4f520001ff");
		Message refPeerAuth = new Message(Message.Type.REQUEST, Message.Method.ANNOUNCE_PEER, 321,
				new AnnouncePeerRequest(peerAuth, token, -1));

		String jsonPeerAuthCas = """
				{"y":35,"t":321,"q":{"tok":305419896,"cas":2,"k":"7n2GPcHPPjuJzD25rbTUkAMGqVWEB2rcFso2wfGYfbAo","n":"qVLmbtDSiim0HmKfxWr8K8LVBekeDdqM","seq":2,"o":"F2rDetsGPpXU3U9ukFtXFAZ7zF65AEVw38r1jQkJ1mGF","os":"mfpreg0alYKaFxefFW59rVKXSZsShu1UOi-LEdYnbZA_Mj63cZhDwUXvjOJ60eb9Pj2e-drxJURT8IJh-FaMBw","sig":"UpYDoCFE2IS8TQy-BFMe2Lb2iAv0KWa1a3Zm8OHjrXiG8F86HmWCjGVD0MmSjB-oQHvxR-7AUf2kHSJoS6HyBg","f":1519981037643232008,"e":"https://example.com/test/service"},"v":1330774017}
				""";
		byte[] cborPeerAuthCas = Hex.decode("bf6179182361741901416171bf63746f6b1a123456786363617302616b582064aeb04e27365babee15f412be8eac992c115bf39d2d0e2a99d24b9fd59975c0616e5818a952e66ed0d28a29b41e629fc56afc2bc2d505e91e0dda8c6373657102616f5820d07cd9537ece7b7bfeef479a8ec0c47514f09773e9611030ef99b6a85014f454626f73584099fa6b7a0d1a95829a17179f156e7dad5297499b1286ed543a2f8b11d6276d903f323eb7719843c145ef8ce27ad1e6fd3e3d9ef9daf1254453f08261f8568c07637369675840529603a02144d884bc4d0cbe04531ed8b6f6880bf42966b56b7666f0e1e3ad7886f05f3a1e65828c6543d0c9928c1fa8407bf147eec051fda41d22684ba1f20661661b15180eb3560df3086165782068747470733a2f2f6578616d706c652e636f6d2f746573742f73657276696365ff61761a4f520001ff");
		Message refPeerAuthCas = new Message(Message.Type.REQUEST, Message.Method.ANNOUNCE_PEER, 321,
				new AnnouncePeerRequest(peerAuth, token, 2));

		String jsonPeerAuthExt = """
				{"y":35,"t":321,"q":{"tok":305419896,"k":"7n2GPcHPPjuJzD25rbTUkAMGqVWEB2rcFso2wfGYfbAo","n":"Jky5gu4VyxsVrzuvfMVnk36KHY71M1je","seq":3,"o":"EzYDtuvC7Fzb584uPJaAsFGRwxBsdLbJbQGsBn3uVnfb","os":"MIp2HWYPc-UskP6uGB9kehm-it4Jjx9zrku68_WM42ZSxtX8GHbWhf_EDxQPoEO7SUrt-_0HKHvJ4wf-RICADw","sig":"zdlcUMVCFT7Z6kwtx0guZ0lqTv2L0VvCR0wbM7DIMI7M89fCHpYFrBuskhFon_oiUNg41cfmILXoeSxzxRx4CQ","f":6004588448283323695,"e":"https://example.com/test/service","ex":"FBvBhGcQVECipquOGmmC205fo_bGHLnaHbsmniZYAIbtbfVR"},"v":1330774017}
				""";
		byte[] cborPeerAuthExt = Hex.decode("bf6179182361741901416171bf63746f6b1a12345678616b582064aeb04e27365babee15f412be8eac992c115bf39d2d0e2a99d24b9fd59975c0616e5818264cb982ee15cb1b15af3baf7cc567937e8a1d8ef53358de6373657103616f5820cfe556e03b4aef92fb22f0c44e5624412171d46c65dfd051b0120a49bbaa5d32626f735840308a761d660f73e52c90feae181f647a19be8ade098f1f73ae4bbaf3f58ce36652c6d5fc1876d685ffc40f140fa043bb494aedfbfd07287bc9e307fe4480800f637369675840cdd95c50c542153ed9ea4c2dc7482e67496a4efd8bd15bc2474c1b33b0c8308eccf3d7c21e9605ac1bac9211689ffa2250d838d5c7e620b5e8792c73c51c780961661b535495614a576d2f6165782068747470733a2f2f6578616d706c652e636f6d2f746573742f736572766963656265785824141bc18467105440a2a6ab8e1a6982db4e5fa3f6c61cb9da1dbb269e26580086ed6df551ff61761a4f520001ff");
		Message refPeerAuthExt = new Message(Message.Type.REQUEST, Message.Method.ANNOUNCE_PEER, 321,
				new AnnouncePeerRequest(peerAuthExt, token, -1));

		String jsonPeerAuthExtCas = """
				{"y":35,"t":321,"q":{"tok":305419896,"cas":3,"k":"7n2GPcHPPjuJzD25rbTUkAMGqVWEB2rcFso2wfGYfbAo","n":"Jky5gu4VyxsVrzuvfMVnk36KHY71M1je","seq":3,"o":"EzYDtuvC7Fzb584uPJaAsFGRwxBsdLbJbQGsBn3uVnfb","os":"MIp2HWYPc-UskP6uGB9kehm-it4Jjx9zrku68_WM42ZSxtX8GHbWhf_EDxQPoEO7SUrt-_0HKHvJ4wf-RICADw","sig":"zdlcUMVCFT7Z6kwtx0guZ0lqTv2L0VvCR0wbM7DIMI7M89fCHpYFrBuskhFon_oiUNg41cfmILXoeSxzxRx4CQ","f":6004588448283323695,"e":"https://example.com/test/service","ex":"FBvBhGcQVECipquOGmmC205fo_bGHLnaHbsmniZYAIbtbfVR"},"v":1330774017}
				""";
		byte[] cborPeerAuthExtCas = Hex.decode("bf6179182361741901416171bf63746f6b1a123456786363617303616b582064aeb04e27365babee15f412be8eac992c115bf39d2d0e2a99d24b9fd59975c0616e5818264cb982ee15cb1b15af3baf7cc567937e8a1d8ef53358de6373657103616f5820cfe556e03b4aef92fb22f0c44e5624412171d46c65dfd051b0120a49bbaa5d32626f735840308a761d660f73e52c90feae181f647a19be8ade098f1f73ae4bbaf3f58ce36652c6d5fc1876d685ffc40f140fa043bb494aedfbfd07287bc9e307fe4480800f637369675840cdd95c50c542153ed9ea4c2dc7482e67496a4efd8bd15bc2474c1b33b0c8308eccf3d7c21e9605ac1bac9211689ffa2250d838d5c7e620b5e8792c73c51c780961661b535495614a576d2f6165782068747470733a2f2f6578616d706c652e636f6d2f746573742f736572766963656265785824141bc18467105440a2a6ab8e1a6982db4e5fa3f6c61cb9da1dbb269e26580086ed6df551ff61761a4f520001ff");
		Message refPeerAuthExtCas = new Message(Message.Type.REQUEST, Message.Method.ANNOUNCE_PEER, 321,
				new AnnouncePeerRequest(peerAuthExt, token, 3));

		return Stream.of(
				Arguments.of("peer", jsonPeer, cborPeer, refPeer),
				Arguments.of("peerCas", jsonPeerCas, cborPeerCas, refPeerCas),
				Arguments.of("peerExt", jsonPeerExt, cborPeerExt, refPeerExt),
				Arguments.of("peerExtCas", jsonPeerExtCas, cborPeerExtCas, refPeerExtCas),
				Arguments.of("peerAuth", jsonPeerAuth, cborPeerAuth, refPeerAuth),
				Arguments.of("peerAuthCas", jsonPeerAuthCas, cborPeerAuthCas, refPeerAuthCas),
				Arguments.of("peerAuthExt", jsonPeerAuthExt, cborPeerAuthExt, refPeerAuthExt),
				Arguments.of("peerAuthExtCas", jsonPeerAuthExtCas, cborPeerAuthExtCas, refPeerAuthExtCas)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("announcePeerRequests")
	void testAnnouncePeerRequest(String name, String json, byte[] cbor, Message ref) {
		Message msg = Message.parse(json);
		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.ANNOUNCE_PEER, msg.getMethod());
		assertThat(msg)
				.usingRecursiveComparison()
				.withComparatorForType(Id::compare, Id.class)
				.isEqualTo(ref);

		msg = Message.parse(cbor);
		assertEquals(Message.Type.REQUEST, msg.getType());
		assertEquals(Message.Method.ANNOUNCE_PEER, msg.getMethod());
		assertThat(msg)
				.usingRecursiveComparison()
				.withComparatorForType(Id::compare, Id.class)
				.isEqualTo(ref);

		assertEquals(json.trim(), ref.toJson());
		assertArrayEquals(cbor, ref.toBytes());
	}

	@Test
	void testAnnouncePeerResponse() throws Exception {
		String json = """
				{"y":67,"t":5678,"v":1330774017}
				""";
		byte[] cbor = Hex.decode("bf61791843617419162e61761a4f520001ff");

		Message ref = new Message(Message.Type.RESPONSE, Message.Method.ANNOUNCE_PEER, 5678, null);

		Message msg = Message.parse(json);
		assertEquals(Message.Type.RESPONSE, msg.getType());
		assertEquals(Message.Method.ANNOUNCE_PEER, msg.getMethod());
		assertThat(msg)
				.usingRecursiveComparison()
				.withComparatorForType(Id::compare, Id.class)
				.isEqualTo(ref);

		msg = Message.parse(cbor);
		assertEquals(Message.Type.RESPONSE, msg.getType());
		assertEquals(Message.Method.ANNOUNCE_PEER, msg.getMethod());
		assertThat(msg)
				.usingRecursiveComparison()
				.withComparatorForType(Id::compare, Id.class)
				.isEqualTo(ref);

		assertEquals(json.trim(), ref.toJson());
		assertArrayEquals(cbor, ref.toBytes());
	}

	static Stream<Arguments> errors() {
		String jsonWithoutMessage = """
				{"y":5,"t":123,"e":{"c":1985229328},"v":1330774017}
				""";
		byte[] cborWithoutMessage = Hex.decode("bf6179056174187b6165bf61631a76543210ff61761a4f520001ff");
		Message refWithoutMessage = new Message(Message.Type.ERROR, Message.Method.STORE_VALUE, 123,
				new Error(0x76543210, null));

		String jsonWithMessage = """
				{"y":2,"t":456,"e":{"c":-1000,"m":"Test error message"},"v":1330774017}
				""";
		byte[] cborWithMessage = Hex.decode("bf61790261741901c86165bf61633903e7616d7254657374206572726f72206d657373616765ff61761a4f520001ff");
		Message refWithMessage = new Message(Message.Type.ERROR, Message.Method.FIND_NODE, 456,
				new Error(-1000, "Test error message"));

		return Stream.of(
				Arguments.of("errorWithoutMessage", jsonWithoutMessage, cborWithoutMessage, refWithoutMessage),
				Arguments.of("errorWithMessage", jsonWithMessage, cborWithMessage, refWithMessage)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("errors")
	void testError(String name, String json, byte[] cbor, Message ref) {
		Message msg = Message.parse(json);
		assertEquals(Message.Type.ERROR, msg.getType());
		assertTrue(msg.getMethod() == Message.Method.FIND_NODE || msg.getMethod() == Message.Method.STORE_VALUE);
		assertThat(msg)
				.usingRecursiveComparison()
				.isEqualTo(ref);

		msg = Message.parse(cbor);
		assertEquals(Message.Type.ERROR, msg.getType());
		assertTrue(msg.getMethod() == Message.Method.FIND_NODE || msg.getMethod() == Message.Method.STORE_VALUE);
		assertThat(msg)
				.usingRecursiveComparison()
				.isEqualTo(ref);

		assertEquals(json.trim(), ref.toJson());
		assertArrayEquals(cbor, ref.toBytes());
	}

	@Test
	void test() {
		for (Message.Type t : Message.Type.values()) {
			for (Message.Method m : Message.Method.values()) {
				System.out.format("%10s %16s  %d\n", t, m, t.value() | m.value());
			}
		}
	}
}