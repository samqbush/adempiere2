package org.adempiere.web.route;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class DeferredResponseBufferTest {

	private static final Path SPOOL =
			Path.of("build", "phase5f", "deferred-response-test");

	@Test
	void exactMemoryBoundaryDoesNotSpill() throws Exception {
		byte[] body = new byte[32];
		try (DeferredResponseBuffer buffer =
				new DeferredResponseBuffer(SPOOL, 64, 32)) {
			buffer.outputStream().write(body);
			assertFalse(buffer.spilled());
			ByteArrayOutputStream committed = new ByteArrayOutputStream();
			buffer.commitTo(committed);
			assertArrayEquals(body, committed.toByteArray());
		}
	}

	@Test
	void oneBytePastMemoryBoundarySpillsWithoutDoublingMemory() throws Exception {
		byte[] body = new byte[33];
		try (DeferredResponseBuffer buffer =
				new DeferredResponseBuffer(SPOOL, 64, 32)) {
			buffer.outputStream().write(body);
			assertTrue(buffer.spilled());
			ByteArrayOutputStream committed = new ByteArrayOutputStream();
			buffer.commitTo(committed);
			assertArrayEquals(body, committed.toByteArray());
		}
		assertTrue(empty(SPOOL));
	}

	@Test
	void overLimitBodyIsNeverCommitted() throws Exception {
		ByteArrayOutputStream publicResponse = new ByteArrayOutputStream();
		try (DeferredResponseBuffer buffer =
				new DeferredResponseBuffer(SPOOL, 32, 8)) {
			buffer.outputStream().write(new byte[32]);
			assertThrows(
					DeferredResponseBuffer.ResponseLimitExceededException.class,
					() -> buffer.outputStream().write(1));
			assertTrue(publicResponse.size() == 0);
		}
	}

	@Test
	void crashStalePredictableFileCannotCollideWithNewSpool()
			throws Exception {
		Files.createDirectories(SPOOL);
		Path stale = SPOOL.resolve("response-1.spool");
		Files.writeString(stale, "stale");
		try (DeferredResponseBuffer buffer =
				new DeferredResponseBuffer(SPOOL, 64, 1)) {
			buffer.outputStream().write(new byte[2]);
			assertTrue(buffer.spilled());
			assertTrue(Files.readString(stale).equals("stale"));
		}
		assertTrue(Files.exists(stale));
		try (Stream<Path> files = Files.list(SPOOL)) {
			assertTrue(files.filter(path -> !path.equals(stale))
					.findAny().isEmpty());
		}
		Files.delete(stale);
	}

	private static boolean empty(Path directory) throws Exception {
		if (!Files.exists(directory)) {
			return true;
		}
		try (Stream<Path> files = Files.list(directory)) {
			return files.findAny().isEmpty();
		}
	}
}
