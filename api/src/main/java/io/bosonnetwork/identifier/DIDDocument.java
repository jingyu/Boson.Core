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
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.InvalidSignatureException;

@JsonPropertyOrder({"@context", "id", "verificationMethod", "authentication", "assertion", "verifiableCredential", "service", "proof"})
public class DIDDocument extends W3CDIDFormat {
	@JsonProperty("@context")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<String> contexts;
	@JsonProperty("id")
	private final Id id;
	@JsonProperty("verificationMethod")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<VerificationMethod> verificationMethods;
	@JsonProperty("authentication")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<VerificationMethod> authentications;
	@JsonProperty("assertion")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<VerificationMethod> assertions;
	@JsonProperty("verifiableCredential")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<VerifiableCredential> credentials;
	@JsonProperty("service")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<Service> services;
	@JsonProperty("proof")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final Proof proof;

	private transient BosonCard bosonCard;

	@JsonCreator
	public DIDDocument(@JsonProperty(value = "@context") List<String> contexts,
					   @JsonProperty(value = "id", required = true) Id id,
					   @JsonProperty(value = "verificationMethod", required = true) List<VerificationMethod> verificationMethods,
					   @JsonProperty(value = "authentication") List<VerificationMethod> authentications,
					   @JsonProperty(value = "assertion") List<VerificationMethod> assertions,
					   @JsonProperty(value = "verifiableCredential") List<VerifiableCredential> credentials,
					   @JsonProperty(value = "service") List<Service> services,
					   @JsonProperty(value = "proof", required = true) Proof proof) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(verificationMethods, "verificationMethods");
		Objects.requireNonNull(proof, "proof");

		this.contexts = contexts == null || contexts.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(contexts);
		this.id = id;

		List<VerificationMethod> methods = new ArrayList<>();
		for (VerificationMethod vm : verificationMethods) {
			if (vm.isReference())
				throw new IllegalArgumentException("verificationMethod must not be a reference");

			methods.add(vm);
		}

		List<VerificationMethod> auths = new ArrayList<>();
		for (VerificationMethod vm : authentications) {
			if (vm instanceof VerificationMethod.Reference vmr) {
				// check that the referenced verification method exists
				VerificationMethod entity = methods.stream()
						.filter(v -> v.getId().equals(vm.getId()))
						.findAny()
						.orElse(null);
				if (entity == null)
					throw new IllegalArgumentException("authentications contains an invalid reference");

				vmr.updateReference(entity);
				auths.add(vm);
			} else {
				methods.add(vm);
				auths.add(vm.getReference());
			}
		}

