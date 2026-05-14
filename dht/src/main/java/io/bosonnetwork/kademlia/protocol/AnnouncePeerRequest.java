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

@JsonPropertyOrder({"tok", "cas", "k", "n", "seq", "o", "os", "sig", "f", "e", "ex"})
public class AnnouncePeerRequest implements Request {
	private final int token;
	private final int expectedSequenceNumber;
	private final PeerInfo peer;

	@JsonCreator
	protected AnnouncePeerRequest(
			@JsonProperty(value = "tok", required = true) int token,
			@JsonProperty(value = "cas") Integer expectedSequenceNumber,
			@JsonProperty(value = "k", required = true) Id peerId,
			@JsonProperty(value = "n", required = true) byte[] nonce,
			@JsonProperty(value = "seq") int sequenceNumber,
			@JsonProperty(value = "o") Id nodeId,
			@JsonProperty(value = "os") byte[] nodeSig,
			@JsonProperty(value = "sig", required = true) byte[] signature,
			@JsonProperty(value = "f", required = true) long fingerprint,
			@JsonProperty(value = "e", required = true) String endpoint,
			@JsonProperty(value = "ex") byte[] extraData) {
		this.token = token;
		this.expectedSequenceNumber = expectedSequenceNumber != null ? expectedSequenceNumber : -1;
		this.peer = PeerInfo.of(peerId, nonce, sequenceNumber, nodeId, nodeSig, signature, fingerprint, endpoint, extraData);
	}

	public AnnouncePeerRequest(PeerInfo peer, int token, int expectedSequenceNumber) {
		this.token = token;
		this.expectedSequenceNumber = expectedSequenceNumber;
		this.peer = peer;
	}

	@JsonProperty("tok")
	public int getToken() {
		return token;
	}

	public int getExpectedSequenceNumber() {
		return expectedSequenceNumber;
	}

	public PeerInfo getPeer() {
		return peer;
	}

	@JsonProperty("cas")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	protected Integer getCas() {
		return expectedSequenceNumber >= 0 ? expectedSequenceNumber : null;
	}

	@JsonProperty("k")
	protected Id getPeerId() {
		return peer.getId();
	}

	@JsonProperty("n")
	protected byte[] getNonce() {
		return peer.getNonce();
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
		return Objects.hash(token, peer);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;

		if (o instanceof AnnouncePeerRequest that)
			return this.token == that.token && this.peer.equals(that.peer);

		return false;
	}
}