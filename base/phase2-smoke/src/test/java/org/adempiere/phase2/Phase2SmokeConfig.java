package org.adempiere.phase2;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

final class Phase2SmokeConfig {

	private static final Properties PROPERTIES = load();

	private Phase2SmokeConfig() {
	}

	static String config(String key) {
		String value = PROPERTIES.getProperty(key);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Missing phase2 smoke config key: " + key);
		}
		return value;
	}

	static String systemOrConfig(String systemProperty, String configKey) {
		return System.getProperty(systemProperty, config(configKey));
	}

	static Path runtimeHome() {
		String value = System.getProperty("phase2.runtime.home");
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(
				"Phase 2 disposable runtime is not configured. Run :base:phase2DisposableRuntimeSmoke " +
					"or pass -Dphase2.runtime.home=<prepared-home>.");
		}
		return Path.of(value);
	}

	static String dbHost() {
		return Objects.requireNonNull(System.getProperty("phase2.db.host"),
			"Missing system property phase2.db.host");
	}

	static String dbPort() {
		return Objects.requireNonNull(System.getProperty("phase2.db.port"),
			"Missing system property phase2.db.port");
	}

	static String dbName() {
		return Objects.requireNonNull(System.getProperty("phase2.db.name"),
			"Missing system property phase2.db.name");
	}

	static String dbUser() {
		return Objects.requireNonNull(System.getProperty("phase2.db.user"),
			"Missing system property phase2.db.user");
	}

	static String dbPassword() {
		return Objects.requireNonNull(System.getProperty("phase2.db.password"),
			"Missing system property phase2.db.password");
	}

	private static Properties load() {
		String resourceName = System.getProperty("phase2.smoke.config.resource", "phase2-smoke.properties");
		Properties properties = new Properties();
		try (InputStream inputStream = Phase2SmokeConfig.class.getClassLoader().getResourceAsStream(resourceName)) {
			if (inputStream == null) {
				throw new IllegalStateException("Missing phase2 smoke resource: " + resourceName);
			}
			properties.load(inputStream);
		}
		catch (IOException exception) {
			throw new IllegalStateException("Unable to read phase2 smoke resource: " + resourceName, exception);
		}
		return properties;
	}
}
