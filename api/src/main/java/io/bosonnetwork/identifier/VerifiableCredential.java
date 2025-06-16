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
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.bosonnetwork.BeforeValidPeriodException;
import io.bosonnetwork.ExpiredException;
import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.InvalidSignatureException;

@JsonPropertyOrder({"@context", "id", "type", "name", "description", "issuer", "validFrom", "validUntil",
		"credentialSubject", "proof"})
public class VerifiableCredential extends W3CDIDFormat {
	@JsonProperty("@context")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<String> contexts;
	@JsonProperty("id")
	private final String id;
	@JsonProperty("type")
	private final List<String> types;
	@JsonProperty("name")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final String name;
	@JsonProperty("description")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final String description;
	@JsonProperty("issuer")
	private final Id issuer;
	@JsonProperty("validFrom")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final Date validFrom;
	@JsonProperty("validUntil")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final Date validUntil;
	@JsonProperty("credentialSubject")
	private final CredentialSubject subject;
	@JsonProperty("proof")
	private final Proof proof;

	private transient BosonCredential bosonCredential;

	// internal constructor used by JSON deserializer
	@JsonCreator
	protected VerifiableCredential(@JsonProperty(value = "@context") List<String> contexts,
								   @JsonProperty(value = "id", required = true) String id,
								   @JsonProperty(value = "type", required = true) List<String> types,
								   @JsonProperty(value = "name") String name,
								   @JsonProperty(value = "description") String description,
								   @JsonProperty(value = "issuer", required = true) Id issuer,
								   @JsonProperty(value = "validFrom") Date validFrom,
								   @JsonProperty(value = "validUntil") Date validUntil,
								   @JsonProperty(value = "credentialSubject", required = true) CredentialSubject subject,
								   @JsonProperty(value = "proof", required = true) Proof proof) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(types, "types");
		Objects.requireNonNull(issuer, "issuer");
		Objects.requireNonNull(subject, "subject");
		Objects.requireNonNull(proof, "proof");

