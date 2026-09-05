package org.adempiere.demo;

import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public final class DemoHttpProbe {
	private DemoHttpProbe() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 2 || args.length > 3) {
			throw new IllegalArgumentException(
				"Usage: DemoHttpProbe <url> <accepted-statuses> [timeout-seconds]");
		}
		Set<Integer> accepted = Arrays.stream(args[1].split(","))
			.map(Integer::valueOf)
			.collect(Collectors.toSet());
		Instant deadline = Instant.now().plusSeconds(
			args.length == 3 ? Long.parseLong(args[2]) : 10);
		Exception lastFailure = null;
		do {
			try {
				HttpURLConnection connection = (HttpURLConnection)
					URI.create(args[0]).toURL().openConnection();
				connection.setConnectTimeout(5000);
				connection.setReadTimeout(10000);
				connection.setInstanceFollowRedirects(false);
				int status = connection.getResponseCode();
				connection.disconnect();
				if (accepted.contains(status)) {
					return;
				}
				lastFailure = new IllegalStateException(
					"Unexpected HTTP status " + status + " from " + args[0]);
			} catch (Exception exception) {
				lastFailure = exception;
			}
			Thread.sleep(Duration.ofSeconds(2).toMillis());
		} while (Instant.now().isBefore(deadline));
		throw new IllegalStateException("Endpoint did not become ready: " + args[0],
			lastFailure);
	}
}
