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

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;

@JsonPropertyOrder({"n4", "n6", "tok", "p"})
public class FindPeerResponse extends LookupResponse {
	@JsonProperty("p")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<PeerInfo> peers;

	@JsonCreator
	protected FindPeerResponse(
			@JsonProperty("n4") List<? extends NodeInfo> nodes4,
			@JsonProperty("n6") List<? extends NodeInfo> nodes6,
			@JsonProperty("p") List<PeerInfo> peers) {
		super(nodes4, nodes6, 0);
		this.peers = peers == null || peers.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(peers);
	}

	protected FindPeerResponse(List<? extends NodeInfo> nodes4, List<? extends NodeInfo> nodes6) {
		this(nodes4, nodes6, null);
	}

	protected FindPeerResponse(List<PeerInfo> peers) {
		this(null, null, peers);
	}

	public boolean hasPeers() {
		return !peers.isEmpty();
	}

	public List<PeerInfo> getPeers() {
		return peers;
	}

	@Override
	public int hashCode() {
		return 0xF1AD9EE2 + super.hashCode() + Objects.hashCode(peers);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;

		if (obj instanceof FindPeerResponse that)
			return Objects.equals(peers, that.peers) && super.equals(obj);

		return false;
	}
}