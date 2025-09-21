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

package io.bosonnetwork.identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;

/**
 * Builder class for constructing {@link Card} instances.
 * <p>
 * A CardBuilder allows adding credentials and services to a Card in a fluent API,
 * ensuring that all credentials belong to the same identity subject and that
 * services have valid properties. The resulting Card is signed with the subject's
 * identity key when {@link #build()} is called.
 */
public class CardBuilder extends BosonIdentityObjectBuilder<Card> {
	/** Collected credentials indexed by credential id. */
	private final Map<String, Credential> credentials;
	/** Collected services indexed by service id. */
	private final Map<String, Card.Service> services;

	/**
	 * Creates a new CardBuilder for the given identity subject.
	 * @param identity the subject identity
	 */
	protected CardBuilder(Identity identity) {
		super(identity);

		credentials = new LinkedHashMap<>();
		services = new LinkedHashMap<>();
	}

	/**
	 * Adds a credential to this CardBuilder.
	 * <p>
	 * The credential's subject must match the identity subject of this builder.
	 * @param credential the credential to add
	 * @return this builder instance
	 * @throws NullPointerException if credential is null
	 * @throws IllegalArgumentException if credential subject does not match identity subject
	 */
	public CardBuilder addCredential(Credential credential) {
		Objects.requireNonNull(credential, "credential");
		// Ensure credential subject matches the identity subject
		if (!credential.getSubject().getId().equals(identity.getId()))
			throw new IllegalArgumentException("Credential subject does not match identity");

		credentials.put(credential.getId(), credential);
		return this;
	}

	/**
	 * Adds multiple credentials to this CardBuilder.
	 * @param credentials varargs array of credentials to add
	 * @return this builder instance
	 */
	public CardBuilder addCredential(Credential... credentials) {
		return addCredential(List.of(credentials));
	}

	/**
	 * Adds a list of credentials to this CardBuilder.
	 * <p>
	 * Null credentials in the list are ignored.
	 * @param credentials list of credentials to add
	 * @return this builder instance
	 * @throws NullPointerException if credentials list is null
	 */
	public CardBuilder addCredential(List<Credential> credentials) {
		Objects.requireNonNull(credentials, "credentials");
		for (Credential cred : credentials) {
			if (cred != null)
				addCredential(cred);
		}
		return this;
	}

	/**
	 * Adds a credential by specifying id, type, and claims.
	 * <p>
	 * Claims map must not be null or empty.
	 * @param id credential id
	 * @param type credential type
	 * @param claims map of claims
	 * @return this builder instance
	 * @throws NullPointerException if id or type is null
	 * @throws IllegalArgumentException if claims is null or empty
	 */
	public CardBuilder addCredential(String id, String type, Map<String, Object> claims) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");

		if (claims == null || claims.isEmpty())
			throw new IllegalArgumentException("Claims cannot be null or empty");

