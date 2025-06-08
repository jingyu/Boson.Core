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

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class CredentialBuilder extends BosonIdentityObjectBuilder {
	private final Identity issuer;
	private String id;
	private final List<String> types;
	private String name;
	private String description;
	private Date validFrom;
	private Date validUntil;
	private Id subject;
	private final Map<String, Object> claims;

	protected CredentialBuilder(Identity issuer) {
		this.issuer = issuer;
		this.types = new ArrayList<>();
		this.claims = new LinkedHashMap<>();
	}

	public CredentialBuilder id(String id) {
		Objects.requireNonNull(id, "id");
		if (id.isEmpty())
			throw new IllegalArgumentException("Id cannot be empty");

		this.id = normalize(id);
		return this;
	}

	public CredentialBuilder type(List<String> types) {
		for (String type : types) {
			if (type == null || type.isEmpty())
				continue;

			type = normalize(type);
			if (this.types.contains(type))
				continue;

			this.types.add(type);
		}

		return this;
	}

	public CredentialBuilder type(String... types) {
		return type(List.of(types));
	}

	public CredentialBuilder name(String name) {
		this.name = name == null || name.isEmpty() ? null : normalize(name);
		return this;
	}

	public CredentialBuilder description(String description) {
		this.description = description == null || description.isEmpty() ? null : normalize(description);
		return this;
	}

	public CredentialBuilder validFrom(Date validFrom) {
		this.validFrom = validFrom == null ? null : trimMillis(validFrom);
		return this;
	}

	public CredentialBuilder validUntil(Date validUntil) {
		this.validUntil = validUntil == null ? null : trimMillis(validUntil);
		return this;
	}

	public CredentialBuilder subject(Id subject) {
		this.subject = subject;
		return this;
	}

	public CredentialBuilder claim(String name, Object value) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(value, "value");
		if (name.isEmpty())
			throw new IllegalArgumentException("Claim name cannot be empty");
		if (name.equals("id"))
			throw new IllegalArgumentException("Claims cannot contain 'id'");

		this.claims.put(normalize(name), normalize(value));
		return this;
	}

	public CredentialBuilder claims(Map<String, Object> claims) {
		if (claims == null || claims.isEmpty())
			return this;

		if (claims.keySet().stream().anyMatch(k -> k.equals("id")))
			throw new IllegalArgumentException("Claims cannot contain 'id'");

		this.claims.putAll(normalize(claims));
		return this;
	}

	public Credential build() {
		if (claims.isEmpty())
			throw new IllegalStateException("Claims cannot be empty");

		Credential unsigned = new Credential(id, types, name, description, issuer.getId(), validFrom, validUntil,
				subject, claims, null, null);
		byte[] signature = issuer.sign(unsigned.getSignData());
		return new Credential(unsigned, now(), signature);
	}
}