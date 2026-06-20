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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import org.jspecify.annotations.Nullable;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.InvalidSignatureException;

/**
 * Representation of a W3C-compliant Decentralized Identifier (DID) Document
 * in the Boson network.
 * <p>
 * A DID Document describes the public keys, authentication methods,
 * verification methods, services, and credentials associated with a DID.
 * This class provides methods to parse, validate, and convert DID Documents
 * from/to {@link Card} objects, as well as to verify cryptographic proofs.
 */
@JsonPropertyOrder({"@context", "id", "verificationMethod", "authentication", "assertion", "verifiableCredential", "service", "proof"})
public class DIDDocument extends W3CDIDFormat {
	/** The list of JSON-LD context URIs associated with this DID Document. */
	@JsonProperty("@context")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<String> contexts;
	/** The unique identifier (DID) for this document. */
	@JsonProperty("id")
	private final Id id;
	/** The list of verification methods (public keys, etc) for this DID. */
	@JsonProperty("verificationMethod")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<VerificationMethod> verificationMethods;
	/** The list of authentication methods (references or methods) for this DID. */
	@JsonProperty("authentication")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<VerificationMethod> authentications;
	/** The list of assertion methods (references or methods) for this DID. */
	@JsonProperty("assertion")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<VerificationMethod> assertions;
	/** The list of verifiable credentials associated with this DID. */
	@JsonProperty("verifiableCredential")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<VerifiableCredential> credentials;
	/** The list of service endpoints described by this DID Document. */
	@JsonProperty("service")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<Service> services;
	/** The cryptographic proof (signature) for this DID Document. */
	@JsonProperty("proof")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final @Nullable Proof proof;

	/**
	 * The internal CardView adapter for this document, used for signature/serialization.
	 */
	private transient volatile @Nullable CardView cardView;

	/**
	 * Constructs a DIDDocument by deserializing all fields.
	 * Resolves all verification method references in authentication and assertion lists.
	 *
	 * @param contexts JSON-LD context URIs for this document
	 * @param id The DID identifier
	 * @param verificationMethods List of verification methods (must NOT be references)
	 * @param authentications List of authentication methods (may be references)
	 * @param assertions List of assertion methods (may be references)
	 * @param credentials List of verifiable credentials
	 * @param services List of service endpoints
	 * @param proof Cryptographic proof for the document
	 * @throws IllegalArgumentException if references are invalid or required fields are missing
	 */
	@JsonCreator
	public DIDDocument(@JsonProperty(value = "@context") @Nullable List<String> contexts,
					   @JsonProperty(value = "id", required = true) Id id,
					   @JsonProperty(value = "verificationMethod", required = true) List<VerificationMethod> verificationMethods,
					   @JsonProperty(value = "authentication") @Nullable List<VerificationMethod> authentications,
					   @JsonProperty(value = "assertion") @Nullable List<VerificationMethod> assertions,
					   @JsonProperty(value = "verifiableCredential") @Nullable List<VerifiableCredential> credentials,
					   @JsonProperty(value = "service") @Nullable List<Service> services,
					   @JsonProperty(value = "proof", required = true) Proof proof) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(verificationMethods, "verificationMethods");
		Objects.requireNonNull(proof, "proof");

		this.contexts = contexts == null || contexts.isEmpty() ? List.of() : List.copyOf(contexts);
		this.id = id;

		// Validate that verificationMethods contains only concrete methods (no references)
		List<VerificationMethod> methods = new ArrayList<>();
		for (VerificationMethod vm : verificationMethods) {
			if (vm.isReference())
				throw new IllegalArgumentException("verificationMethod must not be a reference");

			methods.add(vm);
		}

