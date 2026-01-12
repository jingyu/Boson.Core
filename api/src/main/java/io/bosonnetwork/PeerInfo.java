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
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import io.bosonnetwork.crypto.Hash;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.utils.Hex;
import io.bosonnetwork.json.Json;

/**
 * PeerInfo describes the service information published over the Boson DHT network.
 *
 * <p>PeerInfo has 2 types:
 * <ul>
 *     <li>Authenticated PeerInfo: The service peer is provided by a Boson DHT node, and includes the
 *     nodeId and node signature as proof.</li>
 *     <li>Regular PeerInfo: Without nodeId and node signature.</li>
 * </ul>
 *
 * <p>The PeerInfo is signed by the peer keypair, which is held by the service peer owner.
 *
 * <p>PeerInfo is uniquely identified by the peer ID (public key) and fingerprint.
 */

public class PeerInfo {
	/** The number of bytes in the nonce. */
	public static int NONCE_BYTES = 24;

	/** Attribute key to omit the peer ID in the peer info used in JsonContext. */
	public static final Object ATTRIBUTE_OMIT_PEER_ID = new Object();
	/** Attribute key of the peer ID used in JsonContext. */
	public static final Object ATTRIBUTE_PEER_ID = new Object();

	/** The peer ID. */
	private final Id publicKey;
	/** The private key to sign the peer info. */
	private final byte[] privateKey;
	/** The nonce. */
	private final byte[] nonce;
	/** The sequence number. */
	private final int sequenceNumber;
	/** Optional: The node that provides the peer. */
	private final Id nodeId;
	/** Optional: Signature of the node, mandatory if nodeId is present. */
	private final byte[] nodeSig;
	/** The signature. */
	private final byte[] signature;
	/** Unique fingerprint number for the peer with same peer id. */
	private final long fingerprint;
	/** The service endpoint URI of the peer. */
	private final String endpoint;
	/** The extra data in binary format. */
	private final byte[] extraData;

	private transient Map<String, Object> extra;

	private PeerInfo(Id peerId, byte[] privateKey, byte[] nonce, int sequenceNumber, Id nodeId, byte[] nodeSig,
					 byte[] signature, long fingerprint, String endpoint, byte[] extraData) {
		this.publicKey = peerId;
		this.privateKey = privateKey;
		this.nonce = nonce;
		this.sequenceNumber = sequenceNumber;
		this.nodeId = nodeId;
		this.nodeSig = nodeSig;
		this.signature = signature;
		this.fingerprint = fingerprint;
		this.endpoint = endpoint;
		this.extraData = extraData;
	}

	/**
	 * Creates a new PeerInfo instance from the existing peer information.
	 *
	 * @param peerId         The peer ID.
	 * @param nonce          The nonce.
	 * @param sequenceNumber The sequence number.
	 * @param nodeId         The node ID (optional).
	 * @param nodeSig        The node signature (optional).
	 * @param signature      The signature.
	 * @param fingerprint    The fingerprint.
	 * @param endpoint       The endpoint.
	 * @param extraData      The extra data.
	 * @return The new PeerInfo instance.
	 */
	public static PeerInfo of(Id peerId, byte[] nonce, int sequenceNumber, Id nodeId, byte[] nodeSig,
							  byte[] signature, long fingerprint, String endpoint, byte[] extraData) {
		return of(peerId, null, nonce, sequenceNumber, nodeId, nodeSig, signature, fingerprint, endpoint, extraData);
	}

	/**
	 * Creates a new PeerInfo instance from the existing peer information.
	 *
	 * @param peerId         The peer ID.
	 * @param privateKey     The private key (optional).
	 * @param nonce          The nonce.
	 * @param sequenceNumber The sequence number.
	 * @param nodeId         The node ID (optional).
	 * @param nodeSig        The node signature (optional).
	 * @param signature      The signature.
	 * @param fingerprint    The fingerprint.
	 * @param endpoint       The endpoint.
	 * @param extraData      The extra data.
	 * @return The new PeerInfo instance.
	 */
	public static PeerInfo of(Id peerId, byte[] privateKey, byte[] nonce, int sequenceNumber, Id nodeId, byte[] nodeSig,
							  byte[] signature, long fingerprint, String endpoint, byte[] extraData) {
		if (peerId == null)
			throw new IllegalArgumentException("Invalid peer id");

		// noinspection DuplicatedCode
		if (privateKey != null && privateKey.length != Signature.PrivateKey.BYTES)
			throw new IllegalArgumentException("Invalid private key");

		if (nonce == null || nonce.length != NONCE_BYTES)
			throw new IllegalArgumentException("Invalid nonce");

		if (sequenceNumber < 0)
			throw new IllegalArgumentException("Invalid sequence number");

		if (nodeId != null) {
			if (nodeSig == null || nodeSig.length != Signature.BYTES)
				throw new IllegalArgumentException("Invalid node signature");
		} else {
			if (nodeSig != null)
				throw new IllegalArgumentException("Invalid node signature, should be null if nodeId is null");
		}

		if (signature == null || signature.length != Signature.BYTES)
			throw new IllegalArgumentException("Invalid signature");

		if (endpoint == null || endpoint.isEmpty())
			throw new IllegalArgumentException("Invalid endpoint");

		return new PeerInfo(peerId, privateKey, nonce, sequenceNumber, nodeId, nodeSig, signature, fingerprint, endpoint, extraData);
	}

