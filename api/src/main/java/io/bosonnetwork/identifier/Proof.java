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

/**
 * Represents a cryptographic proof attached to DID Documents or Verifiable Credentials.
 * The proof contains information about the type of signature, creation date, the verification method used,
 * the purpose of the proof, and the cryptographic proof value itself.
 */
@JsonPropertyOrder({ "type", "created", "verificationMethod", "proofPurpose", "proofValue" })
public class Proof {
	/**
	 * The type of cryptographic proof (e.g., signature suite).
	 */
	@JsonProperty("type")
	private final Type type;

	/**
	 * The date and time when the proof was created.
	 */
	@JsonProperty("created")
	private final Date created;

	/**
	 * The verification method (e.g., public key) used to verify the proof.
	 */
	@JsonProperty("verificationMethod")
	private final VerificationMethod verificationMethod;

	/**
	 * The purpose of the proof, indicating the reason or context for which the proof was generated.
	 */
	@JsonProperty("proofPurpose")
	private final Purpose proofPurpose;

	/**
	 * The actual cryptographic proof value (e.g., digital signature bytes).
	 */
	@JsonProperty("proofValue")
	private final byte[] proofValue;

	/**
	 * Enum representing the supported types of cryptographic proofs.
	 */
	public enum Type {
		/** Default type: ED25519 signature */
		Ed25519Signature2020
	}

	/**
	 * Enum representing the purpose of the proof, indicating its intended use or context.
	 */
	public enum Purpose {
		/** Purpose for assertion */
		assertionMethod,
		/** Purpose for authentication */
		authentication,
		/** Purpose for capability invocation */
		capabilityInvocation,
		/** Purpose for capability delegation */
		capabilityDelegation
	}

	/**
	 * Constructs a new Proof instance from JSON properties.
	 * This constructor is used during JSON deserialization and validates that all required fields are present.
	 *
	 * @param type the type of cryptographic proof
	 * @param created the creation date of the proof
	 * @param verificationMethod the verification method used
	 * @param proofPurpose the purpose of the proof
	 * @param proofValue the cryptographic proof value
	 * @throws NullPointerException if any argument is null
	 */
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

	/**
	 * Returns the type of cryptographic proof.
	 *
	 * @return the proof type
	 */
	public Type getType() {
		return type;
	}

	/**
	 * Returns the creation date of the proof.
	 *
	 * @return the creation date
	 */
	public Date getCreated() {
		return created;
	}

	/**
	 * Returns the verification method used to verify the proof.
	 *
	 * @return the verification method
	 */
	public VerificationMethod getVerificationMethod() {
		return verificationMethod;
	}

	/**
	 * Returns the purpose of the proof.
	 *
	 * @return the proof purpose
	 */
	public Purpose getProofPurpose() {
		return proofPurpose;
	}

	/**
	 * Returns the cryptographic proof value (e.g., signature bytes).
	 *
	 * @return the proof value
	 */
	public byte[] getProofValue() {
		return proofValue;
	}

	/**
	 * Verifies the cryptographic proof against the given subject and data.
	 * It checks that the proof value length matches the expected signature size,
	 * that the proof purpose is valid, and that the verification method belongs to the subject.
	 * Finally, it verifies the signature over the data using the subject's signature key.
	 *
	 * @param subject the identity subject of the proof
	 * @param data the data that was signed
	 * @return true if the proof is valid and verifies correctly; false otherwise
	 */
	protected boolean verify(Id subject, byte[] data) {
		// Check if the proofValue length matches the expected signature byte size
		if (proofValue.length != Signature.BYTES)
			return false;

		// Check if the proof purpose is either assertionMethod or authentication
		if (proofPurpose != Purpose.assertionMethod && proofPurpose != Proof.Purpose.authentication)
			return false;

		try {
			// Parse the verification method's DID URL and ensure it belongs to the subject
			DIDURL url = new DIDURL(verificationMethod.getId());
			if (!url.getId().equals(subject))
				return false;

			// Verify the signature over the data using the subject's signature key
			return Signature.verify(data, proofValue, subject.toSignatureKey());
		} catch (MalformedURLException e) {
			// If the verification method ID is malformed, verification fails
			return false;
		}
	}

	/**
	 * Returns a hash code value for the proof.
	 *
	 * @return the hash code
	 */
	@Override
	public int hashCode() {
		return Objects.hash(type, created, verificationMethod, proofPurpose, Arrays.hashCode(proofValue));
	}

	/**
	 * Compares this proof to another object for equality.
	 * Two proofs are equal if all their fields are equal, including deep equality of the proofValue byte array.
	 *
	 * @param o the object to compare to
	 * @return true if the objects are equal; false otherwise
	 */
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

	/**
	 * Returns a string representation of the proof.
	 *
	 * @return a string describing the proof
	 */
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