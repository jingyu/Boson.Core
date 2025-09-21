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
 * Builder class for constructing {@link DIDDocument} objects.
 * <p>
 * This builder enforces identity consistency and validates references and properties
 * of verification methods, credentials, and services. It initializes with default
 * contexts and a default verification method derived from the provided identity.
 */
public class DIDDocumentBuilder extends BosonIdentityObjectBuilder<DIDDocument> {
	/** List of JSON-LD context URIs included in the DID Document. */
	private final List<String> contexts;
	/** Map of verification methods keyed by their IDs. */
	private final Map<String, VerificationMethod> verificationMethods;
	/** Map of authentication verification methods keyed by their IDs. */
	private final Map<String, VerificationMethod> authentications;
	/** Map of assertion verification methods keyed by their IDs. */
	private final Map<String, VerificationMethod> assertions;
	/** Map of verifiable credentials keyed by their IDs. */
	private final Map<String, VerifiableCredential> credentials;
	/** Map of DID Document services keyed by their canonical IDs. */
	private final Map<String, DIDDocument.Service> services;

	/** Reference to the default verification method used for signing. */
	private final VerificationMethod defaultMethodRef;

	/**
	 * Constructs a new DIDDocumentBuilder for the given identity.
	 * <p>
	 * Initializes default contexts, creates a default verification method from the identity's ID,
	 * and adds it as verification method, authentication, and assertion.
	 *
	 * @param identity the identity for which the DID Document is being built
	 */
	protected DIDDocumentBuilder(Identity identity) {
		super(identity);

		contexts = new ArrayList<>();
		verificationMethods = new LinkedHashMap<>();
		authentications = new LinkedHashMap<>();
		assertions = new LinkedHashMap<>();
		credentials = new LinkedHashMap<>();
		services = new LinkedHashMap<>();

		// Initialize with standard contexts
		context(DIDConstants.W3C_DID_CONTEXT, DIDConstants.BOSON_DID_CONTEXT, DIDConstants.W3C_ED25519_CONTEXT);

		VerificationMethod defaultMethod = VerificationMethod.defaultOf(identity.getId());
		defaultMethodRef = defaultMethod.getReference();

		addVerificationMethod(defaultMethod);
		addAuthentication(defaultMethodRef);
		addAssertion(defaultMethodRef);
	}

	/**
	 * Adds one or more JSON-LD context URIs to the DID Document.
	 *
	 * @param contexts array of context URIs
	 * @return this builder instance
	 */
	public DIDDocumentBuilder context(String... contexts) {
		return context(List.of(contexts));
	}

	/**
	 * Adds a list of JSON-LD context URIs to the DID Document.
	 * Duplicate contexts are ignored.
	 *
	 * @param contexts list of context URIs
	 * @return this builder instance
	 */
	public DIDDocumentBuilder context(List<String> contexts) {
		for (String context : contexts) {
			context = normalize(context);

			if (context != null && !this.contexts.contains(context))
				this.contexts.add(context);
		}

		return this;
	}

	/**
	 * Adds a verification method to the DID Document.
	 * <p>
	 * The verification method must not be a reference.
	 *
	 * @param vm the verification method to add
	 * @return this builder instance
	 * @throws IllegalArgumentException if the verification method is a reference
	 */
	protected DIDDocumentBuilder addVerificationMethod(VerificationMethod vm) {
		Objects.requireNonNull(vm, "vm");
		if (vm.isReference())
			throw new IllegalArgumentException("VerificationMethod is a reference");

		verificationMethods.put(vm.getId(), vm);
		return this;
	}

	/**
	 * Adds an authentication verification method.
	 * <p>
	 * If the method is a reference, it validates that the referenced verification method exists.
	 * If validation fails, an exception is thrown.
	 *
	 * @param vm the verification method or its reference
	 * @return this builder instance
	 * @throws IllegalArgumentException if the referenced verification method does not exist
	 */
	protected DIDDocumentBuilder addAuthentication(VerificationMethod vm) {
		Objects.requireNonNull(vm, "vm");
		if (vm instanceof VerificationMethod.Reference vmr) {
			// Validate that the referenced verification method exists
			VerificationMethod entity = verificationMethods.get(vm.getId());
			if (entity == null)
				throw new IllegalArgumentException("verification method is an invalid reference");

			vmr.updateReference(entity);
			authentications.put(vm.getId(), vm);
		} else {
			verificationMethods.put(vm.getId(), vm);
			authentications.put(vm.getId(), vm.getReference());
		}

		return this;
	}

