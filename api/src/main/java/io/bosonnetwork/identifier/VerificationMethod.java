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
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import io.bosonnetwork.Id;

/**
 * Represents a W3C Decentralized Identifier (DID) Verification Method.
 *
 * <p>A Verification Method is used to verify proofs and assertions made by a DID subject.
 * It contains information about the cryptographic key type, the controller of the key,
 * and the public key material encoded in multibase format.</p>
 *
 * <p>This abstract class supports two main representations:</p>
 * <ul>
 *   <li><b>Entity</b>: a full verification method object with all fields.</li>
 *   <li><b>Reference</b>: a lightweight reference to an existing verification method by its ID.</li>
 * </ul>
 */
@JsonDeserialize(using = VerificationMethod.Deserializer.class)
public abstract class VerificationMethod {
	/**
	 * Enumeration of supported verification method types.
	 */
	public enum Type {
		/**
		 * Ed25519 Verification Key 2020 type.
		 */
		Ed25519VerificationKey2020
	}

	/**
	 * Creates a full verification method entity with all fields specified.
	 *
	 * @param id The unique identifier of the verification method.
	 * @param type The type of the verification method (e.g., Ed25519VerificationKey2020).
	 * @param controller The DID controller of this verification method.
	 * @param publicKeyMultibase The public key encoded in multibase format.
	 * @return A new {@link Entity} instance representing the full verification method.
	 * @throws NullPointerException if any argument is null.
	 */
	protected static VerificationMethod of(String id, Type type, Id controller, String publicKeyMultibase) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(controller, "controller");
		Objects.requireNonNull(publicKeyMultibase, "publicKeyMultibase");
		return new Entity(id, type, controller, publicKeyMultibase);
	}

	/**
	 * Creates a verification method reference by its ID.
	 *
	 * <p>This creates a lightweight reference to an existing verification method,
	 * without the full method details.</p>
	 *
	 * @param id The ID of the referenced verification method.
	 * @return A new {@link Reference} instance representing the verification method reference.
	 * @throws NullPointerException if {@code id} is null.
	 */
	protected static VerificationMethod of(String id) {
		Objects.requireNonNull(id, "id");
		return new Reference(id);
	}

	/**
	 * Constructs the default verification method ID for a given DID.
	 *
	 * @param id The DID identifier.
	 * @return The default verification method ID string.
	 */
	private static String defaultMethodId(Id id) {
		return new DIDURL(id, null, null, DIDConstants.DEFAULT_VERIFICATION_METHOD_FRAGMENT).toString();
	}

	/**
	 * Creates a default full verification method entity for the given DID.
	 *
	 * <p>This uses the default method ID, type {@link Type#Ed25519VerificationKey2020},
	 * the DID as controller, and the DID's Base58 string as public key.</p>
	 *
	 * @param id The DID identifier.
	 * @return A new {@link Entity} instance representing the default verification method.
	 * @throws NullPointerException if {@code id} is null.
	 */
	protected static VerificationMethod defaultOf(Id id) {
		Objects.requireNonNull(id, "id");
		return new Entity(defaultMethodId(id), Type.Ed25519VerificationKey2020, id, id.toBase58String());
	}

	/**
	 * Creates a default verification method reference for the given DID.
	 *
	 * <p>This references the default verification method ID for the DID.</p>
	 *
	 * @param id The DID identifier.
	 * @return A new {@link Reference} instance referencing the default verification method.
	 * @throws NullPointerException if {@code id} is null.
	 */
	protected static VerificationMethod defaultReferenceOf(Id id) {
		Objects.requireNonNull(id, "id");
		return new Reference(defaultMethodId(id));
	}

	/**
	 * Returns the unique identifier of this verification method.
	 *
	 * @return The verification method ID string.
	 */
	public abstract String getId();

	/**
	 * Returns the type of this verification method.
	 *
	 * @return The verification method {@link Type}, or null if this is a reference without loaded entity.
	 */
	public Type getType() {
		return null;
	}

	/**
	 * Returns the controller DID of this verification method.
	 *
	 * @return The controller {@link Id}, or null if this is a reference without loaded entity.
	 */
	public Id getController() {
		return null;
	}

	/**
	 * Returns the public key material encoded in multibase format.
	 *
	 * @return The public key multibase string, or null if this is a reference without loaded entity.
	 */
	public String getPublicKeyMultibase() {
		return null;
	}

	/**
	 * Indicates whether this verification method is a reference (i.e., only contains an ID)
	 * rather than a full entity with all fields.
	 *
	 * @return {@code true} if this is a reference; {@code false} if a full entity.
	 */
	public abstract boolean isReference();

	/**
	 * Returns a reference to this verification method.
	 *
	 * <p>If this instance is already a reference, returns itself.
	 * Otherwise, returns a new {@link Reference} wrapping this entity.</p>
	 *
	 * @return A {@link VerificationMethod} reference.
	 */
	public VerificationMethod getReference() {
		return isReference() ? this : new Reference(this);
	}

	@Override
	public String toString() {
		StringBuilder repr = new StringBuilder(256);
		repr.append("VerificationMethod{")
				.append("id='").append(getId()).append('\'');

		// If this is a full entity, append additional fields
		if (!isReference())
			repr.append(", type=").append(getType())
					.append(", controller=").append(getController().toDIDString())
					.append(", publicKeyMultibase='").append(getPublicKeyMultibase()).append('\'');

		repr.append('}');
		return repr.toString();
	}

	/**
	 * Represents a full verification method entity with all fields.
	 *
	 * <p>This class is used for JSON serialization and deserialization of full verification methods,
	 * including id, type, controller, and publicKeyMultibase.</p>
	 */
	@JsonPropertyOrder({"id", "type", "controller", "publicKeyMultibase"})
	@JsonDeserialize
	static class Entity extends VerificationMethod {
		@JsonProperty("id")
		private final String id;

		@JsonProperty("type")
		@JsonInclude(JsonInclude.Include.NON_NULL)
		private final Type type;

		@JsonProperty("controller")
		@JsonInclude(JsonInclude.Include.NON_NULL)
		private final Id controller;

		@JsonProperty("publicKeyMultibase")
		@JsonInclude(JsonInclude.Include.NON_NULL)
		private final String publicKeyMultibase;

		/**
		 * Constructs a full verification method entity.
		 *
		 * @param id The unique identifier of the verification method (required).
		 * @param type The type of the verification method.
		 * @param controller The DID controller of the verification method.
		 * @param publicKeyMultibase The public key encoded in multibase format.
		 * @throws NullPointerException if any required argument is null.
		 */
		@JsonCreator
		protected Entity(@JsonProperty(value = "id", required = true) String id,
						 @JsonProperty(value = "type") Type type,
						 @JsonProperty(value = "controller") Id controller,
						 @JsonProperty(value = "publicKeyMultibase") String publicKeyMultibase) {
			// Require all fields to be non-null for a full entity
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(type, "type");
			Objects.requireNonNull(controller, "controller");
			Objects.requireNonNull(publicKeyMultibase, "publicKeyMultibase");

			this.id = id;
			this.type = type;
			this.controller = controller;
			this.publicKeyMultibase = publicKeyMultibase;
		}

		@Override
		public String getId() {
			return id;
		}

		@Override
		public Type getType() {
			return type;
		}

		@Override
		public Id getController() {
			return controller;
		}

		@Override
		public String getPublicKeyMultibase() {
			return publicKeyMultibase;
		}

		@Override
		public boolean isReference() {
			return false;
		}

		@Override
		public int hashCode() {
			return Objects.hash(id, type, controller, publicKeyMultibase);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o)
				return true;

			if (o instanceof Entity that)
				return Objects.equals(id, that.id) &&
						Objects.equals(type, that.type) &&
						Objects.equals(controller, that.controller) &&
						Objects.equals(publicKeyMultibase, that.publicKeyMultibase);

			return false;
		}
	}

	/**
	 * Represents a lightweight reference to an existing verification method by its ID.
	 *
	 * <p>This class contains only the verification method ID and optionally a cached full entity.
	 * It is used to defer loading or embedding full verification method details.</p>
	 */
	static class Reference extends VerificationMethod {
		private final String id;
		private VerificationMethod entity;

		/**
		 * Constructs a reference to a verification method by its ID.
		 *
		 * @param id The verification method ID.
		 * @throws NullPointerException if {@code id} is null.
		 */
		@JsonCreator
		protected Reference(String id) {
			Objects.requireNonNull(id, "id");
			this.id = id;
		}

		/**
		 * Constructs a reference wrapping a full verification method entity.
		 *
		 * @param entity The full verification method entity.
		 * @throws NullPointerException if {@code entity} is null.
		 */
		protected Reference(VerificationMethod entity) {
			Objects.requireNonNull(entity, "entity");
			this.id = entity.getId();
			this.entity = entity;
		}

		/**
		 * Updates this reference to point to the given full verification method entity.
		 *
		 * <p>This method validates that the new entity is not itself a reference,
		 * and that its ID matches this reference's ID.</p>
		 *
		 * @param entity The full verification method entity to associate.
		 * @throws NullPointerException if {@code entity} is null.
		 * @throws IllegalArgumentException if {@code entity} is a reference or its ID does not match.
		 */
		protected void updateReference(VerificationMethod entity) {
			Objects.requireNonNull(entity, "entity");

			// Ensure the entity is a full entity, not a reference
			if (entity.isReference())
				throw new IllegalArgumentException("entity must not be a reference");

			// Ensure the entity ID matches this reference's ID
			if (!entity.getId().equals(id))
				throw new IllegalArgumentException("entity id does not match reference id");

			this.entity = entity;
		}

		@Override
		@JsonValue
		public String getId() {
			return id;
		}

		@Override
		public Type getType() {
			// Return type from cached entity if present, otherwise null
			return entity == null ? null : entity.getType();
		}

		@Override
		public Id getController() {
			// Return controller from cached entity if present, otherwise null
			return entity == null ? null : entity.getController();
		}

		@Override
		public String getPublicKeyMultibase() {
			// Return public key from cached entity if present, otherwise null
			return entity == null ? null : entity.getPublicKeyMultibase();
		}

		@Override
		public boolean isReference() {
			return true;
		}

		@Override
		public int hashCode() {
			return Objects.hash(id);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;

			if (o instanceof VerificationMethod.Reference that)
				return Objects.equals(id, that.id);

			return false;
		}
	}

	/**
	 * Custom Jackson deserializer for {@link VerificationMethod}.
	 *
	 * <p>Supports deserialization from either a JSON string or a JSON object:</p>
	 * <ul>
	 *   <li>If the JSON token is a string, it is deserialized as a {@link Reference} with that ID.</li>
	 *   <li>If the JSON token is an object, it is deserialized as a full {@link Entity}.</li>
	 *   <li>Otherwise, throws a {@link MismatchedInputException}.</li>
	 * </ul>
	 */
	static class Deserializer extends StdDeserializer<VerificationMethod> {
		private static final long serialVersionUID = 5290136429224550581L;

		public Deserializer() {
			super(VerificationMethod.class);
		}

		public Deserializer(Class<?> vc) {
			super(vc);
		}

		@Override
		public VerificationMethod deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JacksonException {
			if (p.currentToken() == JsonToken.VALUE_STRING)
				// Deserialize as a reference by ID
				return new Reference(p.getText());
			else if (p.currentToken() == JsonToken.START_OBJECT)
				// Deserialize as a full entity object
				return p.readValueAs(Entity.class);
			else
				// Invalid token for VerificationMethod
				throw MismatchedInputException.from(p, VerificationMethod.class, "Invalid VerificationMethod: should be a string or an object");
		}
	}
}