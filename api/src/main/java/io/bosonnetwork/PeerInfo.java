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

package io.bosonnetwork;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Objects;

import io.bosonnetwork.crypto.Hash;
import io.bosonnetwork.crypto.Signature;

/**
 *
 * Represents peer information in the Boson network.
 */
public class PeerInfo {
	public static final Object ATTRIBUTE_OMIT_PEER_ID = new Object();
	public static final Object ATTRIBUTE_PEER_ID = new Object();

	private Id publicKey;			// Peer ID
	private byte[] privateKey;		// Private key to sign the peer info
	private Id nodeId;				// The node that provide the service peer
	private Id origin;				// The node that announce the peer
	private int port;
	private String alternativeURL;
	private byte[] signature;

	private PeerInfo(Id peerId, byte[] privateKey, Id nodeId, Id origin, int port,
			String alternativeURL, byte[] signature) {
		if (peerId == null)
			throw new IllegalArgumentException("Invalid peer id");

		if (privateKey != null && privateKey.length != Signature.PrivateKey.BYTES)
			throw new IllegalArgumentException("Invalid private key");

		if (nodeId == null)
			throw new IllegalArgumentException("Invalid node id");

		if (port <= 0 || port > 65535)
			throw new IllegalArgumentException("Invalid port");

		if (signature == null || signature.length != Signature.BYTES)
			throw new IllegalArgumentException("Invalid signature");

		this.publicKey = peerId;
		this.privateKey = privateKey;
		this.nodeId = nodeId;
		this.origin = origin == null || origin.equals(nodeId) ? null : origin;
		this.port = port;
		if (alternativeURL != null && !alternativeURL.isEmpty())
			this.alternativeURL = Normalizer.normalize(alternativeURL, Normalizer.Form.NFC);
		this.signature = signature;
	}

	protected PeerInfo(Id peerId, Id nodeId, Id origin, int port, String alternativeURL, byte[] signature) {
		this(peerId, null, nodeId, origin, port, alternativeURL, signature);
	}

	private PeerInfo(Signature.KeyPair keypair, Id nodeId, Id origin, int port, String alternativeURL) {
		if (keypair == null)
			throw new IllegalArgumentException("Invalid keypair");

		if (nodeId == null)
			throw new IllegalArgumentException("Invalid node id");

		if (port <= 0 || port > 65535)
			throw new IllegalArgumentException("Invalid port");

		this.publicKey = new Id(keypair.publicKey().bytes());;
		this.privateKey = keypair.privateKey().bytes();
		this.nodeId = nodeId;
		this.origin = origin == null || origin.equals(nodeId) ? null : origin;
		this.port = port;
		if (alternativeURL != null && !alternativeURL.isEmpty())
			this.alternativeURL = Normalizer.normalize(alternativeURL, Normalizer.Form.NFC);
		this.signature = Signature.sign(getSignData(), keypair.privateKey());
	}

	/**
	 * Rebuilds a PeerInfo object with specified information.
	 *
	 * @param peerId the peer ID.
	 * @param nodeId the ID of the node providing the service peer.
	 * @param port the port on which the peer is available.
	 * @param signature the signature of the peer info.
	 * @return a created PeerInfo object.
	 */
	public static PeerInfo of(Id peerId, Id nodeId, int port, byte[] signature) {
		return new PeerInfo(peerId, null, nodeId, null, port, null, signature);
	}

	/**
	 * Rebuilds a PeerInfo object with specified information.
	 *
	 * @param peerId the peer ID.
	 * @param privateKey the private key associated with the peer.
	 * @param nodeId the ID of the node providing the service peer.
	 * @param port the port on which the peer is available.
	 * @param signature the signature of the peer info.
	 * @return a created PeerInfo object.
	 */
	public static PeerInfo of(Id peerId, byte[] privateKey, Id nodeId, int port, byte[] signature) {
		return new PeerInfo(peerId, privateKey, nodeId, null, port, null, signature);
	}

