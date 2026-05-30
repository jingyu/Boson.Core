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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.InvalidSignatureException;

/**
 * Represents a W3C-compliant Verifiable Presentation (VP) in the Boson network.
 * <p>
 * A Verifiable Presentation contains a set of Verifiable Credentials issued to a holder,
 * along with JSON-LD contexts, optional presentation types, and a cryptographic proof.
 * This class provides methods to validate the proof, convert to a compact Boson Vouch,
 * and build presentations from vouches.
 */
@JsonPropertyOrder({ "@context", "id", "type", "holder", "verifiableCredential", "proof" })
public class VerifiablePresentation extends W3CDIDFormat {
	/** JSON-LD context URIs for interpreting the presentation semantics */
	@JsonProperty("@context")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<String> contexts;
	/** Unique identifier of the presentation */
	@JsonProperty("id")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final String id;
	/** List of presentation types, including custom extensions */
	@JsonProperty("type")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<String> types;
	/** Holder identity of the presentation */
	@JsonProperty("holder")
	private final Id holder;
	/** List of included Verifiable Credentials */
	@JsonProperty("verifiableCredential")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<VerifiableCredential> credentials;
	/** Cryptographic proof of the presentation */
	@JsonProperty("proof")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final Proof proof;

	/** Transient compact Boson Vouch representation */
	private transient volatile VouchView vouchView;

	/**
	 * Internal constructor used by JSON deserializer to create a VerifiablePresentation instance.
	 *
	 * @param contexts JSON-LD contexts
	 * @param id unique identifier of the presentation
	 * @param types list of presentation types
	 * @param holder holder identity (required)
	 * @param credentials list of verifiable credentials (required)
	 * @param proof cryptographic proof (required)
	 */
	@JsonCreator
	protected VerifiablePresentation(@JsonProperty(value = "@context") List<String> contexts,
					@JsonProperty(value = "id") String id,
					@JsonProperty(value = "type") List<String> types,
					@JsonProperty(value = "holder", required = true) Id holder,
					@JsonProperty(value = "verifiableCredential", required = true) List<VerifiableCredential> credentials,
					@JsonProperty(value = "proof", required = true) Proof proof) {
		Objects.requireNonNull(holder, "holder");
		Objects.requireNonNull(credentials, "credentials");
		Objects.requireNonNull(proof, "proof");

		this.contexts = contexts == null || contexts.isEmpty() ? List.of() : List.copyOf(contexts);
		this.id = id;
		this.types = types == null || types.isEmpty() ? List.of() : List.copyOf(types);
		this.holder = holder;
		this.credentials = List.copyOf(credentials);
		this.proof = proof;
	}

	/**
	 * Internal constructor used by VerifiablePresentationBuilder.
	 * The caller should transfer ownership of the collections to the new instance.
	 *
	 * @param contexts JSON-LD contexts
	 * @param id unique identifier of the presentation
	 * @param types list of presentation types
	 * @param holder holder identity
	 * @param credentials list of verifiable credentials
	 */
	protected VerifiablePresentation(List<String> contexts, String id, List<String> types, Id holder, List<VerifiableCredential> credentials) {
		this.contexts = contexts == null || contexts.isEmpty() ? List.of() : List.copyOf(contexts);
		this.id = id;
		this.types = types == null || types.isEmpty() ? List.of() : List.copyOf(types);
		this.holder = holder;
		this.credentials = List.copyOf(credentials);
		this.proof = null;
	}

	/**
	 * Internal constructor used by VerifiablePresentationBuilder to create a copy with a proof.
	 *
	 * @param vp existing VerifiablePresentation instance
	 * @param proof cryptographic proof to associate
	 */
	protected VerifiablePresentation(VerifiablePresentation vp, Proof proof) {
		this.contexts = vp.contexts;
		this.id = vp.id;
		this.types = vp.types;
		this.holder = vp.holder;
		this.credentials = vp.credentials;
		this.proof = proof;
	}

