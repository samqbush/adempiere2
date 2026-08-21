package org.compiere.ldap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.compiere.model.MLdapProcessor;
import org.compiere.model.MLdapUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@Tag("UnitTest")
@ExtendWith(MockitoExtension.class)
class LdapResultTest
{
	@Mock
	private MLdapProcessor model;

	private LdapMessage message;
	private LdapResult result;
	private MLdapUser ldapUser;

	@BeforeEach
	void setUp()
	{
		message = new LdapMessage();
		result = new LdapResult();
		ldapUser = new MLdapUser();
	}

	@Test
	void encodesAnonymousBindSuccessWithoutDisconnecting()
	{
		decode(LdapTestFixtures.ANONYMOUS_BIND_REQUEST);

		byte[] response = result.getResult(model);

		assertArrayEquals(LdapTestFixtures.ANONYMOUS_BIND_SUCCESS_RESPONSE, response);
		assertFalse(result.getDone());
		verifyNoInteractions(model);
	}

	@Test
	void encodesAuthenticatedBindSuccessWithParity()
	{
		decode(LdapTestFixtures.AUTHENTICATED_BIND_REQUEST);
		authenticateAs(LdapTestFixtures.PASSWORD);

		byte[] response = result.getResult(model);

		assertArrayEquals(LdapTestFixtures.AUTHENTICATED_BIND_SUCCESS_RESPONSE, response);
		assertTrue(result.getDone());
		verify(model).authenticate(any(MLdapUser.class),
			eq(LdapTestFixtures.USER_ID),
			eq(LdapTestFixtures.ORG),
			eq(LdapTestFixtures.ORG_UNIT));
	}

	@Test
	void encodesAuthenticatedBindFailureWithParity()
	{
		decode(LdapTestFixtures.AUTHENTICATED_BIND_REQUEST);
		authenticateAs("wrong-password");

		byte[] response = result.getResult(model);

		assertArrayEquals(LdapTestFixtures.AUTHENTICATED_BIND_FAILURE_RESPONSE, response);
		assertTrue(result.getDone());
	}

	@Test
	void encodesInvalidAuthenticatedBindErrorWithoutCallingModel()
	{
		decode(LdapTestFixtures.INVALID_CN_BIND_REQUEST);

		byte[] response = result.getResult(model);

		assertArrayEquals(LdapTestFixtures.INVALID_CN_BIND_ERROR_RESPONSE, response);
		assertTrue(result.getDone());
		verifyNoInteractions(model);
	}

	@Test
	void encodesSearchSuccessWithParity()
	{
		decode(LdapTestFixtures.BARE_SEARCH_REQUEST);
		doAnswer(invocation -> invocation.getArgument(0))
			.when(model)
			.authenticate(any(MLdapUser.class),
				eq(LdapTestFixtures.USER_ID),
				eq(LdapTestFixtures.ORG),
				eq(LdapTestFixtures.ORG_UNIT));

		byte[] response = result.getResult(model);

		assertArrayEquals(LdapTestFixtures.SEARCH_SUCCESS_RESPONSE, response);
		assertFalse(result.getDone());
	}

	@Test
	void encodesUnsupportedOperationErrorWithParity()
	{
		decode(LdapTestFixtures.UNSUPPORTED_OPERATION_REQUEST);

		byte[] response = result.getResult(model);

		assertArrayEquals(LdapTestFixtures.PROTOCOL_ERROR_SEARCH_RESPONSE, response);
		assertTrue(result.getDone());
		verifyNoInteractions(model);
	}

	@Test
	void encodesMalformedBindErrorWithParity()
	{
		decode(LdapTestFixtures.TRUNCATED_BIND_REQUEST);

		byte[] response = result.getResult(model);

		assertArrayEquals(LdapTestFixtures.PROTOCOL_ERROR_BIND_RESPONSE, response);
		assertTrue(result.getDone());
		verifyNoInteractions(model);
	}

	private void decode(byte[] packet)
	{
		result.reset(message, ldapUser);
		message.reset(result);
		message.decode(packet, packet.length);
	}

	private void authenticateAs(String password)
	{
		doAnswer(invocation -> {
			MLdapUser user = invocation.getArgument(0);
			user.setUserId(LdapTestFixtures.USER_ID);
			user.setPassword(password);
			return user;
		}).when(model).authenticate(any(MLdapUser.class),
			eq(LdapTestFixtures.USER_ID),
			eq(LdapTestFixtures.ORG),
			eq(LdapTestFixtures.ORG_UNIT));
	}
}