	/**
	 * Rebuilds a PeerInfo object with specified information.
	 *
	 * @param peerId the peer ID.
	 * @param nodeId the ID of the node providing the service peer.
	 * @param port the port on which the peer is available.
	 * @param alternativeURL an alternative URL for the peer.
	 * @param signature the signature of the peer info.
	 * @return a created PeerInfo object.
	 */
	public static PeerInfo of(Id peerId, Id nodeId, int port, String alternativeURL, byte[] signature) {
		return new PeerInfo(peerId, null, nodeId, null, port, alternativeURL, signature);
	}

	/**
	 * Rebuilds a PeerInfo object with specified information.
	 *
	 * @param peerId the peer ID.
	 * @param privateKey the private key associated with the peer.
	 * @param nodeId the ID of the node providing the service peer.
	 * @param port the port on which the peer is available.
	 * @param alternativeURL an alternative URL for the peer.
	 * @param signature the signature of the peer info.
	 * @return a created PeerInfo object.
	 */
	public static PeerInfo of(Id peerId, byte[] privateKey, Id nodeId, int port,
			String alternativeURL, byte[] signature) {
		return new PeerInfo(peerId, privateKey, nodeId, null, port, alternativeURL, signature);
	}

	/**
	 * Rebuilds a PeerInfo object with specified information.
	 *
	 * @param peerId the peer ID.
	 * @param nodeId the ID of the node providing the service peer.
	 * @param origin the node that announces the peer.
	 * @param port the port on which the peer is available.
	 * @param signature the signature of the peer info.
	 * @return a created PeerInfo object.
	 */
	public static PeerInfo of(Id peerId, Id nodeId, Id origin, int port, byte[] signature) {
		return new PeerInfo(peerId, null, nodeId, origin, port, null, signature);
	}

	/**
	 * Rebuilds a PeerInfo object with specified information.
	 *
	 * @param peerId the peer ID.
	 * @param privateKey the private key associated with the peer.
	 * @param nodeId the ID of the node providing the service peer.
	 * @param origin the node that announces the peer.
	 * @param port the port on which the peer is available.
	 * @param signature the signature of the peer info.
	 * @return a created PeerInfo object.
	 */
	public static PeerInfo of(Id peerId, byte[] privateKey, Id nodeId,  Id origin, int port, byte[] signature) {
		return new PeerInfo(peerId, privateKey, nodeId, origin, port, null, signature);
	}

	/**
	 * Rebuilds a PeerInfo object with specified information.
	 *
	 * @param peerId the peer ID.
	 * @param nodeId the ID of the node providing the service peer.
	 * @param origin the node that announces the peer.
	 * @param port the port on which the peer is available.
	 * @param alternativeURL an alternative URL for the peer.
	 * @param signature the signature of the peer info.
	 * @return a created PeerInfo object.
	 */
	public static PeerInfo of(Id peerId, Id nodeId, Id origin, int port, String alternativeURL, byte[] signature) {
		return new PeerInfo(peerId, null, nodeId, origin, port, alternativeURL, signature);
	}

	/**
	 * Rebuilds a PeerInfo object with specified information.
	 *
	 * @param peerId the peer ID.
	 * @param privateKey the private key associated with the peer.
	 * @param nodeId the ID of the node providing the service peer.
	 * @param origin the node that announces the peer.
	 * @param port the port on which the peer is available.
	 * @param alternativeURL an alternative URL for the peer.
	 * @param signature the signature of the peer info.
	 * @return a created PeerInfo object.
	 */
	public static PeerInfo of(Id peerId, byte[] privateKey, Id nodeId, Id origin, int port,
			String alternativeURL, byte[] signature) {
		return new PeerInfo(peerId, privateKey, nodeId, origin, port, alternativeURL, signature);
	}

	/**
	 * Creates a PeerInfo object with specified information. the new created PeerInfo will
	 * be signed by a new generated random key pair.
	 *
	 * @param nodeId the ID of the node providing the service peer.
	 * @param port the port on which the peer is available.
	 * @return a created PeerInfo object.
	 */
	public static PeerInfo create(Id nodeId, int port) {
		return create(null, nodeId, null, port, null);
	}

