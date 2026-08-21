package org.compiere.ldap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.stream.Stream;

import org.compiere.model.MLdapUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
class LdapMessageTest
{
	private LdapMessage message;
	private LdapResult result;

	@BeforeEach
	void setUp()
	{
		message = new LdapMessage();
		result = new LdapResult();
	}

	@Test
	void decodesAnonymousBindRequest()
	{
		decode(LdapTestFixtures.ANONYMOUS_BIND_REQUEST);

		assertAll(
			() -> assertEquals(LdapMessage.BIND_REQUEST, message.getOperation()),
			() -> assertEquals(1, message.getMsgId()),
			() -> assertEquals("", message.getDN()),
			() -> assertEquals("", message.getUserPasswd()),
			() -> assertNull(message.getUserId()),
			() -> assertEquals(LdapResult.LDAP_SUCCESS, result.getErrorNo()));
	}

	@Test
	void rejectsUnsupportedAnonymousBindVersion()
	{
		decode(LdapTestFixtures.UNSUPPORTED_VERSION_BIND_REQUEST);

		assertAll(
			() -> assertEquals(LdapMessage.BIND_REQUEST, message.getOperation()),
			() -> assertEquals(1, message.getMsgId()),
			() -> assertEquals(LdapResult.LDAP_PROTOCOL_ERROR, result.getErrorNo()));
	}

	@Test
	void decodesAuthenticatedBindRequest()
	{
		decode(LdapTestFixtures.AUTHENTICATED_BIND_REQUEST);

		assertAll(
			() -> assertEquals(LdapMessage.BIND_REQUEST, message.getOperation()),
			() -> assertEquals(2, message.getMsgId()),
			() -> assertEquals(LdapTestFixtures.USER_DN, message.getDN()),
			() -> assertEquals(LdapTestFixtures.USER_ID, message.getUserId()),
			() -> assertEquals(LdapTestFixtures.PASSWORD, message.getUserPasswd()),
			() -> assertEquals(LdapTestFixtures.ORG, message.getOrg()),
			() -> assertEquals(LdapTestFixtures.ORG_UNIT, message.getOrgUnit()),
			() -> assertEquals(LdapResult.LDAP_SUCCESS, result.getErrorNo()));
	}

	@Test
	void rejectsUnsupportedAuthenticationMethod()
	{
		decode(LdapTestFixtures.UNSUPPORTED_AUTH_BIND_REQUEST);

		assertAll(
			() -> assertEquals(LdapMessage.BIND_REQUEST, message.getOperation()),
			() -> assertEquals(4, message.getMsgId()),
			() -> assertEquals(LdapResult.LDAP_AUTH_METHOD_NOT_SUPPORTED, result.getErrorNo()));
	}

	@Test
	void rejectsAuthenticatedBindWithoutCommonName()
	{
		decode(LdapTestFixtures.INVALID_CN_BIND_REQUEST);

		assertAll(
			() -> assertEquals(LdapMessage.BIND_REQUEST, message.getOperation()),
			() -> assertEquals(3, message.getMsgId()),
			() -> assertEquals(LdapResult.LDAP_NO_SUCH_OBJECT, result.getErrorNo()));
	}

	@Test
	void decodesUnbindRequest()
	{
		decode(LdapTestFixtures.UNBIND_REQUEST);

		assertAll(
			() -> assertEquals(LdapMessage.UNBIND_REQUEST, message.getOperation()),
			() -> assertEquals(5, message.getMsgId()),
			() -> assertEquals(LdapResult.LDAP_SUCCESS, result.getErrorNo()));
	}

	@Test
	void decodesBareEqualitySearchRequest()
	{
		decode(LdapTestFixtures.BARE_SEARCH_REQUEST);

		assertAll(
			() -> assertEquals(LdapMessage.SEARCH_REQUEST, message.getOperation()),
			() -> assertEquals(6, message.getMsgId()),
			() -> assertEquals(LdapTestFixtures.BASE_DN, message.getBaseObj()),
			() -> assertEquals(LdapTestFixtures.USER_ID, message.getUserId()),
			() -> assertEquals(LdapTestFixtures.ORG, message.getOrg()),
			() -> assertEquals(LdapTestFixtures.ORG_UNIT, message.getOrgUnit()),
			() -> assertEquals(LdapResult.LDAP_SUCCESS, result.getErrorNo()));
	}

	@Test
	void decodesCompoundAndSearchRequest()
	{
		decode(LdapTestFixtures.AND_SEARCH_REQUEST);

		assertAll(
			() -> assertEquals(LdapMessage.SEARCH_REQUEST, message.getOperation()),
			() -> assertEquals(7, message.getMsgId()),
			() -> assertEquals(LdapTestFixtures.BASE_DN, message.getBaseObj()),
			() -> assertEquals(LdapTestFixtures.USER_ID, message.getUserId()),
			() -> assertEquals(LdapResult.LDAP_SUCCESS, result.getErrorNo()));
	}

	@Test
	void rejectsUnsupportedOperation()
	{
		decode(LdapTestFixtures.UNSUPPORTED_OPERATION_REQUEST);

		assertAll(
			() -> assertEquals(102, message.getOperation()),
			() -> assertEquals(8, message.getMsgId()),
			() -> assertEquals(LdapResult.LDAP_PROTOCOL_ERROR, result.getErrorNo()));
	}

	@ParameterizedTest
	@MethodSource("malformedPackets")
	void rejectsMalformedPackets(String ignoredName, byte[] packet)
	{
		decode(packet);

		assertEquals(LdapResult.LDAP_PROTOCOL_ERROR, result.getErrorNo());
	}

	private void decode(byte[] packet)
	{
		result.reset(message, new MLdapUser());
		message.reset(result);
		message.decode(packet, packet.length);
	}

	private static Stream<Arguments> malformedPackets()
	{
		return Stream.of(
			Arguments.of("malformed", LdapTestFixtures.MALFORMED_BIND_REQUEST),
			Arguments.of("truncated", LdapTestFixtures.TRUNCATED_BIND_REQUEST));
	}
}
