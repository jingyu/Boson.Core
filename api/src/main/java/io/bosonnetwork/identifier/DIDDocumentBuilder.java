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

public class DIDDocumentBuilder extends BosonIdentityObjectBuilder<DIDDocument> {
	private final List<String> contexts;
	private final Map<String, VerificationMethod> verificationMethods;
	private final Map<String, VerificationMethod> authentications;
	private final Map<String, VerificationMethod> assertions;
	private final Map<String, VerifiableCredential> credentials;
	private final Map<String, DIDDocument.Service> services;

	private final VerificationMethod defaultMethodRef;

	protected DIDDocumentBuilder(Identity identity) {
		super(identity);

		contexts = new ArrayList<>();
		verificationMethods = new LinkedHashMap<>();
		authentications = new LinkedHashMap<>();
		assertions = new LinkedHashMap<>();
		credentials = new LinkedHashMap<>();
		services = new LinkedHashMap<>();

		context(DIDConstants.W3C_DID_CONTEXT, DIDConstants.BOSON_DID_CONTEXT, DIDConstants.W3C_ED25519_CONTEXT);

		VerificationMethod defaultMethod = VerificationMethod.defaultOf(identity.getId());
		defaultMethodRef = defaultMethod.getReference();

		addVerificationMethod(defaultMethod);
		addAuthentication(defaultMethodRef);
		addAssertion(defaultMethodRef);
	}

	public DIDDocumentBuilder context(String... contexts) {
		return context(List.of(contexts));
	}

	public DIDDocumentBuilder context(List<String> contexts) {
		for (String context : contexts) {
			context = normalize(context);

			if (context != null && !this.contexts.contains(context))
				this.contexts.add(context);
		}

		return this;
	}

	protected DIDDocumentBuilder addVerificationMethod(VerificationMethod vm) {
		Objects.requireNonNull(vm, "vm");
		if (vm.isReference())
			throw new IllegalArgumentException("VerificationMethod is a reference");

		verificationMethods.put(vm.getId(), vm);
		return this;
	}

	protected DIDDocumentBuilder addAuthentication(VerificationMethod vm) {
		Objects.requireNonNull(vm, "vm");
		if (vm instanceof VerificationMethod.Reference vmr) {
			// check that the referenced verification method exists
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

	protected DIDDocumentBuilder addAssertion(VerificationMethod vm) {
		Objects.requireNonNull(vm, "vm");
		if (vm instanceof VerificationMethod.Reference vmr) {
			// check that the referenced verification method exists
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

	public DIDDocumentBuilder addCredential(VerifiableCredential vc) {
		Objects.requireNonNull(vc, "vc");
		if (!vc.getSubject().getId().equals(identity.getId()))
			throw new IllegalArgumentException("VerifiableCredential subject does not match identity");

		credentials.put(vc.getId(), vc);
		return this;
	}

	public DIDDocumentBuilder addCredential(VerifiableCredential... vcs) {
		return addCredential(List.of(vcs));
	}

	public DIDDocumentBuilder addCredential(List<VerifiableCredential> vcs) {
		for (VerifiableCredential vc : vcs) {
			if (vc != null)
				addCredential(vc);
		}

		return this;
	}

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

	public VerifiableCredentialBuilder addCredential() {
		return new VerifiableCredentialBuilder(identity) {
			@Override
			public VerifiableCredentialBuilder subject(Id subject) {
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

	public DIDDocumentBuilder addService(String id, String type, String endpoint, Map<String, Object> properties) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(endpoint, "endpoint");

		DIDURL idUrl;
		if (id.startsWith("did:")) {
			idUrl = DIDURL.create(id);
			if (!idUrl.getId().equals(identity.getId()))
				throw new IllegalStateException("Invalid credential id: should be the subject id based DIDURL");
			if (idUrl.getFragment() == null)
				throw new IllegalStateException("Invalid credential id: should has the fragment part");
		} else {
			idUrl = new DIDURL(identity.getId(), null, null, id);
		}

		if (properties == null || properties.isEmpty()) {
			properties = Map.of();
		} else {
			if (properties.keySet().stream().anyMatch(k -> k.equals("id") ||
					k.equals("type") || k.equals("serviceEndpoint")))
				throw new IllegalArgumentException("Service properties cannot contain 'id', 'type' or 'serviceEndpoint'");

			properties = new LinkedHashMap<>(properties);
		}

		String canonicalId = idUrl.toString();
		services.put(canonicalId, new DIDDocument.Service(canonicalId, type, endpoint, properties));
		return this;
	}

	public DIDDocumentBuilder addService(String id, String type, String endpoint) {
		return addService(id, type, endpoint, null);
	}

	public DIDDocumentBuilder addService(String id, String type, String endpoint, String prop1, Object value1) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(endpoint, "endpoint");
		Objects.requireNonNull(prop1, "prop1");

		Map<String, Object> properties = Map.of(prop1, value1);

		return addService(id, type, endpoint, properties);
	}

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