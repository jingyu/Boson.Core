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

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;

/**
 * Builder class for constructing {@link VerifiableCredential} instances.
 * This builder facilitates setting the issuer, subject, claims, validity periods,
 * and other properties of a verifiable credential, and produces a signed credential
 * with proof.
 */
public class VerifiableCredentialBuilder extends BosonIdentityObjectBuilder<VerifiableCredential> {
	/**
	 * List of JSON-LD contexts associated with the credential.
	 */
	private final List<String> contexts;

	/**
	 * The unique identifier of the credential. Can be a DID URL or a fragment.
	 */
	private String id;

	/**
	 * List of credential types.
	 */
	private final List<String> types;

	/**
	 * Human-readable name of the credential.
	 */
	private String name;

	/**
	 * Description of the credential.
	 */
	private String description;

	/**
	 * The date from which the credential is valid.
	 */
	private Date validFrom;

	/**
	 * The date until which the credential is valid.
	 */
	private Date validUntil;

	/**
	 * The subject of the credential, typically the entity the credential refers to.
	 */
	private Id subject;

	/**
	 * Claims or attributes asserted by the credential about the subject.
	 */
	private final Map<String, Object> claims;

	/**
	 * Constructs a new builder instance with default contexts and types initialized.
	 *
	 * @param identity the identity issuing the credential
	 */
	protected VerifiableCredentialBuilder(Identity identity) {
		super(identity);

		this.contexts = new ArrayList<>();
		this.types = new ArrayList<>();
		this.claims = new LinkedHashMap<>();

		// Add the default type and contexts
		type(DIDConstants.DEFAULT_VC_TYPE,
				DIDConstants.W3C_VC_CONTEXT, DIDConstants.BOSON_VC_CONTEXT, DIDConstants.W3C_ED25519_CONTEXT);
	}

	/**
	 * Sets the identifier of the credential.
	 *
	 * @param id the credential identifier, must be non-null and non-empty,
	 *           if a DID URL, must contain a fragment part
	 * @return this builder instance for chaining
	 * @throws IllegalArgumentException if id is empty or invalid format
	 */
	public VerifiableCredentialBuilder id(String id) {
		Objects.requireNonNull(id, "id");
		if (id.isEmpty())
			throw new IllegalArgumentException("Id cannot be empty");

		if (id.startsWith(DIDConstants.DID_SCHEME + ":")) {
			// Validate DID URL format and ensure it has a fragment
			DIDURL url = DIDURL.create(id); // check the format only
			if (url.getFragment() == null)
				throw new IllegalArgumentException("Id must has the fragment part");
		}

		this.id = normalize(id);
		return this;
	}

	/**
	 * Adds a credential type and associated JSON-LD contexts.
	 *
	 * @param type     the credential type to add, must be non-null
	 * @param contexts optional list of contexts related to the type
	 * @return this builder instance for chaining
	 */
	public VerifiableCredentialBuilder type(String type, String... contexts) {
		return type(type, List.of(contexts));
	}

	/**
	 * Adds a credential type and associated JSON-LD contexts.
	 *
	 * @param type     the credential type to add, must be non-null
	 * @param contexts list of contexts related to the type
	 * @return this builder instance for chaining
	 */
	public VerifiableCredentialBuilder type(String type, List<String> contexts) {
		Objects.requireNonNull(type, "type");

		type = normalize(type);
		if (!this.types.contains(type))
			this.types.add(type);

		for (String context : contexts) {
			if (context == null || context.isEmpty())
				continue;

			context = normalize(context);
			if (!this.contexts.contains(context))
				this.contexts.add(context);
		}

		return this;
	}

	/**
	 * Sets the human-readable name of the credential.
	 *
	 * @param name the name of the credential, null or empty clears the name
	 * @return this builder instance for chaining
	 */
	public VerifiableCredentialBuilder name(String name) {
		this.name = name == null || name.isEmpty() ? null : normalize(name);
		return this;
	}

	/**
	 * Sets the description of the credential.
	 *
	 * @param description the description, null or empty clears the description
	 * @return this builder instance for chaining
	 */
	public VerifiableCredentialBuilder description(String description) {
		this.description = description == null || description.isEmpty() ? null : normalize(description);
		return this;
	}

