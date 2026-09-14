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

package io.bosonnetwork.kademlia.shell;

import java.util.concurrent.Callable;

import io.bosonnetwork.crypto.CryptoBox;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.utils.Base58;
import io.bosonnetwork.utils.Hex;

import picocli.CommandLine.Command;

/**
 * @hidden
 */
@Command(name = "keygen", mixinStandardHelpOptions = true, version = "Boson key generator 2.0",
description = "Create keypair.")
public class GenerateKeyPairCommand implements Callable<Integer> {

	@Override
	public Integer call() throws Exception {
		Signature.KeyPair sigKey = Signature.KeyPair.random();
		CryptoBox.KeyPair encKey = CryptoBox.KeyPair.fromSignatureKeyPair(sigKey);

		System.out.println("Signature:");
		System.out.println("  Private(Hex): " + Hex.encode(sigKey.privateKey().bytes()));
		System.out.println("   Public(Hex): " + Hex.encode(sigKey.publicKey().bytes()));
		System.out.println("   Public(B58): " + Base58.encode(sigKey.publicKey().bytes()));

		System.out.println("Encryption:");
		System.out.println("  Private(Hex): " + Hex.encode(encKey.privateKey().bytes()));
		System.out.println("   Public(Hex): " + Hex.encode(encKey.publicKey().bytes()));
		System.out.println("   Public(B58): " + Base58.encode(encKey.publicKey().bytes()));

		return 0;
	}

}