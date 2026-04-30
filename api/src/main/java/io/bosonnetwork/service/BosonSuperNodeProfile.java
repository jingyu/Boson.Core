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

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.identifier.Card;
import io.bosonnetwork.identifier.CardBuilder;
import io.bosonnetwork.identifier.Credential;

/**
 * Represents a profile for a super node in the Boson network.
 * A super node profile includes information such as name, logo, website, and contact info,
 * as well as the various services it provides (API, Web Gateway, Ion Store, etc.).
 */
public class BosonSuperNodeProfile {
	private static final String DEFAULT_PROFILE_CREDENTIAL_ID = "profile";
	private static final String DEFAULT_PROFILE_CREDENTIAL_TYPE = "BosonSuperNodeProfile";

	private static final String SUPER_NODE_API_SERVICE_TYPE = "io.bosonnetwork.supernodeapi";
	private static final String WEB_GATEWAY_SERVICE_TYPE = "io.bosonnetwork.webgateway";
	private static final String ION_STORE_SERVICE_TYPE = "io.bosonnetwork.ionstore";
	private static final String PHOTON_MESSAGING_SERVICE_TYPE = "io.bosonnetwork.photonmessaging";
	private static final String ACTIVE_PROXY_SERVICE_TYPE = "io.bosonnetwork.activeproxy";

	private final Id nodeId;
	private final String name;
	private final String logo;
	private final String website;
	private final String contact;

	private final Card card;

	/**
	 * Creates a new super node profile.
	 *
	 * @param nodeId the node's identifier
	 * @param name the node's name
	 * @param logo the node's logo URL or identifier
	 * @param website the node's website URL
	 * @param contact the node's contact information
	 * @param card the underlying card object
	 */
	private BosonSuperNodeProfile(Id nodeId, String name, String logo, String website, String contact, Card card) {
		this.nodeId = nodeId;
		this.name = name;
		this.logo = logo;
		this.website = website;
		this.contact = contact;
		this.card = card;
	}

	/**
	 * Creates a {@code BosonSuperNodeProfile} from a {@link Card}.
	 *
	 * @param card the card to extract super node profile information from; must not be null
	 * @return a new super node profile instance
	 * @throws NullPointerException if card is null
	 */
	public static BosonSuperNodeProfile fromCard(Card card) {
		Objects.requireNonNull(card);

		Id id = card.getId();

		String name = null;
		String logo = null;
		String website = null;
		String contact = null;
		Credential profile = card.getCredential(DEFAULT_PROFILE_CREDENTIAL_ID);
		if (profile != null && profile.getTypes().contains(DEFAULT_PROFILE_CREDENTIAL_TYPE)) {
			Map<String, Object> claims = profile.getSubject().getClaims();
			name = String.valueOf(claims.get("name"));
			logo = String.valueOf(claims.get("logo"));
			website = String.valueOf(claims.get("website"));
			contact = String.valueOf(claims.get("contact"));
		}

		return new BosonSuperNodeProfile(id, name, logo, website, contact, card);
	}

	/**
	 * Returns the identifier of the super node.
	 *
	 * @return the node id
	 */
	public Id getNodeId() {
		return nodeId;
	}

	/**
	 * Returns the name of the super node.
	 *
	 * @return the name, or null if not set
	 */
	public String getName() {
		return name;
	}

	/**
	 * Returns the logo of the super node.
	 *
	 * @return the logo string, or null if not set
	 */
	public String getLogo() {
		return logo;
	}

	/**
	 * Returns the website of the super node.
	 *
	 * @return the website URL, or null if not set
	 */
	public String getWebsite() {
		return website;
	}

	/**
	 * Returns the contact information of the super node.
	 *
	 * @return the contact info, or null if not set
	 */
	public String getContact() {
		return contact;
	}

	/**
	 * Returns the service associated with the specified peer identifier.
	 *
	 * @param servicePeerId the peer id of the service
	 * @return the service, or null if not found
	 * @throws NullPointerException if servicePeerId is null
	 */
	public Card.Service getService(Id servicePeerId) {
		Objects.requireNonNull(servicePeerId);
		return card.getService(servicePeerId.toBase58String());
	}

