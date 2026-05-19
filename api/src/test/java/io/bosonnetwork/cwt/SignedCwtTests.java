package io.bosonnetwork.cwt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
	public void testCreateAndParseBase64() throws Exception {
		CryptoIdentity identity = new CryptoIdentity();

		System.out.println(Hex.encode(identity.getKeyPair().publicKey().bytes()));

		String token = SignedCwt.builder(identity)
				.subject(Id.random())
				.audience(Id.random())
				.expiration(Duration.ofDays(7))
				.scope("federation")
				.buildBase64();

		System.out.println(token);

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

	@Test
	public void testParserClaims() throws Exception {
		CryptoIdentity identity = new CryptoIdentity();
		Id subject = Id.random();
		Id audience = Id.random();

		byte[] token = SignedCwt.builder(identity)
				.subject(subject)
				.audience(audience)
				.issuedAt(new java.util.Date(System.currentTimeMillis() + 100000)) // issued in future
				.build();

		// test requireIssuer
		assertThrows(InvalidClaimException.class, () -> SignedCwt.parser().requireIssuer(Id.random()).parse(token));
		
		// test requireSubject
		assertThrows(InvalidClaimException.class, () -> SignedCwt.parser().requireSubject(Id.random()).parse(token));

		// test requireAudience
		assertThrows(InvalidClaimException.class, () -> SignedCwt.parser().requireAudience(Id.random()).parse(token));

		// test ignoreIssuedAt
		assertThrows(InvalidIssuedAtException.class, () -> SignedCwt.parser().parse(token));
		assertNotNull(SignedCwt.parser().ignoreIssuedAt().parse(token));

		// test success
		SignedCwt parsed = SignedCwt.parser()
				.requireIssuer(identity.getId())
				.requireSubject(subject)
				.requireAudience(audience)
				.ignoreIssuedAt()
				.parse(token);
		
		assertNotNull(parsed);
	}

	@Test
	public void testIsExpired() throws Exception {
		CryptoIdentity identity = new CryptoIdentity();

		// Token with no time claims
		byte[] token1 = SignedCwt.builder(identity).build();
		SignedCwt cwt1 = SignedCwt.parse(token1);
		assertFalse(cwt1.isExpired());

		// Token with only an expired exp claim
		byte[] token2 = SignedCwt.builder(identity)
				.expiration(Duration.ofMinutes(-1))
				.build();
		SignedCwt cwt2 = SignedCwt.parser().ignoreExpiration().parse(token2);
		assertTrue(cwt2.isExpired());

		// Token with only a future nbf claim
		byte[] token3 = SignedCwt.builder(identity)
				.notBefore(Duration.ofMinutes(5))
				.build();
		SignedCwt cwt3 = SignedCwt.parser().ignoreNotBefore().parse(token3);
		assertTrue(cwt3.isExpired());

		// Token with only a future iat claim
		byte[] token4 = SignedCwt.builder(identity)
				.issuedAt(new java.util.Date(System.currentTimeMillis() + 100000))
				.build();
		SignedCwt cwt4 = SignedCwt.parser().ignoreIssuedAt().parse(token4);
		assertTrue(cwt4.isExpired());
	}

	@Test
	public void testIsExpiredWithLeeway() throws Exception {
		CryptoIdentity identity = new CryptoIdentity();

		// Expired by 5 seconds
		byte[] token = SignedCwt.builder(identity)
				.expiration(Duration.ofSeconds(-5))
				.build();
		
		SignedCwt cwt = SignedCwt.parser().ignoreExpiration().parse(token);
		
		assertTrue(cwt.isExpired(0)); // strictly expired
		assertFalse(cwt.isExpired(10)); // valid with 10s leeway

		assertThrows(IllegalArgumentException.class, () -> cwt.isExpired(-1));
	}

	@Test
	public void testParserStringClaims() throws Exception {
		CryptoIdentity identity = new CryptoIdentity();
		String subjectStr = "user123";
		String audienceStr = "serviceABC";

		byte[] token = SignedCwt.builder(identity)
				.subject(subjectStr)
				.audience(audienceStr)
				.build();

		// Valid matching
		assertNotNull(SignedCwt.parser().requireSubject(subjectStr).requireAudience(audienceStr).parse(token));

		// Invalid matching
		assertThrows(InvalidClaimException.class, () -> SignedCwt.parser().requireSubject("wrongUser").parse(token));
		assertThrows(InvalidClaimException.class, () -> SignedCwt.parser().requireAudience("wrongAudience").parse(token));
		
		// Passing Id to check string mismatch logic
		assertThrows(InvalidClaimException.class, () -> SignedCwt.parser().requireSubject(Id.random()).parse(token));
	}

	@Test
	public void testParserLeeway() throws Exception {
		CryptoIdentity identity = new CryptoIdentity();

		// Expired by 5 seconds
		byte[] token = SignedCwt.builder(identity)
				.expiration(Duration.ofSeconds(-5))
				.build();

		// Fails with 0 leeway
		assertThrows(TokenExpiredException.class, () -> SignedCwt.parser().parse(token));
		
		// Succeeds with 10 leeway
		assertNotNull(SignedCwt.parser().setLeeway(10).parse(token));
		
		// Invalid config
		assertThrows(IllegalArgumentException.class, () -> SignedCwt.parser().setLeeway(-1));
	}

	@Test
	public void testMissingRequiredClaims() throws Exception {
		CryptoIdentity identity = new CryptoIdentity();

		// Minimal token
		byte[] token = SignedCwt.builder(identity).build();

		// Requires missing subject
		assertThrows(InvalidClaimException.class, () -> SignedCwt.parser().requireSubject(Id.random()).parse(token));
		// Requires missing audience
		assertThrows(InvalidClaimException.class, () -> SignedCwt.parser().requireAudience(Id.random()).parse(token));
	}

	@Test
	public void testAllIgnoreFlags() throws Exception {
		CryptoIdentity identity = new CryptoIdentity();

		byte[] token = SignedCwt.builder(identity)
				.expiration(Duration.ofSeconds(-5)) // expired
				.notBefore(Duration.ofMinutes(5))   // future
				.issuedAt(new java.util.Date(System.currentTimeMillis() + 100000)) // future
				.build();

		// Fails normal parsing
		assertThrows(TokenExpiredException.class, () -> SignedCwt.parser().parse(token));

		// Succeeds with ignore flags
		assertNotNull(SignedCwt.parser()
				.ignoreExpiration()
				.ignoreNotBefore()
				.ignoreIssuedAt()
				.parse(token));
	}

	@Test
	public void testInvalidCoseStructure() {
		// Empty array
		assertThrows(NullPointerException.class, () -> SignedCwt.parse((byte[])null));

		// Random garbage
		byte[] garbage = new byte[]{0x01, 0x02, 0x03};
		assertThrows(InvalidCborTagException.class, () -> SignedCwt.parse(garbage));

		// Invalid tag but right structure
		byte[] wrongTag = new byte[]{(byte) 0xd9, 0x01, 0x01}; // CBOR tag 257 instead of 18
		assertThrows(InvalidCborTagException.class, () -> SignedCwt.parse(wrongTag));
	}
}