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

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonProcessingException;

import io.bosonnetwork.BeforeValidPeriodException;
import io.bosonnetwork.ExpiredException;
import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.InvalidSignatureException;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.utils.Json;

/**
 * A compact representation of a Verifiable Credential (VC) using Boson's compact format.
 * This class maps the typical VC fields to shortened JSON/CBOR keys for efficiency:
 * <ul>
 *   <li><b>id</b> - Credential identifier ("id")</li>
 *   <li><b>t</b> - Credential types ("t")</li>
 *   <li><b>n</b> - Credential name ("n")</li>
 *   <li><b>d</b> - Credential description ("d")</li>
 *   <li><b>i</b> - Issuer identifier ("i")</li>
 *   <li><b>v</b> - Valid from date ("v")</li>
 *   <li><b>e</b> - Valid until date ("e")</li>
 *   <li><b>s</b> - Subject of the credential ("s")</li>
 *   <li><b>sat</b> - Signature timestamp ("sat")</li>
 *   <li><b>sig</b> - Cryptographic signature bytes ("sig")</li>
 * </ul>
 * <p>
 * This class supports JSON and CBOR serialization/deserialization, signature verification,
 * and validation of credential validity periods.
 * </p>
 */
@JsonPropertyOrder({"id", "t", "n", "d", "i", "p", "v", "e", "s", "sat", "sig"})
public class Credential {
	/** Credential identifier ("id") */
	@JsonProperty("id")
	private final String id;

	/** Credential types ("t"), can be empty or null */
	@JsonProperty("t")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<String> types;

	/** Human-readable credential name ("n"), optional */
	@JsonProperty("n")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final String name;

	/** Human-readable credential description ("d"), optional */
	@JsonProperty("d")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final String description;

	/** Issuer identifier ("i"), required */
	@JsonProperty("i")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final Id issuer;

	/** Credential valid from date ("v"), optional */
	@JsonProperty("v")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final Date validFrom;

	/** Credential valid until date ("e"), optional */
	@JsonProperty("e")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final Date validUntil;

	/** Credential subject ("s"), required */
	@JsonProperty("s")
	private final Subject subject;

	/** Signature timestamp ("sat"), optional */
	@JsonProperty("sat")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final Date signedAt;

	/** Cryptographic signature bytes ("sig"), required */
	@JsonProperty("sig")
	private final byte[] signature;

	/**
	 * Internal constructor used by JSON deserializer.
	 * All required fields must be non-null.
	 *
	 * @param id Credential identifier
	 * @param types Credential types
	 * @param name Credential name
	 * @param description Credential description
	 * @param issuer Issuer identifier
	 * @param validFrom Valid from date
	 * @param validUntil Valid until date
	 * @param subject Credential subject
	 * @param signedAt Signature timestamp
	 * @param signature Cryptographic signature bytes
	 */
	@JsonCreator
	protected Credential(@JsonProperty(value = "id", required = true) String id,
						 @JsonProperty(value = "t") List<String> types,
						 @JsonProperty(value = "n") String name,
						 @JsonProperty(value = "d") String description,
						 @JsonProperty(value = "i", required = true) Id issuer,
						 @JsonProperty(value = "v") Date validFrom,
						 @JsonProperty(value = "e") Date validUntil,
						 @JsonProperty(value = "s", required = true) Subject subject,
						 @JsonProperty(value = "sat") Date signedAt,
						 @JsonProperty(value = "sig") byte[] signature) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(issuer, "issuer");
		Objects.requireNonNull(subject, "subject");
		Objects.requireNonNull(signedAt, "signedAt");
		Objects.requireNonNull(signature, "signature");

		this.id = id;
		this.types = types == null || types.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(types);
		this.name = name;
		this.description = description;
		this.issuer = issuer;
		this.validFrom = validFrom;
		this.validUntil = validUntil;
		this.subject = subject;
		this.signedAt = signedAt;
		this.signature = signature;

