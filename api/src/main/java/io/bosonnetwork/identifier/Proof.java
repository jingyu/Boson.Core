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

import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.Date;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.utils.Hex;

@JsonPropertyOrder({ "type", "created", "verificationMethod", "proofPurpose", "proofValue" })
public class Proof {
	@JsonProperty("type")
	private final Type type;
	@JsonProperty("created")
	private final Date created;
	@JsonProperty("verificationMethod")
	private final VerificationMethod verificationMethod;
	@JsonProperty("proofPurpose")
	private final Purpose proofPurpose;
	@JsonProperty("proofValue")
	private final byte[] proofValue;

	public enum Type {
		Ed25519Signature2020
	}

	public enum Purpose {
		assertionMethod, authentication, capabilityInvocation, capabilityDelegation
	}

	@JsonCreator
	protected Proof(@JsonProperty(value = "type", required = true, defaultValue = "Ed25519Signature2020") Type type,
					@JsonProperty(value = "created", required = true) Date created,
					@JsonProperty(value = "verificationMethod", required = true) VerificationMethod verificationMethod,
					@JsonProperty(value = "proofPurpose", required = true) Purpose proofPurpose,
					@JsonProperty(value = "proofValue", required = true) byte[] proofValue) {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(created, "created");
		Objects.requireNonNull(verificationMethod, "verificationMethod");
		Objects.requireNonNull(proofPurpose, "proofPurpose");
		Objects.requireNonNull(proofValue, "proofValue");

		this.type = type;
		this.created = created;
		this.proofPurpose = proofPurpose;
		this.proofValue = proofValue;
		this.verificationMethod = verificationMethod;
	}

	public Type getType() {
		return type;
	}

	public Date getCreated() {
		return created;
	}

	public VerificationMethod getVerificationMethod() {
		return verificationMethod;
	}

	public Purpose getProofPurpose() {
		return proofPurpose;
	}

	public byte[] getProofValue() {
		return proofValue;
	}

	protected boolean verify(Id subject, byte[] data) {
		if (proofValue.length != Signature.BYTES)
			return false;

		if (proofPurpose != Purpose.assertionMethod && proofPurpose != Proof.Purpose.authentication)
			return false;

		try {
			// Make sure the verificationMethod belongs to the subject
			DIDURL url = new DIDURL(verificationMethod.getId());
			if (!url.getId().equals(subject))
				return false;

			return Signature.verify(data, proofValue, subject.toSignatureKey());
		} catch (MalformedURLException e) {
			return false;
		}
	}

	@Override
	public int hashCode() {
		return Objects.hash(type, created, verificationMethod, proofPurpose, Arrays.hashCode(proofValue));
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;

		if (o instanceof Proof that)
			return Objects.equals(type, that.type) &&
					Objects.equals(created, that.created) &&
					Objects.equals(verificationMethod, that.verificationMethod) &&
					proofPurpose == that.proofPurpose &&
					Arrays.equals(proofValue, that.proofValue);

		return false;
	}

	@Override
	public String toString() {
		return "Proof{" +
				"type=" + type +
				", created=" + created.getTime() +
				", verificationMethod=" + verificationMethod +
				", proofPurpose=" + proofPurpose +
				", proofValue=" + Hex.encode(proofValue) +
				'}';
	}
}