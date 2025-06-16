package io.bosonnetwork.identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;

public class VouchBuilder extends BosonIdentityObjectBuilder<Vouch> {
	private String id;
	private final List<String> types;
	private final Map<String, Credential> credentials;

	protected VouchBuilder(Identity identity) {
		super(identity);

		this.types = new ArrayList<>();
		this.credentials = new LinkedHashMap<>();
	}

	public VouchBuilder id(String id) {
		this.id = id == null || id.isEmpty() ? null : normalize(id);
		return this;
	}

	public VouchBuilder type(List<String> types) {
		Objects.requireNonNull(types, "types");

		for (String type : types) {
			if (type == null || type.isEmpty())
				continue;

			type = normalize(type);
			if (this.types.contains(type))
				continue;

			this.types.add(type);
		}

		return this;
	}

	public VouchBuilder type(String... types) {
		return type(List.of(types));
	}

	public VouchBuilder addCredential(Credential credential) {
		Objects.requireNonNull(credential, "credential");
		this.credentials.put(credential.getId(), credential);
		return this;
	}

	public VouchBuilder addCredential(Credential... credentials) {
		return addCredential(List.of(credentials));
	}

	public VouchBuilder addCredential(List<Credential> credentials) {
		Objects.requireNonNull(credentials, "credentials");
		for (Credential cred : credentials) {
			if (cred != null)
				addCredential(cred);
		}
		return this;
	}

	public VouchBuilder addCredential(String id, String type, Map<String, Object> claims) {
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

	public VouchBuilder addCredential(String id, String type, String claim1, Object value1) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(claim1, "claim1");

		return addCredential(new CredentialBuilder(identity)
				.id(id)
				.type(type)
				.claim(claim1, value1)
				.build());
	}

	public VouchBuilder addCredential(String id, String type, String claim1, Object value1,
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

	public VouchBuilder addCredential(String id, String type, String claim1, Object value1,
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
					throw new IllegalArgumentException("Credential subject does not match the holder");

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

	@Override
	public Vouch build() {
		List<Credential> credentials = this.credentials.isEmpty() ? Collections.emptyList() : new ArrayList<>(this.credentials.values());
		Vouch unsigned = new Vouch(id, types, identity.getId(), new ArrayList<>(credentials));
		byte[] signature = identity.sign(unsigned.getSignData());
		return new Vouch(unsigned, now(), signature);
	}
}