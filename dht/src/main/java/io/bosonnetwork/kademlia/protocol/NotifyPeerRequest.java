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

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.bosonnetwork.Id;
import io.bosonnetwork.PeerInfo;

/**
 * NOTIFY_PEER: a storing node telling a subscriber that a peer record under the id it watches is new or
 * has changed. One record per notification.
 * <p>
 * The body is the record exactly as ANNOUNCE_PEER carried it, minus the token and {@code cas}, so a record
 * that fitted in the announce that stored it always fits in the notification that reports it.
 * </p>
 */
@JsonPropertyOrder({"k", "seq", "o", "os", "sig", "f", "e", "ex"})
public class NotifyPeerRequest implements Request {
	private final PeerInfo peer;

	@JsonCreator
	protected NotifyPeerRequest(
			@JsonProperty(value = "k", required = true) Id peerId,
			@JsonProperty(value = "seq") int sequenceNumber,
			@JsonProperty(value = "o") Id nodeId,
			@JsonProperty(value = "os") byte[] nodeSig,
			@JsonProperty(value = "sig", required = true) byte[] signature,
			@JsonProperty(value = "f", required = true) long fingerprint,
			@JsonProperty(value = "e", required = true) String endpoint,
			@JsonProperty(value = "ex") byte[] extraData) {
		this.peer = PeerInfo.of(peerId, sequenceNumber, nodeId, nodeSig, signature, fingerprint, endpoint, extraData);
	}

	public NotifyPeerRequest(PeerInfo peer) {
		this.peer = Objects.requireNonNull(peer, "peer");
	}

	public PeerInfo getPeer() {
		return peer;
	}

	@JsonProperty("k")
	protected Id getPeerId() {
		return peer.getId();
	}

	@JsonProperty("seq")
	@JsonInclude(JsonInclude.Include.NON_DEFAULT)
	protected int getSequenceNumber() {
		return peer.getSequenceNumber();
	}

	@JsonProperty("o")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	protected Id getNodeId() {
		return peer.getNodeId();
	}

	@JsonProperty("os")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	protected byte[] getNodeSignature() {
		return peer.getNodeSignature();
	}

	@JsonProperty("sig")
	protected byte[] getSignature() {
		return peer.getSignature();
	}

	@JsonProperty("f")
	protected long getFingerprint() {
		return peer.getFingerprint();
	}

	@JsonProperty("e")
	protected String getEndpoint() {
		return peer.getEndpoint();
	}

	@JsonProperty("ex")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	protected byte[] getExtraData() {
		return peer.getExtraData();
	}

	@Override
	public int hashCode() {
		return peer.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;

		if (o instanceof NotifyPeerRequest that)
			return peer.equals(that.peer);

		return false;
	}
}
