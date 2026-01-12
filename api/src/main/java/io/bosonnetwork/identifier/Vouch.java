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
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonProcessingException;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.InvalidSignatureException;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.json.Json;

/**
 * Represents the Boson compacted version of a Verifiable Presentation (VP).
 * <p>
 * This class provides a compact form for transmitting and storing a Verifiable Presentation,
 * mapping the standard VP fields to short JSON/CBOR keys as follows:
 * <ul>
 *     <li>{@code id}  &rarr; {@code "id"}: The unique identifier for the presentation.</li>
 *     <li>{@code types}  &rarr; {@code "t"}: The types associated with the presentation.</li>
 *     <li>{@code holder}  &rarr; {@code "h"}: The identifier of the entity presenting the credentials.</li>
 *     <li>{@code credentials}  &rarr; {@code "c"}: The list of credentials included in the presentation.</li>
 *     <li>{@code signedAt}  &rarr; {@code "sat"}: The timestamp when the presentation was signed.</li>
 *     <li>{@code signature}  &rarr; {@code "sig"}: The signature over the presentation data.</li>
 * </ul>
 */
@JsonPropertyOrder({"id", "t", "h", "c", "sat", "sig"})
public class Vouch {
	/**
	 * The unique identifier for this presentation.
	 * <p>
	 * Mapped to the compact JSON/CBOR key {@code "id"}.
	 */
	@JsonProperty("id")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final String id;
	/**
	 * The types associated with this presentation.
	 * <p>
	 * Mapped to the compact JSON/CBOR key {@code "t"}.
	 */
	@JsonProperty("t")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<String> types;
	/**
	 * The identifier of the entity presenting the credentials (the holder).
	 * <p>
	 * Mapped to the compact JSON/CBOR key {@code "h"}.
	 */
	@JsonProperty("h")
	private final Id holder;
	/**
	 * The list of credentials included in the presentation.
	 * <p>
	 * Mapped to the compact JSON/CBOR key {@code "c"}.
	 */
	@JsonProperty("c")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<Credential> credentials;
	/**
	 * The timestamp at which the presentation was signed.
	 * <p>
	 * Mapped to the compact JSON/CBOR key {@code "sat"}.
	 */
	@JsonProperty("sat")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final Date signedAt;
	/**
	 * The signature over the presentation data.
	 * <p>
	 * Mapped to the compact JSON/CBOR key {@code "sig"}.
	 */
	@JsonProperty("sig")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final byte[] signature;

	/**
	 * Internal constructor used by JSON deserializer.
	 * <p>
	 * This constructor is used to create a {@code Vouch} instance from JSON or CBOR data.
	 *
	 * @param id          the unique identifier for the presentation
	 * @param types       the types associated with the presentation
	 * @param holder      the identifier of the entity presenting the credentials
	 * @param credentials the list of credentials included in the presentation
	 * @param signedAt    the timestamp at which the presentation was signed
	 * @param signature   the signature over the presentation data
	 */
	@JsonCreator
	protected Vouch(@JsonProperty(value = "id") String id,
					@JsonProperty(value = "t") List<String> types,
					@JsonProperty(value = "h", required = true) Id holder,
					@JsonProperty(value = "c", required = true) List<Credential> credentials,
					@JsonProperty(value = "sat", required = true) Date signedAt,
					@JsonProperty(value = "sig", required = true) byte[] signature) {
		Objects.requireNonNull(holder, "holder");
		Objects.requireNonNull(credentials, "credentials");
		Objects.requireNonNull(signedAt, "signedAt");
		Objects.requireNonNull(signature, "signature");

		this.id = id;
		// Defensive: always wrap as unmodifiable list (or empty)
		this.types = types == null || types.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(types);
		this.holder = holder;
		this.credentials = Collections.unmodifiableList(credentials);
		this.signedAt = signedAt;
		this.signature = signature;
	}

	/**
	 * Internal constructor used by {@link VouchBuilder}.
	 * <p>
	 * The caller should transfer ownership of the collections to the new instance.
	 * Used for building unsigned {@code Vouch} objects.
	 *
	 * @param id          the unique identifier for the presentation
	 * @param types       the types associated with the presentation
	 * @param holder      the identifier of the entity presenting the credentials
	 * @param credentials the list of credentials included in the presentation
	 */
	protected Vouch(String id, List<String> types, Id holder, List<Credential> credentials) {
		this.id = id;
		this.types = types == null || types.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(types);
		this.holder = holder;
		this.credentials = Collections.unmodifiableList(credentials);
		this.signedAt = null;
		this.signature = null;
	}

	/**
	 * Internal constructor used by {@link VouchBuilder} for copying and adding a signature.
	 *
	 * @param vouch     the original vouch to copy
	 * @param signedAt  the timestamp at which the presentation was signed
	 * @param signature the signature over the presentation data
	 */
	protected Vouch(Vouch vouch, Date signedAt, byte[] signature) {
		this.id = vouch.id;
		this.types = vouch.types;
		this.holder = vouch.holder;
		this.credentials = vouch.credentials;
		this.signedAt = signedAt;
		this.signature = signature;
	}

	/**
	 * Returns the unique identifier for this presentation.
	 * <p>
	 * Mapped to the compact JSON/CBOR key {@code "id"}.
	 *
	 * @return the unique identifier, or {@code null} if not set
	 */
	public String getId() {
		return id;
	}

	/**
	 * Returns the types associated with this presentation.
	 * <p>
	 * Mapped to the compact JSON/CBOR key {@code "t"}.
	 *
	 * @return an unmodifiable list of types, possibly empty
	 */
	public List<String> getTypes() {
		return types;
	}