		this.contexts = contexts == null || contexts.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(contexts);
		this.id = id;
		this.types = types.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(types);
		this.name = name;
		this.description = description;
		this.issuer = issuer;
		this.validFrom = validFrom;
		this.validUntil = validUntil;
		this.subject = subject;
		this.proof = proof;
	}

	// internal constructor used by the VerifiableCredentialBuilder
	// the caller should transfer ownership of the Collections to the new instance
	protected VerifiableCredential(List<String> contexts, String id, List<String> types, String name, String description,
								   Id issuer, Date validFrom, Date validUntil, Id subject, Map<String, Object> claims) {
		this.contexts = contexts == null || contexts.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(contexts);
		this.id = id;
		this.types = types.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(types);
		this.name = name;
		this.description = description;
		this.issuer = issuer;
		this.validFrom = validFrom;
		this.validUntil = validUntil;
		this.subject = new CredentialSubject(subject, claims);
		this.proof = null;
	}

	// internal constructor used by the VerifiableCredentialBuilder
	protected VerifiableCredential(VerifiableCredential vc, Proof proof) {
		this.contexts = vc.contexts;
		this.id = vc.id;
		this.types = vc.types;
		this.name = vc.name;
		this.description = vc.description;
		this.issuer = vc.issuer;
		this.validFrom = vc.validFrom;
		this.validUntil = vc.validUntil;
		this.subject = vc.subject;
		this.proof = proof;
	}

	public List<String> getContexts() {
		return contexts;
	}

	public String getId() {
		return id;
	}

	public List<String> getTypes() {
		return types;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public Id getIssuer() {
		return issuer;
	}

	public Date getValidFrom() {
		return validFrom;
	}

	public Date getValidUntil() {
		return validUntil;
	}

	public CredentialSubject getSubject() {
		return subject;
	}

	public Proof getProof() {
		return proof;
	}

	public boolean selfIssued() {
		return subject.getId() == null || subject.getId().equals(issuer);
	}

	public boolean isValid() {
		if (validFrom == null && validUntil == null)
			return true;

		Date now = new Date();
		if (validFrom != null && now.before(validFrom))
			return false;

		return validUntil == null || !now.after(validUntil);
	}

	public boolean isGenuine() {
		if (proof == null)
			return false;

		return proof.verify(issuer, getSignData());
	}

	public void validate() throws BeforeValidPeriodException, ExpiredException, InvalidSignatureException {
		Date now = new Date();
		if (validFrom != null && now.before(validFrom))
			throw new BeforeValidPeriodException();

		if (validUntil != null && now.after(validUntil))
			throw new ExpiredException();

		if (!isGenuine())
			throw new InvalidSignatureException();
	}

	public Credential toCredential() {
		if (bosonCredential == null)
			bosonCredential = new BosonCredential(this);

		return bosonCredential;
	}

	public static VerifiableCredential fromCredential(Credential credential, Map<String, List<String>> typeContexts) {
		Objects.requireNonNull(credential, "credential");
		if (credential instanceof BosonCredential vcCard)
			return vcCard.vc;

		List<String> contexts = new ArrayList<>();
		List<String> types = new ArrayList<>();

		contexts.add(DIDConstants.W3C_VC_CONTEXT);
		contexts.add(DIDConstants.BOSON_VC_CONTEXT);
		contexts.add(DIDConstants.W3C_ED25519_CONTEXT);
		types.add(DIDConstants.DEFAULT_VC_TYPE);

		if (typeContexts == null)
			typeContexts = Map.of();

		for (String type : credential.getTypes()) {
			if (type.equals(DIDConstants.DEFAULT_VC_TYPE))
				continue;

			types.add(type);

			if (typeContexts.containsKey(type)) {
				for (String context : typeContexts.get(type)) {
					if (!contexts.contains(context))
						contexts.add(context);
				}
			}
		}

		VerifiableCredential vc = new VerifiableCredential(contexts,
				new DIDURL(credential.getSubject().getId(), null, null, credential.getId()).toString(),
				types, credential.getName(), credential.getDescription(), credential.getIssuer(),
				credential.getValidFrom(), credential.getValidUntil(),
				new CredentialSubject(credential.getSubject().getId(), credential.getSubject().getClaims()),
				new Proof(Proof.Type.Ed25519Signature2020, credential.getSignedAt(),
						VerificationMethod.defaultReferenceOf(credential.getIssuer()), Proof.Purpose.assertionMethod,
						credential.getSignature()));

		vc.bosonCredential = new BosonCredential(credential, vc);
		return vc;
	}

	public static VerifiableCredential fromCredential(Credential credential) {
		return fromCredential(credential, Map.of());
	}

	protected byte[] getSignData() {
		BosonCredential unsigned = bosonCredential != null ? bosonCredential : new BosonCredential(this);
		return unsigned.getSignData();
	}

	@Override
	public int hashCode() {
		return Objects.hash("boson.did.vc", id, types, name, description, issuer, validFrom, validUntil, proof);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;

		if (o instanceof VerifiableCredential that)
			return Objects.equals(contexts, that.contexts) &&
					Objects.equals(id, that.id) &&
					Objects.equals(types, that.types) &&
					Objects.equals(name, that.name) &&
					Objects.equals(description, that.description) &&
					Objects.equals(issuer, that.issuer) &&
					Objects.equals(validFrom, that.validFrom) &&
					Objects.equals(validUntil, that.validUntil) &&
					Objects.equals(subject, that.subject) &&
					Objects.equals(proof, that.proof);

		return false;
	}

	public static VerifiableCredential parse(String json) {
		return parse(json, VerifiableCredential.class);
	}

	public static VerifiableCredential parse(byte[] cbor) {
		return parse(cbor, VerifiableCredential.class);
	}

	public static VerifiableCredentialBuilder builder(Identity issuer) {
		Objects.requireNonNull(issuer, "issuer");
		return new VerifiableCredentialBuilder(issuer);
	}

	@JsonPropertyOrder({"id"})
	public static class CredentialSubject {
		@JsonProperty("id")
		@JsonInclude(JsonInclude.Include.NON_NULL)
		private final Id id;
		@JsonAnySetter
		@JsonAnyGetter
		private final Map<String, Object> claims;

		// internal constructor
		// the claims are expected to be normalized to NFC.
		// the caller should transfer ownership of the claims to the Subject
		protected CredentialSubject(Id id, Map<String, Object> claims) {
			this.id = id;
			this.claims = claims == null ? Collections.emptyMap() : claims;
		}

		@JsonCreator
		protected CredentialSubject(@JsonProperty(value = "id", required = true) Id id) {
			Objects.requireNonNull(id, "id");
			this.id = id;
			this.claims = new LinkedHashMap<>();
		}

		public Id getId() {
			return id;
		}

		public Map<String, Object> getClaims() {
			return Collections.unmodifiableMap(claims);
		}

		@SuppressWarnings("unchecked")
		public <T> T getClaim(String name) {
			return (T) claims.get(name);
		}

		@Override
		public int hashCode() {
			return Objects.hash(id, claims);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o)
				return true;

			if (o instanceof CredentialSubject that)
				return Objects.equals(id, that.id) &&
						Objects.equals(claims, that.claims);

			return true;
		}
	}

	protected static class BosonCredential extends Credential {
		private final VerifiableCredential vc;

		protected BosonCredential(VerifiableCredential vc) {
			super(DIDURL.create(vc.id).getFragment(),
					vc.types.stream().filter(t -> !t.equals(DIDConstants.DEFAULT_VC_TYPE)).collect(Collectors.toList()),
					vc.name, vc.description, vc.issuer, vc.validFrom, vc.validUntil,
					vc.subject.id, vc.subject.claims,
					vc.proof == null ? null : vc.proof.getCreated(),
					vc.proof == null ? null : vc.proof.getProofValue());

			this.vc = vc;
		}

		protected BosonCredential(Credential cred, VerifiableCredential vc) {
			super(cred, cred.getSignedAt(), cred.getSignature());
			this.vc = vc;
		}

		@Override
		protected byte[] getSignData() {
			return super.getSignData();
		}

		public VerifiableCredential getVerifiableCredential() {
			return vc;
		}
	}
}