	/**
	 * Creates a PeerInfo object with specified information and key pair.
	 *
	 * @param keypair the key pair key to sign the peer information.
	 * @param nodeId the ID of the node providing the service peer.
	 * @param port the port on which the peer is available.
	 * @return a created PeerInfo object.
	 */
	public static PeerInfo create(Signature.KeyPair keypair, Id nodeId, int port) {
		return create(keypair, nodeId, null, port, null);
	}

	/**
	 * Creates a PeerInfo object with specified information. the new created PeerInfo will
	 * be signed by a new generated random key pair.
	 *
	 * @param nodeId the ID of the node providing the service peer.
	 * @param origin the node that announces the peer.
	 * @param port the port on which the peer is available.
	 * @return a created PeerInfo object.
	 */
	public static PeerInfo create(Id nodeId, Id origin, int port) {
		return create(null, nodeId, origin, port, null);
	}

	/**
	 * Creates a PeerInfo object with specified information and key pair.
	 *
	 * @param keypair the key pair key to sign the peer information.
	 * @param nodeId the ID of the node providing the service peer.
	 * @param origin the node that announces the peer.
	 * @param port the port on which the peer is available.
	 * @return a created PeerInfo object.
	 */
	public static PeerInfo create(Signature.KeyPair keypair, Id nodeId, Id origin, int port) {
		return create(keypair, nodeId, origin, port, null);
	}

	/**
	 * Creates a PeerInfo object with specified information. the new created PeerInfo will
	 * be signed by a new generated random key pair.
	 *
	 * @param nodeId the ID of the node providing the service peer.
	 * @param port the port on which the peer is available.
	 * @param alternativeURL an alternative URL for the peer.
	 * @return a created PeerInfo object.
	 */
	public static PeerInfo create(Id nodeId, int port, String alternativeURL) {
		return create(null, nodeId, null, port, alternativeURL);
	}

	/**
	 * Creates a PeerInfo object with specified information and key pair.
	 *
	 * @param keypair the key pair key to sign the peer information.
	 * @param nodeId the ID of the node providing the service peer.
	 * @param port the port on which the peer is available.
	 * @param alternativeURL an alternative URL for the peer.
	 * @return a created PeerInfo object.
	 */
	public static PeerInfo create(Signature.KeyPair keypair, Id nodeId, int port, String alternativeURL) {
		return create(keypair, nodeId, null, port, alternativeURL);
	}

	/**
	 * Creates a PeerInfo object with specified information. the new created PeerInfo will
	 * be signed by a new generated random key pair.
	 *
	 * @param nodeId the ID of the node providing the service peer.
	 * @param origin the node that announces the peer.
	 * @param port the port on which the peer is available.
	 * @param alternativeURL an alternative URL for the peer.
	 * @return a created PeerInfo object.
	 */
	public static PeerInfo create(Id nodeId, Id origin, int port, String alternativeURL) {
		return create(null, nodeId, origin, port, alternativeURL);
	}

	/**
	 * Creates a PeerInfo object with specified information and key pair.
	 *
	 * @param keypair the key pair key to sign the peer information.
	 * @param nodeId the ID of the node providing the service peer.
	 * @param origin the node that announces the peer.
	 * @param port the port on which the peer is available.
	 * @param alternativeURL an alternative URL for the peer.
	 * @return a created PeerInfo object.
	 */
	public static PeerInfo create(Signature.KeyPair keypair, Id nodeId, Id origin,
			int port, String alternativeURL) {
		if (keypair == null)
			keypair = Signature.KeyPair.random();

		return new PeerInfo(keypair, nodeId, origin, port, alternativeURL);
	}

	/**
	 * Gets the peer ID.
	 *
	 * @return The peer ID.
	 */
	public Id getId() {
		return publicKey;
	}

	/**
	 * Checks if the current node has the peer's private key.
	 *
	 * @return {@code true} if the node has the private key, {@code false} otherwise.
	 */
	public boolean hasPrivateKey() {
		return privateKey != null;
	}

