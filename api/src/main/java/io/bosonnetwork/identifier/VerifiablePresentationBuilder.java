package io.bosonnetwork.identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;

/**
 * Builder class for constructing {@link VerifiablePresentation} instances.
 * <p>
 * This builder allows setting the presentation's identifier, types, contexts, and
 * associated Verifiable Credentials. It enforces that credential subjects match
 * the holder, collects credentials, and produces a signed VerifiablePresentation
 * with a cryptographic proof using the holder's identity key.
 */
public class VerifiablePresentationBuilder extends BosonIdentityObjectBuilder<VerifiablePresentation> {
	/** JSON-LD context URIs for the presentation */
	private final List<String> contexts;
	/** Unique identifier of the presentation (DID URL with fragment) */
	private String id;
	/** List of presentation types */
	private final List<String> types;
	/** Map of credential ID to VerifiableCredential included in the presentation */
	private final Map<String, VerifiableCredential> credentials;

	/**
	 * Constructs a new VerifiablePresentationBuilder for the given holder identity.
	 * Initializes default contexts and types including W3C VC context, Boson VC context,
	 * and Ed25519 signature context.
	 *
	 * @param holder the holder identity of the presentation
	 */
	protected VerifiablePresentationBuilder(Identity holder) {
		super(holder);

		contexts = new ArrayList<>();
		types = new ArrayList<>();
		credentials = new LinkedHashMap<>();

		// Add default types and contexts for Verifiable Presentation
		type(DIDConstants.DEFAULT_VP_TYPE,
				DIDConstants.W3C_VC_CONTEXT, DIDConstants.BOSON_VC_CONTEXT, DIDConstants.W3C_ED25519_CONTEXT);
	}

	/**
	 * Sets the identifier of the Verifiable Presentation.
	 * <p>
	 * The id must be a DID URL that starts with the holder's DID and must contain a fragment.
	 * If a simple fragment id is provided, it is converted to a DIDURL with the holder's DID.
	 *
	 * @param id the identifier string (DID URL or fragment)
	 * @return this builder instance
	 * @throws NullPointerException if id is null
	 * @throws IllegalArgumentException if id is empty, does not start with holder DID, or lacks fragment
	 */
	public VerifiablePresentationBuilder id(String id) {
		Objects.requireNonNull(id, "id");
		if (id.isEmpty())
			throw new IllegalArgumentException("Id cannot be empty");

		DIDURL idUrl;
		// Check if id is a full DID URL starting with DID scheme
		if (id.startsWith(DIDConstants.DID_SCHEME + ":")) {
			idUrl = DIDURL.create(id);
			// Validate that the DID URL's id matches the holder's DID
			if (!idUrl.getId().equals(identity.getId()))
				throw new IllegalArgumentException("Id must be the holder id based DIDURL");
			// Validate that the DID URL contains a fragment part
			if (idUrl.getFragment() == null)
				throw new IllegalArgumentException("Id must has the fragment part");
		} else {
			// If id is a fragment, create a DIDURL using the holder's DID and the fragment
			idUrl = new DIDURL(identity.getId(), null, null, id);
		}

		this.id = idUrl.toString();
		return this;
	}

	/**
	 * Adds a type and associated contexts to the presentation.
	 * <p>
	 * Type and contexts are normalized and duplicates are avoided.
	 *
	 * @param type the type string to add
	 * @param contexts varargs of context URIs to add
	 * @return this builder instance
	 * @throws NullPointerException if type is null
	 */
	public VerifiablePresentationBuilder type(String type, String... contexts) {
		return type(type, List.of(contexts));
	}

	/**
	 * Adds a type and associated contexts to the presentation.
	 * <p>
	 * Type and contexts are normalized and duplicates are avoided.
	 *
	 * @param type the type string to add
	 * @param contexts list of context URIs to add
	 * @return this builder instance
	 * @throws NullPointerException if type is null
	 */
	public VerifiablePresentationBuilder type(String type, List<String> contexts) {
		Objects.requireNonNull(type, "type");

		// Normalize the type string
		type = normalize(type);
		if (!this.types.contains(type))
			this.types.add(type);

		// Normalize and add contexts, avoiding duplicates and null/empty
		for (String context : contexts) {
			if (context == null || context.isEmpty())
				continue;

			context = normalize(context);
			if (!this.contexts.contains(context))
				this.contexts.add(context);
		}

		return this;
	}

	/**
	 * Adds a VerifiableCredential instance to the presentation.
	 *
	 * @param vc the VerifiableCredential to add
	 * @return this builder instance
	 * @throws NullPointerException if vc is null
	 */
	public VerifiablePresentationBuilder addCredential(VerifiableCredential vc) {
		Objects.requireNonNull(vc, "vc");
		credentials.put(vc.getId(), vc);
		return this;
	}

	/**
	 * Adds multiple VerifiableCredential instances to the presentation.
	 *
	 * @param vcs array of VerifiableCredentials to add
	 * @return this builder instance
	 */
	public VerifiablePresentationBuilder addCredential(VerifiableCredential... vcs) {
		return addCredential(List.of(vcs));
	}

	/**
	 * Adds multiple VerifiableCredential instances to the presentation.
	 *
	 * @param vcs list of VerifiableCredentials to add
	 * @return this builder instance
	 */
	public VerifiablePresentationBuilder addCredential(List<VerifiableCredential> vcs) {
		for (VerifiableCredential vc : vcs) {
			if (vc != null)
				addCredential(vc);
		}

		return this;
	}

