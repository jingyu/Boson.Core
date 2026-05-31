/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
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
 * Cryptographic primitives for Boson — Ed25519 signatures and Curve25519 key exchange / encryption,
 * wrapping <a href="https://www.libsodium.org/">libsodium</a>.
 *
 * <ul>
 *   <li>{@link io.bosonnetwork.crypto.Signature} — Ed25519 key pairs, signing and verification;</li>
 *   <li>{@link io.bosonnetwork.crypto.CryptoBox} — Curve25519 authenticated encryption
 *       ({@code crypto_box}), with nonce handling;</li>
 *   <li>{@link io.bosonnetwork.crypto.CryptoIdentity} and
 *       {@link io.bosonnetwork.crypto.CachedCryptoIdentity} — an {@link io.bosonnetwork.Identity}
 *       backed by an Ed25519 key pair, with a derived encryption context;</li>
 *   <li>{@link io.bosonnetwork.crypto.Hash} — hashing helpers (SHA-256/512; MD5 is provided only as
 *       a non-cryptographic legacy checksum);</li>
 *   <li>{@link io.bosonnetwork.crypto.PasswordHash} — password hashing / key derivation;</li>
 *   <li>{@link io.bosonnetwork.crypto.Random} — a cryptographically secure random source.</li>
 * </ul>
 *
 * <p>Boson uses libsodium-style 64-byte private keys (32-byte seed concatenated with the 32-byte
 * public key). Holders of secret key material expose explicit {@code destroy()}/{@code close()}
 * methods; failures are reported as {@link io.bosonnetwork.crypto.CryptoException}.
 */
package io.bosonnetwork.crypto;