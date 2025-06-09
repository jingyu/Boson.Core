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

import io.bosonnetwork.BosonIdentityObjectBuilder;
import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;

public class VerifiableCredentialBuilder extends BosonIdentityObjectBuilder<VerifiableCredential> {
	private final List<String> contexts;
	private String id;
	private final List<String> types;
	private String name;
	private String description;
	private Date validFrom;
	private Date validUntil;
	private Id subject;
	private final Map<String, Object> claims;

	protected VerifiableCredentialBuilder(Identity identity) {
		super(identity);

		this.contexts = new ArrayList<>();
		this.types = new ArrayList<>();
		this.claims = new LinkedHashMap<>();

		// Add the default type and contexts
		type(DIDConstants.DEFAULT_VC_TYPE,
				DIDConstants.W3C_VC_CONTEXT, DIDConstants.BOSON_VC_CONTEXT, DIDConstants.W3C_ED25519_CONTEXT);
	}

	public VerifiableCredentialBuilder id(String id) {
		Objects.requireNonNull(id, "id");
		if (id.isEmpty())
			throw new IllegalArgumentException("Id cannot be empty");

		if (id.startsWith(DIDConstants.DID_SCHEME + ":")) {
			DIDURL url = DIDURL.create(id); // check the format only
			if (url.getFragment() == null)
				throw new IllegalArgumentException("Id must has the fragment part");
		}

		this.id = normalize(id);
		return this;
	}

	public VerifiableCredentialBuilder type(String type, String... contexts) {
		return type(type, List.of(contexts));
	}

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

	public VerifiableCredentialBuilder name(String name) {
		this.name = name == null || name.isEmpty() ? null : normalize(name);
		return this;
	}

	public VerifiableCredentialBuilder description(String description) {
		this.description = description == null || description.isEmpty() ? null : normalize(description);
		return this;
	}

	public VerifiableCredentialBuilder validFrom(Date validFrom) {
		this.validFrom = validFrom == null ? null : trimMillis(validFrom);
		return this;
	}

	public VerifiableCredentialBuilder validUntil(Date validUntil) {
		this.validUntil = validUntil == null ? null : trimMillis(validUntil);
		return this;
	}

	public VerifiableCredentialBuilder subject(Id subject) {
		this.subject = subject;
		return this;
	}

	public VerifiableCredentialBuilder claim(String name, Object value) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(value, "value");
		if (name.isEmpty())
			throw new IllegalArgumentException("Claim name cannot be empty");
		if (name.equals("id"))
			throw new IllegalArgumentException("Claims cannot contain 'id'");

		this.claims.put(normalize(name), normalize(value));
		return this;
	}

	public VerifiableCredentialBuilder claims(Map<String, Object> claims) {
		if (claims == null || claims.isEmpty())
			return this;

		if (claims.keySet().stream().anyMatch(k -> k.equals("id")))
			throw new IllegalArgumentException("Claims cannot contain 'id'");

		this.claims.putAll(normalize(claims));
		return this;
	}

	@Override
	public VerifiableCredential build() {
		Id issuer = identity.getId();

		if (subject == null)
			subject = identity.getId();

		DIDURL idUrl;
		if (id.startsWith(DIDConstants.DID_SCHEME + ":")) {
			// Canonical DIDURL
			idUrl = DIDURL.create(id);
			if (!idUrl.getId().equals(subject))
				throw new IllegalStateException("Invalid credential id: should be the subject id based DIDURL");
			if (idUrl.getFragment() == null)
				throw new IllegalStateException("Invalid credential id: should has the fragment part");
		} else {
			// fragment/reference only
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