	/**
	 * Sets the date from which the credential is valid.
	 *
	 * @param validFrom the start validity date, null clears the value
	 * @return this builder instance for chaining
	 */
	public VerifiableCredentialBuilder validFrom(Date validFrom) {
		this.validFrom = validFrom == null ? null : trimMillis(validFrom);
		return this;
	}

	/**
	 * Sets the date until which the credential is valid.
	 *
	 * @param validUntil the end validity date, null clears the value
	 * @return this builder instance for chaining
	 */
	public VerifiableCredentialBuilder validUntil(Date validUntil) {
		this.validUntil = validUntil == null ? null : trimMillis(validUntil);
		return this;
	}

	/**
	 * Sets the subject of the credential.
	 *
	 * @param subject the subject identifier of the credential
	 * @return this builder instance for chaining
	 */
	public VerifiableCredentialBuilder subject(Id subject) {
		this.subject = subject;
		return this;
	}

	/**
	 * Adds a single claim (attribute) to the credential.
	 *
	 * @param name  the claim name, must be non-null, non-empty, and not "id"
	 * @param value the claim value, must be non-null
	 * @return this builder instance for chaining
	 * @throws IllegalArgumentException if name is empty or "id"
	 */
	public VerifiableCredentialBuilder claim(String name, Object value) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(value, "value");
		if (name.isEmpty())
			throw new IllegalArgumentException("Claim name cannot be empty");
		// Claims cannot contain the reserved "id" field
		if (name.equals("id"))
			throw new IllegalArgumentException("Claims cannot contain 'id'");

		this.claims.put(normalize(name), normalize(value));
		return this;
	}

	/**
	 * Adds multiple claims to the credential.
	 *
	 * @param claims a map of claim names to values, must not contain "id" key
	 * @return this builder instance for chaining
	 * @throws IllegalArgumentException if claims contain "id" key
	 */
	public VerifiableCredentialBuilder claims(Map<String, Object> claims) {
		if (claims == null || claims.isEmpty())
			return this;

		// Ensure no claim is named "id"
		if (claims.keySet().stream().anyMatch(k -> k.equals("id")))
			throw new IllegalArgumentException("Claims cannot contain 'id'");

		this.claims.putAll(normalize(claims));
		return this;
	}

	/**
	 * Builds the {@link VerifiableCredential} instance.
	 * <p>
	 * Validates that the subject is set (defaults to issuer if not),
	 * the credential id format is consistent with the subject,
	 * and that claims are not empty.
	 * Signs the credential data and attaches a proof.
	 *
	 * @return the fully constructed and signed verifiable credential
	 * @throws IllegalStateException if validation fails or claims are empty
	 */
	@Override
	public VerifiableCredential build() {
		Id issuer = identity.getId();

		if (subject == null)
			subject = identity.getId();

		DIDURL idUrl;
		if (id.startsWith(DIDConstants.DID_SCHEME + ":")) {
			// Canonical DIDURL
			idUrl = DIDURL.create(id);
			// The credential id must be a DIDURL based on the subject's DID
			if (!idUrl.getId().equals(subject))
				throw new IllegalStateException("Invalid credential id: should be the subject id based DIDURL");
			// The DIDURL must contain a fragment part
			if (idUrl.getFragment() == null)
				throw new IllegalStateException("Invalid credential id: should has the fragment part");
		} else {
			// id is a fragment or relative reference only, create DIDURL using subject DID and fragment
			idUrl = new DIDURL(subject, null, null, id);
		}

		if (claims.isEmpty())
			throw new IllegalStateException("Claims cannot be empty");

		VerifiableCredential unsigned = new VerifiableCredential(contexts, idUrl.toString(), types,
				name, description, issuer, validFrom, validUntil, subject, claims);
		byte[] signature = identity.sign(unsigned.getSignData());
		Proof proof = new Proof(Proof.Type.Ed25519Signature2020, now(),
				VerificationMethod.defaultReferenceOf(issuer), Proof.Purpose.assertionMethod, signature);

		return new VerifiableCredential(unsigned, proof);
	}
}