		return addCredential(new CredentialBuilder(identity)
				.id(id)
				.type(type)
				.claims(claims)
				.build());
	}

	/**
	 * Adds a credential with a single claim.
	 * @param id credential id
	 * @param type credential type
	 * @param claim1 claim name
	 * @param value1 claim value
	 * @return this builder instance
	 * @throws NullPointerException if id, type, or claim1 is null
	 */
	public CardBuilder addCredential(String id, String type, String claim1, Object value1) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(claim1, "claim1");

		return addCredential(new CredentialBuilder(identity)
				.id(id)
				.type(type)
				.claim(claim1, value1)
				.build());
	}

	/**
	 * Adds a credential with two claims.
	 * @param id credential id
	 * @param type credential type
	 * @param claim1 first claim name
	 * @param value1 first claim value
	 * @param claim2 second claim name
	 * @param value2 second claim value
	 * @return this builder instance
	 * @throws NullPointerException if id, type, claim1, or claim2 is null
	 */
	public CardBuilder addCredential(String id, String type, String claim1, Object value1,
									 String claim2, Object value2) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(claim1, "claim1");
		Objects.requireNonNull(claim2, "claim2");

		return addCredential(new CredentialBuilder(identity)
				.id(id)
				.type(type)
				.claim(claim1, value1)
				.claim(claim2, value2)
				.build());
	}

	/**
	 * Adds a credential with three claims.
	 * @param id credential id
	 * @param type credential type
	 * @param claim1 first claim name
	 * @param value1 first claim value
	 * @param claim2 second claim name
	 * @param value2 second claim value
	 * @param claim3 third claim name
	 * @param value3 third claim value
	 * @return this builder instance
	 * @throws NullPointerException if id, type, claim1, claim2, or claim3 is null
	 */
	public CardBuilder addCredential(String id, String type, String claim1, Object value1,
									 String claim2, Object value2, String claim3, Object value3) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(claim1, "claim1");
		Objects.requireNonNull(claim2, "claim2");
		Objects.requireNonNull(claim3, "claim3");

		return addCredential(new CredentialBuilder(identity)
				.id(id)
				.type(type)
				.claim(claim1, value1)
				.claim(claim2, value2)
				.claim(claim3, value3)
				.build());
	}

	/**
	 * Returns a {@link CredentialBuilder} for building a credential.
	 * <p>
	 * The builder enforces that the credential subject matches the identity subject,
	 * and automatically adds the built credential to this CardBuilder.
	 * @return a CredentialBuilder instance
	 */
	public CredentialBuilder addCredential() {
		return new CredentialBuilder(identity) {
			@Override
			public CredentialBuilder subject(Id subject) {
				// Enforce that the credential subject matches the identity subject
				if (subject != null && !subject.equals(identity.getId()))
					throw new IllegalArgumentException("Credential subject does not match identity");

				return super.subject(subject);
			}

			@Override
			public Credential build() {
				Credential credential = super.build();
				addCredential(credential);
				return credential;
			}
		};
	}

	/**
	 * Adds a service to this CardBuilder.
	 * <p>
	 * Service properties cannot contain the reserved keys "id", "t", or "e".
	 * Property values and id/type/endpoint are normalized.
	 * @param id service id
	 * @param type service type
	 * @param endpoint service endpoint
	 * @param properties map of service properties (may be null or empty)
	 * @return this builder instance
	 * @throws NullPointerException if id, type, or endpoint is null
	 * @throws IllegalArgumentException if properties contain reserved keys
	 */
	public CardBuilder addService(String id, String type, String endpoint, Map<String, Object> properties) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(endpoint, "endpoint");

		if (properties == null || properties.isEmpty()) {
			properties = Map.of();
		} else {
			// Reject reserved keys in service properties
			if (properties.keySet().stream().anyMatch(k -> k.equals("id") || k.equals("t") || k.equals("e")))
				throw new IllegalArgumentException("Service properties cannot contain 'id', 't' or 'e'");

			properties = normalize(properties);
		}

		services.put(id, new Card.Service(normalize(id), normalize(type), normalize(endpoint), properties));
		return this;
	}

	/**
	 * Adds a service without properties.
	 * @param id service id
	 * @param type service type
	 * @param endpoint service endpoint
	 * @return this builder instance
	 */
	public CardBuilder addService(String id, String type, String endpoint) {
		return addService(id, type, endpoint, null);
	}

	/**
	 * Adds a service with a single property.
	 * @param id service id
	 * @param type service type
	 * @param endpoint service endpoint
	 * @param prop1 property name
	 * @param value1 property value
	 * @return this builder instance
	 * @throws NullPointerException if id, type, endpoint, or prop1 is null
	 */
	public CardBuilder addService(String id, String type, String endpoint, String prop1, Object value1) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(endpoint, "endpoint");
		Objects.requireNonNull(prop1, "prop1");

		Map<String, Object> properties = Map.of(prop1, value1);
		return addService(id, type, endpoint, properties);
	}

	/**
	 * Adds a service with two properties.
	 * @param id service id
	 * @param type service type
	 * @param endpoint service endpoint
	 * @param prop1 first property name
	 * @param value1 first property value
	 * @param prop2 second property name
	 * @param value2 second property value
	 * @return this builder instance
	 * @throws NullPointerException if id, type, endpoint, prop1, or prop2 is null
	 */
	public CardBuilder addService(String id, String type, String endpoint, String prop1, Object value1,
								  String prop2, Object value2) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(endpoint, "endpoint");
		Objects.requireNonNull(prop1, "prop1");
		Objects.requireNonNull(prop2, "prop2");

		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put(prop1, value1);
		properties.put(prop2, value2);

		return addService(id, type, endpoint, properties);
	}

	/**
	 * Adds a service with three properties.
	 * @param id service id
	 * @param type service type
	 * @param endpoint service endpoint
	 * @param prop1 first property name
	 * @param value1 first property value
	 * @param prop2 second property name
	 * @param value2 second property value
	 * @param prop3 third property name
	 * @param value3 third property value
	 * @return this builder instance
	 * @throws NullPointerException if id, type, endpoint, prop1, prop2, or prop3 is null
	 */
	public CardBuilder addService(String id, String type, String endpoint, String prop1, Object value1,
								  String prop2, Object value2, String prop3, Object value3) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(endpoint, "endpoint");
		Objects.requireNonNull(prop1, "prop1");
		Objects.requireNonNull(prop2, "prop2");
		Objects.requireNonNull(prop3, "prop3");

		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put(prop1, value1);
		properties.put(prop2, value2);
		properties.put(prop3, value3);

		return addService(id, type, endpoint, properties);
	}

	/**
	 * Builds and signs the Card.
	 * <p>
	 * Collects all added credentials and services into a Card, signs it with the identity,
	 * and returns the signed Card instance.
	 * @return signed Card instance
	 */
	@Override
	public Card build() {
		List<Credential> credentials = this.credentials.isEmpty() ? Collections.emptyList() : new ArrayList<>(this.credentials.values());
		List<Card.Service> services = this.services.isEmpty() ? Collections.emptyList() : new ArrayList<>(this.services.values());
		Card unsigned = new Card(identity.getId(), credentials, services);
		byte[] signature = identity.sign(unsigned.getSignData());
		return new Card(unsigned, now(), signature);
	}
}