	/**
	 * Returns the JSON-LD context URIs for interpreting the presentation semantics.
	 *
	 * @return list of context URIs
	 */
	public List<String> getContexts() {
		return contexts;
	}

	/**
	 * Returns the unique identifier of the presentation.
	 *
	 * @return presentation ID
	 */
	public String getId() {
		return id;
	}

	/**
	 * Returns the list of presentation types, including any custom extensions.
	 *
	 * @return list of types
	 */
	public List<String> getTypes() {
		return types;
	}

	/**
	 * Returns the holder identity of the presentation.
	 *
	 * @return holder Id
	 */
	public Id getHolder() {
		return holder;
	}

	/**
	 * Returns the list of included Verifiable Credentials.
	 *
	 * @return list of VerifiableCredential
	 */
	public List<VerifiableCredential> getCredentials() {
		return credentials;
	}

	/**
	 * Returns the list of included Verifiable Credentials filtered by the specified type.
	 *
	 * @param type the credential type to filter by (non-null)
	 * @return list of VerifiableCredential matching the type
	 * @throws NullPointerException if type is null
	 */
	public List<VerifiableCredential> getCredentials(String type) {
		Objects.requireNonNull(type, "type");
		return credentials.stream()
				.filter(vc -> vc.getTypes().contains(type))
				.collect(Collectors.toList());
	}

	/**
	 * Returns the Verifiable Credential matching the specified identifier string.
	 * If the id does not start with the DID scheme prefix, it is interpreted as a fragment relative to the holder.
	 *
	 * @param id the credential identifier string (non-null)
	 * @return matching VerifiableCredential or null if not found
	 * @throws NullPointerException if id is null
	 */
	public VerifiableCredential getCredential(String id) {
		Objects.requireNonNull(id, "id");
		// Construct DIDURL from id string, relative to holder if no DID scheme prefix
		DIDURL idUrl = id.startsWith(DIDConstants.DID_SCHEME + ":") ?
				DIDURL.create(id) : new DIDURL(holder, null, null, id);

		return getCredential(idUrl);
	}

	/**
	 * Returns the Verifiable Credential matching the specified DIDURL.
	 *
	 * @param id the DIDURL of the credential (non-null)
	 * @return matching VerifiableCredential or null if not found
	 * @throws NullPointerException if id is null
	 */
	public VerifiableCredential getCredential(DIDURL id) {
		Objects.requireNonNull(id, "id");

		String sid = id.toString();
		return credentials.stream()
				.filter(vc -> vc.getId().equals(sid))
				.findFirst()
				.orElse(null);
	}

	/**
	 * Returns the cryptographic proof of the presentation.
	 *
	 * @return proof object, may be null if not present
	 */
	public Proof getProof() {
		return proof;
	}

	/**
	 * Validates the presentation: the holder's proof and the signature of every embedded
	 * credential must all verify. Throws InvalidSignatureException if any of them is invalid
	 * or the proof is missing.
	 * <p>
	 * Note: this verifies <em>signatures</em> only. The validity period (validFrom/validUntil)
	 * of the embedded credentials is a caller policy and is NOT checked here; call
	 * {@link VerifiableCredential#validate()} on the individual credentials if needed.
	 *
	 * @throws InvalidSignatureException if signature verification fails
	 */
	public void validate() throws InvalidSignatureException {
		if (!isGenuine())
			throw new InvalidSignatureException();
	}

	/**
	 * Checks whether this presentation is genuine: the holder's proof verifies <em>and</em> every
	 * embedded {@link VerifiableCredential} is itself genuine. The holder's envelope signature
	 * alone is not sufficient, since it does not attest to the authenticity of the contained
	 * credentials.
	 * <p>
	 * This checks signatures only, not the validity period of the embedded credentials.
	 *
	 * @return true if the holder proof and all embedded credential signatures verify; false otherwise
	 */
	public boolean isGenuine() {
		if (proof == null)
			return false;

		try {
			if (!proof.verify(holder, getSignData()))
				return false;
		} catch (RuntimeException e) {
			// Malformed id makes sign-data reconstruction throw; treat as not genuine.
			return false;
		}

		// The envelope is authentic; now require every embedded credential to be genuine too.
		return credentials.stream().allMatch(VerifiableCredential::isGenuine);
	}

