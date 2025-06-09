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

package io.bosonnetwork;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonProcessingException;

import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.utils.Json;

@JsonPropertyOrder({"id", "t", "h", "c", "sat", "sig"})
public class Vouch {
	@JsonProperty("id")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final String id;
	@JsonProperty("t")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<String> types;
	@JsonProperty("h")
	private final Id holder;
	@JsonProperty("c")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<Credential> credentials;
	@JsonProperty("sat")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final Date signedAt;
	@JsonProperty("sig")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final byte[] signature;

	// internal constructor used by JSON deserializer
	@JsonCreator
	protected Vouch(@JsonProperty(value = "id") String id,
					@JsonProperty(value = "t") List<String> types,
					@JsonProperty(value = "h", required = true) Id holder,
					@JsonProperty("c") List<Credential> credentials,
					@JsonProperty(value = "sat", required = true) Date signedAt,
					@JsonProperty(value = "sig", required = true) byte[] signature) {
		Objects.requireNonNull(holder, "holder");
		Objects.requireNonNull(signedAt, "signedAt");
		Objects.requireNonNull(signature, "signature");

		this.id = id;
		this.types = types == null || types.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(types);
		this.holder = holder;
		this.credentials = credentials == null || credentials.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(credentials);
		this.signedAt = signedAt;
		this.signature = signature;
	}

	// internal constructor used by VouchBuilder
	// the caller should transfer ownership of the Collections to the new instance
	protected Vouch(String id, List<String> types, Id holder, List<Credential> credentials) {
		this.id = id;
		this.types = types == null || types.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(types);
		this.holder = holder;
		this.credentials = credentials == null || credentials.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(credentials);
		this.signedAt = null;
		this.signature = null;
	}

	// internal constructor used by VouchBuilder
	protected Vouch(Vouch vouch, Date signedAt, byte[] signature) {
		this.id = vouch.id;
		this.types = vouch.types;
		this.holder = vouch.holder;
		this.credentials = vouch.credentials;
		this.signedAt = signedAt;
		this.signature = signature;
	}

	public String getId() {
		return id;
	}

	public List<String> getTypes() {
		return types;
	}

	public Id getHolder() {
		return holder;
	}

	public List<Credential> getCredentials() {
		return credentials;
	}

	public List<Credential> getCredentials(String type) {
		Objects.requireNonNull(type, "type");
		return credentials.stream()
				.filter(c -> c.getTypes().contains(type))
				.collect(Collectors.toList());
	}

	public Credential getCredential(String id) {
		Objects.requireNonNull(id, "id");
		return credentials.stream()
				.filter(c -> c.getId().equals(id))
				.findFirst()
				.orElse(null);
	}

	public Date getSignedAt() {
		return signedAt;
	}

	public byte[] getSignature() {
		return signature;
	}

	public void validate() throws InvalidSignatureException {
		if (!isGenuine())
			throw new InvalidSignatureException();
	}

	public boolean isGenuine() {
		if (signature == null || signature.length != Signature.BYTES)
			return false;

		return Signature.verify(getSignData(), signature, holder.toSignatureKey());
	}

	protected byte[] getSignData() {
		if (signature != null)	// already signed
			return new Vouch(this, null, null).toBytes();
		else 					// unsigned
			return toBytes();
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, types, holder, credentials, Arrays.hashCode(signature));
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;

		if (o instanceof Vouch that)
			return Objects.equals(id, that.id) &&
					Objects.equals(types, that.types) &&
					Objects.equals(holder, that.holder) &&
					Objects.equals(credentials, that.credentials) &&
					Objects.equals(signedAt, that.signedAt) &&
					Arrays.equals(signature, that.signature);

		return false;
	}

	@Override
	public String toString() {
		try {
			return Json.objectMapper().writeValueAsString(this);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("INTERNAL ERROR: Vouch is not serializable", e);
		}
	}

	public String toPrettyString() {
		try {
			return Json.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(this);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("INTERNAL ERROR: Vouch is not serializable", e);
		}
	}

	public byte[] toBytes() {
		try {
			return Json.cborMapper().writeValueAsBytes(this);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("INTERNAL ERROR: Vouch is not serializable", e);
		}
	}

	public static Vouch parse(String json) {
		try {
			return Json.objectMapper().readValue(json, Vouch.class);
		} catch (IOException e) {
			throw new IllegalArgumentException("Invalid JSON date for Vouch", e);
		}
	}

	public static Vouch parse(byte[] cbor) {
		try {
			return Json.cborMapper().readValue(cbor, Vouch.class);
		} catch (IOException e) {
			throw new IllegalArgumentException("Invalid CBOR date for Vouch", e);
		}
	}

	public static VouchBuilder builder(Identity holder) {
		Objects.requireNonNull(holder, "holder");
		return new VouchBuilder(holder);
	}
}