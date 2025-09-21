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

/**
 * Represents a W3C-compliant Verifiable Credential.
 * <p>
 * This class encapsulates the standard fields of a verifiable credential including contexts,
 * identifier, types, issuer, validity period, subject, and cryptographic proof.
 * It supports JSON serialization/deserialization and provides methods for validation,
 * signature verification, and conversion to/from a more compact Credential representation.
 */
@JsonPropertyOrder({"@context", "id", "type", "name", "description", "issuer", "validFrom", "validUntil",
		"credentialSubject", "proof"})
public class VerifiableCredential extends W3CDIDFormat {
	/**
	 * The JSON-LD contexts of this verifiable credential.
	 */
	@JsonProperty("@context")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<String> contexts;

	/**
	 * The unique identifier of this verifiable credential.
	 */
	@JsonProperty("id")
	private final String id;

	/**
	 * The types associated with this verifiable credential.
	 */
	@JsonProperty("type")
	private final List<String> types;

	/**
	 * The name of this verifiable credential.
	 */
	@JsonProperty("name")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final String name;

	/**
	 * The description of this verifiable credential.
	 */
	@JsonProperty("description")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final String description;

	/**
	 * The issuer identity of this verifiable credential.
	 */
	@JsonProperty("issuer")
	private final Id issuer;

	/**
	 * The start date from which this verifiable credential is valid.
	 */
	@JsonProperty("validFrom")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final Date validFrom;

	/**
	 * The end date until which this verifiable credential is valid.
	 */
	@JsonProperty("validUntil")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final Date validUntil;

	/**
	 * The subject of this verifiable credential.
	 */
	@JsonProperty("credentialSubject")
	private final CredentialSubject subject;

	/**
	 * The cryptographic proof of this verifiable credential.
	 */
	@JsonProperty("proof")
	private final Proof proof;

	/**
	 * A transient BosonCredential representation of this verifiable credential.
	 */
	private transient BosonCredential bosonCredential;

	/**
	 * Internal constructor used by JSON deserializer.
	 *
	 * @param contexts   the JSON-LD contexts
	 * @param id         the unique identifier
	 * @param types      the types of the credential
	 * @param name       the name of the credential
	 * @param description the description of the credential
	 * @param issuer     the issuer identity
	 * @param validFrom  the validity start date
	 * @param validUntil the validity end date
	 * @param subject    the credential subject
	 * @param proof      the cryptographic proof
	 */
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

	/**
	 * Internal constructor used by the VerifiableCredentialBuilder.
	 * The caller should transfer ownership of the collections to the new instance.
	 *
	 * @param contexts   the JSON-LD contexts
	 * @param id         the unique identifier
	 * @param types      the types of the credential
	 * @param name       the name of the credential
	 * @param description the description of the credential
	 * @param issuer     the issuer identity
	 * @param validFrom  the validity start date
	 * @param validUntil the validity end date
	 * @param subject    the subject identity
	 * @param claims     the claims of the subject
	 */
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

	/**
	 * Internal constructor used by the VerifiableCredentialBuilder to create a copy with a proof.
	 *
	 * @param vc    the original verifiable credential
	 * @param proof the cryptographic proof
	 */
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

	/**
	 * Returns the JSON-LD contexts of this verifiable credential.
	 *
	 * @return the list of contexts
	 */
	public List<String> getContexts() {
		return contexts;
	}

	/**
	 * Returns the unique identifier of this verifiable credential.
	 *
	 * @return the identifier string
	 */
	public String getId() {
		return id;
	}

	/**
	 * Returns the types associated with this verifiable credential.
	 *
	 * @return the list of types
	 */
	public List<String> getTypes() {
		return types;
	}

	/**
	 * Returns the name of this verifiable credential.
	 *
	 * @return the name or null if not set
	 */
	public String getName() {
		return name;
	}

	/**
	 * Returns the description of this verifiable credential.
	 *
	 * @return the description or null if not set
	 */
	public String getDescription() {
		return description;
	}

	/**
	 * Returns the issuer identity of this verifiable credential.
	 *
	 * @return the issuer Id
	 */
	public Id getIssuer() {
		return issuer;
	}

