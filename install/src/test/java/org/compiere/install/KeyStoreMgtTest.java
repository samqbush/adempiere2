/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution                       *
 * Copyright (C) 2006-2026 ADempiere Foundation, All Rights Reserved.         *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program, if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 *****************************************************************************/
package org.compiere.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class KeyStoreMgtTest
{
	private Path m_workDir;
	private String m_originalJavaHome;

	@BeforeEach
	void setUp () throws IOException
	{
		Assumptions.assumeFalse(JavaToolSupport.isWindows(),
			"These tests use shell-based fake keytool homes");
		m_workDir = Path.of("build", "tmp", "KeyStoreMgtTest",
			UUID.randomUUID().toString());
		Files.createDirectories(m_workDir);
		m_originalJavaHome = System.getProperty(ConfigurationData.JAVA_HOME);
	}

	@AfterEach
	void tearDown () throws IOException
	{
		if (m_originalJavaHome == null)
			System.clearProperty(ConfigurationData.JAVA_HOME);
		else
			System.setProperty(ConfigurationData.JAVA_HOME, m_originalJavaHome);
		deleteRecursively(m_workDir);
	}

	@Test
	void genkeyUsesEnvironmentPasswordSources () throws IOException
	{
		Path argsFile = m_workDir.resolve("args.txt");
		Path storePasswordFile = m_workDir.resolve("storepass.txt");
		Path keyPasswordFile = m_workDir.resolve("keypass.txt");
		Path javaHome = createKeytoolHome(
			"#!/bin/sh\n"
			+ "printf '%s\\n' \"$@\" > " + quote(argsFile) + "\n"
			+ "printf '%s' \"$" + KeyStoreMgt.KEYTOOL_STOREPASS_ENV + "\" > "
			+ quote(storePasswordFile) + "\n"
			+ "printf '%s' \"$" + KeyStoreMgt.KEYTOOL_KEYPASS_ENV + "\" > "
			+ quote(keyPasswordFile) + "\n");
		System.setProperty(ConfigurationData.JAVA_HOME, javaHome.toString());

		String password = "superSecret!";
		Path keystorePath = m_workDir.resolve("keystore folder").resolve("my keystore.jks");
		KeyStoreMgt.genkey("adempiere", password.toCharArray(),
			keystorePath.toString(), "CN=server.test, OU=unit, O=org, C=US");

		List<String> args = Files.readAllLines(argsFile, StandardCharsets.UTF_8);
		assertTrue(args.contains("-storepass:env"));
		assertTrue(args.contains(KeyStoreMgt.KEYTOOL_STOREPASS_ENV));
		assertTrue(args.contains("-keypass:env"));
		assertTrue(args.contains(KeyStoreMgt.KEYTOOL_KEYPASS_ENV));
		assertTrue(args.contains(keystorePath.toString()));
		assertFalse(args.contains(password));
		assertEquals(password, Files.readString(storePasswordFile, StandardCharsets.UTF_8));
		assertEquals(password, Files.readString(keyPasswordFile, StandardCharsets.UTF_8));
	}

	@Test
	void verifyPropagatesKeytoolFailures () throws IOException
	{
		Path javaHome = createKeytoolHome(
			"#!/bin/sh\n"
			+ "echo 'simulated keytool failure'\n"
			+ "exit 7\n");
		System.setProperty(ConfigurationData.JAVA_HOME, javaHome.toString());

		KeyStoreMgt keyStoreMgt = new KeyStoreMgt(
			m_workDir.resolve("keystore").resolve("myKeystore").toString(),
			"myPassword".toCharArray());
		keyStoreMgt.setCommonName("server.test");
		keyStoreMgt.setOrganization("org");
		keyStoreMgt.setOrganizationUnit("unit");
		keyStoreMgt.setCountry("US");

		String error = keyStoreMgt.verify(null);

		assertNotNull(error);
		assertTrue(error.contains("exit code 7"));
		assertTrue(error.contains("simulated keytool failure"));
	}

	private Path createKeytoolHome (String scriptBody) throws IOException
	{
		Path javaHome = Files.createDirectories(m_workDir.resolve("java-home"));
		Path binDirectory = Files.createDirectories(javaHome.resolve("bin"));
		Path keytoolExecutable = binDirectory.resolve("keytool");
		Files.writeString(keytoolExecutable,
			scriptBody.endsWith("\n") ? scriptBody : scriptBody + "\nexit 0\n",
			StandardCharsets.UTF_8);
		assertTrue(keytoolExecutable.toFile().setExecutable(true, false));
		return javaHome;
	}

	private String quote (Path path)
	{
		return "'" + path.toAbsolutePath().toString().replace("'", "'\"'\"'") + "'";
	}

	private void deleteRecursively (Path path) throws IOException
	{
		if (path == null || Files.notExists(path))
			return;
		try (java.util.stream.Stream<Path> paths = Files.walk(path))
		{
			paths.sorted(Comparator.reverseOrder())
				.forEach(currentPath -> {
					try
					{
						Files.deleteIfExists(currentPath);
					}
					catch (IOException e)
					{
						throw new IllegalStateException("Unable to delete " + currentPath, e);
					}
				});
		}
	}
}
