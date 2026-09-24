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

package io.bosonnetwork.kademlia.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.json.Json;

/**
 * Covers the observed endpoint a reply carries: the {@code o} field, holding the address the request
 * arrived from as the replying node saw it.
 * <p>
 * It sits at the top level of the message rather than in the body, and that is a compatibility
 * requirement, not a style choice: PING, STORE_VALUE and ANNOUNCE_PEER responses have no body, and a
 * parser rejects any body on them - while unknown top-level keys are skipped, by nodes that predate the
 * field as much as by this one.
 * </p>
 */
public class ObservedEndpointTests {
	private static final InetSocketAddress V4 = new InetSocketAddress("155.138.245.211", 39001);
	private static final InetSocketAddress V6 = new InetSocketAddress("2001:4860:4860::8888", 39001);

	private static Message findNodeResponse() {
		return Message.findNodeResponse(0x1234, List.of(NodeInfo.of(Id.random(), "45.32.138.246", 39001)), null, 0);
	}

	@Test
	void aReplyCarriesItsObservedEndpointThroughTheCodec() {
		for (InetSocketAddress observed : List.of(V4, V6)) {
			Message pong = Message.pingResponse(0x1234).setObserved(observed);
			assertEquals(observed, Message.parse(pong.toBytes()).getObserved());
			assertEquals(observed, Message.parse(pong.toJson()).getObserved());

			Message found = findNodeResponse().setObserved(observed);
			Message parsed = Message.parse(found.toBytes());
			assertEquals(observed, parsed.getObserved());
			// The body is untouched by it.
			assertEquals(found.<FindNodeResponse>getBody(), parsed.getBody());

			Message error = Message.error(Message.Method.FIND_NODE, 0x1234, 1000, "no").setObserved(observed);
			assertEquals(observed, Message.parse(error.toBytes()).getObserved());
		}
	}

	@Test
	void itCostsNineBytesForIPv4AndTwentyOneForIPv6() {
		// The figures the packet budget (KadConstants.RESPONSE_OVERHEAD) reserves room for.
		int bare = findNodeResponse().toBytes().length;
		Message message = findNodeResponse();
		assertEquals(bare + 9, message.setObserved(V4).toBytes().length);
		assertEquals(bare + 21, message.setObserved(V6).toBytes().length);
	}

	@Test
	void aMessageWithoutItParsesWithout() {
		assertNull(Message.parse(Message.pingResponse(0x1234).toBytes()).getObserved());
	}

	@Test
	void aRequestNeverCarriesOne() {
		// Only a reply has anything to report; one on a request is ignored rather than trusted.
		Message ping = Message.pingRequest().setObserved(V4);
		assertNull(Message.parse(ping.toBytes()).getObserved());
	}

	@Test
	void unknownTopLevelKeysAreSkipped() throws IOException {
		// The rule a node that predates the field relies on to ignore it - including on a PING response,
		// which has no body and on which a body key would be rejected.
		byte[] bytes = withTopLevel(Message.pingResponse(0x1234), tree -> tree.put("zz", "anything"));
		assertEquals(Message.Method.PING, Message.parse(bytes).getMethod());
	}

	@Test
	void aMalformedObservedEndpointLosesTheReportButNotTheReply() throws IOException {
		// The field is a hint riding on a reply; the reply itself is still good.
		List<Consumer<ObjectNode>> malformed = List.of(
				tree -> tree.put("o", new byte[5]),                     // no address is 3 bytes long
				tree -> tree.put("o", new byte[] {1, 2, 3, 4, 0, 0}),   // port 0
				tree -> tree.put("o", 42),                              // not binary
				tree -> tree.putArray("o").add(1).add(2),               // not binary, and nested
				tree -> tree.putObject("o").put("a", 1));

		for (Consumer<ObjectNode> edit : malformed) {
			Message parsed = Message.parse(withTopLevel(findNodeResponse(), edit));
			assertEquals(Message.Method.FIND_NODE, parsed.getMethod());
			assertEquals(1, parsed.<FindNodeResponse>getBody().getNodes4().size());
			assertNull(parsed.getObserved());
		}
	}

	private static byte[] withTopLevel(Message message, Consumer<ObjectNode> edit) throws IOException {
		ObjectNode tree = (ObjectNode) Json.cborMapper().readTree(message.toBytes());
		edit.accept(tree);
		return Json.cborMapper().writeValueAsBytes(tree);
	}
}