	/**
	 * Returns the start date from which this credential is valid.
	 *
	 * @return the validity start date or null if not set
	 */
	public Date getValidFrom() {
		return validFrom;
	}

	/**
	 * Returns the end date until which this credential is valid.
	 *
	 * @return the validity end date or null if not set
	 */
	public Date getValidUntil() {
		return validUntil;
	}

	/**
	 * Returns the subject of this verifiable credential.
	 *
	 * @return the credential subject
	 */
	public CredentialSubject getSubject() {
		return subject;
	}

	/**
	 * Returns the cryptographic proof of this verifiable credential.
	 *
	 * @return the proof object
	 */
	public Proof getProof() {
		return proof;
	}

	/**
	 * Determines if this credential is self-issued, i.e., the subject is the same as the issuer or subject id is null.
	 *
	 * @return true if self-issued, false otherwise
	 */
	public boolean selfIssued() {
		return subject.getId() == null || subject.getId().equals(issuer);
	}

	/**
	 * Checks if the credential is currently valid based on the validity period.
	 * <p>
	 * If both validFrom and validUntil are null, the credential is considered always valid.
	 *
	 * @return true if valid, false otherwise
	 */
	public boolean isValid() {
		if (validFrom == null && validUntil == null)
			return true;

		Date now = new Date();
		// Check if current date is before the validFrom date
		if (validFrom != null && now.before(validFrom))
			return false;

		// Check if current date is after the validUntil date
		return validUntil == null || !now.after(validUntil);
	}

	/**
	 * Verifies the cryptographic signature of this credential.
	 *
	 * @return true if the proof is present and valid, false otherwise
	 */
	public boolean isGenuine() {
		if (proof == null)
			return false;

		// Verify the proof using the issuer's identity and signing data
		return proof.verify(issuer, getSignData());
	}

	/**
	 * Validates the credential by checking validity period and signature.
	 *
	 * @throws BeforeValidPeriodException if the credential is used before validFrom
	 * @throws ExpiredException           if the credential is used after validUntil
	 * @throws InvalidSignatureException  if the signature is invalid
	 */
	public void validate() throws BeforeValidPeriodException, ExpiredException, InvalidSignatureException {
		Date now = new Date();
		if (validFrom != null && now.before(validFrom))
			throw new BeforeValidPeriodException();

		if (validUntil != null && now.after(validUntil))
			throw new ExpiredException();

		if (!isGenuine())
			throw new InvalidSignatureException();
	}

	/**
	 * Converts this verifiable credential to a more compact Credential representation.
	 *
	 * @return the Credential object
	 */
	public Credential toCredential() {
		if (bosonCredential == null)
			bosonCredential = new BosonCredential(this);

		return bosonCredential;
	}

	/**
	 * Creates a VerifiableCredential from a given Credential and optional type-context mappings.
	 *
	 * @param credential   the source credential
	 * @param typeContexts optional mapping of types to additional contexts
	 * @return the constructed VerifiableCredential
	 */
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

	/**
	 * Creates a VerifiableCredential from a given Credential without additional type-context mappings.
	 *
	 * @param credential the source credential
	 * @return the constructed VerifiableCredential
	 */
	public static VerifiableCredential fromCredential(Credential credential) {
		return fromCredential(credential, Map.of());
	}

	/**
	 * Returns the data to be signed for this verifiable credential.
	 *
	 * @return the byte array of sign data
	 */
	protected byte[] getSignData() {
		BosonCredential unsigned = bosonCredential != null ? bosonCredential : new BosonCredential(this);
		return unsigned.getSignData();
	}