	/**
	 * Adds an assertion verification method.
	 * <p>
	 * If the method is a reference, it validates that the referenced verification method exists.
	 * If validation fails, an exception is thrown.
	 *
	 * @param vm the verification method or its reference
	 * @return this builder instance
	 * @throws IllegalArgumentException if the referenced verification method does not exist
	 */
	protected DIDDocumentBuilder addAssertion(VerificationMethod vm) {
		Objects.requireNonNull(vm, "vm");
		if (vm instanceof VerificationMethod.Reference vmr) {
			// Validate that the referenced verification method exists
			VerificationMethod entity = verificationMethods.get(vm.getId());
			if (entity == null)
				throw new IllegalArgumentException("verification method is an invalid reference");

			vmr.updateReference(entity);
			assertions.put(vm.getId(), vm);
		} else {
			verificationMethods.put(vm.getId(), vm);
			assertions.put(vm.getId(), vm.getReference());
		}

		return this;
	}

	/**
	 * Adds a verifiable credential to the DID Document.
	 * <p>
	 * The credential's subject ID must match the identity's ID.
	 *
	 * @param vc the verifiable credential to add
	 * @return this builder instance
	 * @throws IllegalArgumentException if the credential subject does not match the identity
	 */
	public DIDDocumentBuilder addCredential(VerifiableCredential vc) {
		Objects.requireNonNull(vc, "vc");
		// Validate subject consistency
		if (!vc.getSubject().getId().equals(identity.getId()))
			throw new IllegalArgumentException("VerifiableCredential subject does not match identity");

		credentials.put(vc.getId(), vc);
		return this;
	}

	/**
	 * Adds multiple verifiable credentials to the DID Document.
	 *
	 * @param vcs array of verifiable credentials
	 * @return this builder instance
	 */
	public DIDDocumentBuilder addCredential(VerifiableCredential... vcs) {
		return addCredential(List.of(vcs));
	}

	/**
	 * Adds a list of verifiable credentials to the DID Document.
	 *
	 * @param vcs list of verifiable credentials
	 * @return this builder instance
	 */
	public DIDDocumentBuilder addCredential(List<VerifiableCredential> vcs) {
		for (VerifiableCredential vc : vcs) {
			if (vc != null)
				addCredential(vc);
		}

		return this;
	}

