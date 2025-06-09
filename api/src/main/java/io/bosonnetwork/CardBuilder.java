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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class CardBuilder extends BosonIdentityObjectBuilder<Card> {
	private final Map<String, Credential> credentials;
	private final Map<String, Card.Service> services;

	protected CardBuilder(Identity identity) {
		super(identity);

		credentials = new LinkedHashMap<>();
		services = new LinkedHashMap<>();
	}

	public CardBuilder addCredential(Credential credential) {
		Objects.requireNonNull(credential, "credential");
		if (!credential.getSubject().getId().equals(identity.getId()))
			throw new IllegalArgumentException("Credential subject does not match identity");

		credentials.put(credential.getId(), credential);
		return this;
	}

	public CardBuilder addCredential(Credential... credentials) {
		return addCredential(List.of(credentials));
	}

	public CardBuilder addCredential(List<Credential> credentials) {
		Objects.requireNonNull(credentials, "credentials");
		for (Credential cred : credentials) {
			if (cred != null)
				addCredential(cred);
		}
		return this;
	}

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

	public CredentialBuilder addCredential() {
		return new CredentialBuilder(identity) {
			@Override
			public CredentialBuilder subject(Id subject) {
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

	public CardBuilder addService(String id, String type, String endpoint, Map<String, Object> properties) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(endpoint, "endpoint");

		if (properties == null || properties.isEmpty()) {
			properties = Map.of();
		} else {
			if (properties.keySet().stream().anyMatch(k -> k.equals("id") || k.equals("t") || k.equals("e")))
				throw new IllegalArgumentException("Service properties cannot contain 'id', 't' or 'e'");

			properties = normalize(properties);
		}

		services.put(id, new Card.Service(normalize(id), normalize(type), normalize(endpoint), properties));
		return this;
	}

	public CardBuilder addService(String id, String type, String endpoint) {
		return addService(id, type, endpoint, null);
	}

	public CardBuilder addService(String id, String type, String endpoint, String prop1, Object value1) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(endpoint, "endpoint");
		Objects.requireNonNull(prop1, "prop1");

		Map<String, Object> properties = Map.of(prop1, value1);
		return addService(id, type, endpoint, properties);
	}

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

	@Override
	public Card build() {
		List<Credential> credentials = this.credentials.isEmpty() ? Collections.emptyList() : new ArrayList<>(this.credentials.values());
		List<Card.Service> services = this.services.isEmpty() ? Collections.emptyList() : new ArrayList<>(this.services.values());
		Card unsigned = new Card(identity.getId(), credentials, services);
		byte[] signature = identity.sign(unsigned.getSignData());
		return new Card(unsigned, now(), signature);
	}
}