	@Override
	public int hashCode() {
		return Objects.hash(contexts, id, types, name, description, issuer, validFrom, validUntil, subject, proof);
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

	/**
	 * Parses a JSON string into a VerifiableCredential object.
	 *
	 * @param json the JSON string
	 * @return the parsed VerifiableCredential
	 */
	public static VerifiableCredential parse(String json) {
		return parse(json, VerifiableCredential.class);
	}

	/**
	 * Parses a CBOR byte array into a VerifiableCredential object.
	 *
	 * @param cbor the CBOR byte array
	 * @return the parsed VerifiableCredential
	 */
	public static VerifiableCredential parse(byte[] cbor) {
		return parse(cbor, VerifiableCredential.class);
	}

	/**
	 * Creates a builder for constructing a VerifiableCredential.
	 *
	 * @param issuer the issuer identity
	 * @return a new VerifiableCredentialBuilder
	 */
	public static VerifiableCredentialBuilder builder(Identity issuer) {
		Objects.requireNonNull(issuer, "issuer");
		return new VerifiableCredentialBuilder(issuer);
	}

	/**
	 * Represents the subject of a verifiable credential.
	 * <p>
	 * Contains an identifier and a map of claims about the subject.
	 */
	@JsonPropertyOrder({"id"})
	public static class CredentialSubject {
		/**
		 * The identifier of the subject.
		 */
		@JsonProperty("id")
		@JsonInclude(JsonInclude.Include.NON_NULL)
		private final Id id;

		/**
		 * The claims associated with the subject.
		 */
		@JsonAnySetter
		@JsonAnyGetter
		private final Map<String, Object> claims;

		/**
		 * Internal constructor.
		 * The claims are expected to be normalized to NFC.
		 * The caller should transfer ownership of the claims to the subject.
		 *
		 * @param id     the subject identifier
		 * @param claims the claims map
		 */
		protected CredentialSubject(Id id, Map<String, Object> claims) {
			this.id = id;
			this.claims = claims == null ? Collections.emptyMap() : claims;
		}

		/**
		 * Constructor used by JSON deserializer.
		 *
		 * @param id the subject identifier
		 */
		@JsonCreator
		protected CredentialSubject(@JsonProperty(value = "id", required = true) Id id) {
			Objects.requireNonNull(id, "id");
			this.id = id;
			this.claims = new LinkedHashMap<>();
		}

		/**
		 * Returns the identifier of the subject.
		 *
		 * @return the subject Id
		 */
		public Id getId() {
			return id;
		}

		/**
		 * Returns an unmodifiable view of the claims.
		 *
		 * @return the claims map
		 */
		public Map<String, Object> getClaims() {
			return Collections.unmodifiableMap(claims);
		}

		/**
		 * Returns the claim value for the given claim name.
		 *
		 * @param <T>  the expected type of the claim
		 * @param name the claim name
		 * @return the claim value or null if not present
		 */
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
				return Objects.equals(id, that.id) && Objects.equals(claims, that.claims);

			return true;
		}
	}

	/**
	 * Adapts a VerifiableCredential into a compact Credential representation.
	 * <p>
	 * This class is used internally to optimize storage and signing operations.
	 */
	protected static class BosonCredential extends Credential {
		private final VerifiableCredential vc;

		/**
		 * Constructs a BosonCredential from a VerifiableCredential.
		 *
		 * @param vc the source verifiable credential
		 */
		protected BosonCredential(VerifiableCredential vc) {
			super(DIDURL.create(vc.id).getFragment(),
					vc.types.stream().filter(t -> !t.equals(DIDConstants.DEFAULT_VC_TYPE)).collect(Collectors.toList()),
					vc.name, vc.description, vc.issuer, vc.validFrom, vc.validUntil,
					vc.subject.id, vc.subject.claims,
					vc.proof == null ? null : vc.proof.getCreated(),
					vc.proof == null ? null : vc.proof.getProofValue());

			this.vc = vc;
		}

		/**
		 * Constructs a BosonCredential from a Credential and associates it with a VerifiableCredential.
		 *
		 * @param cred the source credential
		 * @param vc   the associated verifiable credential
		 */
		protected BosonCredential(Credential cred, VerifiableCredential vc) {
			super(cred, cred.getSignedAt(), cred.getSignature());
			this.vc = vc;
		}

		@Override
		protected byte[] getSignData() {
			return super.getSignData();
		}

		/**
		 * Returns the associated VerifiableCredential.
		 *
		 * @return the verifiable credential
		 */
		public VerifiableCredential getVerifiableCredential() {
			return vc;
		}
	}
}