		List<VerificationMethod> as = new ArrayList<>();
		for (VerificationMethod vm : assertions) {
			if (vm instanceof VerificationMethod.Reference vmr) {
				// check that the referenced verification method exists
				VerificationMethod entity = methods.stream()
						.filter(v -> v.getId().equals(vm.getId()))
						.findAny()
						.orElse(null);
				if (entity == null)
					throw new IllegalArgumentException("assertions contains an invalid reference");

				vmr.updateReference(entity);
				as.add(vm);
			} else {
				methods.add(vm);
				as.add(vm.getReference());
			}
		}
		this.verificationMethods = methods.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(methods);
		this.authentications = auths.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(auths);
		this.assertions = as.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(as);
		this.credentials = credentials == null || credentials.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(credentials);
		this.services = services == null || services.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(services);
		this.proof = proof;
	}

	// internal constructor used by builder.
	// the caller should transfer ownership of the collections to the new instance
	protected DIDDocument(List<String> contexts, Id id, List<VerificationMethod> verificationMethods,
						  List<VerificationMethod> authentications, List<VerificationMethod> assertions,
						  List<VerifiableCredential> credentials, List<Service> services) {
		this.contexts = contexts;
		this.id = id;
		this.verificationMethods = verificationMethods.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(verificationMethods);
		this.authentications = authentications == null || authentications.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(authentications);
		this.assertions = assertions == null || assertions.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(assertions);
		this.credentials = credentials == null || credentials.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(credentials);
		this.services = services == null || services.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(services);
		this.proof = null;
	}

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

	public List<String> getContexts() {
		return contexts;
	}

	public Id getId() {
		return id;
	}

	public List<VerificationMethod> getVerificationMethods() {
		return verificationMethods;
	}

	public List<VerificationMethod> getVerificationMethods(VerificationMethod.Type type) {
		return verificationMethods.stream()
				.filter(vm -> vm.getType()== type)
				.collect(Collectors.toList());
	}

	public VerificationMethod getVerificationMethod(String id) {
		Objects.requireNonNull(id, "id");
		DIDURL idUrl = id.startsWith(DIDConstants.DID_SCHEME + ":") ?
				DIDURL.create(id) : new DIDURL(getId(), null, null, id);

		return getVerificationMethod(idUrl);
	}

	public VerificationMethod getVerificationMethod(DIDURL id) {
		Objects.requireNonNull(id, "id");

		String sid = id.toString();
		return verificationMethods.stream()
			.filter(vm -> vm.getId().equals(sid))
			.findFirst()
			.orElse(null);
	}

	public List<VerificationMethod> getAuthentications() {
		return authentications;
	}

	public VerificationMethod getAuthentication(String id) {
		Objects.requireNonNull(id, "id");
		DIDURL idUrl = id.startsWith(DIDConstants.DID_SCHEME + ":") ?
				DIDURL.create(id) : new DIDURL(getId(), null, null, id);

		return getAuthentication(idUrl);
	}

	public VerificationMethod getAuthentication(DIDURL id) {
		Objects.requireNonNull(id, "id");

		String sid = id.toString();
		return authentications.stream()
			.filter(vm -> vm.getId().equals(sid))
			.findFirst()
			.orElse(null);
	}

	public List<VerificationMethod> getAssertions() {
		return assertions;
	}

	public VerificationMethod getAssertion(String id) {
		Objects.requireNonNull(id, "id");
		DIDURL idUrl = id.startsWith(DIDConstants.DID_SCHEME + ":") ?
				DIDURL.create(id) : new DIDURL(getId(), null, null, id);

		return getAssertion(idUrl);
	}

	public VerificationMethod getAssertion(DIDURL id) {
		Objects.requireNonNull(id, "id");

		String sid = id.toString();
		return assertions.stream()
			.filter(vm -> vm.getId().equals(sid))
			.findFirst()
			.orElse(null);
	}

	public List<VerifiableCredential> getCredentials() {
		return credentials;
	}

	public List<VerifiableCredential> getCredentials(String type) {
		Objects.requireNonNull(type, "type");
		return credentials.stream()
				.filter(vc -> vc.getTypes().contains(type))
				.collect(Collectors.toList());
	}

	public VerifiableCredential getCredential(String id) {
		Objects.requireNonNull(id, "id");
		DIDURL idUrl = id.startsWith(DIDConstants.DID_SCHEME + ":") ?
				DIDURL.create(id) : new DIDURL(getId(), null, null, id);

		return getCredential(idUrl);
	}

	public VerifiableCredential getCredential(DIDURL id) {
		Objects.requireNonNull(id, "id");

		String sid = id.toString();
		return credentials.stream()
			.filter(vc -> vc.getId().equals(sid))
			.findFirst()
			.orElse(null);
	}

	public List<Service> getServices() {
		return services;
	}

	public List<Service> getServices(String type) {
		return services.stream()
				.filter(service -> service.getType().equals(type))
				.collect(Collectors.toList());
	}

	public Service getService(String id) {
		Objects.requireNonNull(id, "id");
		DIDURL idUrl = id.startsWith(DIDConstants.DID_SCHEME + ":") ?
				DIDURL.create(id) : new DIDURL(getId(), null, null, id);

		return getService(idUrl);
	}

	public Service getService(DIDURL id) {
		Objects.requireNonNull(id, "id");

		String sid = id.toString();
		return services.stream()
			.filter(service -> service.getId().equals(sid))
			.findFirst()
			.orElse(null);
	}

	public Proof getProof() {
		return proof;
	}

	public boolean isGenuine() {
		if (proof == null)
			return false;

		return proof.verify(id, getSignData());
	}

	public void validate() throws InvalidSignatureException {
		if (!isGenuine())
			throw new InvalidSignatureException();
	}

	public Card toCard() {
		if (bosonCard == null)
			bosonCard = new BosonCard(this);

		return bosonCard;
	}

	public static DIDDocument fromCard(Card card, List<String> documentContexts,
									   Map<String, List<String>> vcTypeContexts) {
		if (card instanceof BosonCard bc)
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

		doc.bosonCard = new BosonCard(card, doc);
		return doc;
	}

	public static DIDDocument fromCard(Card card, Map<String, List<String>> vcTypeContexts) {
		return fromCard(card, List.of(), vcTypeContexts);
	}

	public static DIDDocument fromCard(Card card) {
		return fromCard(card, List.of(), Map.of());
	}

	protected byte[] getSignData() {
		BosonCard unsigned = bosonCard != null ? bosonCard : new BosonCard(this, true);
		return unsigned.getSignData();
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, verificationMethods, authentications, assertions, services, proof);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;

		if (o instanceof DIDDocument that)
			return Objects.equals(id, that.id) &&
					Objects.equals(verificationMethods, that.verificationMethods) &&
					Objects.equals(authentications, that.authentications) &&
					Objects.equals(assertions, that.assertions) &&
					Objects.equals(services, that.services) &&
					Objects.equals(proof, that.proof);

		return false;
	}

	public static DIDDocument parse(String json) {
		return parse(json, DIDDocument.class);
	}

	public static DIDDocument parse(byte[] cbor) {
		return parse(cbor, DIDDocument.class);
	}

	public static DIDDocumentBuilder builder(Identity subject) {
		Objects.requireNonNull(subject, "subject");
		return new DIDDocumentBuilder(subject);
	}

	protected static class BosonCard extends Card {
		private final DIDDocument doc;

		protected BosonCard(DIDDocument doc) {
			super(doc.id,
					doc.credentials.stream().map(VerifiableCredential::toCredential).collect(Collectors.toList()),
					doc.services.stream().map(s -> new Card.Service(s.getId(), s.getType(), s.getEndpoint(), s.getProperties()))
							.collect(Collectors.toList()),
					doc.proof.getCreated(), doc.proof.getProofValue());

			this.doc = doc;
		}

		// unsigned is not used, just as the method signature for overriding
		protected BosonCard(DIDDocument doc, boolean unsigned) {
			super(doc.id,
					doc.credentials.stream().map(VerifiableCredential::toCredential).collect(Collectors.toList()),
					doc.services.stream().map(s -> new Card.Service(s.getId(), s.getType(), s.getEndpoint(), s.getProperties()))
							.collect(Collectors.toList()));

			this.doc = doc;
		}

		protected BosonCard(Card card, DIDDocument doc) {
			super(card, card.getSignedAt(), card.getSignature());
			this.doc = doc;
		}

		@Override
		protected byte[] getSignData() {
			return super.getSignData();
		}

		public DIDDocument getDocument() {
			return doc;
		}
	}

	@JsonPropertyOrder({"id", "type", "serviceEndpoint"})
	public static class Service {
		@JsonProperty("id")
		private final String id;
		@JsonProperty("type")
		private final String type;
		@JsonProperty("serviceEndpoint")
		private final String endpoint;
		@JsonAnyGetter
		@JsonAnySetter
		private final Map<String, Object> properties;

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

		protected Service(String id, String type, String endpoint, Map<String, Object> properties) {
			this.id = id;
			this.type = type;
			this.endpoint = endpoint;
			this.properties = properties == null || properties.isEmpty() ? Collections.emptyMap() : properties;
		}

		public String getId() {
			return id;
		}

		public String getType() {
			return type;
		}

		public String getEndpoint() {
			return endpoint;
		}

		public Map<String, Object> getProperties() {
			return Collections.unmodifiableMap(properties);
		}

		@SuppressWarnings("unchecked")
		public <T> T getProperty(String name) {
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