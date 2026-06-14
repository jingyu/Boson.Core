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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;

/**
 * Builder class for constructing Boson's compact {@link Credential} instances.
 * <p>
 * This builder allows setting the credential's identifier, types, name, description,
 * validity period, subject, and claims. It ensures claims do not contain reserved fields,
 * normalizes all strings, and produces a signed Credential with the issuer's identity key.
 */
public class CredentialBuilder extends BosonIdentityObjectBuilder<Credential> {
	/** Credential identifier */
	private String id;
	/** List of credential types */
	private final List<String> types;
	/** Human-readable name of the credential */
	private @Nullable String name;
	/** Description of the credential */
	private @Nullable String description;
	/** Start date of credential validity */
	private @Nullable Date validFrom;
	/** End date of credential validity */
	private @Nullable Date validUntil;
	/** Subject of the credential */
	private Id subject;
	/** Claims or attributes asserted by the credential */
	private final Map<String, Object> claims;

	/**
	 * Creates a new CredentialBuilder for the given issuer identity.
	 * Initializes types list and claims map.
	 *
	 * @param identity the issuer identity
	 */
	protected CredentialBuilder(Identity identity) {
		super(identity);

		this.types = new ArrayList<>();
		this.claims = new LinkedHashMap<>();
	}

	/**
	 * Sets the credential identifier.
	 * <p>
	 * The id is normalized and must be non-null and non-empty.
	 *
	 * @param id the credential identifier
	 * @return this builder instance
	 * @throws NullPointerException if id is null
	 * @throws IllegalArgumentException if id is empty
	 */
	public CredentialBuilder id(String id) {
		Objects.requireNonNull(id, "id");
		if (id.isEmpty())
			throw new IllegalArgumentException("Credential id must not be empty");

		this.id = normalize(id);
		return this;
	}

	/**
	 * Adds one or more credential types from a list.
	 * <p>
	 * Each type is normalized, duplicates are ignored, and null or empty types are skipped.
	 *
	 * @param types list of credential types
	 * @return this builder instance
	 * @throws NullPointerException if types is null
	 */
	public CredentialBuilder type(List<String> types) {
		Objects.requireNonNull(types, "types");

		for (String type : types) {
			if (type == null || type.isEmpty())
				continue;

			// Normalize type string
			type = normalize(type);
			if (this.types.contains(type))
				continue;

			this.types.add(type);
		}

		return this;
	}

	/**
	 * Adds one or more credential types from varargs.
	 * Delegates to {@link #type(List)}.
	 *
	 * @param types one or more credential types
	 * @return this builder instance
	 */
	public CredentialBuilder type(String... types) {
		return type(List.of(types));
	}

	/**
	 * Sets the human-readable name of the credential.
	 * <p>
	 * The name is normalized if non-null and non-empty; otherwise set to null.
	 *
	 * @param name the credential name
	 * @return this builder instance
	 */
	public CredentialBuilder name(@Nullable String name) {
		this.name = name == null || name.isEmpty() ? null : normalize(name);
		return this;
	}

	/**
	 * Sets the description of the credential.
	 * <p>
	 * The description is normalized if non-null and non-empty; otherwise set to null.
	 *
	 * @param description the credential description
	 * @return this builder instance
	 */
	public CredentialBuilder description(@Nullable String description) {
		this.description = description == null || description.isEmpty() ? null : normalize(description);
		return this;
	}

	/**
	 * Sets the start date of the credential validity period.
	 * <p>
	 * The date is trimmed to remove milliseconds for consistency.
	 *
	 * @param validFrom the start date
	 * @return this builder instance
	 */
	public CredentialBuilder validFrom(@Nullable Date validFrom) {
		// Trim milliseconds from date if not null
		this.validFrom = validFrom == null ? null : trimMillis(validFrom);
		return this;
	}

	/**
	 * Sets the end date of the credential validity period.
	 * <p>
	 * The date is trimmed to remove milliseconds for consistency.
	 *
	 * @param validUntil the end date
	 * @return this builder instance
	 */
	public CredentialBuilder validUntil(@Nullable Date validUntil) {
		// Trim milliseconds from date if not null
		this.validUntil = validUntil == null ? null : trimMillis(validUntil);
		return this;
	}

	/**
	 * Sets the subject of the credential.
	 *
	 * @param subject the credential subject
	 * @return this builder instance
	 */
	public CredentialBuilder subject(Id subject) {
		this.subject = subject;
		return this;
	}

	/**
	 * Adds a single claim (attribute) to the credential.
	 * <p>
	 * The claim name and value are normalized. The claim name must be non-null,
	 * non-empty, and cannot be the reserved key "id".
	 *
	 * @param name the claim name
	 * @param value the claim value
	 * @return this builder instance
	 * @throws NullPointerException if name or value is null
	 * @throws IllegalArgumentException if name is empty or equals "id"
	 */
	public CredentialBuilder claim(String name, Object value) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(value, "value");
		if (name.isEmpty())
			throw new IllegalArgumentException("Credential claim name must not be empty");
		// Reserved claim key check
		if (name.equals("id"))
			throw new IllegalArgumentException("Credential claims must not contain reserved key: id");

		// Normalize name and value before adding
		this.claims.put(normalize(name), normalize(value));
		return this;
	}

	/**
	 * Adds multiple claims (attributes) to the credential.
	 * <p>
	 * The claims map must not be null or empty and cannot contain the reserved key "id".
	 * All keys and values are normalized before adding.
	 *
	 * @param claims map of claim names to values
	 * @return this builder instance
	 * @throws IllegalArgumentException if claims contain "id" key
	 */
	public CredentialBuilder claims(Map<String, Object> claims) {
		if (claims == null || claims.isEmpty())
			return this;

		// Reserved claim key check
		if (claims.keySet().stream().anyMatch(k -> k.equals("id")))
			throw new IllegalArgumentException("Credential claims must not contain reserved key: id");

		// Normalize all keys and values before adding
		this.claims.putAll(normalize(claims));
		return this;
	}

	/**
	 * Builds and signs the Credential.
	 * <p>
	 * Validates that claims are not empty, constructs an unsigned Credential,
	 * signs it using the issuer's identity, and returns the signed Credential instance.
	 *
	 * @return signed Credential instance
	 * @throws IllegalStateException if claims are empty
	 */
	@Override
	public Credential build() {
		if (id == null || id.isEmpty())
			throw new IllegalStateException("Credential id must be set and non-empty");

		if (claims.isEmpty())
			throw new IllegalStateException("Credential must contain at least one claim");

		// Stamp signedAt before signing so it is covered by the signature.
		Date signedAt = now();
		Credential unsigned = new Credential(id, types, name, description, identity.getId(), validFrom, validUntil,
				subject, claims, signedAt, null);
		byte[] signature = identity.sign(unsigned.getSignData());
		return new Credential(unsigned, signature);
	}
}