		// Resolve authentication references and ensure referenced methods exist
		List<VerificationMethod> auths = new ArrayList<>();
		if (authentications != null && !authentications.isEmpty()) {
			for (VerificationMethod vm : authentications) {
				if (vm instanceof VerificationMethod.Reference vmr) {
					// Check that the referenced verification method exists in methods list
					VerificationMethod entity = methods.stream()
							.filter(v -> v.getId().equals(vm.getId()))
							.findAny()
							.orElse(null);
					if (entity == null)
						throw new IllegalArgumentException("authentications contains an invalid reference");
					// Update the reference to point to the actual method
					vmr.updateReference(entity);
					auths.add(vm);
				} else {
					// Add new method and reference if not a reference
					methods.add(vm);
					auths.add(vm.getReference());
				}
			}
		}

		// Resolve assertion references and ensure referenced methods exist
		List<VerificationMethod> as = new ArrayList<>();
		if (assertions != null && !assertions.isEmpty()) {
			for (VerificationMethod vm : assertions) {
				if (vm instanceof VerificationMethod.Reference vmr) {
					// Check that the referenced verification method exists in methods list
					VerificationMethod entity = methods.stream()
							.filter(v -> v.getId().equals(vm.getId()))
							.findAny()
							.orElse(null);
					if (entity == null)
						throw new IllegalArgumentException("assertions contains an invalid reference");

					// Update the reference to point to the actual method
					vmr.updateReference(entity);
					as.add(vm);
				} else {
					// Add new method and reference if not a reference
					methods.add(vm);
					as.add(vm.getReference());
				}
			}
		}