	/**
	 * Converts this VerifiablePresentation into a compact Boson Vouch representation.
	 *
	 * @return Vouch representation of this presentation
	 */
	public Vouch toVouch() {
		if (vouchView == null)
			vouchView = new VouchView(this);

		return vouchView;
	}

	/**
	 * Creates a VerifiablePresentation instance from a given Vouch and optional type-context mappings.
	 *
	 * @param vouch the Vouch to convert (non-null)
	 * @param typeContexts optional map of type to JSON-LD contexts; may be null
	 * @return constructed VerifiablePresentation instance
	 * @throws NullPointerException if vouch is null
	 */
	public static VerifiablePresentation fromVouch(Vouch vouch, Map<String, List<String>> typeContexts) {
		Objects.requireNonNull(vouch, "vouch");
		if (vouch instanceof VouchView bv)
			return bv.getVerifiablePresentation();

		List<String> contexts = new ArrayList<>();
		List<String> types = new ArrayList<>();

		// Add default contexts for W3C VC, Boson VC, and Ed25519 signature suite
		contexts.add(DIDConstants.W3C_VC_CONTEXT);
		contexts.add(DIDConstants.BOSON_VC_CONTEXT);
		contexts.add(DIDConstants.W3C_ED25519_CONTEXT);
		types.add(DIDConstants.DEFAULT_VP_TYPE);

		Map<String, List<String>> _typeContexts = typeContexts == null ? Map.of() : typeContexts;
		for (String type : vouch.getTypes()) {
			if (type.equals(DIDConstants.DEFAULT_VP_TYPE))
				continue;

			types.add(type);

			// Add additional contexts associated with the type, avoiding duplicates
			if (_typeContexts.containsKey(type)) {
				for (String context : _typeContexts.get(type)) {
					if (!contexts.contains(context))
						contexts.add(context);
				}
			}
		}

		// Construct VerifiablePresentation with mapped credentials and proof
		VerifiablePresentation vp = new VerifiablePresentation(contexts,
				vouch.getId() == null ? null :
						// Construct DIDURL string using holder and fragment id
						new DIDURL(vouch.getHolder(), null, null, vouch.getId()).toString(),
				types, vouch.getHolder(),
				// Map credentials from Vouch to VerifiableCredential instances
				vouch.getCredentials().stream().map(c -> VerifiableCredential.fromCredential(c, _typeContexts))
						.collect(Collectors.toList()),
				new Proof(Proof.Type.Ed25519Signature2020, vouch.getSignedAt(),
						VerificationMethod.defaultReferenceOf(vouch.getHolder()),
						Proof.Purpose.assertionMethod, vouch.getSignature()));

		vp.vouchView = new VouchView(vouch, vp);
		return vp;

	}

	/**
	 * Returns the byte array data over which the proof signature is computed.
	 *
	 * @return byte array of sign data
	 */
	protected byte[] getSignData() {
		// At verification time the proof is non-null and provides the signed-at timestamp,
		// so the VouchView view rebuilds the exact bytes the underlying Vouch signature covers
		// (id, types, holder, credentials, sat).
		VouchView view = vouchView != null ? vouchView : new VouchView(this);
		return view.getSignData();
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, types, holder, credentials, proof);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;

		if (o instanceof VerifiablePresentation that)
			return Objects.equals(id, that.id) &&
					Objects.equals(types, that.types) &&
					Objects.equals(holder, that.holder) &&
					Objects.equals(credentials, that.credentials) &&
					Objects.equals(proof, that.proof);

