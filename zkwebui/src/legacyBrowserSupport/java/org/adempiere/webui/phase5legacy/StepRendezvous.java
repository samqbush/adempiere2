package org.adempiere.webui.phase5legacy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;

/**
 * The per-step rendezvous between the browser driver and the snapshot
 * orchestrator.
 *
 * <p>Phase 5g-1a measures the database effect around EACH operation, not once
 * around the whole flow. That only means something if the snapshot is taken
 * when the step has committed and before the next step starts. Two processes
 * must therefore take strict turns, and every way that handshake can silently
 * degrade into "no measurement" is closed here rather than in either caller.
 *
 * <h2>Why a file protocol at all</h2>
 *
 * The driver is a JVM inside a Gradle test worker and the orchestrator is a
 * shell script; they have no shared address space. A file rendezvous is the
 * simplest thing both can use, provided it is built to fail loudly.
 *
 * <h2>What would otherwise go wrong</h2>
 *
 * <ul>
 * <li><b>A partially written marker.</b> A reader can observe a file that a
 * writer has created but not finished. Every marker is therefore written to a
 * temporary name and moved into place with {@link StandardCopyOption#ATOMIC_MOVE},
 * so it is either absent or complete.</li>
 * <li><b>A stale marker from an earlier run.</b> A leftover acknowledgement
 * would satisfy the next run instantly, and the capture would proceed without
 * any snapshot having been taken -- a silently unmeasured oracle. Every marker
 * carries a per-run token, {@link #reset()} empties the directory at launch, and
 * a token mismatch is a hard failure rather than a retry.</li>
 * <li><b>A dead peer.</b> Waiting on a file cannot notice that the process that
 * was going to write it has died. Both sides publish their PID and each wait
 * checks the peer is still alive, so a crashed orchestrator fails the driver
 * immediately with the reason, instead of after a long timeout with none.</li>
 * <li><b>A timeout treated as a skip.</b> It never is. Every wait that expires
 * throws.</li>
 * </ul>
 */
public final class StepRendezvous {

	private static final Duration POLL = Duration.ofMillis(100);

	private final Path directory;
	private final String token;
	private final String self;
	private final String peer;

	/**
	 * @param directory a per-capture directory, not shared between captures
	 * @param token a per-run token that must match on both sides
	 * @param self {@code "driver"} or {@code "orchestrator"}
	 * @param peer the other side's role name
	 */
	public StepRendezvous(Path directory, String token, String self, String peer) {
		this.directory = directory;
		this.token = token;
		this.self = self;
		this.peer = peer;
	}

	/**
	 * Empties the rendezvous directory and republishes this side's liveness.
	 *
	 * <p>Called by the side that starts the capture. Clearing is not optional: a
	 * surviving acknowledgement from a previous capture is indistinguishable from
	 * a fresh one to a file-existence check.
	 */
	public void reset() {
		try {
			if (Files.isDirectory(directory)) {
				try (var walk = Files.walk(directory)) {
					walk.sorted(Comparator.reverseOrder())
							.filter(path -> !path.equals(directory))
							.forEach(path -> {
								try {
									Files.delete(path);
								} catch (IOException exception) {
									throw new UncheckedIOException(exception);
								}
							});
				}
			} else {
				Files.createDirectories(directory);
			}
			announce();
		} catch (IOException exception) {
			throw new UncheckedIOException(
					"could not reset the rendezvous directory " + directory, exception);
		}
	}

	/** Publishes this side's PID so the peer can detect its death. */
	public void announce() {
		write(directory.resolve(self + ".pid"), Long.toString(ProcessHandle.current().pid()));
	}

	/** Records an unrecoverable failure so the peer stops waiting immediately. */
	public void fail(String reason) {
		write(directory.resolve(self + ".failed"), reason);
	}

	/**
	 * Publishes a request for the peer to act on step {@code sequence}.
	 *
	 * @param stepId the step's contract identity, carried in the marker so a
	 *        misordered handshake is diagnosable from the evidence alone
	 */
	public void request(int sequence, String stepId) {
		write(marker(sequence, "request"), token + "\n" + stepId);
	}

