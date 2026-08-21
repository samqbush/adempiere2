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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class JavaToolSupportTest
{
	private Path m_workDir;

	@BeforeEach
	void setUp () throws IOException
	{
		Assumptions.assumeFalse(JavaToolSupport.isWindows(),
			"These tests use shell-based fake Java homes");
		m_workDir = Path.of("build", "tmp", "JavaToolSupportTest",
			UUID.randomUUID().toString());
		Files.createDirectories(m_workDir);
	}

	@AfterEach
	void tearDown () throws IOException
	{
		deleteRecursively(m_workDir);
	}

	@Test
	void acceptsSupportedVersionFromDifferentlyNamedJavaHome () throws IOException
	{
		Path javaHome = createJavaHome("mystery-runtime", javaProbeScript("21", 0));

		JavaToolSupport.JavaProbeResult result = JavaToolSupport.DEFAULT
			.probeJavaHome(javaHome.toFile());

		assertTrue(result.isSupported());
		assertEquals(21, result.getFeatureVersion());
	}

	@Test
	void rejectsOlderJavaSpecificationVersion () throws IOException
	{
		Path javaHome = createJavaHome("old-runtime", javaProbeScript("17", 0));

		JavaToolSupport.JavaProbeResult result = JavaToolSupport.DEFAULT
			.probeJavaHome(javaHome.toFile());

		assertEquals("Wrong Java Version: Should be 21 or newer (found 17)",
			result.getErrorMessage());
	}

	@Test
	void rejectsMissingJavaExecutable () throws IOException
	{
		Path javaHome = Files.createDirectories(m_workDir.resolve("missing-java"));
		Files.createDirectories(javaHome.resolve("bin"));

		JavaToolSupport.JavaProbeResult result = JavaToolSupport.DEFAULT
			.probeJavaHome(javaHome.toFile());

		assertTrue(result.getErrorMessage().contains("Not found: Java executable"));
	}

	@Test
	void rejectsMalformedJavaSpecificationVersion () throws IOException
	{
		Path javaHome = createJavaHome("malformed-runtime",
			javaProbeScript("twenty-one", 0));

		JavaToolSupport.JavaProbeResult result = JavaToolSupport.DEFAULT
			.probeJavaHome(javaHome.toFile());

		assertTrue(result.getErrorMessage().contains("Malformed java.specification.version"));
	}

	@Test
	void rejectsMissingJavaSpecificationVersion () throws IOException
	{
		Path javaHome = createJavaHome("missing-version", javaProbeScript(null, 0));

		JavaToolSupport.JavaProbeResult result = JavaToolSupport.DEFAULT
			.probeJavaHome(javaHome.toFile());

		assertTrue(result.getErrorMessage().contains("Missing java.specification.version"));
	}

	@Test
	void rejectsTimedOutJavaProbe () throws IOException
	{
		Path javaHome = createJavaHome("timeout-runtime", "#!/bin/sh\nsleep 2\n");
		JavaToolSupport toolSupport = new JavaToolSupport(100L);

		JavaToolSupport.JavaProbeResult result = toolSupport.probeJavaHome(javaHome.toFile());

		assertEquals("Timed out probing Java Home: " + javaHome.toFile().getAbsolutePath(),
			result.getErrorMessage());
	}

	@Test
	void rejectsNonZeroExitCode () throws IOException
	{
		Path javaHome = createJavaHome("nonzero-runtime",
			"#!/bin/sh\n"
			+ "echo 'Property settings:'\n"
			+ "echo '    java.specification.version = 21'\n"
			+ "echo 'probe failed'\n"
			+ "exit 9\n");

		JavaToolSupport.JavaProbeResult result = JavaToolSupport.DEFAULT
			.probeJavaHome(javaHome.toFile());

		assertTrue(result.getErrorMessage().contains("exit code 9"));
		assertTrue(result.getErrorMessage().contains("probe failed"));
	}

	@Test
	void configVmImplementationsShareSameProbeLogic () throws IOException
	{
		Path javaHome = createJavaHome("runtime-without-version-in-name",
			javaProbeScript("21", 0));

		assertNull(testConfig(new ConfigVMOracle(newConfigurationData("oracle", javaHome))));
		assertNull(testConfig(new ConfigVMOpenJDK(newConfigurationData("openJDK", javaHome))));
		assertNull(testConfig(new ConfigVMMacOS(newConfigurationData("macOS", javaHome))));
	}

	private ConfigurationData newConfigurationData (String javaType, Path javaHome)
	{
		ConfigurationData data = new ConfigurationData(null);
		data.setJavaType(javaType);
		data.setJavaHome(javaHome.toString());
		return data;
	}

	private String testConfig (Config config)
	{
		return config.test();
	}

	private Path createJavaHome (String directoryName, String script) throws IOException
	{
		Path javaHome = Files.createDirectories(m_workDir.resolve(directoryName));
		Path binDirectory = Files.createDirectories(javaHome.resolve("bin"));
		Path javaExecutable = binDirectory.resolve("java");
		Files.writeString(javaExecutable, script, StandardCharsets.UTF_8);
		assertTrue(javaExecutable.toFile().setExecutable(true, false));
		return javaHome;
	}

	private String javaProbeScript (String specificationVersion, int exitCode)
	{
		StringBuilder script = new StringBuilder("#!/bin/sh\n");
		script.append("echo 'Property settings:'\n");
		if (specificationVersion != null)
			script.append("echo '    java.specification.version = ")
				.append(specificationVersion).append("'\n");
		script.append("exit ").append(exitCode).append("\n");
		return script.toString();
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
