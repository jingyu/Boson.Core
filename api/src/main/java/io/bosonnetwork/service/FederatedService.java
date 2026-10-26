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


package io.bosonnetwork.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.net.ssl.X509ExtendedTrustManager;

import org.jspecify.annotations.Nullable;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.crypto.HybridTrustManager;
import io.bosonnetwork.json.Json;
import io.bosonnetwork.web.client.SelfIssuedAccessTokens;

/**
 * A service of another node, verified: its peer announcement carries the node's attestation, so the
 * service belongs to {@link #nodeId()} as surely as the node's own signature can say.
 * <p>
 * The endpoint and the extra data are the service's own, from its announcement, and the freshest there
 * are. The type and the name come from the node's {@link NodeDescriptor descriptor}, and are absent when
 * the descriptor wasn't consulted or doesn't list the service.
 * <p>
 * It is also the principal of a request authenticated through
 * {@link FederationContext#getWebAuthenticator()}.
 *
 * @param peerId         the service's peer id
 * @param nodeId         the node it belongs to
 * @param serviceType    the service type id ({@code io.bosonnetwork.photonmessaging}, ...), if known
 * @param serviceName    the service's display name, if known
 * @param endpoint       the endpoint it announces
 * @param extra          the extra data it announces, an unmodifiable map, empty if none
 * @param fingerprint    the fingerprint of the announcement
 * @param sequenceNumber the sequence number of the announcement
 */
public record FederatedService(Id peerId, Id nodeId, @Nullable String serviceType, @Nullable String serviceName,
							   String endpoint, Map<String, Object> extra, long fingerprint, int sequenceNumber)
		implements Principal {
	/**
	 * Creates a service.
	 *
	 * @param peerId         the service's peer id
	 * @param nodeId         the node it belongs to
	 * @param serviceType    the service type id, if known
	 * @param serviceName    the service's display name, if known
	 * @param endpoint       the endpoint it announces
	 * @param extra          the extra data it announces, empty if none
	 * @param fingerprint    the fingerprint of the announcement
	 * @param sequenceNumber the sequence number of the announcement
	 */
	public FederatedService {
		Objects.requireNonNull(peerId, "peerId");
		Objects.requireNonNull(nodeId, "nodeId");
		Objects.requireNonNull(endpoint, "endpoint");
		extra = Map.copyOf(Objects.requireNonNull(extra, "extra"));
	}

	/**
	 * Creates the service a peer announcement describes.
	 *
	 * @param peer        the announcement; must carry its node's attestation
	 * @param serviceType the service type id, if known
	 * @param serviceName the service's display name, if known
	 * @return the service
	 * @throws IllegalArgumentException if the announcement carries no node attestation
	 */
	public static FederatedService of(PeerInfo peer, @Nullable String serviceType, @Nullable String serviceName) {
		Objects.requireNonNull(peer, "peer");
		Id nodeId = peer.getNodeId();
		if (nodeId == null || !peer.isAuthenticated())
			throw new IllegalArgumentException("Peer " + peer.getId() + " is not attested by a node");

		Map<String, Object> extra = Map.of();
		byte[] data = peer.getExtraData();
		if (data != null && data.length > 0) {
			try {
				extra = Json.parse(data);
			} catch (Exception e) {
				// Extra data that isn't a CBOR map is the service's own business; there is nothing to show.
			}
		}

		return new FederatedService(peer.getId(), nodeId, serviceType, serviceName, peer.getEndpoint(), extra,
				peer.getFingerprint(), peer.getSequenceNumber());
	}

	/**
	 * Returns the service that one peer's announcements show: only announcements that carry a node's
	 * attestation count, and the newest of them wins. If they name different nodes, none is believed.
	 *
	 * @param announcements the peer's announcements, as a DHT lookup returns them
	 * @return the service, or empty if no announcement is attested or they disagree on the node; tell
	 *         {@link Admission.Reason#UNKNOWN_PEER UNKNOWN_PEER} (no announcements) from
	 *         {@link Admission.Reason#UNVERIFIED_PEER UNVERIFIED_PEER} by whether there were any
	 */
	public static Optional<FederatedService> fromAnnouncements(List<PeerInfo> announcements) {
		Objects.requireNonNull(announcements, "announcements");

		PeerInfo newest = null;
		Id nodeId = null;
		for (PeerInfo p : announcements) {
			if (!p.isAuthenticated() || !p.isValid())
				continue;

			if (nodeId == null)
				nodeId = p.getNodeId();
			else if (!nodeId.equals(p.getNodeId()))
				return Optional.empty();

			if (newest == null || p.getSequenceNumber() > newest.getSequenceNumber())
				newest = p;
		}

		return newest == null ? Optional.empty() : Optional.of(of(newest, null, null));
	}

	/**
	 * Returns a trust manager that accepts this service's TLS certificate: a self-signed one only if it is
	 * bound to the service's peer id, any other as the platform verifies it.
	 *
	 * @return the trust manager
	 */
	public X509ExtendedTrustManager trustManager() {
		return new HybridTrustManager(peerId.toString(), peerId.bytesUnsafe());
	}

	/**
	 * Returns a builder of the access tokens a local service presents to this one: issued by the local
	 * service, for the local node as subject, with the local service as client id, the federation scope,
	 * and this service as audience. Set the lifetime and the logger, then build.
	 *
	 * @param localService the local service's identity, which signs the tokens
	 * @param localNodeId  the local node's id
	 * @return the preset builder
	 */
	public SelfIssuedAccessTokens.Builder accessTokens(Identity localService, Id localNodeId) {
		return SelfIssuedAccessTokens.builder(localService)
				.subject(localNodeId)
				.clientId(localService.getId())
				.scope(AccessScope.FEDERATION)
				.audience(peerId);
	}
}