	/** Acknowledges that step {@code sequence} has been fully handled. */
	public void acknowledge(int sequence, String stepId) {
		write(marker(sequence, "ack"), token + "\n" + stepId);
	}

	public String awaitRequest(int sequence, Duration timeout) {
		return await(marker(sequence, "request"), sequence, timeout);
	}

	public void awaitAcknowledgement(int sequence, String stepId, Duration timeout) {
		String observed = await(marker(sequence, "ack"), sequence, timeout);
		if (!observed.equals(stepId)) {
			throw new IllegalStateException("rendezvous step " + sequence
					+ " was acknowledged as '" + observed + "' but requested as '"
					+ stepId + "'. The two sides are not measuring the same step.");
		}
	}

	private String await(Path target, int sequence, Duration timeout) {
		long deadline = System.nanoTime() + timeout.toNanos();
		Path peerFailure = directory.resolve(peer + ".failed");
		while (true) {
			if (Files.isRegularFile(target)) {
				String[] lines = read(target).split("\n", 2);
				if (!token.equals(lines[0])) {
					// A stale marker from an earlier run. Retrying would be worse
					// than failing: the wait would eventually succeed against a
					// marker nobody in this run wrote.
					throw new IllegalStateException("rendezvous marker " + target
							+ " carries token '" + lines[0] + "' but this run is '"
							+ token + "'. A marker from an earlier capture survived.");
				}
				return lines.length > 1 ? lines[1] : "";
			}
			if (Files.isRegularFile(peerFailure)) {
				throw new IllegalStateException("the " + peer + " reported a failure while "
						+ self + " waited for step " + sequence + ": " + read(peerFailure));
			}
			if (!peerAlive()) {
				throw new IllegalStateException("the " + peer + " is no longer running; "
						+ self + " would have waited for step " + sequence + " forever.");
			}
			if (System.nanoTime() > deadline) {
				throw new IllegalStateException(self + " timed out after " + timeout
						+ " waiting for " + target + ". A rendezvous timeout is a "
						+ "failure, never a skipped measurement.");
			}
			try {
				Thread.sleep(POLL.toMillis());
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted while waiting for " + target);
			}
		}
	}

	/**
	 * True when the peer has not yet published a PID, or has published one that
	 * still resolves to a live process.
	 *
	 * <p>The "not yet published" case is deliberately treated as alive: the peer
	 * may simply not have started. It is bounded by the caller's timeout, which
	 * is what turns a never-arriving peer into a failure.
	 */
	private boolean peerAlive() {
		Path pidFile = directory.resolve(peer + ".pid");
		if (!Files.isRegularFile(pidFile)) {
			return true;
		}
		try {
			return ProcessHandle.of(Long.parseLong(read(pidFile).trim()))
					.map(ProcessHandle::isAlive)
					.orElse(false);
		} catch (NumberFormatException exception) {
			return true;
		}
	}

	private Path marker(int sequence, String kind) {
		return directory.resolve("step-" + sequence + "." + kind);
	}

	private void write(Path target, String content) {
		try {
			Files.createDirectories(directory);
			Path temporary = directory.resolve(target.getFileName() + ".partial");
			Files.writeString(temporary, content, StandardCharsets.UTF_8);
			try {
				Files.move(temporary, target,
						StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException exception) {
				// Refused rather than silently downgraded to a copy: a
				// non-atomic publish is exactly the partially-visible marker this
				// protocol exists to prevent.
				throw new IllegalStateException("the rendezvous directory " + directory
						+ " does not support atomic renames, so a marker could be "
						+ "observed half-written.", exception);
			}
		} catch (IOException exception) {
			throw new UncheckedIOException("could not publish " + target, exception);
		}
	}

	private String read(Path target) {
		try {
			return Files.readString(target, StandardCharsets.UTF_8);
		} catch (IOException exception) {
			throw new UncheckedIOException("could not read " + target, exception);
		}
	}
}
