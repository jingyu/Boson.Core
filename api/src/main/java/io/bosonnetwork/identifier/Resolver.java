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

/**
 * <p>
 * Interface for asynchronously resolving Boson IDs to {@link Card} or {@link DIDDocument} representations.
 * </p>
 * <p>
 * Implementations of this interface are responsible for retrieving and validating Cards associated with a given
 * Boson {@link Id}, optionally using caching and TTL controls, and for mapping resolved Cards to DID Documents.
 * </p>
 */
public interface Resolver {
	/**
	 * Status codes for the result of a resolution attempt.
	 * <ul>
	 *     <li>{@link #SUCCESS}: The resolution succeeded and a valid result was returned.</li>
	 *     <li>{@link #INVALID}: The input was invalid or the resolved data was malformed.</li>
	 *     <li>{@link #NOT_FOUND}: No result could be found for the requested ID.</li>
	 *     <li>{@link #REPRESENTATION_NOT_SUPPORTED}: The requested representation is not supported.</li>
	 *     <li>{@link #UNSUPPORTED_METHOD}: The resolution method is not supported.</li>
	 * </ul>
	 */
	enum ResolutionStatus {
		/**
		 * Resolution succeeded.
		 */
		SUCCESS(0),
		/**
		 * The resolved data was invalid.
		 */
		INVALID(-1),
		/**
		 * No result was found for the requested ID.
		 */
		NOT_FOUND(-2),
		/**
		 * The requested representation is not supported.
		 */
		REPRESENTATION_NOT_SUPPORTED(-3),
		/**
		 * The resolution method is not supported.
		 */
		UNSUPPORTED_METHOD(-4);

		private final int code;

		ResolutionStatus(int code) {
			this.code = code;
		}

		/**
		 * Returns the integer code for this status.
		 * @return integer code corresponding to the status
		 */
		@JsonValue
		public int getCode() {
			return code;
		}

		/**
		 * Returns the {@link ResolutionStatus} corresponding to the given code.
		 * @param code the integer code
		 * @return the matching {@link ResolutionStatus}
		 * @throws IllegalArgumentException if the code does not correspond to a valid status
		 */
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

	/**
	 * Options controlling the resolution process, such as caching and time-to-live (TTL).
	 * <p>
	 * Allows callers to specify whether to use cached results and how long a result is considered valid.
	 * </p>
	 */
	class ResolutionOptions {
		// Default options: use cache, no TTL (0 disables TTL).
		private static final ResolutionOptions DEFAULT = new ResolutionOptions(true, 0); // no cache

		private final boolean usingCache;
		private final long validTTL;

		/**
		 * @param usingCache whether to use cached results if available
		 * @param validTTL time-to-live for the cached result in milliseconds (0 disables TTL)
		 */
		public ResolutionOptions(boolean usingCache, long validTTL) {
			this.usingCache = usingCache;
			this.validTTL = validTTL;
		}

		/**
		 * Whether to use cached results if available.
		 * @return true if cache should be used, false otherwise
		 */
		public boolean usingCache() {
			return usingCache;
		}

		/**
		 * Gets the time-to-live (TTL) for cached results, in milliseconds.
		 * A value of 0 disables TTL.
		 * @return TTL in milliseconds
		 */
		public long validTTL() {
			return validTTL;
		}

		/**
		 * Returns the default resolution options (use cache, no TTL).
		 * @return default options
		 */
		public static ResolutionOptions defaultOptions() {
			return DEFAULT;
		}
	}

	/**
	 * Metadata describing the resolved result, such as creation date, update date, resolution time,
	 * deactivation status, and version.
	 */
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

		/**
		 * @param created date the resource was created
		 * @param updated date the resource was last updated
		 * @param resolved date/time when this result was resolved
		 * @param deactivated whether the resource is deactivated
		 * @param version version number of the resource
		 */
		public ResolutionResultMetadata(Date created, Date updated, Date resolved, boolean deactivated, int version) {
			this.created = created;
			this.updated = updated;
			this.resolved = resolved;
			this.deactivated = deactivated;
			this.version = version;
		}

		/**
		 * @return the creation date of the resource
		 */
		public Date getCreated() {
			return created;
		}

		/**
		 * @return the last update date of the resource
		 */
		public Date getUpdated() {
			return updated;
		}

		/**
		 * @return the date/time when this result was resolved
		 */
		public Date getResolved() {
			return resolved;
		}

		/**
		 * @return true if the resource is deactivated, false otherwise
		 */
		public boolean isDeactivated() {
			return deactivated;
		}

		/**
		 * @return the version number of the resource
		 */
		public int getVersion() {
			return version;
		}
	}

