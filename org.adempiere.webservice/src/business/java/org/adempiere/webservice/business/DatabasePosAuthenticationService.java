package org.adempiere.webservice.business;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.xml.namespace.QName;

import org.adempiere.webservice.ServiceFault;
import org.apache.commons.codec.binary.Base64;
import org.compiere.util.DB;
import org.compiere.util.SecureEngine;

public final class DatabasePosAuthenticationService
		implements PosAuthenticationService {

	@Override
	public void authenticate(
			String username,
			String password,
			String service,
			String operation)
			throws ServiceFault {
		String databasePassword = DB.getSQLValueString(
				null,
				"SELECT Password FROM AD_User WHERE Name=? AND Password IS NOT NULL",
				username);
		if (databasePassword == null || databasePassword.isEmpty()) {
			throw authenticationFault(
					"Invalid user/password", "username", null);
		}

		String encrypted = DB.getSQLValueString(
				null,
				"SELECT IsEncrypted FROM AD_Column WHERE AD_Column_ID=417");
		if ("Y".equals(encrypted)) {
			databasePassword = SecureEngine.decrypt(databasePassword);
		}

		String hashPassword;
		try {
			hashPassword = new String(
					Base64.encodeBase64(MessageDigest.getInstance("SHA-1")
							.digest(databasePassword.getBytes("UTF-8"))),
					"ASCII");
		} catch (UnsupportedEncodingException | NoSuchAlgorithmException exception) {
			throw authenticationFault(
					"Error hashing db password", "username", exception);
		}

		if (!hashPassword.equals(password)) {
			throw authenticationFault(
					"Invalid user/password", "password", null);
		}

		throw authenticationFault(
				"Security not implemented yet", "webServiceName", null);
	}

	private static ServiceFault authenticationFault(
			String message,
			String localFaultCode,
			Throwable cause) {
		return new ServiceFault(
				"AUTHENTICATION",
				message,
				new QName(localFaultCode),
				null,
				cause);
	}
}
