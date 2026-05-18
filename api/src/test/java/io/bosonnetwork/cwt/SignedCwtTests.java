package io.bosonnetwork.cwt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.utils.Hex;

public class SignedCwtTests {
	@Test
	public void testCreateAndParse() throws Exception {
		CryptoIdentity identity = new CryptoIdentity();

		System.out.println(Hex.encode(identity.getKeyPair().publicKey().bytes()));

		byte[] token = SignedCwt.builder(identity)
				.subject(Id.random())
				.audience(Id.random())
				.expiration(Duration.ofDays(7))
				.scope("federation")
				.build();

		System.out.println(Hex.encode(token));

		SignedCwt cwt = SignedCwt.parse(token);
		assertNotNull(cwt);
		System.out.println(cwt.toString());
	}

	@Test
	public void testSignedCwt() throws Exception {
		CryptoIdentity identity = new CryptoIdentity();
		Id subject = Id.random();
		Id audience = Id.random();

		Id scope = Id.random();

		System.out.println(Hex.encode(identity.getKeyPair().publicKey().bytes()));

		byte[] token = SignedCwt.builder(identity)
				.subject(subject)
				.audience(audience)
				.notBeforeNow()
				.expiration(Duration.ofDays(7))
				.tokenId("test#01")
				.scope(scope.bytes())
				.build();

		System.out.println(Hex.encode(token));

		SignedCwt cwt = SignedCwt.parse(token);
		assertNotNull(cwt);
		System.out.println(cwt.toString());

		assertArrayEquals(subject.bytes(), cwt.getClaim(Claim.SUBJECT.getValue()));
		assertArrayEquals(audience.bytes(), cwt.getClaim(Claim.AUDIENCE.getValue()));
		assertArrayEquals(scope.bytes(), cwt.getClaim(Claim.SCOPE.getValue()));
		int notBefore = cwt.getClaim(Claim.NOT_BEFORE.getValue());
		assertTrue(notBefore <= (System.currentTimeMillis() / 1000));
		int expiration = cwt.getClaim(Claim.EXPIRATION.getValue());
		assertTrue(expiration <= (System.currentTimeMillis() + Duration.ofDays(7).toMillis()) / 1000);
	}


	@Test
	public void testNotGenuine() throws Exception {
		CryptoIdentity identity = new CryptoIdentity();

		byte[] token = SignedCwt.builder(identity)
				.subject(Id.random())
				.audience(Id.random())
				.expiration(Duration.ofDays(7))
				.scope("federation")
				.build();

		token[token.length - 8] = (byte)(token[token.length - 8] + 1);

		assertThrows(InvalidSignatureException.class, () -> SignedCwt.parse(token));
	}

	@Test
	public void testExpired() throws Exception {
		CryptoIdentity identity = new CryptoIdentity();

		byte[] token = SignedCwt.builder(identity)
				.subject(Id.random())
				.audience(Id.random())
				.expiration(Duration.ofMinutes(-1))
				.build();

		assertThrows(TokenExpiredException.class, () -> SignedCwt.parse(token));
	}

	@Test
	public void testNotBefore() throws Exception {
		CryptoIdentity identity = new CryptoIdentity();

		byte[] token = SignedCwt.builder(identity)
				.subject(Id.random())
				.audience(Id.random())
				.notBefore(Duration.ofMinutes(5))
				.build();

		assertThrows(NotBeforeException.class, () -> SignedCwt.parse(token));
	}
}