package io.bosonnetwork.shell;

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
