/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution                       *
 * Copyright (C) 1999-2006 ComPiere, Inc. All Rights Reserved.                *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 * For the text or an alternative of this public license, you may reach us    *
 * ComPiere, Inc., 2620 Augustine Dr. #245, Santa Clara, CA 95054, USA        *
 * or via info@compiere.org or http://www.compiere.org/license.html           *
 *****************************************************************************/
package org.compiere.install;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;

import org.compiere.util.CLogMgt;
import org.compiere.util.CLogger;

final class JavaToolSupport
{
	static final int MINIMUM_JAVA_FEATURE = 21;
	static final long DEFAULT_COMMAND_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10);
	static final JavaToolSupport DEFAULT = new JavaToolSupport();

	private static final long PROCESS_DESTROY_GRACE_MILLIS = 250L;
	private static final String JAVA_SPECIFICATION_VERSION = "java.specification.version";
	private static final Pattern JAVA_SPECIFICATION_VERSION_PATTERN = Pattern.compile(
		"(?m)^\\s*" + Pattern.quote(JAVA_SPECIFICATION_VERSION) + "\\s*=\\s*(.+?)\\s*$");

	private static CLogger log = CLogger.getCLogger(JavaToolSupport.class);

	private final long m_commandTimeoutMillis;

	JavaToolSupport ()
	{
		this(DEFAULT_COMMAND_TIMEOUT_MILLIS);
	}

	JavaToolSupport (long commandTimeoutMillis)
	{
		if (commandTimeoutMillis <= 0)
			throw new IllegalArgumentException("Command timeout must be positive");
		m_commandTimeoutMillis = commandTimeoutMillis;
	}

	void initJavaHome (ConfigurationData data)
	{
		data.setJavaHome(normalizeJavaHome(System.getProperty("java.home")));
	}

	String validateJavaHome (Config config, ConfigurationData data)
	{
		String configuredJavaHome = data.getJavaHome();
		boolean pass = configuredJavaHome != null && configuredJavaHome.trim().length() > 0;
		String error = "Not found: Java Home";
		if (config.getPanel() != null)
			config.signalOK(config.getPanel().okJavaHome, "ErrorJavaHome", pass, true, error);
		if (!pass)
			return error;

		File javaHome = new File(normalizeJavaHome(configuredJavaHome));
		pass = javaHome.isDirectory();
		if (config.getPanel() != null)
			config.signalOK(config.getPanel().okJavaHome, "ErrorJavaHome", pass, true, error);
		if (!pass)
			return error;

		if (CLogMgt.isLevelFinest())
			CLogMgt.printProperties(System.getProperties(), "System", true);

		JavaProbeResult probeResult = probeJavaHome(javaHome);
		pass = probeResult.isSupported();
		error = probeResult.getErrorMessage();
		if (config.getPanel() != null)
			config.signalOK(config.getPanel().okJavaHome, "ErrorJavaHome", pass, true, error);
		if (!pass)
			return error;

		String javaHomePath = javaHome.getAbsolutePath();
		log.info("OK: JavaHome=" + javaHomePath);
		log.info("OK: Version=" + probeResult.getSpecificationVersion());
		data.setJavaHome(javaHomePath);
		config.setProperty(ConfigurationData.JAVA_HOME, javaHomePath);
		System.setProperty(ConfigurationData.JAVA_HOME, javaHomePath);
		String javaType = data.getJavaType();
		if (javaType != null)
			config.setProperty(ConfigurationData.JAVA_TYPE, javaType);
		return null;
	}

	JavaProbeResult probeJavaHome (File javaHome)
	{
		File javaExecutable = resolveJavaExecutable(javaHome);
		if (!javaExecutable.isFile())
			return JavaProbeResult.failure("Not found: Java executable - " + javaExecutable.getAbsolutePath());
		if (!javaExecutable.canExecute())
			return JavaProbeResult.failure("Cannot execute: Java executable - " + javaExecutable.getAbsolutePath());

		CommandResult result;
		try
		{
			result = executeCommand(buildCommand(javaExecutable,
				"-XshowSettings:properties", "-version"), Collections.<String, String>emptyMap());
		}
		catch (IllegalStateException e)
		{
			String error = "Failed to probe Java Home: " + e.getMessage();
			log.log(Level.WARNING, error, e);
			return JavaProbeResult.failure(error);
		}

		if (result.isTimedOut())
		{
			String error = "Timed out probing Java Home: " + javaHome.getAbsolutePath();
			log.warning(error);
			return JavaProbeResult.failure(error);
		}
		if (result.getExitCode() != 0)
		{
			String error = appendDiagnostics(
				"Java version probe failed (exit code " + result.getExitCode() + ")",
				result.getOutput());
			log.warning(error);
			return JavaProbeResult.failure(error);
		}

		String specificationVersion = extractJavaSpecificationVersion(result.getOutput());
		if (specificationVersion == null)
		{
			String error = "Missing java.specification.version for Java Home: "
				+ javaHome.getAbsolutePath();
			log.warning(error);
			return JavaProbeResult.failure(error);
		}

		int featureVersion;
		try
		{
			featureVersion = parseJavaFeatureVersion(specificationVersion);
		}
		catch (IllegalArgumentException e)
		{
			String error = "Malformed java.specification.version '" + specificationVersion
				+ "' for Java Home: " + javaHome.getAbsolutePath();
			log.warning(error);
			return JavaProbeResult.failure(error);
		}

		if (featureVersion < MINIMUM_JAVA_FEATURE)
		{
			String error = "Wrong Java Version: Should be " + MINIMUM_JAVA_FEATURE
				+ " or newer (found " + specificationVersion + ")";
			log.warning(error);
			return JavaProbeResult.failure(error);
		}

		return JavaProbeResult.success(specificationVersion, featureVersion);
	}

	CommandResult executeConfiguredKeytool (List<String> args, Map<String, String> environment)
	{
		return executeJavaTool(resolveConfiguredJavaHome(), "keytool", args, environment);
	}

	long getCommandTimeoutMillis ()
	{
		return m_commandTimeoutMillis;
	}

	static String normalizeJavaHome (String javaHome)
	{
		if (javaHome == null)
			return null;

		File home = new File(javaHome);
		if ("jre".equalsIgnoreCase(home.getName()))
		{
			File parent = home.getParentFile();
			if (parent != null)
				return parent.getAbsolutePath();
		}
		return home.getAbsolutePath();
	}

	static boolean isWindows ()
	{
		return System.getProperty("os.name", "")
			.toLowerCase(Locale.ROOT).startsWith("win");
	}

	static String summarizeDiagnostics (String output)
	{
		if (output == null)
			return "";

		String normalized = output.replace('\r', '\n')
			.replace('\n', ' ')
			.replace('\t', ' ')
			.trim();
		while (normalized.contains("  "))
			normalized = normalized.replace("  ", " ");
		if (normalized.length() > 240)
			return normalized.substring(0, 240) + "...";
		return normalized;
	}

	private CommandResult executeJavaTool (File javaHome, String toolName,
		List<String> args, Map<String, String> environment)
	{
		File executable = resolveJavaTool(javaHome, toolName);
		if (!executable.isFile())
			throw new IllegalStateException("Not found: " + toolName + " executable - "
				+ executable.getAbsolutePath());
		if (!executable.canExecute())
			throw new IllegalStateException("Cannot execute: " + toolName + " executable - "
				+ executable.getAbsolutePath());
		return executeCommand(buildCommand(executable, args), environment);
	}

	private File resolveConfiguredJavaHome ()
	{
		String configuredJavaHome = System.getProperty(ConfigurationData.JAVA_HOME);
		if (configuredJavaHome != null && configuredJavaHome.trim().length() > 0)
			return new File(normalizeJavaHome(configuredJavaHome));
		return new File(normalizeJavaHome(System.getProperty("java.home")));
	}

	private static File resolveJavaExecutable (File javaHome)
	{
		return resolveJavaTool(javaHome, "java");
	}

	private static File resolveJavaTool (File javaHome, String toolName)
	{
		String executableName = isWindows() ? toolName + ".exe" : toolName;
		return new File(new File(javaHome, "bin"), executableName);
	}

	private List<String> buildCommand (File executable, String... args)
	{
		List<String> command = new ArrayList<String>(args.length + 1);
		command.add(executable.getAbsolutePath());
		Collections.addAll(command, args);
		return command;
	}

	private List<String> buildCommand (File executable, List<String> args)
	{
		List<String> command = new ArrayList<String>(args.size() + 1);
		command.add(executable.getAbsolutePath());
		command.addAll(args);
		return command;
	}

	private CommandResult executeCommand (List<String> command, Map<String, String> environment)
	{
		ProcessBuilder processBuilder = new ProcessBuilder(command);
		processBuilder.redirectErrorStream(true);
		if (environment != null && !environment.isEmpty())
			processBuilder.environment().putAll(environment);

		Process process;
		try
		{
			process = processBuilder.start();
		}
		catch (IOException e)
		{
			throw new IllegalStateException("Failed to start " + command.get(0), e);
		}

		CompletableFuture<String> outputReader = readOutput(process.getInputStream());
		boolean finished;
		try
		{
			finished = process.waitFor(m_commandTimeoutMillis, TimeUnit.MILLISECONDS);
			if (!finished)
			{
				process.destroy();
				if (!process.waitFor(PROCESS_DESTROY_GRACE_MILLIS, TimeUnit.MILLISECONDS))
				{
					process.destroyForcibly();
					process.waitFor(PROCESS_DESTROY_GRACE_MILLIS, TimeUnit.MILLISECONDS);
				}
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			throw new IllegalStateException("Interrupted while waiting for " + command.get(0), e);
		}

		String output = waitForOutput(outputReader);
		return new CommandResult(Collections.unmodifiableList(new ArrayList<String>(command)),
			output, !finished, finished ? process.exitValue() : -1);
	}

	private CompletableFuture<String> readOutput (InputStream inputStream)
	{
		return CompletableFuture.supplyAsync(() -> {
			try (InputStream stream = inputStream;
				ByteArrayOutputStream buffer = new ByteArrayOutputStream())
			{
				stream.transferTo(buffer);
				return buffer.toString(StandardCharsets.UTF_8);
			}
			catch (IOException e)
			{
				throw new IllegalStateException("Failed to capture process output", e);
			}
		});
	}

	private String waitForOutput (CompletableFuture<String> outputReader)
	{
		try
		{
			return outputReader.get();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while capturing process output", e);
		}
		catch (ExecutionException e)
		{
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException)
				throw (RuntimeException)cause;
			throw new IllegalStateException("Failed to capture process output", cause);
		}
	}

	private static String extractJavaSpecificationVersion (String output)
	{
		Matcher matcher = JAVA_SPECIFICATION_VERSION_PATTERN.matcher(output);
		if (!matcher.find())
			return null;
		return matcher.group(1).trim();
	}

	private static int parseJavaFeatureVersion (String specificationVersion)
	{
		String normalized = specificationVersion.trim();
		if (normalized.startsWith("1."))
			normalized = normalized.substring(2);
		int dotIndex = normalized.indexOf('.');
		if (dotIndex > 0)
			normalized = normalized.substring(0, dotIndex);
		if (!normalized.matches("\\d+"))
			throw new IllegalArgumentException("Invalid java.specification.version: "
				+ specificationVersion);
		return Integer.parseInt(normalized);
	}

	private static String appendDiagnostics (String message, String output)
	{
		String diagnostics = summarizeDiagnostics(output);
		if (diagnostics.length() == 0)
			return message;
		return message + ": " + diagnostics;
	}

	static final class JavaProbeResult
	{
		private final String m_specificationVersion;
		private final int m_featureVersion;
		private final String m_errorMessage;

		private JavaProbeResult (String specificationVersion, int featureVersion,
			String errorMessage)
		{
			m_specificationVersion = specificationVersion;
			m_featureVersion = featureVersion;
			m_errorMessage = errorMessage;
		}

		static JavaProbeResult success (String specificationVersion, int featureVersion)
		{
			return new JavaProbeResult(specificationVersion, featureVersion, null);
		}

		static JavaProbeResult failure (String errorMessage)
		{
			return new JavaProbeResult(null, -1, errorMessage);
		}

		boolean isSupported ()
		{
			return m_errorMessage == null;
		}

		String getSpecificationVersion ()
		{
			return m_specificationVersion;
		}

		int getFeatureVersion ()
		{
			return m_featureVersion;
		}

		String getErrorMessage ()
		{
			return m_errorMessage;
		}
	}

	static final class CommandResult
	{
		private final List<String> m_command;
		private final String m_output;
		private final boolean m_timedOut;
		private final int m_exitCode;

		private CommandResult (List<String> command, String output,
			boolean timedOut, int exitCode)
		{
			m_command = command;
			m_output = output;
			m_timedOut = timedOut;
			m_exitCode = exitCode;
		}

		List<String> getCommand ()
		{
			return m_command;
		}

		String getOutput ()
		{
			return m_output;
		}

		boolean isTimedOut ()
		{
			return m_timedOut;
		}

		int getExitCode ()
		{
			return m_exitCode;
		}
	}
}