	/**
	 * Constructs and adds a VerifiableCredential with given id, type, contexts, and claims.
	 *
	 * @param id the credential identifier
	 * @param type the credential type (nullable)
	 * @param contexts list of context URIs (nullable)
	 * @param claims map of claim names to values
	 * @return this builder instance
	 * @throws NullPointerException if id or claims is null
	 */
	public VerifiablePresentationBuilder addCredential(String id, String type, List<String> contexts, Map<String, Object> claims) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(claims, "claims");

		VerifiableCredentialBuilder vcb = new VerifiableCredentialBuilder(identity);
		if (type != null)
			vcb.type(type, contexts == null ? List.of() : contexts);

		return addCredential(vcb.id(id)
				.claims(claims)
				.build());
	}

	/**
	 * Constructs and adds a VerifiableCredential with one claim.
	 *
	 * @param id the credential identifier
	 * @param type the credential type (nullable)
	 * @param contexts list of context URIs (nullable)
	 * @param claim1 first claim name
	 * @param value1 first claim value
	 * @return this builder instance
	 * @throws NullPointerException if id or claim1 is null
	 */
	public VerifiablePresentationBuilder addCredential(String id, String type, List<String> contexts, String claim1, Object value1) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(claim1, "claim1");

		VerifiableCredentialBuilder vcb = new VerifiableCredentialBuilder(identity);
		if (type != null)
			vcb.type(type, contexts == null ? List.of() : contexts);

		return addCredential(vcb.id(id)
				.claim(claim1, value1)
				.build());
	}

	/**
	 * Constructs and adds a VerifiableCredential with two claims.
	 *
	 * @param id the credential identifier
	 * @param type the credential type (nullable)
	 * @param contexts list of context URIs (nullable)
	 * @param claim1 first claim name
	 * @param value1 first claim value
	 * @param claim2 second claim name
	 * @param value2 second claim value
	 * @return this builder instance
	 * @throws NullPointerException if id, claim1, or claim2 is null
	 */
	public VerifiablePresentationBuilder addCredential(String id, String type, List<String> contexts, String claim1, Object value1,
										 String claim2, Object value2) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(claim1, "claim1");
		Objects.requireNonNull(claim2, "claim2");

		VerifiableCredentialBuilder vcb = new VerifiableCredentialBuilder(identity);
		if (type != null)
			vcb.type(type, contexts == null ? List.of() : contexts);

		return addCredential(vcb.id(id)
				.claim(claim1, value1)
				.claim(claim2, value2)
				.build());
	}

	/**
	 * Constructs and adds a VerifiableCredential with three claims.
	 *
	 * @param id the credential identifier
	 * @param type the credential type (nullable)
	 * @param contexts list of context URIs (nullable)
	 * @param claim1 first claim name
	 * @param value1 first claim value
	 * @param claim2 second claim name
	 * @param value2 second claim value
	 * @param claim3 third claim name
	 * @param value3 third claim value
	 * @return this builder instance
	 * @throws NullPointerException if id, claim1, claim2, or claim3 is null
	 */
	public VerifiablePresentationBuilder addCredential(String id, String type, List<String> contexts, String claim1, Object value1,
										 String claim2, Object value2, String claim3, Object value3) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(claim1, "claim1");
		Objects.requireNonNull(claim2, "claim2");
		Objects.requireNonNull(claim3, "claim3");

		VerifiableCredentialBuilder vcb = new VerifiableCredentialBuilder(identity);
		if (type != null)
			vcb.type(type, contexts == null ? List.of() : contexts);

		return addCredential(vcb.id(id)
				.claim(claim1, value1)
				.claim(claim2, value2)
				.claim(claim3, value3)
				.build());
	}

	/**
	 * Returns a VerifiableCredentialBuilder to build and add a credential to this presentation.
	 * <p>
	 * The credential subject must match the holder identity or be null.
	 * When the credential is built, it is automatically added to this presentation.
	 *
	 * @return a new VerifiableCredentialBuilder instance
	 */
	public VerifiableCredentialBuilder addCredential() {
		return new VerifiableCredentialBuilder(identity) {
			@Override
			public VerifiableCredentialBuilder subject(Id subject) {
				// Enforce that credential subject matches the holder identity
				if (subject != null && !subject.equals(identity.getId()))
					throw new IllegalArgumentException("Credential subject does not match the holder");

				return super.subject(subject);
			}

			@Override
			public VerifiableCredential build() {
				VerifiableCredential vc = super.build();
				// Automatically add the built credential to this presentation
				addCredential(vc);
				return vc;
			}
		};
	}

	/**
	 * Builds the VerifiablePresentation instance.
	 * <p>
	 * Validates that at least one credential is present.
	 * Constructs an unsigned VerifiablePresentation, signs it with the holder's identity key,
	 * creates a Proof object, and returns the presentation with the proof attached.
	 *
	 * @return the signed VerifiablePresentation instance
	 * @throws IllegalStateException if no credentials have been added
	 */
	@Override
	public VerifiablePresentation build() {
		if (credentials.isEmpty())
			throw new IllegalStateException("Credentials cannot be empty");

		List<VerifiableCredential> credentials = new ArrayList<>(this.credentials.values());
		// Create unsigned VerifiablePresentation object
		VerifiablePresentation unsigned = new VerifiablePresentation(contexts, id, types, identity.getId(), credentials);
		// Sign the presentation data with holder's identity key
		byte[] signature = identity.sign(unsigned.getSignData());
		// Create cryptographic proof object with signature
		Proof proof = new Proof(Proof.Type.Ed25519Signature2020, now(),
				VerificationMethod.defaultReferenceOf(identity.getId()),
				Proof.Purpose.assertionMethod, signature);

		// Return the VerifiablePresentation with attached proof
		return new VerifiablePresentation(unsigned, proof);
	}
}