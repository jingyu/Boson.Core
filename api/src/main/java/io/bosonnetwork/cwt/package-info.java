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

/**
 * CBOR Web Token (CWT) support, used as the authentication token format across Boson's HTTP
 * services (super node ↔ client ↔ federation).
 *
 * <h2>{@link io.bosonnetwork.cwt.SignedCwt}</h2>
 * The entry point: a CWT structured as a COSE_Sign1 single-signer object. It is specialized for
 * Boson — it strictly enforces the EdDSA (Ed25519) signature algorithm and binds the {@code "iss"}
 * (issuer) claim to a Boson {@link io.bosonnetwork.Id} / {@link io.bosonnetwork.Identity}. Build
 * tokens with {@link io.bosonnetwork.cwt.SignedCwt#builder(io.bosonnetwork.Identity)} and verify
 * them with {@link io.bosonnetwork.cwt.SignedCwt#parser()}.
 * <p>
 * <strong>Security:</strong> a token is verified against the public key carried in its own
 * {@code "iss"} claim (self-asserted issuer), so a successful parse proves only that the holder of
 * that key signed it — callers must pin trust via
 * {@link io.bosonnetwork.cwt.SignedCwt.Parser#requireIssuer(io.bosonnetwork.Id)} or validate the
 * issuer against a trust anchor after parsing.
 *
 * <h2>Codec vocabulary</h2>
 * {@link io.bosonnetwork.cwt.Claim} (CWT claim keys), {@link io.bosonnetwork.cwt.Header} (COSE
 * header parameters) and {@link io.bosonnetwork.cwt.Algorithm} (COSE algorithm identifiers)
 * enumerate the integer keys used on the wire.
 *
 * <h2>Errors</h2>
 * All parsing/validation failures are reported as {@link io.bosonnetwork.cwt.CwtException} or one
 * of its typed subclasses (invalid CBOR tag, COSE structure, algorithm, signature, issuer key,
 * claim, expiration, not-before, issued-at).
 *
 * <h2>References</h2>
 * <ul>
 *   <li><a href="https://datatracker.ietf.org/doc/html/rfc8392">RFC 8392</a> — CBOR Web Token (CWT)</li>
 *   <li><a href="https://datatracker.ietf.org/doc/html/rfc8152">RFC 8152</a> / <a href="https://www.rfc-editor.org/rfc/rfc9052.html">RFC 9052</a> — COSE</li>
 * </ul>
 */
package io.bosonnetwork.cwt;