	/**
	 * Gets the private key associated with the peer.
	 *
	 * @return The private key.
	 */
	public byte[] getPrivateKey() {
		return privateKey;
	}

	/**
	 * Gets the ID of the node providing the service peer.
	 *
	 * @return The node ID.
	 */
	public Id getNodeId() {
		return nodeId;
	}

	/**
	 * Gets the node that announces the peer.
	 *
	 * @return The origin node ID. null if the peer is not delegated.
	 */
	public Id getOrigin() {
		return origin;
	}

	/**
	 * Checks if the peer is delegated (announced by a different node).
	 *
	 * @return {@code true} if the peer is delegated, {@code false} otherwise.
	 */
	public boolean isDelegated() {
		return origin != null;
	}

	/**
	 * Gets the port on which the peer is available.
	 *
	 * @return The port number.
	 */
	public int getPort() {
		return port;
	}

	/**
	 * Gets the alternative URL for the peer.
	 *
	 * @return The alternative URL.
	 */
	public String getAlternativeURL() {
		return alternativeURL;
	}

	/**
	 * Checks if the peer has an alternative URL.
	 *
	 * @return {@code true} if the peer has an alternative URL, {@code false} otherwise.
	 */
	public boolean hasAlternativeURL() {
		return alternativeURL != null && !alternativeURL.isEmpty();
	}

	/**
	 * Gets the signature of the peer info.
	 *
	 * @return The signature.
	 */
	public byte[] getSignature() {
		return signature;
	}

	private byte[] getSignData() {
		// TODO: optimize with incremental digest, and return sha256 hash as sign input
		/*
		byte[] alt = alternativeURL == null || alternativeURL.isEmpty() ?
				null : alternativeURL.getBytes(UTF_8);

		byte[] toSign = new byte[Id.BYTES * 2 + Short.BYTES + (alt == null ? 0 : alt.length)];
		ByteBuffer buf = ByteBuffer.wrap(toSign);
		buf.put(nodeId.bytes())
			.put(origin.bytes())
			.putShort((short)port);
		if (alt != null)
			buf.put(alt);

		return toSign;
		*/

		MessageDigest sha = Hash.sha256();
		sha.update(publicKey.bytes());
		sha.update(nodeId.bytes());
		if (origin != null)
			sha.update(origin.bytes());
		sha.update(ByteBuffer.allocate(Short.BYTES).putShort((short)port).array());
		if (alternativeURL != null)
			sha.update(alternativeURL.getBytes(UTF_8));

		return sha.digest();
	}

	/**
	 * Checks if the PeerInfo object is valid, including checks for data integrity and
	 * signature verification.
	 *
	 * @return {@code true} if the value is valid, {@code false} otherwise.
	 */
	public boolean isValid() {
		if (signature == null || signature.length != Signature.BYTES)
			return false;

		Signature.PublicKey pk = publicKey.toSignatureKey();

		return Signature.verify(getSignData(), signature, pk);
	}

	@Override
	public int hashCode() {
		return 0x6030A + Objects.hash(publicKey, nodeId, origin, port, alternativeURL, Arrays.hashCode(signature));
	}

	@Override
	public boolean equals(Object o) {
		if (o == this)
			return true;

		if (o instanceof PeerInfo that) {
			return Objects.equals(this.publicKey, that.publicKey) &&
					Objects.equals(this.nodeId, that.nodeId) &&
					Objects.equals(this.origin, that.origin) &&
					this.port == that.port &&
					Objects.equals(this.alternativeURL, that.alternativeURL) &&
					Arrays.equals(this.signature, that.signature);
		}

		return false;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("<")
			.append(publicKey.toString()).append(',')
			.append(nodeId.toString()).append(',');
		if (isDelegated())
			sb.append(getOrigin().toString()).append(',');
		sb.append(port);
		if (hasAlternativeURL())
			sb.append(",").append(alternativeURL);
		sb.append(">");
		return sb.toString();
	}
}