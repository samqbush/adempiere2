package org.adempiere.web.route;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.PosixFileAttributeView;

/**
 * Bounded response staging that spills to a caller-owned directory.
 *
 * <p>The public response is untouched until the complete backend response has
 * passed its byte cap. Small bodies remain in memory; larger bodies use one
 * bounded spool file and are streamed to the servlet response without a second
 * in-memory copy.
 */
public final class DeferredResponseBuffer implements AutoCloseable {

	public static final int DEFAULT_MEMORY_LIMIT = 64 * 1024;

	private final Path spoolDirectory;
	private final long maximumBytes;
	private final int memoryLimit;
	private ByteArrayOutputStream memory = new ByteArrayOutputStream();
	private OutputStream destination = memory;
	private Path spool;
	private long size;
	private boolean closed;

	public DeferredResponseBuffer(Path spoolDirectory, long maximumBytes) {
		this(spoolDirectory, maximumBytes, DEFAULT_MEMORY_LIMIT);
	}

	DeferredResponseBuffer(
			Path spoolDirectory, long maximumBytes, int memoryLimit) {
		if (spoolDirectory == null || maximumBytes < 0 || memoryLimit < 0) {
			throw new IllegalArgumentException(
					"A spool directory and non-negative limits are required");
		}
		this.spoolDirectory = spoolDirectory;
		this.maximumBytes = maximumBytes;
		this.memoryLimit = memoryLimit;
	}

	public OutputStream outputStream() {
		return new OutputStream() {
			@Override
			public void write(int value) throws IOException {
				write(new byte[] {(byte) value}, 0, 1);
			}

			@Override
			public void write(byte[] bytes, int offset, int length)
					throws IOException {
				ensureOpen();
				if (length < 0 || size + length > maximumBytes) {
					throw new ResponseLimitExceededException(maximumBytes);
				}
				if (spool == null && size + length > memoryLimit) {
					spill();
				}
				destination.write(bytes, offset, length);
				size += length;
			}

			@Override
			public void flush() throws IOException {
				ensureOpen();
				destination.flush();
			}
		};
	}

	public long size() {
		return size;
	}

	public boolean spilled() {
		return spool != null;
	}

	/** Streams the already-validated body to the public response. */
	public void commitTo(OutputStream target) throws IOException {
		ensureOpen();
		destination.flush();
		try (InputStream input = spool == null
				? new ByteArrayInputStream(memory.toByteArray())
				: Files.newInputStream(spool)) {
			byte[] chunk = new byte[ProxyLimits.BUFFER_BYTES];
			for (int read = input.read(chunk); read >= 0; read = input.read(chunk)) {
				target.write(chunk, 0, read);
			}
		}
		target.flush();
	}

	private void spill() throws IOException {
		Files.createDirectories(spoolDirectory);
		spool = Files.createTempFile(
				spoolDirectory, "phase5f-response-", ".spool");
		if (Files.getFileAttributeView(
				spool, PosixFileAttributeView.class) != null) {
			Files.setPosixFilePermissions(
					spool, PosixFilePermissions.fromString("rw-------"));
		}
		destination = Files.newOutputStream(spool);
		memory.writeTo(destination);
		memory = null;
	}

	private void ensureOpen() throws IOException {
		if (closed) {
			throw new IOException("The deferred response buffer is closed");
		}
	}

	@Override
	public void close() throws IOException {
		if (closed) {
			return;
		}
		closed = true;
		destination.close();
		if (spool != null) {
			Files.deleteIfExists(spool);
		}
	}

	/** Closed exception type so the proxy can report the correct failure. */
	public static final class ResponseLimitExceededException extends IOException {
		private static final long serialVersionUID = 1L;

		private ResponseLimitExceededException(long limit) {
			super("The response exceeded " + limit + " bytes");
		}
	}
}
