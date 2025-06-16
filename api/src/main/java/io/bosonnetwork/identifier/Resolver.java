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

import java.util.Date;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import io.bosonnetwork.Id;

public interface Resolver {
	enum ResolutionStatus {
		SUCCESS(0),
		INVALID(-1),
		NOT_FOUND(-2),
		REPRESENTATION_NOT_SUPPORTED(-3),
		UNSUPPORTED_METHOD(-4);

		private final int code;

		ResolutionStatus(int code) {
			this.code = code;
		}

		@JsonValue
		public int getCode() {
			return code;
		}

		@JsonCreator
		public static ResolutionStatus from(int code) {
			return switch (code) {
				case 0 -> SUCCESS;
				case -1 -> INVALID;
				case -2 -> NOT_FOUND;
				case -3 -> REPRESENTATION_NOT_SUPPORTED;
				case -4 -> UNSUPPORTED_METHOD;
				default -> throw new IllegalArgumentException("Invalid resolution status: " + code);
			};
		}
	}

	class ResolutionOptions {
		private static final ResolutionOptions DEFAULT = new ResolutionOptions(true, 0); // no cache

		private final boolean usingCache;
		private final long validTTL;

		public ResolutionOptions(boolean usingCache, long validTTL) {
			this.usingCache = usingCache;
			this.validTTL = validTTL;
		}

		public boolean usingCache() {
			return usingCache;
		}

		public long validTTL() {
			return validTTL;
		}

		public static ResolutionOptions defaultOptions() {
			return DEFAULT;
		}
	}

	class ResolutionResultMetadata {
		@JsonProperty("created")
		private final Date created;
		@JsonProperty("updated")
		private final Date updated;
		@JsonProperty("resolved")
		private final Date resolved;
		@JsonProperty("deactivated")
		private final boolean deactivated;
		@JsonProperty("version")
		private final int version;

		public ResolutionResultMetadata(Date created, Date updated, Date resolved, boolean deactivated, int version) {
			this.created = created;
			this.updated = updated;
			this.resolved = resolved;
			this.deactivated = deactivated;
			this.version = version;
		}

		public Date getCreated() {
			return created;
		}

		public Date getUpdated() {
			return updated;
		}

		public Date getResolved() {
			return resolved;
		}

		public boolean isDeactivated() {
			return deactivated;
		}

		public int getVersion() {
			return version;
		}
	}

	class ResolutionResult<T> {
		@SuppressWarnings("rawtypes")
		private static final ResolutionResult NOT_FOUND = new ResolutionResult<>(ResolutionStatus.NOT_FOUND, null, null);
		@SuppressWarnings("rawtypes")
		private static final ResolutionResult INVALID = new ResolutionResult<>(ResolutionStatus.INVALID, null, null);

		@JsonProperty("status")
		private final ResolutionStatus status;
		@JsonProperty("result")
		private final T result;
		@JsonProperty("resultMetadata")
		private final ResolutionResultMetadata metadata;

		public ResolutionResult(ResolutionStatus status, T result, ResolutionResultMetadata metadata) {
			this.status = status;
			this.result = result;
			this.metadata = metadata;
		}

		public ResolutionResult(T result, ResolutionResultMetadata metadata) {
			this(ResolutionStatus.SUCCESS, result, metadata);
		}

		public ResolutionStatus getResolutionStatus() {
			return status;
		}

		public T getResult() {
			return result;
		}

		public ResolutionResultMetadata getResultMetadata() {
			return metadata;
		}

		public boolean succeeded() {
			return status == ResolutionStatus.SUCCESS;
		}

		public boolean failed() {
			return status != ResolutionStatus.SUCCESS;
		}

		@SuppressWarnings("unchecked")
		public static <T> ResolutionResult<T> notfound() {
			return (ResolutionResult<T>) NOT_FOUND;
		}

		@SuppressWarnings("unchecked")
		public static <T> ResolutionResult<T> invalid() {
			return (ResolutionResult<T>) INVALID;
		}
	}

	CompletableFuture<ResolutionResult<Card>> resolve(Id id, ResolutionOptions options);

	default CompletableFuture<ResolutionResult<Card>> resolve(Id id) {
		return resolve(id, ResolutionOptions.defaultOptions());
	}

	default CompletableFuture<ResolutionResult<DIDDocument>> resolveDID(Id id, ResolutionOptions options) {
		Objects.requireNonNull(id, "id");

		return resolve(id, options).thenApply(rr -> rr.succeeded() ?
			new ResolutionResult<>(rr.getResolutionStatus(), DIDDocument.fromCard(rr.getResult()), rr.getResultMetadata()) :
			new ResolutionResult<>(rr.getResolutionStatus(), null, rr.getResultMetadata()));
	}

	default CompletableFuture<ResolutionResult<DIDDocument>> resolveDID(Id id) {
		return resolveDID(id, ResolutionOptions.defaultOptions());
	}
}