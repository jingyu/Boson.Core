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

package io.bosonnetwork;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.bosonnetwork.identifier.Card;
import io.bosonnetwork.identifier.CardBuilder;
import io.bosonnetwork.identifier.Credential;

/**
 * Represents a user profile in the Boson network.
 * A profile encapsulates user identity information such as name, avatar, bio,
 * and associated service endpoints like home nodes and messaging peers.
 * It is backed by a {@link Card} (DID Document).
 */
public class UserProfile {
	private static final String DEFAULT_PROFILE_CREDENTIAL_ID = "profile";
	private static final String DEFAULT_PROFILE_CREDENTIAL_TYPE = "BosonProfile";

	private static final String NAME = "name";
	private static final String AVATAR = "avatar";
	private static final String BIO = "bio";
	private static final String HOME_NODE = "homeNode";
	private static final String MESSAGING_HOME_PEER = "messagingHomePeer";

	private final Id id;
	private final @Nullable String name;
	private final @Nullable String avatar;
	private final @Nullable String bio;
	private final @Nullable Id homeNode;
	private final @Nullable Id messagingHomePeer;

	private final Card card;

	/**
	 * Creates a new user profile.
	 *
	 * @param id the user's identifier
	 * @param name the user's name
	 * @param avatar the user's avatar URL or identifier
	 * @param bio the user's biography
	 * @param homeNode the user's home node identifier
	 * @param messagingHomePeer the user's messaging home peer identifier
	 * @param card the underlying card object
	 */
	private UserProfile(Id id, @Nullable String name, @Nullable String avatar, @Nullable String bio,
	                    @Nullable Id homeNode, @Nullable Id messagingHomePeer, Card card) {
		this.id = id;
		this.name = name;
		this.avatar = avatar;
		this.bio = bio;
		this.homeNode = homeNode;
		this.messagingHomePeer = messagingHomePeer;
		this.card = card;
	}

	/**
	 * Creates a {@code UserProfile} from a {@link Card}.
	 *
	 * @param card the card to extract profile information from; must not be null
	 * @return a new user profile instance
	 * @throws NullPointerException if card is null
	 */
	public static UserProfile fromCard(Card card) {
		Objects.requireNonNull(card);

		Id id = card.getId();

		String name = null;
		String avatar = null;
		String bio = null;
		Id homeNode = null;
		Id messagingHomePeer = null;
		Credential profile = card.getCredential(DEFAULT_PROFILE_CREDENTIAL_ID).orElse(null);
		if (profile != null && profile.getTypes().contains(DEFAULT_PROFILE_CREDENTIAL_TYPE)) {
			Map<String, Object> claims = profile.getSubject().getClaims();
			name = (String) claims.get(NAME);
			avatar = (String) claims.get(AVATAR);
			bio = (String) claims.get(BIO);

			Object value = claims.get(HOME_NODE);
			if (value != null) {
				try {
					homeNode = Id.of((String) value);
				} catch (Exception ignored) {
				}
			}
			value = claims.get(MESSAGING_HOME_PEER);
			if (value != null) {
				try {
					messagingHomePeer = Id.of((String) value);
				} catch (Exception ignored) {
				}
			}
		}

		return new UserProfile(id, name, avatar, bio, homeNode, messagingHomePeer, card);
	}

	/**
	 * Returns the identifier of the user.
	 *
	 * @return the user id
	 */
	public Id getId() {
		return id;
	}

	/**
	 * Returns the name of the user.
	 *
	 * @return an {@link Optional} with the user name, or empty if not set
	 */
	public Optional<String> getName() {
		return Optional.ofNullable(name);
	}

	/**
	 * Returns the avatar of the user.
	 *
	 * @return an {@link Optional} with the avatar string, or empty if not set
	 */
	public Optional<String> getAvatar() {
		return Optional.ofNullable(avatar);
	}

	/**
	 * Returns the biography of the user.
	 *
	 * @return an {@link Optional} with the bio, or empty if not set
	 */
	public Optional<String> getBio() {
		return Optional.ofNullable(bio);
	}

	/**
	 * Returns the identifier of the user's home node.
	 *
	 * @return an {@link Optional} with the home node id, or empty if not set
	 */
	public Optional<Id> getHomeNode() {
		return Optional.ofNullable(homeNode);
	}

	/**
	 * Returns the identifier of the user's messaging home peer.
	 *
	 * @return an {@link Optional} with the messaging home peer id, or empty if not set
	 */
	public Optional<Id> getMessagingHomePeer() {
		return Optional.ofNullable(messagingHomePeer);
	}

	/**
	 * Returns the underlying {@link Card} representing this profile.
	 *
	 * @return the card
	 */
	public Card getCard() {
		return card;
	}

	/**
	 * Creates a new builder for creating a {@code UserProfile}.
	 *
	 * @param identity the identity to associate with the profile
	 * @return a new builder instance
	 */
	public static Builder builder(Identity identity) {
		Objects.requireNonNull(identity);
		return new Builder(identity);
	}

	/**
	 * Builder class for {@link UserProfile}.
	 */
	public static class Builder {
		private final Identity identity;
		private @Nullable String name;
		private @Nullable String avatar;
		private @Nullable String bio;
		private @Nullable Id homeNode;
		private @Nullable Id messagingHomePeer;
		private @Nullable Card card;

		private Builder(Identity identity) {
			this.identity = identity;
		}

		/**
		 * Sets the user's name.
		 *
		 * @param name the name
		 * @return the builder instance
		 */
		public Builder name(@Nullable String name) {
			this.name = name;
			return this;
		}

		/**
		 * Sets the user's avatar.
		 *
		 * @param avatar the avatar string
		 * @return the builder instance
		 */
		public Builder avatar(@Nullable String avatar) {
			this.avatar = avatar;
			return this;
		}

		/**
		 * Sets the user's biography.
		 *
		 * @param bio the bio
		 * @return the builder instance
		 */
		public Builder bio(@Nullable String bio) {
			this.bio = bio;
			return this;
		}

		/**
		 * Sets the user's home node.
		 *
		 * @param homeNode the home node id
		 * @return the builder instance
		 */
		public Builder homeNode(@Nullable Id homeNode) {
			this.homeNode = homeNode;
			return this;
		}

		/**
		 * Sets the user's messaging home peer.
		 *
		 * @param messagingHomePeer the messaging home peer id
		 * @return the builder instance
		 */
		public Builder messagingHomePeer(@Nullable Id messagingHomePeer) {
			this.messagingHomePeer = messagingHomePeer;
			return this;
		}

		/**
		 * Builds a {@link UserProfile} instance based on the provided data.
		 *
		 * @return a new user profile
		 * @throws IllegalStateException if no profile data is provided
		 */
		public UserProfile build() {
			Map<String, Object> claims = new LinkedHashMap<>();
			if (name != null)
				claims.put(NAME, name);
			if (avatar != null)
				claims.put(AVATAR, avatar);
			if (bio != null)
				claims.put(BIO, bio);
			if (homeNode != null)
				claims.put(HOME_NODE, homeNode.toString());
			if (messagingHomePeer != null)
				claims.put(MESSAGING_HOME_PEER, messagingHomePeer.toString());

			if (claims.isEmpty())
				throw new IllegalStateException("No profile data provided");

			CardBuilder builder = Card.builder(identity);
			if (!claims.isEmpty())
				builder.addCredential(DEFAULT_PROFILE_CREDENTIAL_ID, DEFAULT_PROFILE_CREDENTIAL_TYPE, claims);

			Card card = builder.build();
			return new UserProfile(identity.getId(), name, avatar, bio, homeNode, messagingHomePeer, card);
		}
	}
}