	/**
	 * Creates a new PeerInfo builder.
	 *
	 * @return a new PeerInfo builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Helper method to create a new PeerInfo instance with common logic.
	 *
	 * @param keypair        The peer keypair.
	 * @param node           The node identity (optional).
	 * @param sequenceNumber The sequence number.
	 * @param fingerprint    The fingerprint.
	 * @param endpoint       The endpoint.
	 * @param extraData      The extra data.
	 * @return The new PeerInfo instance.
	 */
	private static PeerInfo create(Signature.KeyPair keypair, Identity node, int sequenceNumber,
								   long fingerprint, String endpoint, byte[] extraData) {
		byte[] nonce = new byte[NONCE_BYTES];
		Random.secureRandom().nextBytes(nonce);

		Id peerId = Id.of(keypair.publicKey().bytes());
		Id nodeId;
		byte[] nodeSig;
		if (node != null) {
			nodeId = node.getId();
			byte[] digest = Hash.sha256(peerId.bytes(), nodeId.bytes(), nonce);
			nodeSig = node.sign(digest);
		} else {
			nodeId = null;
			nodeSig = null;
		}

		byte[] digest = new PeerInfo(peerId, null, nonce, sequenceNumber,
				nodeId, nodeSig, null, fingerprint, endpoint, extraData).digest();
		byte[] sig = Signature.sign(digest, keypair.privateKey());

		return new PeerInfo(peerId, keypair.privateKey().bytes(), nonce, sequenceNumber,
				nodeId, nodeSig, sig, fingerprint, endpoint, extraData);
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
	 * Gets the nonce.
	 *
	 * @return the nonce
	 */
	public byte[] getNonce() {
		return nonce;
	}

	/**
	 * Gets the sequence number.
	 *
	 * @return the sequence number
	 */
	public int getSequenceNumber() {
		return sequenceNumber;
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
	 * Gets the node signature.
	 *
	 * @return the node signature
	 */
	public byte[] getNodeSignature() {
		return nodeSig;
	}

	/**
	 * Checks if the peer info is authenticated.
	 *
	 * <p>Authenticated means the service peer provided by a Boson DHT node, and include the nodeId and
	 * node signature to proof.
	 *
	 * @return {@code true} if the peer info is authenticated, {@code false} otherwise.
	 */
	public boolean isAuthenticated() {
		return nodeId != null && nodeSig != null;
	}

	/**
	 * Gets the signature of the peer info.
	 *
	 * @return The signature.
	 */
	public byte[] getSignature() {
		return signature;
	}

	/**
	 * Gets the fingerprint.
	 *
	 * @return the fingerprint
	 */
	public long getFingerprint() {
		return fingerprint;
	}

	/**
	 * Gets the endpoint.
	 *
	 * @return the endpoint
	 */
	public String getEndpoint() {
		return endpoint;
	}

	/**
	 * Checks if the extra data is present.
	 *
	 * @return {@code true} if the extra data is present, {@code false} otherwise.
	 */
	public boolean hasExtra() {
		return extraData != null && extraData.length > 0;
	}

	/**
	 * Gets the extra data.
	 *
	 * @return the extra data
	 */
	public byte[] getExtraData() {
		return extraData;
	}

	/**
	 * Gets the extra data as a map.
	 *
	 * @return the extra data map
	 */
	public Map<String, Object> getExtra() {
		if (extra == null) {
			if (hasExtra()) {
				try {
					extra = Collections.unmodifiableMap(Json.parse(extraData));
				} catch (Exception e) {
					throw new IllegalStateException("Invalid extra data", e);
				}
			} else {
				extra = Collections.emptyMap();
			}
		}

		return extra;
	}

	/**
	 * Computes the digest for signing the peer info.
	 *
	 * @return the digest
	 */
	private byte[] digest() {
		MessageDigest sha = Hash.sha256();
		sha.update(publicKey.bytes());
		sha.update(nonce);
		sha.update(ByteBuffer.allocate(Integer.BYTES).putInt(sequenceNumber).array());
		if (nodeId != null) {
			sha.update(nodeId.bytes());
			sha.update(nodeSig);
		}
		sha.update(ByteBuffer.allocate(Long.BYTES).putLong(fingerprint).array());
		sha.update(endpoint.getBytes(UTF_8));
		if (extraData != null)
			sha.update(extraData);
		return sha.digest();
	}

	/**
	 * Checks if the PeerInfo object is valid.
	 *
	 * <p>This method performs the following checks:
	 * <ul>
	 *     <li>Data integrity checks (non-null fields where expected).</li>
	 *     <li>Signature verification:
	 *         <ul>
	 *             <li>If authenticated (nodeId present), verifies the node signature against the node ID.</li>
	 *             <li>Verifies the peer signature against the peer public key.</li>
	 *         </ul>
	 *     </li>
	 * </ul>
	 *
	 * @return {@code true} if the value is valid, {@code false} otherwise.
	 */
	public boolean isValid() {
		if (signature == null || signature.length != Signature.BYTES)
			return false;

		if (nonce == null || nonce.length != NONCE_BYTES)
			return false;

		if (nodeId != null) {
			if (nodeSig == null || nodeSig.length != Signature.BYTES)
				return false;

			Signature.PublicKey nodePk = nodeId.toSignatureKey();
			byte[] digest = Hash.sha256(publicKey.getBytes(), nodeId.bytes(), nonce);
			if (!Signature.verify(digest, nodeSig, nodePk))
				return false;
		} else {
			if (nodeSig != null)
				return false;
		}

		Signature.PublicKey peerPk = publicKey.toSignatureKey();
		return Signature.verify(digest(), signature, peerPk);
	}

	/**
	 * Returns a new PeerInfo instance with the same properties as the current instance,
	 * but with the private key set to null. If the current instance already has a null
	 * private key, it returns the current instance itself.
	 *
	 * @return a new PeerInfo instance without a private key, or the current instance if the private key is already null.
	 */
	public PeerInfo withoutPrivateKey() {
		if (privateKey == null)
			return this;

		return new PeerInfo(publicKey, null, nonce, sequenceNumber, nodeId, nodeSig, signature, fingerprint, endpoint, extraData);
	}

	/**
	 * Updates the endpoint of the peer info.
	 *
	 * <p>This method creates a new PeerInfo instance with the updated endpoint and an incremented sequence number.
	 * The new instance will be signed with the private key held by this instance.
	 *
	 * @param endpoint The new endpoint.
	 * @return The updated PeerInfo instance.
	 */
	public PeerInfo update(String endpoint) {
		return update(null, endpoint, this.extraData);
	}

	/**
	 * Updates the endpoint and extra data of the peer info.
	 *
	 * <p>This method creates a new PeerInfo instance with the updated endpoint/extra data and an incremented sequence number.
	 * The new instance will be signed with the private key held by this instance.
	 *
	 * @param endpoint  The new endpoint.
	 * @param extraData The new extra data.
	 * @return The updated PeerInfo instance.
	 */
	public PeerInfo update(String endpoint, byte[] extraData) {
		return update(null, endpoint, extraData);
	}

	/**
	 * Updates the endpoint and extra data of the peer info.
	 *
	 * <p>This method creates a new PeerInfo instance with the updated endpoint/extra data and an incremented sequence number.
	 * The new instance will be signed with the private key held by this instance.
	 *
	 * @param endpoint The new endpoint.
	 * @param extra    The new extra data map.
	 * @return The updated PeerInfo instance.
	 */
	public PeerInfo update(String endpoint, Map<String, Object> extra) {
		byte[] extraData = extra != null && !extra.isEmpty() ? Json.toBytes(extra) : null;
		return update(null, endpoint, extraData);
	}

	/**
	 * Updates the endpoint of the peer info, optionally adding node authentication.
	 *
	 * <p>This method creates a new PeerInfo instance with the updated endpoint and an incremented sequence number.
	 * The new instance will be signed with the private key held by this instance.
	 *
	 * @param node     The node identity for authentication (optional).
	 * @param endpoint The new endpoint.
	 * @return The updated PeerInfo instance.
	 */
	public PeerInfo update(Identity node, String endpoint) {
		return update(node, endpoint, this.extraData);
	}

	/**
	 * Updates the endpoint and extra data of the peer info, optionally adding node authentication.
	 *
	 * <p>This method creates a new PeerInfo instance with the updated endpoint/extra data and an incremented sequence number.
	 * The new instance will be signed with the private key held by this instance.
	 *
	 * @param node     The node identity for authentication (optional).
	 * @param endpoint The new endpoint.
	 * @param extra    The new extra data map.
	 * @return The updated PeerInfo instance.
	 */
	public PeerInfo update(Identity node, String endpoint, Map<String, Object> extra) {
		byte[] extraData = extra != null && !extra.isEmpty() ? Json.toBytes(extra) : null;
		return update(node, endpoint, extraData);
	}

	/**
	 * Updates the endpoint and extra data of the peer info, optionally adding node authentication.
	 *
	 * <p>This method creates a new PeerInfo instance with the updated endpoint/extra data and an incremented sequence number.
	 * The new instance will be signed with the private key held by this instance.
	 *
	 * @param node      The node identity for authentication (optional).
	 * @param endpoint  The new endpoint.
	 * @param extraData The new extra data.
	 * @return The updated PeerInfo instance.
	 * @throws UnsupportedOperationException If this instance does not hold the private key.
	 * @throws IllegalArgumentException      If the endpoint is invalid or if attempting to change the authenticating node ID.
	 */
	public PeerInfo update(Identity node, String endpoint, byte[] extraData) {
		if (privateKey == null)
			throw new UnsupportedOperationException("Not the owner of the peer info");

		if (endpoint == null || endpoint.isEmpty())
			throw new IllegalArgumentException("Invalid endpoint");

		if (this.nodeId != null) {
			if (node == null)
				throw new UnsupportedOperationException("Cannot update node authenticated peer info without the owner node");
			if (!node.getId().equals(this.nodeId))
				throw new IllegalArgumentException("Cannot update node authenticated peer info with a different node");
		}

		String _endpoint = Normalizer.normalize(endpoint, Normalizer.Form.NFC);
		byte[] _extraData = extraData == null || extraData.length == 0 ? null : extraData;
		Id nodeId = node != null ? node.getId() : null;

		if (_endpoint.equals(this.endpoint) && Objects.equals(this.nodeId, nodeId) && Arrays.equals(this.extraData, _extraData))
			return this; // nothing to update

		int sequenceNumber = this.sequenceNumber + 1;
		Signature.KeyPair keyPair = Signature.KeyPair.fromPrivateKey(this.privateKey);
		return create(keyPair, node, sequenceNumber, fingerprint, _endpoint, _extraData);
	}

	/**
	 * Returns a hash code value for the object.
	 *
	 * @return a hash code value for this object.
	 */
	@Override
	public int hashCode() {
		return 0x6030A + Objects.hash(publicKey, Arrays.hashCode(nonce), sequenceNumber, nodeId,
				Arrays.hashCode(nodeSig), Arrays.hashCode(signature), fingerprint, endpoint, Arrays.hashCode(extraData));
	}

	/**
	 * Indicates whether some other object is "equal to" this one.
	 *
	 * @param o the reference object with which to compare.
	 * @return {@code true} if this object is the same as the obj argument; {@code false} otherwise.
	 */
	@Override
	public boolean equals(Object o) {
		if (o == this)
			return true;

		if (o instanceof PeerInfo that) {
			return Objects.equals(this.publicKey, that.publicKey) &&
					Arrays.equals(this.nonce, that.nonce) &&
					this.sequenceNumber == that.sequenceNumber &&
					Objects.equals(this.nodeId, that.nodeId) &&
					Arrays.equals(this.nodeSig, that.nodeSig) &&
					Arrays.equals(this.signature, that.signature) &&
					this.fingerprint == that.fingerprint &&
					Objects.equals(this.endpoint, that.endpoint) &&
					Arrays.equals(this.extraData, that.extraData);
		}

		return false;
	}

	/**
	 * Returns a string representation of the object.
	 *
	 * @return a string representation of the object.
	 */
	@Override
	public String toString() {
		StringBuilder repr = new StringBuilder();
		repr.append("id:").append(publicKey)
				.append(",endpoint:").append(endpoint);
		if (hasExtra()) {
			if (extra != null)
				repr.append(",extra:").append(extra);
			else
				repr.append(",extra:").append(Hex.encode(extraData));
		}

		if (fingerprint != 0)
			repr.append(",sn:").append(fingerprint);

		if (sequenceNumber > 0)
			repr.append(",seq:").append(sequenceNumber);

		if (nodeId != null) {
			repr.append(",nodeId:").append(nodeId);
			repr.append(",nodeSig:").append(Hex.encode(nodeSig));
		}
		repr.append(",sig:").append(Hex.encode(signature));
		return repr.toString();
	}

	/**
	 * PeerInfo builder.
	 */
	public static class Builder {
		private Signature.KeyPair keyPair = null;
		private int sequenceNumber = 0;
		private Identity node = null;
		private long fingerprint = 0;
		private String endpoint = null;
		private byte[] extraData = null;

		private Builder() {
		}

		/**
		 * Sets the endpoint for the peer info.
		 *
		 * @param endpoint the endpoint
		 * @return the builder instance
		 * @throws IllegalArgumentException if the endpoint is null or empty
		 */
		public Builder endpoint(String endpoint) {
			if (endpoint == null || endpoint.isEmpty())
				throw new IllegalArgumentException("Endpoint cannot be null or empty");
			this.endpoint = Normalizer.normalize(endpoint, Normalizer.Form.NFC);
			return this;
		}

		/**
		 * Sets the extra data for the peer info.
		 *
		 * @param extra the extra data map
		 * @return the builder instance
		 */
		public Builder extra(Map<String, Object> extra) {
			this.extraData = extra != null && !extra.isEmpty() ? Json.toBytes(extra) : null;
			return this;
		}

		/**
		 * Sets the extra data for the peer info.
		 *
		 * @param extra the extra data
		 * @return the builder instance
		 */
		public Builder extra(byte[] extra) {
			this.extraData = extra;
			return this;
		}

		/**
		 * Sets the fingerprint for the peer info.
		 *
		 * @param fingerprint the fingerprint
		 * @return the builder instance
		 */
		public Builder fingerprint(long fingerprint) {
			this.fingerprint = fingerprint;
			return this;
		}

		/**
		 * Sets the node identity for authentication.
		 *
		 * @param node the node identity
		 * @return the builder instance
		 */
		public Builder node(Identity node) {
			this.node = node;
			return this;
		}

		/**
		 * Sets the sequence number for the peer info.
		 *
		 * @param sequenceNumber the sequence number
		 * @return the builder instance
		 * @throws IllegalArgumentException if the sequence number is negative
		 */
		public Builder sequenceNumber(int sequenceNumber) {
			if (sequenceNumber < 0)
				throw new IllegalArgumentException("Invalid sequence number");
			this.sequenceNumber = sequenceNumber;
			return this;
		}

		/**
		 * Sets the keypair for the peer info.
		 *
		 * @param keyPair the keypair
		 * @return the builder instance
		 */
		public Builder key(Signature.KeyPair keyPair) {
			this.keyPair = keyPair;
			return this;
		}

		/**
		 * Sets the private key for the peer info.
		 *
		 * @param privateKey the private key
		 * @return the builder instance
		 * @throws NullPointerException if the private key is null
		 */
		public Builder key(Signature.PrivateKey privateKey) {
			Objects.requireNonNull(privateKey);
			this.keyPair = Signature.KeyPair.fromPrivateKey(privateKey);
			return this;
		}

		/**
		 * Sets the private key for the peer info.
		 *
		 * @param privateKey the private key bytes
		 * @return the builder instance
		 * @throws NullPointerException if the private key is null
		 * @throws IllegalArgumentException if the private key length is invalid
		 */
		public Builder key(byte[] privateKey) {
			Objects.requireNonNull(privateKey);
			if (privateKey.length != Signature.PrivateKey.BYTES)
				throw new IllegalArgumentException("Invalid private key");
			this.keyPair = Signature.KeyPair.fromPrivateKey(privateKey);
			return this;
		}

		/**
		 * Builds the PeerInfo instance.
		 *
		 * @return the created PeerInfo instance
		 * @throws IllegalStateException if the endpoint is missing
		 */
		public PeerInfo build() {
			if (endpoint == null || endpoint.isEmpty())
				throw new IllegalStateException("Missing endpoint");

			if (keyPair == null)
				keyPair = Signature.KeyPair.random();

			if (fingerprint == 0)
				fingerprint = Random.secureRandom().nextLong();

			return PeerInfo.create(keyPair, node, sequenceNumber, fingerprint, endpoint, extraData);
		}
	}
}