	/**
	 * Returns a list of services of the specified type.
	 *
	 * @param type the service type to filter by
	 * @return a list of services, never null
	 * @throws NullPointerException if type is null
	 */
	public List<Card.Service> getServices(String type) {
		Objects.requireNonNull(type);
		return card.getServices(type);
	}

	/**
	 * Returns an unmodifiable list of all services associated with this profile.
	 *
	 * @return list of services
	 */
	public List<Card.Service> getServices() {
		return card.getServices();
	}

	/**
	 * Returns the API service for this super node.
	 *
	 * @return the API service, or null if not found
	 */
	public Card.Service getApiService() {
		// The API service for a super node is identified by the same ID as the node's unique identifier.
		return card.getService(nodeId.toBase58String());
	}

	/**
	 * Returns the list of Web Gateway services provided by this super node.
	 *
	 * @return list of web gateway services
	 */
	public List<Card.Service> getWebGatewayServices() {
		return card.getServices(WEB_GATEWAY_SERVICE_TYPE);
	}

	/**
	 * Returns the list of Ion Store services provided by this super node.
	 *
	 * @return list of ion store services
	 */
	public List<Card.Service> getIonStoreServices() {
		return card.getServices(ION_STORE_SERVICE_TYPE);
	}

	/**
	 * Returns the list of Photon Messaging services provided by this super node.
	 *
	 * @return list of photon messaging services
	 */
	public List<Card.Service> getPhotonMessagingServices() {
		return card.getServices(PHOTON_MESSAGING_SERVICE_TYPE);
	}

	/**
	 * Returns the list of Active Proxy services provided by this super node.
	 *
	 * @return list of active proxy services
	 */
	public List<Card.Service> getActiveProxyServices() {
		return card.getServices(ACTIVE_PROXY_SERVICE_TYPE);
	}

	/**
	 * Returns the underlying {@link Card} representing this super node profile.
	 *
	 * @return the card
	 */
	public Card getCard() {
		return card;
	}

	/**
	 * Creates a new builder for creating a {@code BosonSuperNodeProfile}.
	 *
	 * @param identity the identity to associate with the profile
	 * @return a new builder instance
	 */
	public static Builder builder(Identity identity) {
		return new Builder(identity);
	}

	/**
	 * Builder class for {@link BosonSuperNodeProfile}.
	 */
	public static class Builder {
		private final Identity identity;
		private String name;
		private String logo;
		private String website;
		private String contact;

		private final CardBuilder cardBuilder;

		private Builder(Identity identity) {
			this.identity = identity;
			this.cardBuilder = Card.builder(identity);
		}

		/**
		 * Sets the super node's name.
		 *
		 * @param name the name of the super node
		 * @return the builder instance
		 */
		public Builder name(String name) {
			this.name = name;
			return this;
		}

		/**
		 * Sets the super node's logo.
		 *
		 * @param logo the logo URL or identifier for the super node
		 * @return the builder instance
		 */
		public Builder logo(String logo) {
			this.logo = logo;
			return this;
		}

		/**
		 * Sets the super node's website.
		 *
		 * @param website the website URL of the super node
		 * @return the builder instance
		 */
		public Builder website(String website) {
			this.website = website;
			return this;
		}

		/**
		 * Sets the super node's contact information.
		 *
		 * @param contact the contact information (e.g., email or handle)
		 * @return the builder instance
		 */
		public Builder contact(String contact) {
			this.contact = contact;
			return this;
		}

		/**
		 * Adds a super node API service to the profile.
		 * The service ID will be derived from the node's identifier.
		 *
		 * @param endpoint the service endpoint URI
		 * @return the builder instance
		 * @throws NullPointerException if endpoint is null
		 */
		public Builder apiService(URI endpoint) {
			Objects.requireNonNull(endpoint);
			cardBuilder.addService(identity.getId().toBase58String(), SUPER_NODE_API_SERVICE_TYPE, endpoint.toString());
			return this;
		}

		/**
		 * Adds a Web Gateway service to the profile.
		 *
		 * @param peerId the peer identifier for the gateway service
		 * @param endpoint the service endpoint URI
		 * @return the builder instance
		 * @throws NullPointerException if peerId or endpoint is null
		 */
		public Builder webGatewayService(Id peerId, URI endpoint) {
			Objects.requireNonNull(peerId);
			Objects.requireNonNull(endpoint);
			cardBuilder.addService(peerId.toBase58String(), WEB_GATEWAY_SERVICE_TYPE, endpoint.toString());
			return this;
		}

