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

@JsonPropertyOrder({"id", "t", "n", "d", "i", "p", "v", "e", "s", "sat", "sig"})
public class Credential {
	@JsonProperty("id")
	private final String id;
	@JsonProperty("t")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<String> types;
	@JsonProperty("n")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final String name;
	@JsonProperty("d")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final String description;
	@JsonProperty("i")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final Id issuer;
	@JsonProperty("v")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final Date validFrom;
	@JsonProperty("e")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final Date validUntil;
	@JsonProperty("s")
	private final Subject subject;
	@JsonProperty("sat")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final Date signedAt;
	@JsonProperty("sig")
	private final byte[] signature;

	// Internal constructor used by JSON deserializer
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

		this.subject.implicitCheck(issuer);
	}

	// Internal constructor used by CredentialBuilder
	// The caller should transfer ownership of all Collections to the new instance
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
		this.subject.implicitCheck(issuer);

		this.signedAt = signedAt; // signedAt should be trimmed the milliseconds
		this.signature = signature;
	}

	// Internal constructor used by CredentialBuilder
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
		return issuer != null ? issuer : subject.getId();
	}

	public Date getValidFrom() {
		return validFrom;
	}

	public Date getValidUntil() {
		return validUntil;
	}

	public Subject getSubject() {
		return subject;
	}

	public Date getSignedAt() {
		return signedAt;
	}

	public byte[] getSignature() {
		return signature;
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
		if (signature == null || signature.length != Signature.BYTES)
			return false;

		return Signature.verify(getSignData(), signature, getIssuer().toSignatureKey());
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

	@Override
	public String toString() {
		try {
			return Json.objectMapper().writeValueAsString(this);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("INTERNAL ERROR: Credential is not serializable", e);
		}
	}

	public String toPrettyString() {
		try {
			return Json.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(this);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("INTERNAL ERROR: Credential is not serializable", e);
		}
	}

	public byte[] toBytes() {
		try {
			return Json.cborMapper().writeValueAsBytes(this);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("INTERNAL ERROR: Credential is not serializable", e);
		}
	}

	public static Credential parse(String json) {
		try {
			return Json.objectMapper().readValue(json, Credential.class);
		} catch (IOException e) {
			throw new IllegalArgumentException("Invalid JSON data for Credential", e);
		}
	}

	public static Credential parse(byte[] cbor) {
		try {
			return Json.cborMapper().readValue(cbor, Credential.class);
		} catch (IOException e) {
			throw new IllegalArgumentException("Invalid CBOR data for Credential", e);
		}
	}

	public static CredentialBuilder builder(Identity issuer) {
		Objects.requireNonNull(issuer, "issuer");
		return new CredentialBuilder(issuer);
	}

	@JsonPropertyOrder({"id"})
	public static class Subject {
		@JsonProperty("id")
		@JsonInclude(JsonInclude.Include.NON_NULL)
		private Id id;
		@JsonAnySetter
		@JsonAnyGetter
		private final Map<String, Object> claims;

		boolean implicit = false;

		// internal constructor used by CredentialBuilder.
		// the claims are expected to be normalized to NFC.
		// the caller should transfer ownership of the claims to the Subject
		protected Subject(Id id, Map<String, Object> claims) {
			this.id = id;
			this.claims = claims == null ? Collections.emptyMap() : claims;
		}

		@JsonCreator
		protected Subject() {
			this.id = null;
			this.claims = new LinkedHashMap<>();
		}

		@JsonProperty("id")
		@JsonInclude(JsonInclude.Include.NON_NULL)
		private Id _getId() {
			return implicit ? null : id;
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

		private void implicitCheck(Id issuer) {
			if (this.id == null) {
				this.id = issuer;
				this.implicit = true;
			} else {
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