	/**
	 * Result of a resolution attempt, including status, the resolved object (if any), and associated metadata.
	 *
	 * @param <T> the type of the resolved object (e.g. {@link Card} or {@link DIDDocument})
	 */
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

		/**
		 * Constructs a resolution result with the given status, result, and metadata.
		 * @param status the resolution status
		 * @param result the resolved object, or null if not found or invalid
		 * @param metadata metadata about the resolved object
		 */
		public ResolutionResult(ResolutionStatus status, T result, ResolutionResultMetadata metadata) {
			this.status = status;
			this.result = result;
			this.metadata = metadata;
		}

		/**
		 * Constructs a successful resolution result.
		 * @param result the resolved object
		 * @param metadata metadata about the resolved object
		 */
		public ResolutionResult(T result, ResolutionResultMetadata metadata) {
			this(ResolutionStatus.SUCCESS, result, metadata);
		}

		/**
		 * @return the status of the resolution (e.g. success, not found, invalid)
		 */
		public ResolutionStatus getResolutionStatus() {
			return status;
		}

		/**
		 * @return the resolved object, or null if not found or invalid
		 */
		public T getResult() {
			return result;
		}

		/**
		 * @return metadata about the resolved object
		 */
		public ResolutionResultMetadata getResultMetadata() {
			return metadata;
		}

		/**
		 * @return true if the resolution was successful, false otherwise
		 */
		public boolean succeeded() {
			return status == ResolutionStatus.SUCCESS;
		}

		/**
		 * @return true if the resolution failed (not successful), false otherwise
		 */
		public boolean failed() {
			return status != ResolutionStatus.SUCCESS;
		}

		/**
		 * Returns a static resolution result representing "not found".
		 * @param <T> type parameter
		 * @return a not found result
		 */
		@SuppressWarnings("unchecked")
		public static <T> ResolutionResult<T> notfound() {
			return (ResolutionResult<T>) NOT_FOUND;
		}

		/**
		 * Returns a static resolution result representing "invalid".
		 * @param <T> type parameter
		 * @return an invalid result
		 */
		@SuppressWarnings("unchecked")
		public static <T> ResolutionResult<T> invalid() {
			return (ResolutionResult<T>) INVALID;
		}
	}

	/**
	 * Resolves a Boson {@link Id} to a {@link Card} asynchronously, using the provided resolution options.
	 *
	 * @param id the Boson ID to resolve
	 * @param options options controlling caching and TTL
	 * @return a future containing the resolution result (status, card, and metadata)
	 */
	CompletableFuture<ResolutionResult<Card>> resolve(Id id, ResolutionOptions options);

	/**
	 * Resolves a Boson {@link Id} to a {@link Card} asynchronously, using default resolution options.
	 *
	 * @param id the Boson ID to resolve
	 * @return a future containing the resolution result (status, card, and metadata)
	 */
	default CompletableFuture<ResolutionResult<Card>> resolve(Id id) {
		// Use default options (cache enabled, no TTL) if not specified.
		return resolve(id, ResolutionOptions.defaultOptions());
	}

	/**
	 * Resolves a Boson {@link Id} to a {@link DIDDocument} asynchronously, using the provided resolution options.
	 * <p>
	 * This method first resolves the Card, and if successful, maps it to a DIDDocument.
	 * </p>
	 *
	 * @param id the Boson ID to resolve
	 * @param options options controlling caching and TTL
	 * @return a future containing the resolution result (status, DID document, and metadata)
	 */
	default CompletableFuture<ResolutionResult<DIDDocument>> resolveDID(Id id, ResolutionOptions options) {
		Objects.requireNonNull(id, "id");

		// First resolve the Card, then map to a DIDDocument if successful
		return resolve(id, options).thenApply(rr -> rr.succeeded() ?
			// Map Card to DIDDocument and preserve status/metadata
			new ResolutionResult<>(rr.getResolutionStatus(), DIDDocument.fromCard(rr.getResult()), rr.getResultMetadata()) :
			new ResolutionResult<>(rr.getResolutionStatus(), null, rr.getResultMetadata()));
	}

	/**
	 * Resolves a Boson {@link Id} to a {@link DIDDocument} asynchronously, using default resolution options.
	 * <p>
	 * This method first resolves the Card, and if successful, maps it to a DIDDocument.
	 * </p>
	 *
	 * @param id the Boson ID to resolve
	 * @return a future containing the resolution result (status, DID document, and metadata)
	 */
	default CompletableFuture<ResolutionResult<DIDDocument>> resolveDID(Id id) {
		// Use default options (cache enabled, no TTL) if not specified.
		return resolveDID(id, ResolutionOptions.defaultOptions());
	}
}