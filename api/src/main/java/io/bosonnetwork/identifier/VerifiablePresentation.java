package io.bosonnetwork.identifier;

import java.util.ArrayList;
import java.util.Collections;
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

@JsonPropertyOrder({ "@context", "id", "type", "holder", "verifiableCredential", "proof" })
public class VerifiablePresentation extends W3CDIDFormat {
	@JsonProperty("@context")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<String> contexts;
	@JsonProperty("id")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final String id;
	@JsonProperty("type")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<String> types;
	@JsonProperty("holder")
	private final Id holder;
	@JsonProperty("verifiableCredential")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<VerifiableCredential> credentials;
	@JsonProperty("proof")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final Proof proof;

	private transient BosonVouch bosonVouch;

	// internal constructor used by JSON deserializer
	@JsonCreator
	protected VerifiablePresentation(@JsonProperty(value = "@context") List<String> contexts,
					@JsonProperty(value = "id") String id,
					@JsonProperty(value = "type") List<String> types,
					@JsonProperty(value = "holder", required = true) Id holder,
					@JsonProperty("verifiableCredential") List<VerifiableCredential> credentials,
					@JsonProperty(value = "proof", required = true) Proof proof) {
		Objects.requireNonNull(holder, "holder");
		Objects.requireNonNull(proof, "proof");

		this.contexts = contexts == null || contexts.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(contexts);
		this.id = id;
		this.types = types == null || types.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(types);
		this.holder = holder;
		this.credentials = credentials == null || credentials.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(credentials);
		this.proof = proof;
	}

	// internal constructor used by VerifiablePresentationBuilder
	// the caller should transfer ownership of the Collections to the new instance
	protected VerifiablePresentation(List<String> contexts, String id, List<String> types, Id holder, List<VerifiableCredential> credentials) {
		this.contexts = contexts == null || contexts.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(contexts);
		this.id = id;
		this.types = types == null || types.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(types);
		this.holder = holder;
		this.credentials = credentials == null || credentials.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(credentials);
		this.proof = null;
	}

	// internal constructor used by VerifiablePresentationBuilder
	protected VerifiablePresentation(VerifiablePresentation vp, Proof proof) {
		this.contexts = vp.contexts;
		this.id = vp.id;
		this.types = vp.types;
		this.holder = vp.holder;
		this.credentials = vp.credentials;
		this.proof = proof;
	}

	public List<String> getContexts() {
		return contexts;
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

	public List<VerifiableCredential> getCredentials() {
		return credentials;
	}

	public List<VerifiableCredential> getCredentials(String type) {
		Objects.requireNonNull(type, "type");
		return credentials.stream()
				.filter(vc -> vc.getTypes().contains(type))
				.collect(Collectors.toList());
	}

	public VerifiableCredential getCredential(String id) {
		Objects.requireNonNull(id, "id");
		DIDURL idUrl = id.startsWith(DIDConstants.DID_SCHEME + ":") ?
				DIDURL.create(id) : new DIDURL(holder, null, null, id);

		return getCredential(idUrl);
	}

	public VerifiableCredential getCredential(DIDURL id) {
		Objects.requireNonNull(id, "id");

		String sid = id.toString();
		return credentials.stream()
				.filter(vc -> vc.getId().equals(sid))
				.findFirst()
				.orElse(null);
	}

	public Proof getProof() {
		return proof;
	}

	public void validate() throws InvalidSignatureException {
		if (!isGenuine())
			throw new InvalidSignatureException();
	}

	public boolean isGenuine() {
		if (proof == null)
			return false;

		return proof.verify(holder, getSignData());
	}

	public Vouch toVouch() {
		if (bosonVouch == null)
			bosonVouch = new BosonVouch(this);

		return bosonVouch;
	}

	public static VerifiablePresentation fromVouch(Vouch vouch, Map<String, List<String>> typeContexts) {
		Objects.requireNonNull(vouch, "vouch");
		if (vouch instanceof BosonVouch bv)
			return bv.getVerifiablePresentation();

		List<String> contexts = new ArrayList<>();
		List<String> types = new ArrayList<>();

		contexts.add(DIDConstants.W3C_VC_CONTEXT);
		contexts.add(DIDConstants.BOSON_VC_CONTEXT);
		contexts.add(DIDConstants.W3C_ED25519_CONTEXT);
		types.add(DIDConstants.DEFAULT_VP_TYPE);

		Map<String, List<String>> _typeContexts = typeContexts == null ? Map.of() : typeContexts;
		for (String type : vouch.getTypes()) {
			if (type.equals(DIDConstants.DEFAULT_VP_TYPE))
				continue;

			types.add(type);

			if (_typeContexts.containsKey(type)) {
				for (String context : typeContexts.get(type)) {
					if (!contexts.contains(context))
						contexts.add(context);
				}
			}
		}

		VerifiablePresentation vp = new VerifiablePresentation(contexts,
				vouch.getId() == null ? null :
						new DIDURL(vouch.getHolder(), null, null, vouch.getId()).toString(),
				types, vouch.getHolder(),
				vouch.getCredentials().stream().map(c -> VerifiableCredential.fromCredential(c, _typeContexts))
						.collect(Collectors.toList()),
				new Proof(Proof.Type.Ed25519Signature2020, vouch.getSignedAt(),
						VerificationMethod.defaultReferenceOf(vouch.getHolder()),
						Proof.Purpose.assertionMethod, vouch.getSignature()));

		vp.bosonVouch = new BosonVouch(vouch, vp);
		return vp;

	}

	protected byte[] getSignData() {
		BosonVouch unsigned = bosonVouch != null ? bosonVouch : new BosonVouch(this, true);
		return unsigned.getSignData();
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

	public static VerifiablePresentation parse(String json) {
		return parse(json, VerifiablePresentation.class);
	}

	public static VerifiablePresentation parse(byte[] cbor) {
		return parse(cbor, VerifiablePresentation.class);
	}

	public static VerifiablePresentationBuilder builder(Identity holder) {
		Objects.requireNonNull(holder, "holder");
		return new VerifiablePresentationBuilder(holder);
	}

	protected static class BosonVouch extends Vouch {
		private final VerifiablePresentation vp;

		protected BosonVouch(VerifiablePresentation vp) {
			super(vp.id == null ? null : DIDURL.create(vp.id).getFragment(),
					vp.types.stream().filter(t -> !t.equals(DIDConstants.DEFAULT_VP_TYPE)).collect(Collectors.toList()),
					vp.holder,
					vp.credentials.stream().map(VerifiableCredential::toCredential).collect(Collectors.toList()),
					vp.proof.getCreated(), vp.proof.getProofValue());

			this.vp = vp;
		}

		// unsigned is not used, just as the method signature for overriding
		protected BosonVouch(VerifiablePresentation vp, boolean unsigned) {
			super(vp.id == null ? null : DIDURL.create(vp.id).getFragment(),
					vp.types.stream().filter(t -> !t.equals(DIDConstants.DEFAULT_VP_TYPE)).collect(Collectors.toList()),
					vp.holder,
					vp.credentials.stream().map(VerifiableCredential::toCredential).collect(Collectors.toList()));

			this.vp = vp;
		}

		protected BosonVouch(Vouch vouch, VerifiablePresentation vp) {
			super(vouch, vouch.getSignedAt(), vouch.getSignature());
			this.vp = vp;
		}

		protected byte[] getSignData() {
			return super.getSignData();
		}

		public VerifiablePresentation getVerifiablePresentation() {
			return vp;
		}
	}
}