		this.verificationMethods = methods.isEmpty() ? List.of() : List.copyOf(methods);
		this.authentications = auths.isEmpty() ? List.of() : List.copyOf(auths);
		this.assertions = as.isEmpty() ? List.of() : List.copyOf(as);
		this.credentials = credentials == null || credentials.isEmpty() ? List.of() : List.copyOf(credentials);
		this.services = services == null || services.isEmpty() ? List.of() : List.copyOf(services);
		this.proof = proof;
	}

	/**
	 * Internal constructor used by builder.
	 * The caller should transfer ownership of the collections to the new instance.
	 *
	 * @param contexts JSON-LD context URIs
	 * @param id The DID identifier
	 * @param verificationMethods Verification methods
	 * @param authentications Authentication methods
	 * @param assertions Assertion methods
	 * @param credentials Verifiable credentials
	 * @param services Service endpoints
	 */
	protected DIDDocument(List<String> contexts, Id id, List<VerificationMethod> verificationMethods,
						  @Nullable List<VerificationMethod> authentications, @Nullable List<VerificationMethod> assertions,
						  @Nullable List<VerifiableCredential> credentials, @Nullable List<Service> services) {
		this.contexts = contexts;
		this.id = id;
		this.verificationMethods = verificationMethods.isEmpty() ? List.of() : List.copyOf(verificationMethods);
		this.authentications = authentications == null || authentications.isEmpty() ? List.of() : List.copyOf(authentications);
		this.assertions = assertions == null || assertions.isEmpty() ? List.of() : List.copyOf(assertions);
		this.credentials = credentials == null || credentials.isEmpty() ? List.of() : List.copyOf(credentials);
		this.services = services == null || services.isEmpty() ? List.of() : List.copyOf(services);
		this.proof = null;
	}

	/**
	 * Constructs a new DIDDocument as a copy of an unsigned document with a given proof.
	 * Used for signing a built document.
	 *
	 * @param unsigned The unsigned DIDDocument
	 * @param proof The cryptographic proof to attach
	 */
	protected DIDDocument(DIDDocument unsigned, Proof proof) {
		this.contexts = unsigned.getContexts();
		this.id = unsigned.getId();
		this.verificationMethods = unsigned.getVerificationMethods();
		this.authentications = unsigned.getAuthentications();
		this.assertions = unsigned.getAssertions();
		this.credentials = unsigned.getCredentials();
		this.services = unsigned.getServices();
		this.proof = proof;
	}

	/**
	 * Returns the list of JSON-LD context URIs associated with this document.
	 * @return List of context URIs
	 */
	public List<String> getContexts() {
		return contexts;
	}

	/**
	 * Returns the unique DID identifier for this document.
	 * @return The DID id
	 */
	public Id getId() {
		return id;
	}

	/**
	 * Returns all verification methods defined in this document.
	 * @return List of verification methods
	 */
	public List<VerificationMethod> getVerificationMethods() {
		return verificationMethods;
	}

	/**
	 * Returns all verification methods of the specified type.
	 * @param type The type of verification method
	 * @return List of matching verification methods
	 */
	public List<VerificationMethod> getVerificationMethods(VerificationMethod.Type type) {
		Objects.requireNonNull(type, "type");
		return verificationMethods.stream()
				.filter(vm -> vm.getType() == type)
				.collect(Collectors.toList());
	}

	/**
	 * Returns the verification method with the specified id, or empty if not found.
	 * @param id The id of the verification method (DID URL or fragment)
	 * @return an {@link Optional} with the verification method, or empty if not found
	 */
	public Optional<VerificationMethod> getVerificationMethod(String id) {
		Objects.requireNonNull(id, "id");
		DIDURL idUrl = id.startsWith(DIDConstants.DID_SCHEME + ":") ?
				DIDURL.create(id) : new DIDURL(getId(), null, null, id);

		return getVerificationMethod(idUrl);
	}

	/**
	 * Returns the verification method with the specified DIDURL, or empty if not found.
	 * @param id The DIDURL of the verification method
	 * @return an {@link Optional} with the verification method, or empty if not found
	 */
	public Optional<VerificationMethod> getVerificationMethod(DIDURL id) {
		Objects.requireNonNull(id, "id");

		String sid = id.toString();
		return verificationMethods.stream()
			.filter(vm -> vm.getId().equals(sid))
			.findFirst();
	}

	/**
	 * Returns the list of authentication methods for this DID.
	 * @return List of authentication methods
	 */
	public List<VerificationMethod> getAuthentications() {
		return authentications;
	}

	/**
	 * Returns the authentication method with the specified id, or empty if not found.
	 * @param id The id of the authentication method (DID URL or fragment)
	 * @return an {@link Optional} with the authentication method, or empty if not found
	 */
	public Optional<VerificationMethod> getAuthentication(String id) {
		Objects.requireNonNull(id, "id");
		DIDURL idUrl = id.startsWith(DIDConstants.DID_SCHEME + ":") ?
				DIDURL.create(id) : new DIDURL(getId(), null, null, id);

		return getAuthentication(idUrl);
	}

	/**
	 * Returns the authentication method with the specified DIDURL, or empty if not found.
	 * @param id The DIDURL of the authentication method
	 * @return an {@link Optional} with the authentication method, or empty if not found
	 */
	public Optional<VerificationMethod> getAuthentication(DIDURL id) {
		Objects.requireNonNull(id, "id");

		String sid = id.toString();
		return authentications.stream()
			.filter(vm -> vm.getId().equals(sid))
			.findFirst();
	}

	/**
	 * Returns the list of assertion methods for this DID.
	 * @return List of assertion methods
	 */
	public List<VerificationMethod> getAssertions() {
		return assertions;
	}

	/**
	 * Returns the assertion method with the specified id, or empty if not found.
	 * @param id The id of the assertion method (DID URL or fragment)
	 * @return an {@link Optional} with the assertion method, or empty if not found
	 */
	public Optional<VerificationMethod> getAssertion(String id) {
		Objects.requireNonNull(id, "id");
		DIDURL idUrl = id.startsWith(DIDConstants.DID_SCHEME + ":") ?
				DIDURL.create(id) : new DIDURL(getId(), null, null, id);

		return getAssertion(idUrl);
	}

	/**
	 * Returns the assertion method with the specified DIDURL, or empty if not found.
	 * @param id The DIDURL of the assertion method
	 * @return an {@link Optional} with the assertion method, or empty if not found
	 */
	public Optional<VerificationMethod> getAssertion(DIDURL id) {
		Objects.requireNonNull(id, "id");

		String sid = id.toString();
		return assertions.stream()
			.filter(vm -> vm.getId().equals(sid))
			.findFirst();
	}

	/**
	 * Returns the list of verifiable credentials associated with this DID.
	 * @return List of verifiable credentials
	 */
	public List<VerifiableCredential> getCredentials() {
		return credentials;
	}

	/**
	 * Returns the list of verifiable credentials containing the specified type.
	 * @param type Credential type to filter by
	 * @return List of verifiable credentials of given type
	 */
	public List<VerifiableCredential> getCredentials(String type) {
		Objects.requireNonNull(type, "type");
		return credentials.stream()
				.filter(vc -> vc.getTypes().contains(type))
				.collect(Collectors.toList());
	}

	/**
	 * Returns the verifiable credential with the specified id, or empty if not found.
	 * @param id The id of the credential (DID URL or fragment)
	 * @return an {@link Optional} with the verifiable credential, or empty if not found
	 */
	public Optional<VerifiableCredential> getCredential(String id) {
		Objects.requireNonNull(id, "id");
		DIDURL idUrl = id.startsWith(DIDConstants.DID_SCHEME + ":") ?
				DIDURL.create(id) : new DIDURL(getId(), null, null, id);

		return getCredential(idUrl);
	}

	/**
	 * Returns the verifiable credential with the specified DIDURL, or empty if not found.
	 * @param id The DIDURL of the credential
	 * @return an {@link Optional} with the verifiable credential, or empty if not found
	 */
	public Optional<VerifiableCredential> getCredential(DIDURL id) {
		Objects.requireNonNull(id, "id");

		String sid = id.toString();
		return credentials.stream()
			.filter(vc -> vc.getId().equals(sid))
			.findFirst();
	}

	/**
	 * Retrieves a verifiable credential based on the specified identifier and type.
	 *
	 * @param id the identifier of the credential to be retrieved; must not be null
	 * @param type the type of the credential to be retrieved; must not be null
	 * @return the verifiable credential matching the specified identifier and type
	 * @throws NullPointerException if either the id or type is null
	 */
	public Optional<VerifiableCredential> getCredential(String id, String type) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		DIDURL idUrl = id.startsWith(DIDConstants.DID_SCHEME + ":") ?
				DIDURL.create(id) : new DIDURL(getId(), null, null, id);

		return getCredential(idUrl, type);
	}

	/**
	 * Retrieves a verifiable credential that matches the specified DID URL and credential type.
	 *
	 * @param id   The DID URL that uniquely identifies the credential. Must not be null.
	 * @param type The type of the credential to retrieve. Must not be null.
	 * @return A {@code VerifiableCredential} instance that matches the given ID and type,
	 *         or {@code null} if no matching credential is found.
	 */
	public Optional<VerifiableCredential> getCredential(DIDURL id, String type) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");

		String sid = id.toString();
		return credentials.stream()
				.filter(vc -> vc.getId().equals(sid) && vc.getTypes().contains(type))
				.findFirst();
	}

	/**
	 * Returns the list of service endpoints described by this document.
	 * @return List of service endpoints
	 */
	public List<Service> getServices() {
		return services;
	}

	/**
	 * Returns all services of the specified type.
	 * @param type Service type to filter by
	 * @return List of matching services
	 */
	public List<Service> getServices(String type) {
		Objects.requireNonNull(type, "type");
		return services.stream()
				.filter(service -> service.getType().equals(type))
				.collect(Collectors.toList());
	}

	/**
	 * Returns the service with the specified id, or empty if not found.
	 * @param id The id of the service (DID URL or fragment)
	 * @return an {@link Optional} with the service, or empty if not found
	 */
	public Optional<Service> getService(String id) {
		Objects.requireNonNull(id, "id");
		DIDURL idUrl = id.startsWith(DIDConstants.DID_SCHEME + ":") ?
				DIDURL.create(id) : new DIDURL(getId(), null, null, id);

		return getService(idUrl);
	}

	/**
	 * Returns the service with the specified DIDURL, or empty if not found.
	 * @param id The DIDURL of the service
	 * @return an {@link Optional} with the service, or empty if not found
	 */
	public Optional<Service> getService(DIDURL id) {
		Objects.requireNonNull(id, "id");

		String sid = id.toString();
		return services.stream()
			.filter(service -> service.getId().equals(sid))
			.findFirst();
	}

	/**
	 * Retrieves a service object based on the provided service identifier and type.
	 *
	 * @param id the unique identifier of the service, must not be null
	 * @param type the type of the service, must not be null
	 * @return the service object associated with the given identifier and type
	 * @throws NullPointerException if either the id or type is null
	 */
	public Optional<Service> getService(String id, String type) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		DIDURL idUrl = id.startsWith(DIDConstants.DID_SCHEME + ":") ?
				DIDURL.create(id) : new DIDURL(getId(), null, null, id);

		return getService(idUrl, type);
	}

	/**
	 * Retrieves a service based on its DIDURL identifier and type.
	 *
	 * @param id the DIDURL identifier of the service; must not be null
	 * @param type the type of the service; must not be null
	 * @return the matching Service object if found, otherwise null
	 */
	public Optional<Service> getService(DIDURL id, String type) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");

		String sid = id.toString();
		return services.stream()
				.filter(service -> service.getId().equals(sid) && service.getType().equals(type))
				.findFirst();
	}

	/**
	 * Returns the cryptographic proof (signature) for this DID Document.
	 * @return The proof
	 */
	public Proof getProof() {
		return Objects.requireNonNull(proof);
	}

	/**
	 * Checks if the DID Document's proof is genuine (signature is valid).
	 * @return true if the proof is valid, false otherwise
	 */
	public boolean isGenuine() {
		if (proof == null)
			return false;

		return proof.verify(id, getSignData());
	}

	/**
	 * Validates the DID Document's proof, throwing if invalid.
	 * @throws InvalidSignatureException if the proof is invalid
	 */
	public void validate() throws InvalidSignatureException {
		if (!isGenuine())
			throw new InvalidSignatureException();
	}

	/**
	 * Converts this DID Document to a corresponding Boson Card object.
	 * @return The Card representation of this DID Document
	 */
	public Card toCard() {
		CardView view = cardView;
		if (view == null) {
			view = new CardView(this);
			this.cardView = view;
		}

		return view;
	}

	/**
	 * Creates a DIDDocument from a Card object, using the specified document and credential contexts.
	 * This adapts a Boson Card to a W3C-compliant DID Document.
	 *
	 * @param card The Card to convert
	 * @param documentContexts Additional context URIs for the document
	 * @param vcTypeContexts Map of credential type to context URIs
	 * @return The corresponding DIDDocument
	 */
	public static DIDDocument fromCard(Card card, @Nullable List<String> documentContexts,
									   @Nullable Map<String, List<String>> vcTypeContexts) {
		if (card instanceof CardView bc)
			return bc.getDocument();

		List<String> contexts = new ArrayList<>();
		contexts.add(DIDConstants.W3C_DID_CONTEXT);
		contexts.add(DIDConstants.BOSON_DID_CONTEXT);
		contexts.add(DIDConstants.W3C_ED25519_CONTEXT);

		if (documentContexts != null && !documentContexts.isEmpty()) {
			for (String context : documentContexts) {
				if (!contexts.contains(context))
					contexts.add(context);
			}
		}

		VerificationMethod defaultMethod = VerificationMethod.defaultOf(card.getId());
		VerificationMethod defaultMethodRef = defaultMethod.getReference();

		DIDDocument doc = new DIDDocument(contexts, card.getId(),
				List.of(defaultMethod), List.of(defaultMethodRef), List.of(defaultMethodRef),
				card.getCredentials().stream().map(c -> VerifiableCredential.fromCredential(c, vcTypeContexts))
						.collect(Collectors.toList()),
				card.getServices().stream().map(s -> new Service(s.getId(), s.getType(), s.getEndpoint(), s.getProperties()))
						.collect(Collectors.toList()),
				new Proof(Proof.Type.Ed25519Signature2020, card.getSignedAt(), defaultMethodRef,
						Proof.Purpose.assertionMethod, card.getSignature()));

		doc.cardView = new CardView(card, doc);
		return doc;
	}

	/**
	 * Creates a DIDDocument from a Card object with the given credential contexts.
	 * @param card The Card to convert
	 * @param vcTypeContexts Map of credential type to context URIs
	 * @return The corresponding DIDDocument
	 */
	public static DIDDocument fromCard(Card card, Map<String, List<String>> vcTypeContexts) {
		return fromCard(card, List.of(), vcTypeContexts);
	}

	/**
	 * Creates a DIDDocument from a Card object with default contexts.
	 * @param card The Card to convert
	 * @return The corresponding DIDDocument
	 */
	public static DIDDocument fromCard(Card card) {
		return fromCard(card, List.of(), Map.of());
	}

	/**
	 * Returns the signable data for this document, used for signature verification.
	 * @return Byte array of signable data
	 */
	protected byte[] getSignData() {
		// At verification time the proof is non-null and provides the signed-at timestamp,
		// so the CardView view rebuilds the exact bytes the underlying Card signature covers
		// (id, credentials, services, sat).
		Card view = toCard();
		return view.getSignData();
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, contexts, verificationMethods, authentications, assertions, services, credentials, proof);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;

		if (o instanceof DIDDocument that)
			return Objects.equals(id, that.id) &&
					Objects.equals(contexts, that.contexts) &&
					Objects.equals(verificationMethods, that.verificationMethods) &&
					Objects.equals(authentications, that.authentications) &&
					Objects.equals(assertions, that.assertions) &&
					Objects.equals(services, that.services) &&
					Objects.equals(credentials, that.credentials) &&
					Objects.equals(proof, that.proof);

		return false;
	}

	/**
	 * Parses a DIDDocument from its JSON representation.
	 * @param json The JSON string
	 * @return The parsed DIDDocument
	 */
	public static DIDDocument parse(String json) {
		return parse(json, DIDDocument.class);
	}

	/**
	 * Parses a DIDDocument from its CBOR byte representation.
	 * @param cbor The CBOR bytes
	 * @return The parsed DIDDocument
	 */
	public static DIDDocument parse(byte[] cbor) {
		return parse(cbor, DIDDocument.class);
	}

	/**
	 * Creates a new builder for constructing a DIDDocument for the given subject.
	 * @param subject The identity subject of the document
	 * @return A new DIDDocumentBuilder
	 */
	public static DIDDocumentBuilder builder(Identity subject) {
		Objects.requireNonNull(subject, "subject");
		return new DIDDocumentBuilder(subject);
	}

	/**
	 * Internal adapter class that wraps a DIDDocument as a {@link Card}.
	 * Used for signature and serialization compatibility with Boson cards.
	 */
	protected static class CardView extends Card {
		/** The wrapped DIDDocument instance. */
		private final DIDDocument doc;

		/**
		 * Constructs a CardView from a DIDDocument (signed).
		 * @param doc The DIDDocument to wrap
		 */
		protected CardView(DIDDocument doc) {
			super(doc.id,
					doc.credentials.stream().map(VerifiableCredential::toCredential).collect(Collectors.toList()),
					doc.services.stream().map(s -> new Card.Service(s.getId(), s.getType(), s.getEndpoint(), s.getProperties()))
							.collect(Collectors.toList()),
					Objects.requireNonNull(doc.proof).getCreated(), doc.proof.getProofValue());

			this.doc = doc;
		}

		/**
		 * Constructs a sat-stamped, unsigned CardView view of a DIDDocument.
		 * <p>
		 * Used at sign time by {@link DIDDocumentBuilder} to compute the bytes the signature will
		 * cover. {@code signedAt} must be set so that it is part of the signed data; the resulting
		 * proof's {@code created} value should be the same timestamp.
		 *
		 * @param doc the DIDDocument to wrap
		 * @param signedAt the signing timestamp to embed in the signed bytes
		 */
		protected CardView(DIDDocument doc, Date signedAt) {
			super(doc.id,
					doc.credentials.stream().map(VerifiableCredential::toCredential).collect(Collectors.toList()),
					doc.services.stream().map(s -> new Card.Service(s.getId(), s.getType(), s.getEndpoint(), s.getProperties()))
							.collect(Collectors.toList()),
					signedAt);

			this.doc = doc;
		}

		/**
		 * Constructs a CardView from an existing Card and DIDDocument.
		 * @param card The Card to wrap
		 * @param doc The associated DIDDocument
		 */
		protected CardView(Card card, DIDDocument doc) {
			super(card, card.getSignature());
			this.doc = doc;
		}

		@Override
		protected byte[] getSignData() {
			return super.getSignData();
		}

		/**
		 * Returns the wrapped DIDDocument instance.
		 * @return The DIDDocument
		 */
		public DIDDocument getDocument() {
			return doc;
		}
	}

	/**
	 * Representation of a DID Document service endpoint.
	 * Each service describes a protocol endpoint associated with the DID subject.
	 */
	@JsonPropertyOrder({"id", "type", "serviceEndpoint"})
	public static class Service {
		/** The unique identifier of the service (DID URL or fragment). */
		@JsonProperty("id")
		private final String id;
		/** The type of the service (e.g., "LinkedDomains"). */
		@JsonProperty("type")
		private final String type;
		/** The endpoint URI for the service. */
		@JsonProperty("serviceEndpoint")
		private final String endpoint;
		/** Additional custom properties of the service. */
		@JsonAnyGetter
		@JsonAnySetter
		private final Map<String, Object> properties;

		/**
		 * Constructs a Service instance from JSON properties.
		 * @param id The service id
		 * @param type The service type
		 * @param endpoint The service endpoint URI
		 */
		@JsonCreator
		protected Service(@JsonProperty(value = "id", required = true) String id,
						  @JsonProperty(value = "type", required = true) String type,
						  @JsonProperty(value = "serviceEndpoint", required = true) String endpoint) {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(type, "type");
			Objects.requireNonNull(endpoint, "serviceEndpoint");

			this.id = id;
			this.type = type;
			this.endpoint = endpoint;
			this.properties = new LinkedHashMap<>();
		}

		/**
		 * Constructs a Service instance with explicit properties.
		 * @param id The service id
		 * @param type The service type
		 * @param endpoint The service endpoint URI
		 * @param properties Additional service properties
		 */
		protected Service(String id, String type, String endpoint, @Nullable Map<String, Object> properties) {
			this.id = id;
			this.type = type;
			this.endpoint = endpoint;
			this.properties = properties == null || properties.isEmpty() ? Map.of() : new LinkedHashMap<>(properties);
		}

		/**
		 * Returns the service id.
		 * @return The id
		 */
		public String getId() {
			return id;
		}

		/**
		 * Returns the service type.
		 * @return The type
		 */
		public String getType() {
			return type;
		}

		/**
		 * Returns the service endpoint URI.
		 * @return The endpoint URI
		 */
		public String getEndpoint() {
			return endpoint;
		}

		/**
		 * Returns an unmodifiable map of all additional service properties.
		 * @return The property map
		 */
		public Map<String, Object> getProperties() {
			return Collections.unmodifiableMap(properties);
		}

		/**
		 * Returns the value of a named property, or null if not present.
		 * @param name Property name
		 * @return The property value, or null
		 * @param <T> The property type
		 */
		@SuppressWarnings("unchecked")
		public <T> @Nullable T getProperty(String name) {
			return (T) properties.get(name);
		}

		@Override
		public int hashCode() {
			return Objects.hash(id, type, endpoint, properties);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o)
				return true;

			if (o instanceof Service that)
				return Objects.equals(id, that.id) &&
						Objects.equals(type, that.type) &&
						Objects.equals(endpoint, that.endpoint) &&
						Objects.equals(properties, that.properties);

			return false;
		}
	}
}