		// Ensure the subject's id is consistent with the issuer if implicit
		this.subject.implicitCheck(issuer);
	}

	/**
	 * Internal constructor used by CredentialBuilder.
	 * The caller should transfer ownership of all collections to the new instance.
	 *
	 * @param id Credential identifier
	 * @param types Credential types
	 * @param name Credential name
	 * @param description Credential description
	 * @param issuer Issuer identifier
	 * @param validFrom Valid from date
	 * @param validUntil Valid until date
	 * @param subject Subject identifier
	 * @param claims Subject claims
	 * @param signedAt Signature timestamp (should be trimmed of milliseconds)
	 * @param signature Cryptographic signature bytes
	 */
	protected Credential(String id, List<String> types, String name, String description, Id issuer, Date validFrom,
						 Date validUntil, Id subject, Map<String, Object> claims, Date signedAt, byte[] signature) {
		this.id = id;
		this.types = types == null || types.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(types);
		this.name = name;
		this.description = description;
		this.issuer = issuer;
		this.validFrom = validFrom;
		this.validUntil = validUntil;
		this.subject = new Subject(subject, claims);
		// Ensure consistency between subject id and issuer for implicit subjects
		this.subject.implicitCheck(issuer);

		this.signedAt = signedAt; // signedAt should be trimmed the milliseconds
		this.signature = signature;
	}

	/**
	 * Internal constructor used by CredentialBuilder to create a copy with new signedAt and signature.
	 *
	 * @param cred Original credential
	 * @param signedAt Signature timestamp (should be trimmed of milliseconds)
	 * @param signature Cryptographic signature bytes
	 */
	protected Credential(Credential cred, Date signedAt, byte[] signature) {
		this.id = cred.id;
		this.types = cred.types;
		this.name = cred.name;
		this.description = cred.description;
		this.issuer = cred.issuer;
		this.validFrom = cred.validFrom;
		this.validUntil = cred.validUntil;
		this.subject = cred.subject;

		this.signedAt = signedAt; // signedAt should be trimmed the milliseconds
		this.signature = signature;
	}

	/**
	 * Gets the credential identifier.
	 *
	 * @return Credential id string
	 */
	public String getId() {
		return id;
	}

	/**
	 * Gets the list of credential types.
	 *
	 * @return List of types (may be empty)
	 */
	public List<String> getTypes() {
		return types;
	}

	/**
	 * Gets the human-readable name of the credential.
	 *
	 * @return Credential name or null if not set
	 */
	public String getName() {
		return name;
	}

	/**
	 * Gets the human-readable description of the credential.
	 *
	 * @return Credential description or null if not set
	 */
	public String getDescription() {
		return description;
	}

	/**
	 * Gets the issuer identifier of the credential.
	 * If issuer is null, returns the subject's id.
	 *
	 * @return Issuer Id
	 */
	public Id getIssuer() {
		return issuer != null ? issuer : subject.getId();
	}

	/**
	 * Gets the date from which the credential is valid.
	 *
	 * @return Valid from date or null if not set
	 */
	public Date getValidFrom() {
		return validFrom;
	}

	/**
	 * Gets the date until which the credential is valid.
	 *
	 * @return Valid until date or null if not set
	 */
	public Date getValidUntil() {
		return validUntil;
	}

	/**
	 * Gets the subject of the credential.
	 *
	 * @return Subject object
	 */
	public Subject getSubject() {
		return subject;
	}

	/**
	 * Gets the signature timestamp.
	 *
	 * @return Signed at date
	 */
	public Date getSignedAt() {
		return signedAt;
	}

	/**
	 * Gets the cryptographic signature bytes.
	 *
	 * @return Signature byte array
	 */
	public byte[] getSignature() {
		return signature;
	}

	/**
	 * Determines if the credential is self-issued.
	 * A credential is self-issued if the subject's id is null or equals the issuer.
	 *
	 * @return True if self-issued, false otherwise
	 */
	public boolean selfIssued() {
		return subject.getId() == null || subject.getId().equals(issuer);
	}

	/**
	 * Checks if the credential is currently valid based on the validFrom and validUntil dates.
	 *
	 * @return True if valid, false if outside the validity period
	 */
	public boolean isValid() {
		// If no validity period specified, consider always valid
		if (validFrom == null && validUntil == null)
			return true;

		Date now = new Date();
		// Check if current time is before validFrom
		if (validFrom != null && now.before(validFrom))
			return false;

		// Check if current time is after validUntil
		return validUntil == null || !now.after(validUntil);
	}

	/**
	 * Verifies the cryptographic signature of the credential.
	 *
	 * @return True if signature is valid, false otherwise
	 */
	public boolean isGenuine() {
		// Signature must be present and of correct length
		if (signature == null || signature.length != Signature.BYTES)
			return false;

		// Verify signature against the sign data and issuer's signature key
		return Signature.verify(getSignData(), signature, getIssuer().toSignatureKey());
	}

	/**
	 * Validates the credential by checking its validity period and signature.
	 *
	 * @throws BeforeValidPeriodException if current time is before validFrom
	 * @throws ExpiredException if current time is after validUntil
	 * @throws InvalidSignatureException if signature verification fails
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
	 * Gets the byte array of the credential data that is signed.
	 * If signature is present, returns the unsigned credential bytes for verification.
	 * Otherwise, returns the bytes of the current credential.
	 *
	 * @return Byte array of the data to be signed or verified
	 */
	protected byte[] getSignData() {
		if (signature != null)	// already signed
			return new Credential(this, null,null).toBytes();
		else 					// unsigned
			return toBytes();
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, types, name, description, issuer,
				validFrom, validUntil, subject, signedAt, Arrays.hashCode(signature));
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;

		if (o instanceof Credential that)
			return Objects.equals(id, that.id) &&
					Objects.equals(types, that.types) &&
					Objects.equals(name, that.name) &&
					Objects.equals(description, that.description) &&
					Objects.equals(issuer, that.issuer) &&
					Objects.equals(validFrom, that.validFrom) &&
					Objects.equals(validUntil, that.validUntil) &&
					Objects.equals(subject, that.subject) &&
					Objects.equals(signedAt, that.signedAt) &&
					Arrays.equals(signature, that.signature);

		return false;
	}

	/**
	 * Returns the JSON string representation of the credential.
	 *
	 * @return JSON string
	 * @throws IllegalStateException if serialization fails
	 */
	@Override
	public String toString() {
		try {
			return Json.objectMapper().writeValueAsString(this);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("INTERNAL ERROR: Credential is not serializable", e);
		}
	}

	/**
	 * Returns a pretty-printed JSON string representation of the credential.
	 *
	 * @return Pretty JSON string
	 * @throws IllegalStateException if serialization fails
	 */
	public String toPrettyString() {
		try {
			return Json.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(this);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("INTERNAL ERROR: Credential is not serializable", e);
		}
	}

	/**
	 * Serializes the credential to CBOR bytes.
	 *
	 * @return CBOR byte array
	 * @throws IllegalStateException if serialization fails
	 */
	public byte[] toBytes() {
		try {
			return Json.cborMapper().writeValueAsBytes(this);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("INTERNAL ERROR: Credential is not serializable", e);
		}
	}

	/**
	 * Parses a JSON string into a Credential object.
	 *
	 * @param json JSON string representing a credential
	 * @return Parsed Credential object
	 * @throws IllegalArgumentException if parsing fails
	 */
	public static Credential parse(String json) {
		try {
			return Json.objectMapper().readValue(json, Credential.class);
		} catch (IOException e) {
			throw new IllegalArgumentException("Invalid JSON data for Credential", e);
		}
	}

	/**
	 * Parses CBOR bytes into a Credential object.
	 *
	 * @param cbor CBOR byte array representing a credential
	 * @return Parsed Credential object
	 * @throws IllegalArgumentException if parsing fails
	 */
	public static Credential parse(byte[] cbor) {
		try {
			return Json.cborMapper().readValue(cbor, Credential.class);
		} catch (IOException e) {
			throw new IllegalArgumentException("Invalid CBOR data for Credential", e);
		}
	}

	/**
	 * Creates a new CredentialBuilder for the given issuer.
	 *
	 * @param issuer Issuer identity
	 * @return CredentialBuilder instance
	 * @throws NullPointerException if issuer is null
	 */
	public static CredentialBuilder builder(Identity issuer) {
		Objects.requireNonNull(issuer, "issuer");
		return new CredentialBuilder(issuer);
	}

	/**
	 * Represents the subject of a Credential.
	 * The subject contains an identifier and a set of claims.
	 * The fields are serialized compactly with Boson's shortened keys.
	 * <p>
	 * The subject id can be implicit: if null, it is assumed to be the issuer's id.
	 * The {@link #implicitCheck(Id)} method enforces this logic.
	 * </p>
	 */
	@JsonPropertyOrder({"id"})
	public static class Subject {
		/** Subject identifier ("id"), may be null if implicit */
		@JsonProperty("id")
		@JsonInclude(JsonInclude.Include.NON_NULL)
		private Id id;

		/** Additional claims for the subject as key-value pairs */
		@JsonAnySetter
		@JsonAnyGetter
		private final Map<String, Object> claims;

		/** Flag indicating if the subject id is implicit (same as issuer) */
		boolean implicit = false;

		/**
		 * Internal constructor used by CredentialBuilder.
		 * The claims are expected to be normalized to NFC.
		 * The caller should transfer ownership of the claims to this Subject.
		 *
		 * @param id Subject identifier
		 * @param claims Subject claims map
		 */
		protected Subject(Id id, Map<String, Object> claims) {
			this.id = id;
			this.claims = claims == null ? Collections.emptyMap() : claims;
		}

		/**
		 * Default constructor used by JSON deserializer.
		 * Initializes with null id and empty claims.
		 */
		@JsonCreator
		protected Subject() {
			this.id = null;
			this.claims = new LinkedHashMap<>();
		}

		/**
		 * Internal getter for JSON serialization.
		 * Returns null if the id is implicit, omitting the id field from serialization.
		 *
		 * @return Subject id or null if implicit
		 */
		@JsonProperty("id")
		@JsonInclude(JsonInclude.Include.NON_NULL)
		private Id _getId() {
			return implicit ? null : id;
		}

		/**
		 * Gets the subject identifier.
		 *
		 * @return Subject Id or null if implicit
		 */
		public Id getId() {
			return id;
		}

		/**
		 * Gets an unmodifiable view of the subject claims.
		 *
		 * @return Map of claims
		 */
		public Map<String, Object> getClaims() {
			return Collections.unmodifiableMap(claims);
		}

		/**
		 * Gets the value of a specific claim by name.
		 *
		 * @param name Claim name
		 * @param <T> Expected type of the claim value
		 * @return Claim value or null if not present
		 */
		@SuppressWarnings("unchecked")
		public <T> T getClaim(String name) {
			return (T) claims.get(name);
		}

		/**
		 * Checks if the subject id is null, and if so, sets it to the issuer's id,
		 * marking it as implicit. If the id is already set, marks implicit as true
		 * only if the id equals the issuer's id.
		 *
		 * <p>This ensures that a missing subject id is treated as the issuer,
		 * enabling compact representation.</p>
		 *
		 * @param issuer Issuer Id to check against
		 */
		private void implicitCheck(Id issuer) {
			if (this.id == null) {
				// If subject id is missing, assign issuer id and mark as implicit
				this.id = issuer;
				this.implicit = true;
			} else {
				// Mark implicit true only if subject id equals issuer id
				this.implicit = this.id.equals(issuer);
			}
		}

		@Override
		public int hashCode() {
			return Objects.hash(id, claims);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o)
				return true;

			if (o instanceof Subject that)
				return Objects.equals(id, that.id) && Objects.equals(claims, that.claims);

			return true;
		}
	}
}