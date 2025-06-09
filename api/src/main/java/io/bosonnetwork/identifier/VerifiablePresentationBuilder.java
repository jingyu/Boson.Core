package io.bosonnetwork.identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.bosonnetwork.BosonIdentityObjectBuilder;
import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;

public class VerifiablePresentationBuilder extends BosonIdentityObjectBuilder<VerifiablePresentation> {
	private final List<String> contexts;
	private String id;
	private final List<String> types;
	private final Map<String, VerifiableCredential> credentials;

	protected VerifiablePresentationBuilder(Identity holder) {
		super(holder);

		contexts = new ArrayList<>();
		types = new ArrayList<>();
		credentials = new LinkedHashMap<>();

		type(DIDConstants.DEFAULT_VP_TYPE,
				DIDConstants.W3C_VC_CONTEXT, DIDConstants.BOSON_VC_CONTEXT, DIDConstants.W3C_ED25519_CONTEXT);
	}

	public VerifiablePresentationBuilder id(String id) {
		Objects.requireNonNull(id, "id");
		if (id.isEmpty())
			throw new IllegalArgumentException("Id cannot be empty");

		DIDURL idUrl;
		if (id.startsWith(DIDConstants.DID_SCHEME + ":")) {
			idUrl = DIDURL.create(id);
			if (!idUrl.getId().equals(identity.getId()))
				throw new IllegalArgumentException("Id must be the holder id based DIDURL");
			if (idUrl.getFragment() == null)
				throw new IllegalArgumentException("Id must has the fragment part");
		} else {
			idUrl = new DIDURL(identity.getId(), null, null, id);
		}

		this.id = idUrl.toString();
		return this;
	}

	public VerifiablePresentationBuilder type(String type, String... contexts) {
		return type(type, List.of(contexts));
	}

	public VerifiablePresentationBuilder type(String type, List<String> contexts) {
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

	public VerifiablePresentationBuilder addCredential(VerifiableCredential vc) {
		Objects.requireNonNull(vc, "vc");
		credentials.put(vc.getId(), vc);
		return this;
	}

	public VerifiablePresentationBuilder addCredential(VerifiableCredential... vcs) {
		return addCredential(List.of(vcs));
	}

	public VerifiablePresentationBuilder addCredential(List<VerifiableCredential> vcs) {
		for (VerifiableCredential vc : vcs) {
			if (vc != null)
				addCredential(vc);
		}

		return this;
	}

	public VerifiablePresentationBuilder addCredential(String id, String type, List<String> contexts, Map<String, Object> claims) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(claims, "claims");

		VerifiableCredentialBuilder vcb = new VerifiableCredentialBuilder(identity);
		if (type != null)
			vcb.type(type, contexts == null ? List.of() : contexts);

		return addCredential(vcb.id(id)
				.claims(claims)
				.build());
	}

	public VerifiablePresentationBuilder addCredential(String id, String type, List<String> contexts, String claim1, Object value1) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(claim1, "claim1");

		VerifiableCredentialBuilder vcb = new VerifiableCredentialBuilder(identity);
		if (type != null)
			vcb.type(type, contexts == null ? List.of() : contexts);

		return addCredential(vcb.id(id)
				.claim(claim1, value1)
				.build());
	}

	public VerifiablePresentationBuilder addCredential(String id, String type, List<String> contexts, String claim1, Object value1,
										 String claim2, Object value2) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(claim1, "claim1");
		Objects.requireNonNull(claim2, "claim2");

		VerifiableCredentialBuilder vcb = new VerifiableCredentialBuilder(identity);
		if (type != null)
			vcb.type(type, contexts == null ? List.of() : contexts);

		return addCredential(vcb.id(id)
				.claim(claim1, value1)
				.claim(claim2, value2)
				.build());
	}

	public VerifiablePresentationBuilder addCredential(String id, String type, List<String> contexts, String claim1, Object value1,
										 String claim2, Object value2, String claim3, Object value3) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(claim1, "claim1");
		Objects.requireNonNull(claim2, "claim2");
		Objects.requireNonNull(claim3, "claim3");

		VerifiableCredentialBuilder vcb = new VerifiableCredentialBuilder(identity);
		if (type != null)
			vcb.type(type, contexts == null ? List.of() : contexts);

		return addCredential(vcb.id(id)
				.claim(claim1, value1)
				.claim(claim2, value2)
				.claim(claim3, value3)
				.build());
	}

	public VerifiableCredentialBuilder addCredential() {
		return new VerifiableCredentialBuilder(identity) {
			@Override
			public VerifiableCredentialBuilder subject(Id subject) {
				if (subject != null && !subject.equals(identity.getId()))
					throw new IllegalArgumentException("Credential subject does not match the holder");

				return super.subject(subject);
			}

			@Override
			public VerifiableCredential build() {
				VerifiableCredential vc = super.build();
				addCredential(vc);
				return vc;
			}
		};
	}

	@Override
	public VerifiablePresentation build() {
		List<VerifiableCredential> credentials = this.credentials.isEmpty() ? Collections.emptyList() : new ArrayList<>(this.credentials.values());
		VerifiablePresentation unsigned = new VerifiablePresentation(contexts, id, types, identity.getId(), credentials);
		byte[] signature = identity.sign(unsigned.getSignData());
		Proof proof = new Proof(Proof.Type.Ed25519Signature2020, now(),
				VerificationMethod.defaultReferenceOf(identity.getId()),
				Proof.Purpose.assertionMethod, signature);

		return new VerifiablePresentation(unsigned, proof);
	}
}