		/**
		 * Adds an Ion Store service to the profile.
		 *
		 * @param peerId the peer identifier for the store service
		 * @param endpoint the service endpoint URI
		 * @return the builder instance
		 * @throws NullPointerException if peerId or endpoint is null
		 */
		public Builder ionStoreService(Id peerId, URI endpoint) {
			Objects.requireNonNull(peerId);
			Objects.requireNonNull(endpoint);
			cardBuilder.addService(peerId.toBase58String(), ION_STORE_SERVICE_TYPE, endpoint.toString());
			return this;
		}

		/**
		 * Adds a Photon Messaging service to the profile.
		 *
		 * @param peerId the peer identifier for the messaging service
		 * @param endpoint the service endpoint URI
		 * @return the builder instance
		 * @throws NullPointerException if peerId or endpoint is null
		 */
		public Builder photonMessagingService(Id peerId, URI endpoint) {
			Objects.requireNonNull(peerId);
			Objects.requireNonNull(endpoint);
			cardBuilder.addService(peerId.toBase58String(), PHOTON_MESSAGING_SERVICE_TYPE, endpoint.toString());
			return this;
		}

		/**
		 * Adds an Active Proxy service to the profile.
		 *
		 * @param peerId the peer identifier for the proxy service
		 * @param endpoint the service endpoint URI
		 * @return the builder instance
		 * @throws NullPointerException if peerId or endpoint is null
		 */
		public Builder activeProxyService(Id peerId, URI endpoint) {
			Objects.requireNonNull(peerId);
			Objects.requireNonNull(endpoint);
			cardBuilder.addService(peerId.toBase58String(), ACTIVE_PROXY_SERVICE_TYPE, endpoint.toString());
			return this;
		}

		/**
		 * Adds a custom service to the profile.
		 *
		 * @param peerId the peer identifier for the service
		 * @param type the service type identifier
		 * @param endpoint the service endpoint URI
		 * @return the builder instance
		 * @throws NullPointerException if peerId, type, or endpoint is null
		 */
		public Builder service(Id peerId, String type, URI endpoint) {
			Objects.requireNonNull(peerId);
			Objects.requireNonNull(type);
			Objects.requireNonNull(endpoint);
			cardBuilder.addService(peerId.toBase58String(), type, endpoint.toString());
			return this;
		}

		/**
		 * Adds a custom service with additional properties to the profile.
		 *
		 * @param peerId the peer identifier for the service
		 * @param type the service type identifier
		 * @param endpoint the service endpoint URI
		 * @param properties a map of additional service properties
		 * @return the builder instance
		 * @throws NullPointerException if peerId, type, or endpoint is null
		 */
		public Builder service(Id peerId, String type, URI endpoint, Map<String, Object> properties) {
			Objects.requireNonNull(peerId);
			Objects.requireNonNull(type);
			Objects.requireNonNull(endpoint);
			cardBuilder.addService(peerId.toBase58String(), type, endpoint.toString(), properties);
			return this;
		}

		/**
		 * Builds a {@link BosonSuperNodeProfile} instance.
		 *
		 * @return a new super node profile
		 * @throws IllegalStateException if no profile metadata (name, logo, website, or contact) was provided
		 */
		public BosonSuperNodeProfile build() {
			Map<String, Object> claims = new LinkedHashMap<>();
			if (name != null)
				claims.put("name", name);
			if (logo != null)
				claims.put("logo", logo);
			if (website != null)
				claims.put("website", website);
			if (contact != null)
				claims.put("contact", contact);

			if (claims.isEmpty())
				throw new IllegalStateException("No profile data provided");

			cardBuilder.addCredential(DEFAULT_PROFILE_CREDENTIAL_ID, DEFAULT_PROFILE_CREDENTIAL_TYPE, claims);
			Card card = cardBuilder.build();

			return new BosonSuperNodeProfile(identity.getId(), name, logo, website, contact, card);
		}

	}
}