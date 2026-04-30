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

import io.bosonnetwork.identifier.Card;
import io.bosonnetwork.identifier.CardBuilder;
import io.bosonnetwork.identifier.Credential;

/**
 * Represents a user profile in the Boson network.
 * A profile encapsulates user identity information such as name, avatar, bio,
 * and associated service endpoints like home nodes and messaging peers.
 * It is backed by a {@link Card} (DID Document).
 */
public class BosonUserProfile {
	private static final String DEFAULT_PROFILE_CREDENTIAL_ID = "profile";
	private static final String DEFAULT_PROFILE_CREDENTIAL_TYPE = "BosonProfile";

	private static final String NAME = "name";
	private static final String AVATAR = "avatar";
	private static final String BIO = "bio";
	private static final String HOME_NODE = "homeNode";
	private static final String MESSAGING_HOME_PEER = "messagingHomePeer";

	private final Id id;
	private final String name;
	private final String avatar;
	private final String bio;
	private final Id homeNode;
	private final Id messagingHomePeer;

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
	private BosonUserProfile(Id id, String name, String avatar, String bio, Id homeNode, Id messagingHomePeer, Card card) {
		this.id = id;
		this.name = name;
		this.avatar = avatar;
		this.bio = bio;
		this.homeNode = homeNode;
		this.messagingHomePeer = messagingHomePeer;
		this.card = card;
	}

	/**
	 * Creates a {@code BosonUserProfile} from a {@link Card}.
	 *
	 * @param card the card to extract profile information from; must not be null
	 * @return a new user profile instance
	 * @throws NullPointerException if card is null
	 */
	public static BosonUserProfile fromCard(Card card) {
		Objects.requireNonNull(card);

		Id id = card.getId();

		String name = null;
		String avatar = null;
		String bio = null;
		Id homeNode = null;
		Id messagingHomePeer = null;
		Credential profile = card.getCredential(DEFAULT_PROFILE_CREDENTIAL_ID);
		if (profile != null && profile.getTypes().contains(DEFAULT_PROFILE_CREDENTIAL_TYPE)) {
			Map<String, Object> claims = profile.getSubject().getClaims();
			name = String.valueOf(claims.get(NAME));
			avatar = String.valueOf(claims.get(AVATAR));
			bio = String.valueOf(claims.get(BIO));

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

		return new BosonUserProfile(id, name, avatar, bio, homeNode, messagingHomePeer, card);
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
	 * @return the user name, or null if not set
	 */
	public String getName() {
		return name;
	}

	/**
	 * Returns the avatar of the user.
	 *
	 * @return the avatar string, or null if not set
	 */
	public String getAvatar() {
		return avatar;
	}

	/**
	 * Returns the biography of the user.
	 *
	 * @return the bio, or null if not set
	 */
	public String getBio() {
		return bio;
	}

	/**
	 * Returns the identifier of the user's home node.
	 *
	 * @return the home node id, or null if not set
	 */
	public Id getHomeNode() {
		return homeNode;
	}

	/**
	 * Returns the identifier of the user's messaging home peer.
	 *
	 * @return the messaging home peer id, or null if not set
	 */
	public Id getMessagingHomePeer() {
		return messagingHomePeer;
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
	 * Creates a new builder for creating a {@code BosonUserProfile}.
	 *
	 * @param identity the identity to associate with the profile
	 * @return a new builder instance
	 */
	public static Builder builder(Identity identity) {
		Objects.requireNonNull(identity);
		return new Builder(identity);
	}

	/**
	 * Builder class for {@link BosonUserProfile}.
	 */
	public static class Builder {
		private final Identity identity;
		private String name;
		private String avatar;
		private String bio;
		private Id homeNode;
		private Id messagingHomePeer;
		private Card card;

		private Builder (Identity identity) {
			this.identity = identity;
		}

		/**
		 * Sets the user's name.
		 *
		 * @param name the name
		 * @return the builder instance
		 */
		public Builder name(String name) {
			this.name = name;
			 return this;
		}

		/**
		 * Sets the user's avatar.
		 *
		 * @param avatar the avatar string
		 * @return the builder instance
		 */
		public Builder avatar(String avatar) {
			this.avatar = avatar;
			return this;
		}

		/**
		 * Sets the user's biography.
		 *
		 * @param bio the bio
		 * @return the builder instance
		 */
		public Builder bio(String bio) {
			this.bio = bio;
			 return this;
		}

		/**
		 * Sets the user's home node.
		 *
		 * @param homeNode the home node id
		 * @return the builder instance
		 */
		public Builder homeNode(Id homeNode) {
			this.homeNode = homeNode;
			return this;
		}

		/**
		 * Sets the user's messaging home peer.
		 *
		 * @param messagingHomePeer the messaging home peer id
		 * @return the builder instance
		 */
		public Builder messagingHomePeer(Id messagingHomePeer) {
			this.messagingHomePeer = messagingHomePeer;
			 return this;
		}

		/**
		 * Builds a {@link BosonUserProfile} instance based on the provided data.
		 *
		 * @return a new user profile
		 * @throws IllegalStateException if no profile data is provided
		 */
		public BosonUserProfile build() {
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
			return new BosonUserProfile(identity.getId(), name, avatar, bio, homeNode, messagingHomePeer, card);
		}
	}
}