	/**
	 * Adds a verifiable credential with specified id, type, contexts, and claims.
	 *
	 * @param id       the credential ID
	 * @param type     the credential type
	 * @param contexts the list of contexts for the credential type
	 * @param claims   the claims map
	 * @return this builder instance
	 */
	public DIDDocumentBuilder addCredential(String id, String type, List<String> contexts, Map<String, Object> claims) {
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
	 * Adds a verifiable credential with one claim.
	 *
	 * @param id       the credential ID
	 * @param type     the credential type
	 * @param contexts the list of contexts for the credential type
	 * @param claim1   the claim key
	 * @param value1   the claim value
	 * @return this builder instance
	 */
	public DIDDocumentBuilder addCredential(String id, String type, List<String> contexts, String claim1, Object value1) {
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
	 * Adds a verifiable credential with two claims.
	 *
	 * @param id       the credential ID
	 * @param type     the credential type
	 * @param contexts the list of contexts for the credential type
	 * @param claim1   the first claim key
	 * @param value1   the first claim value
	 * @param claim2   the second claim key
	 * @param value2   the second claim value
	 * @return this builder instance
	 */
	public DIDDocumentBuilder addCredential(String id, String type, List<String> contexts, String claim1, Object value1,
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
	 * Adds a verifiable credential with three claims.
	 *
	 * @param id       the credential ID
	 * @param type     the credential type
	 * @param contexts the list of contexts for the credential type
	 * @param claim1   the first claim key
	 * @param value1   the first claim value
	 * @param claim2   the second claim key
	 * @param value2   the second claim value
	 * @param claim3   the third claim key
	 * @param value3   the third claim value
	 * @return this builder instance
	 */
	public DIDDocumentBuilder addCredential(String id, String type, List<String> contexts, String claim1, Object value1,
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
	 * Returns a builder for creating a verifiable credential.
	 * <p>
	 * The credential subject is validated to match the identity's ID.
	 * When built, the credential is automatically added to this DID Document builder.
	 *
	 * @return a verifiable credential builder
	 */
	public VerifiableCredentialBuilder addCredential() {
		return new VerifiableCredentialBuilder(identity) {
			@Override
			public VerifiableCredentialBuilder subject(Id subject) {
				// Ensure credential subject matches the identity
				if (subject != null && !subject.equals(identity.getId()))
					throw new IllegalArgumentException("Credential subject does not match identity");

				return super.subject(subject);
			}

			@Override
			public VerifiableCredential build() {
				VerifiableCredential vc = super.build();
				addCredential(vc);
				return vc;
			}
		};
	}

	/**
	 * Adds a service to the DID Document.
	 * <p>
	 * The service ID must be a DIDURL with the same subject as the identity and must contain a fragment.
	 * Reserved properties 'id', 'type', and 'serviceEndpoint' are not allowed in the properties map.
	 *
	 * @param id         the service ID, either a full DIDURL or fragment
	 * @param type       the service type
	 * @param endpoint   the service endpoint URI
	 * @param properties additional service properties
	 * @return this builder instance
	 * @throws IllegalStateException    if the service ID does not match the identity or lacks a fragment
	 * @throws IllegalArgumentException if reserved properties are included
	 */
	public DIDDocumentBuilder addService(String id, String type, String endpoint, Map<String, Object> properties) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(endpoint, "endpoint");

		DIDURL idUrl;
		if (id.startsWith("did:")) {
			idUrl = DIDURL.create(id);
			// Validate that the DIDURL subject matches the identity
			if (!idUrl.getId().equals(identity.getId()))
				throw new IllegalStateException("Invalid credential id: should be the subject id based DIDURL");
			// Validate that the DIDURL contains a fragment
			if (idUrl.getFragment() == null)
				throw new IllegalStateException("Invalid credential id: should has the fragment part");
		} else {
			idUrl = new DIDURL(identity.getId(), null, null, id);
		}

		if (properties == null || properties.isEmpty()) {
			properties = Map.of();
		} else {
			// Reserved properties are not allowed
			if (properties.keySet().stream().anyMatch(k -> k.equals("id") ||
					k.equals("type") || k.equals("serviceEndpoint")))
				throw new IllegalArgumentException("Service properties cannot contain 'id', 'type' or 'serviceEndpoint'");

			properties = new LinkedHashMap<>(properties);
		}

		String canonicalId = idUrl.toString();
		services.put(canonicalId, new DIDDocument.Service(canonicalId, type, endpoint, properties));
		return this;
	}

	/**
	 * Adds a service to the DID Document without additional properties.
	 *
	 * @param id       the service ID
	 * @param type     the service type
	 * @param endpoint the service endpoint URI
	 * @return this builder instance
	 */
	public DIDDocumentBuilder addService(String id, String type, String endpoint) {
		return addService(id, type, endpoint, null);
	}

	/**
	 * Adds a service with one additional property.
	 *
	 * @param id       the service ID
	 * @param type     the service type
	 * @param endpoint the service endpoint URI
	 * @param prop1    the property key
	 * @param value1   the property value
	 * @return this builder instance
	 */
	public DIDDocumentBuilder addService(String id, String type, String endpoint, String prop1, Object value1) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(endpoint, "endpoint");
		Objects.requireNonNull(prop1, "prop1");

		Map<String, Object> properties = Map.of(prop1, value1);

		return addService(id, type, endpoint, properties);
	}

	/**
	 * Adds a service with two additional properties.
	 *
	 * @param id       the service ID
	 * @param type     the service type
	 * @param endpoint the service endpoint URI
	 * @param prop1    the first property key
	 * @param value1   the first property value
	 * @param prop2    the second property key
	 * @param value2   the second property value
	 * @return this builder instance
	 */
	public DIDDocumentBuilder addService(String id, String type, String endpoint, String prop1, Object value1,
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
	 * Adds a service with three additional properties.
	 *
	 * @param id       the service ID
	 * @param type     the service type
	 * @param endpoint the service endpoint URI
	 * @param prop1    the first property key
	 * @param value1   the first property value
	 * @param prop2    the second property key
	 * @param value2   the second property value
	 * @param prop3    the third property key
	 * @param value3   the third property value
	 * @return this builder instance
	 */
	public DIDDocumentBuilder addService(String id, String type, String endpoint, String prop1, Object value1,
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
	 * Builds the DID Document.
	 * <p>
	 * Assembles all added contexts, verification methods, authentications, assertions,
	 * credentials, and services into an unsigned DID Document, signs it using the identity,
	 * and returns a signed DID Document.
	 *
	 * @return the signed DID Document
	 */
	@Override
	public DIDDocument build() {
		DIDDocument unsigned = new DIDDocument(contexts, identity.getId(), new ArrayList<>(verificationMethods.values()),
				new ArrayList<>(authentications.values()), new ArrayList<>(assertions.values()),
				credentials.isEmpty() ? Collections.emptyList() : new ArrayList<>(credentials.values()),
				services.isEmpty() ? Collections.emptyList() : new ArrayList<>(services.values()));

		byte[] signature = identity.sign(unsigned.getSignData());
		Proof proof = new Proof(Proof.Type.Ed25519Signature2020, now(), defaultMethodRef,
				Proof.Purpose.assertionMethod, signature);

		return new DIDDocument(unsigned, proof);
	}
}