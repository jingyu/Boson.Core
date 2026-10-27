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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.identifier.Card;
import io.bosonnetwork.identifier.CardBuilder;
import io.bosonnetwork.identifier.Credential;

/**
 * Represents a profile for a super node in the Boson network: who runs it, for people - its name, logo,
 * website and contact - published as the node's card in the DHT.
 * <p>
 * The card says nothing about the node's services. A card is a mutable DHT value, limited to 896 bytes,
 * and a node with all its services needs more; it is also meant to be stable, while services and their
 * endpoints change with every redeployment. What a node runs is its signed {@link NodeDescriptor}, and
 * where to reach each service is the service's own peer announcement.
 */
public class SuperNodeProfile {
	private static final String DEFAULT_PROFILE_CREDENTIAL_ID = "profile";
	private static final String DEFAULT_PROFILE_CREDENTIAL_TYPE = "SuperNodeProfile";

	private final Id nodeId;
	private final @Nullable String name;
	private final @Nullable String logo;
	private final @Nullable String website;
	private final @Nullable String contact;

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
	private SuperNodeProfile(Id nodeId, @Nullable String name, @Nullable String logo, @Nullable String website,
							 @Nullable String contact, Card card) {
		this.nodeId = nodeId;
		this.name = name;
		this.logo = logo;
		this.website = website;
		this.contact = contact;
		this.card = card;
	}

	/**
	 * Creates a {@code SuperNodeProfile} from a {@link Card}.
	 *
	 * @param card the card to extract super node profile information from; must not be null
	 * @return a new super node profile instance
	 * @throws NullPointerException if card is null
	 */
	public static SuperNodeProfile fromCard(Card card) {
		Objects.requireNonNull(card);

		Id id = card.getId();

		String name = null;
		String logo = null;
		String website = null;
		String contact = null;
		Credential profile = card.getCredential(DEFAULT_PROFILE_CREDENTIAL_ID).orElse(null);
		if (profile != null && profile.getTypes().contains(DEFAULT_PROFILE_CREDENTIAL_TYPE)) {
			Map<String, Object> claims = profile.getSubject().getClaims();
			name = (String) claims.get("name");
			logo = (String) claims.get("logo");
			website = (String) claims.get("website");
			contact = (String) claims.get("contact");
		}

		return new SuperNodeProfile(id, name, logo, website, contact, card);
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
	 * @return an {@link Optional} with the name, or empty if not set
	 */
	public Optional<String> getName() {
		return Optional.ofNullable(name);
	}

	/**
	 * Returns the logo of the super node.
	 *
	 * @return an {@link Optional} with the logo string, or empty if not set
	 */
	public Optional<String> getLogo() {
		return Optional.ofNullable(logo);
	}

	/**
	 * Returns the website of the super node.
	 *
	 * @return an {@link Optional} with the website URL, or empty if not set
	 */
	public Optional<String> getWebsite() {
		return Optional.ofNullable(website);
	}

	/**
	 * Returns the contact information of the super node.
	 *
	 * @return an {@link Optional} with the contact info, or empty if not set
	 */
	public Optional<String> getContact() {
		return Optional.ofNullable(contact);
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
	 * Creates a new builder for creating a {@code SuperNodeProfile}.
	 *
	 * @param identity the identity to associate with the profile
	 * @return a new builder instance
	 */
	public static Builder builder(Identity identity) {
		Objects.requireNonNull(identity, "Identity cannot be null");
		return new Builder(identity);
	}

	/**
	 * Builder class for {@link SuperNodeProfile}.
	 */
	public static class Builder {
		private final Identity identity;
		private @Nullable String name;
		private @Nullable String logo;
		private @Nullable String website;
		private @Nullable String contact;

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
		 * Builds a {@link SuperNodeProfile} instance.
		 * <p>
		 * A display {@link #name(String) name} is mandatory - operators publishing a profile must
		 * declare who they are. The {@code logo}, {@code website}, and {@code contact} fields are
		 * optional and only emitted into the profile credential when set.
		 *
		 * @return a new super node profile
		 * @throws IllegalStateException if {@code name} has not been set
		 */
		public SuperNodeProfile build() {
			if (name == null || name.isEmpty())
				throw new IllegalStateException("Super node profile name is required");

			Map<String, Object> claims = new LinkedHashMap<>();
			claims.put("name", name);
			if (logo != null)
				claims.put("logo", logo);
			if (website != null)
				claims.put("website", website);
			if (contact != null)
				claims.put("contact", contact);

			cardBuilder.addCredential(DEFAULT_PROFILE_CREDENTIAL_ID, DEFAULT_PROFILE_CREDENTIAL_TYPE, claims);
			Card card = cardBuilder.build();

			return new SuperNodeProfile(identity.getId(), name, logo, website, contact, card);
		}
	}
}
