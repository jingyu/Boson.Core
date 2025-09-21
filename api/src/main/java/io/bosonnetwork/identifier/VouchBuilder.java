package io.bosonnetwork.identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;

/**
 * Builder class for constructing a Boson compacted Verifiable Presentation (Vouch).
 * <p>
 * This builder allows setting an identifier, adding multiple types, and collecting credentials
 * to form a Vouch object. The Vouch represents a verifiable presentation that can be signed
 * by the holder's identity.
 */
public class VouchBuilder extends BosonIdentityObjectBuilder<Vouch> {
	/**
	 * Optional identifier for the Vouch.
	 */
	private String id;

	/**
	 * List of types associated with the Vouch.
	 */
	private final List<String> types;

	/**
	 * Map of credentials keyed by their identifier.
	 */
	private final Map<String, Credential> credentials;

	/**
	 * Constructs a new VouchBuilder associated with the given identity.
	 *
	 * @param identity the holder's identity used for signing and validation
	 */
	protected VouchBuilder(Identity identity) {
		super(identity);

		this.types = new ArrayList<>();
		this.credentials = new LinkedHashMap<>();
	}

	/**
	 * Sets the identifier for the Vouch.
	 * <p>
	 * If the given id is null or empty, it will be cleared (set to null).
	 * The id is normalized before being stored.
	 *
	 * @param id the identifier string
	 * @return this builder instance for chaining
	 */
	public VouchBuilder id(String id) {
		// Normalize the id or set to null if empty
		this.id = id == null || id.isEmpty() ? null : normalize(id);
		return this;
	}

	/**
	 * Adds one or more types to the Vouch.
	 * <p>
	 * Null or empty types are ignored. Duplicate types are not added.
	 * Each type is normalized before adding.
	 *
	 * @param types list of type strings to add
	 * @return this builder instance for chaining
	 * @throws NullPointerException if types is null
	 */
	public VouchBuilder type(List<String> types) {
		Objects.requireNonNull(types, "types");

		for (String type : types) {
			if (type == null || type.isEmpty())
				continue;

			// Normalize type string
			type = normalize(type);
			if (this.types.contains(type))
				continue;

			this.types.add(type);
		}

		return this;
	}

	/**
	 * Adds one or more types to the Vouch.
	 * <p>
	 * This is a convenience method accepting varargs.
	 *
	 * @param types one or more type strings to add
	 * @return this builder instance for chaining
	 */
	public VouchBuilder type(String... types) {
		return type(List.of(types));
	}

	/**
	 * Adds a single Credential to the Vouch.
	 *
	 * @param credential the Credential to add
	 * @return this builder instance for chaining
	 * @throws NullPointerException if credential is null
	 */
	public VouchBuilder addCredential(Credential credential) {
		Objects.requireNonNull(credential, "credential");
		this.credentials.put(credential.getId(), credential);
		return this;
	}

	/**
	 * Adds multiple Credentials to the Vouch.
	 * <p>
	 * This is a convenience method accepting varargs.
	 *
	 * @param credentials one or more Credentials to add
	 * @return this builder instance for chaining
	 */
	public VouchBuilder addCredential(Credential... credentials) {
		return addCredential(List.of(credentials));
	}

	/**
	 * Adds multiple Credentials to the Vouch.
	 *
	 * @param credentials list of Credentials to add
	 * @return this builder instance for chaining
	 * @throws NullPointerException if credentials list is null
	 */
	public VouchBuilder addCredential(List<Credential> credentials) {
		Objects.requireNonNull(credentials, "credentials");
		for (Credential cred : credentials) {
			if (cred != null)
				addCredential(cred);
		}
		return this;
	}

	/**
	 * Adds a Credential with the specified id, type, and claims map.
	 * <p>
	 * The claims map must not be null or empty.
	 *
	 * @param id     the credential identifier
	 * @param type   the credential type
	 * @param claims map of claims for the credential
	 * @return this builder instance for chaining
	 * @throws NullPointerException     if id or type is null
	 * @throws IllegalArgumentException if claims is null or empty
	 */
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

	/**
	 * Adds a Credential with a single claim.
	 *
	 * @param id     the credential identifier
	 * @param type   the credential type
	 * @param claim1 the claim key
	 * @param value1 the claim value
	 * @return this builder instance for chaining
	 * @throws NullPointerException if id, type, or claim1 is null
	 */
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

	/**
	 * Adds a Credential with two claims.
	 *
	 * @param id     the credential identifier
	 * @param type   the credential type
	 * @param claim1 the first claim key
	 * @param value1 the first claim value
	 * @param claim2 the second claim key
	 * @param value2 the second claim value
	 * @return this builder instance for chaining
	 * @throws NullPointerException if id, type, claim1, or claim2 is null
	 */
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

	/**
	 * Adds a Credential with three claims.
	 *
	 * @param id     the credential identifier
	 * @param type   the credential type
	 * @param claim1 the first claim key
	 * @param value1 the first claim value
	 * @param claim2 the second claim key
	 * @param value2 the second claim value
	 * @param claim3 the third claim key
	 * @param value3 the third claim value
	 * @return this builder instance for chaining
	 * @throws NullPointerException if id, type, claim1, claim2, or claim3 is null
	 */
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

	/**
	 * Returns a CredentialBuilder to build and add a Credential to this Vouch.
	 * <p>
	 * The subject of the credential must match the holder's identity.
	 * The credential is automatically added to this Vouch when built.
	 *
	 * @return a new CredentialBuilder instance
	 */
	public CredentialBuilder addCredential() {
		return new CredentialBuilder(identity) {
			@Override
			public CredentialBuilder subject(Id subject) {
				// Ensure the credential subject matches the holder's identity
				if (subject != null && !subject.equals(identity.getId()))
					throw new IllegalArgumentException("Credential subject does not match the holder");

				return super.subject(subject);
			}

			@Override
			public Credential build() {
				// Build the credential and add it to this VouchBuilder
				Credential credential = super.build();
				addCredential(credential);
				return credential;
			}
		};
	}

	/**
	 * Builds the Vouch object.
	 * <p>
	 * Validates that at least one credential has been added.
	 * Signs the Vouch using the holder's identity.
	 *
	 * @return the signed Vouch instance
	 * @throws IllegalStateException if no credentials have been added
	 */
	@Override
	public Vouch build() {
		if (credentials.isEmpty())
			throw new IllegalStateException("Credentials cannot be empty");

		List<Credential> credentials = new ArrayList<>(this.credentials.values());
		// Create an unsigned Vouch with the collected data
		Vouch unsigned = new Vouch(id, types, identity.getId(), new ArrayList<>(credentials));
		// Sign the Vouch's data with the identity's private key
		byte[] signature = identity.sign(unsigned.getSignData());
		// Return a new signed Vouch with timestamp and signature
		return new Vouch(unsigned, now(), signature);
	}
}