		return false;
	}

	/**
	 * Parses a JSON string into a VerifiablePresentation instance.
	 *
	 * @param json JSON representation of the presentation
	 * @return parsed VerifiablePresentation instance
	 */
	public static VerifiablePresentation parse(String json) {
		return parse(json, VerifiablePresentation.class);
	}

	/**
	 * Parses CBOR-encoded bytes into a VerifiablePresentation instance.
	 *
	 * @param cbor CBOR byte array representation
	 * @return parsed VerifiablePresentation instance
	 */
	public static VerifiablePresentation parse(byte[] cbor) {
		return parse(cbor, VerifiablePresentation.class);
	}

	/**
	 * Creates a builder for a VerifiablePresentation using the specified holder identity.
	 *
	 * @param holder identity of the presentation holder (non-null)
	 * @return VerifiablePresentationBuilder instance
	 * @throws NullPointerException if holder is null
	 */
	public static VerifiablePresentationBuilder builder(Identity holder) {
		Objects.requireNonNull(holder, "holder");
		return new VerifiablePresentationBuilder(holder);
	}

	/**
	 * Represents the compact Boson Vouch form of a VerifiablePresentation.
	 * <p>
	 * This nested class wraps the VP data into a simplified Vouch structure,
	 * including types, holder, credentials, creation time, and signature.
	 */
	protected static class VouchView extends Vouch {
		/** The wrapped VerifiablePresentation instance */
		private final VerifiablePresentation vp;

		/**
		 * Constructs a VouchView from a VerifiablePresentation with proof.
		 *
		 * @param vp the VerifiablePresentation instance (must have proof)
		 */
		protected VouchView(VerifiablePresentation vp) {
			super(vp.id == null ? null : DIDURL.create(vp.id).getFragment(),
					// Filter out the default VP type from types list
					vp.types.stream().filter(t -> !t.equals(DIDConstants.DEFAULT_VP_TYPE)).collect(Collectors.toList()),
					vp.holder,
					// Map VerifiableCredentials to Credentials
					vp.credentials.stream().map(VerifiableCredential::toCredential).collect(Collectors.toList()),
					vp.proof.getCreated(), vp.proof.getProofValue());

			this.vp = vp;
		}

		/**
		 * Constructs a sat-stamped, unsigned VouchView view of a VerifiablePresentation.
		 * <p>
		 * Used at sign time by {@link VerifiablePresentationBuilder} to compute the bytes the
		 * signature will cover. {@code signedAt} must be set so that it is part of the signed
		 * data; the resulting proof's {@code created} value should be the same timestamp.
		 *
		 * @param vp the VerifiablePresentation instance (proof may be null)
		 * @param signedAt the signing timestamp to embed in the signed bytes
		 */
		protected VouchView(VerifiablePresentation vp, Date signedAt) {
			super(vp.id == null ? null : DIDURL.create(vp.id).getFragment(),
					vp.types.stream().filter(t -> !t.equals(DIDConstants.DEFAULT_VP_TYPE)).collect(Collectors.toList()),
					vp.holder,
					vp.credentials.stream().map(VerifiableCredential::toCredential).collect(Collectors.toList()),
					signedAt);

			this.vp = vp;
		}

		/**
		 * Constructs a VouchView from an existing Vouch and associated VerifiablePresentation.
		 *
		 * @param vouch the existing Vouch instance
		 * @param vp the associated VerifiablePresentation
		 */
		protected VouchView(Vouch vouch, VerifiablePresentation vp) {
			super(vouch, vouch.getSignature());
			this.vp = vp;
		}

		/**
		 * Returns the byte array data over which the proof signature is computed.
		 *
		 * @return byte array of sign data
		 */
		protected byte[] getSignData() {
			return super.getSignData();
		}

		/**
		 * Returns the wrapped VerifiablePresentation instance.
		 *
		 * @return VerifiablePresentation
		 */
		public VerifiablePresentation getVerifiablePresentation() {
			return vp;
		}
	}
}