	/**
	 * Returns the identifier of the entity presenting the credentials (the holder).
	 * <p>
	 * Mapped to the compact JSON/CBOR key {@code "h"}.
	 *
	 * @return the holder's identifier
	 */
	public Id getHolder() {
		return holder;
	}

	/**
	 * Returns the list of credentials included in this presentation.
	 * <p>
	 * Mapped to the compact JSON/CBOR key {@code "c"}.
	 *
	 * @return an unmodifiable list of credentials
	 */
	public List<Credential> getCredentials() {
		return credentials;
	}

	/**
	 * Returns a list of credentials that contain the specified type.
	 *
	 * @param type the credential type to filter by (must not be null)
	 * @return an unmodifiable list of credentials containing the given type
	 */
	public List<Credential> getCredentials(String type) {
		Objects.requireNonNull(type, "type");
		return credentials.stream()
				.filter(c -> c.getTypes().contains(type))
				.collect(Collectors.toList());
	}

	/**
	 * Returns the credential with the specified identifier, or {@code null} if not found.
	 *
	 * @param id the credential identifier to search for (must not be null)
	 * @return the credential with the given id, or {@code null} if not present
	 */
	public Credential getCredential(String id) {
		Objects.requireNonNull(id, "id");
		return credentials.stream()
				.filter(c -> c.getId().equals(id))
				.findFirst()
				.orElse(null);
	}

	/**
	 * Returns the timestamp at which this presentation was signed.
	 * <p>
	 * Mapped to the compact JSON/CBOR key {@code "sat"}.
	 *
	 * @return the signing timestamp, or {@code null} if unsigned
	 */
	public Date getSignedAt() {
		return signedAt;
	}

	/**
	 * Returns the signature over the presentation data.
	 * <p>
	 * Mapped to the compact JSON/CBOR key {@code "sig"}.
	 *
	 * @return the signature byte array, or {@code null} if unsigned
	 */
	public byte[] getSignature() {
		return signature;
	}

	/**
	 * Validates the signature of this presentation.
	 *
	 * @throws InvalidSignatureException if the signature is invalid or not genuine
	 */
	public void validate() throws InvalidSignatureException {
		if (!isGenuine())
			throw new InvalidSignatureException();
	}

	/**
	 * Checks if the signature of this presentation is genuine.
	 *
	 * @return {@code true} if the signature is valid and genuine, {@code false} otherwise
	 */
	public boolean isGenuine() {
		// Signature must be present and of correct length
		if (signature == null || signature.length != Signature.BYTES)
			return false;

		// Verify the signature against the sign data and holder's public key
		return Signature.verify(getSignData(), signature, holder.toSignatureKey());
	}

	/**
	 * Generates the data to be signed (or verified) for this presentation.
	 * <p>
	 * If this instance is already signed, returns the CBOR encoding of the presentation with {@code signature} and {@code signedAt} set to {@code null},
	 * otherwise returns the CBOR encoding of this instance.
	 *
	 * @return the byte array to be signed or verified
	 */
	protected byte[] getSignData() {
		// If already signed, strip signature/signedAt for sign data
		if (signature != null)	// already signed
			return new Vouch(this, null, null).toBytes();
		else 					// unsigned
			return toBytes();
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, types, holder, credentials, signedAt, Arrays.hashCode(signature));
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

	/**
	 * Returns the compact JSON string representation of this presentation.
	 *
	 * @return the compact JSON string
	 * @throws IllegalStateException if serialization fails
	 */
	@Override
	public String toString() {
		try {
			return Json.objectMapper().writeValueAsString(this);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("INTERNAL ERROR: Vouch is not serializable", e);
		}
	}

	/**
	 * Returns a pretty-printed JSON string representation of this presentation.
	 *
	 * @return the pretty JSON string
	 * @throws IllegalStateException if serialization fails
	 */
	public String toPrettyString() {
		try {
			return Json.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(this);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("INTERNAL ERROR: Vouch is not serializable", e);
		}
	}

	/**
	 * Serializes this presentation to its compact CBOR byte representation.
	 *
	 * @return the CBOR byte array
	 * @throws IllegalStateException if serialization fails
	 */
	public byte[] toBytes() {
		try {
			return Json.cborMapper().writeValueAsBytes(this);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("INTERNAL ERROR: Vouch is not serializable", e);
		}
	}

	/**
	 * Parses a {@code Vouch} instance from its JSON string representation.
	 *
	 * @param json the JSON string
	 * @return the parsed {@code Vouch} instance
	 * @throws IllegalArgumentException if the JSON is invalid
	 */
	public static Vouch parse(String json) {
		try {
			return Json.objectMapper().readValue(json, Vouch.class);
		} catch (IOException e) {
			throw new IllegalArgumentException("Invalid JSON date for Vouch", e);
		}
	}

	/**
	 * Parses a {@code Vouch} instance from its CBOR byte array representation.
	 *
	 * @param cbor the CBOR byte array
	 * @return the parsed {@code Vouch} instance
	 * @throws IllegalArgumentException if the CBOR is invalid
	 */
	public static Vouch parse(byte[] cbor) {
		try {
			return Json.cborMapper().readValue(cbor, Vouch.class);
		} catch (IOException e) {
			throw new IllegalArgumentException("Invalid CBOR date for Vouch", e);
		}
	}

	/**
	 * Creates a new {@link VouchBuilder} for the given holder.
	 *
	 * @param holder the identity of the presentation holder (must not be null)
	 * @return a new {@code VouchBuilder} instance
	 */
	public static VouchBuilder builder(Identity holder) {
		Objects.requireNonNull(holder, "holder");
		return new VouchBuilder(holder);
	}
}