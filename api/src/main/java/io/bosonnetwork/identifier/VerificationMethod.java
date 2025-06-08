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

@JsonDeserialize(using = VerificationMethod.Deserializer.class)
public abstract class VerificationMethod {
	public enum Type {
		Ed25519VerificationKey2020
	}

	protected static VerificationMethod of(String id, Type type, Id controller, String publicKeyMultibase) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(controller, "controller");
		Objects.requireNonNull(publicKeyMultibase, "publicKeyMultibase");
		return new Entity(id, type, controller, publicKeyMultibase);
	}

	protected static VerificationMethod of(String id) {
		Objects.requireNonNull(id, "id");
		return new Reference(id);
	}

	private static String defaultMethodId(Id id) {
		return new DIDURL(id, null, null, DIDConstants.DEFAULT_VERIFICATION_METHOD_FRAGMENT).toString();
	}

	protected static VerificationMethod defaultOf(Id id) {
		Objects.requireNonNull(id, "id");
		return new Entity(defaultMethodId(id), Type.Ed25519VerificationKey2020, id, id.toBase58String());
	}

	protected static VerificationMethod defaultReferenceOf(Id id) {
		Objects.requireNonNull(id, "id");
		return new Reference(defaultMethodId(id));
	}

	public abstract String getId();

	public Type getType() {
		return null;
	}

	public Id getController() {
		return null;
	}

	public String getPublicKeyMultibase() {
		return null;
	}

	public abstract boolean isReference();

	public VerificationMethod getReference() {
		return isReference() ? this : new Reference(this);
	}

	@Override
	public String toString() {
		StringBuilder repr = new StringBuilder(256);
		repr.append("VerificationMethod{")
				.append("id='").append(getId()).append('\'');

		if (!isReference())
			repr.append(", type=").append(getType())
					.append(", controller=").append(getController().toDIDString())
					.append(", publicKeyMultibase='").append(getPublicKeyMultibase()).append('\'');

		repr.append('}');
		return repr.toString();
	}

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

		@JsonCreator
		protected Entity(@JsonProperty(value = "id", required = true) String id,
						 @JsonProperty(value = "type") Type type,
						 @JsonProperty(value = "controller") Id controller,
						 @JsonProperty(value = "publicKeyMultibase") String publicKeyMultibase) {
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

	static class Reference extends VerificationMethod {
		private final String id;
		private VerificationMethod entity;

		@JsonCreator
		protected Reference(String id) {
			Objects.requireNonNull(id, "id");
			this.id = id;
		}

		protected Reference(VerificationMethod entity) {
			Objects.requireNonNull(entity, "entity");
			this.id = entity.getId();
			this.entity = entity;
		}

		protected void updateReference(VerificationMethod entity) {
			Objects.requireNonNull(entity, "entity");

			if (entity.isReference())
				throw new IllegalArgumentException("entity must not be a reference");

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
			return entity == null ? null : entity.getType();
		}

		@Override
		public Id getController() {
			return entity == null ? null : entity.getController();
		}

		@Override
		public String getPublicKeyMultibase() {
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
				return new Reference(p.getText());
			else if (p.currentToken() == JsonToken.START_OBJECT)
				return p.readValueAs(Entity.class);
			else
				throw MismatchedInputException.from(p, VerificationMethod.class, "Invalid VerificationMethod: should be a string or an object");
		}
	}
}