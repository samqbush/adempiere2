package org.adempiere.web.handoff;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * The shared secret that authenticates the internal Tomcat 9 to Tomcat 10
 * handoff.
 *
 * <p>Both runtimes execute under the same operating-system account, so the key
 * is an ordinary file owned by that account rather than a credential exchanged
 * over the network. The rules it must satisfy are checked on load and are the
 * whole of its security model:
 *
 * <ul>
 *   <li>a regular file, not a symbolic link, so the path cannot be redirected
 *       at a file the operator never reviewed;</li>
 *   <li>at least {@value #MINIMUM_LENGTH} bytes;</li>
 *   <li>mode {@code 0600} exactly - no group or other access, and not
 *       executable;</li>
 *   <li>not a placeholder: a key whose bytes are all identical, or which is
 *       printable ASCII, is rejected. The repository ships no key and generates
 *       no default, so there is nothing to forget to replace.</li>
 * </ul>
 *
 * <p>Neither {@link #toString()} nor any exception message includes key
 * material.
 */
public final class HandoffKey {

	/** HMAC-SHA-256 keys shorter than the digest add nothing. */
	public static final int MINIMUM_LENGTH = 32;

	private static final Set<PosixFilePermission> REQUIRED_MODE =
			EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

	private final byte[] material;

	private HandoffKey(byte[] material) {
		this.material = material;
	}

	/**
	 * Loads and validates the key.
	 *
	 * @throws HandoffKeyException when the key is absent, unreadable, weak, or a
	 *                             placeholder
	 */
	public static HandoffKey load(Path path) throws HandoffKeyException {
		if (path == null) {
			throw new HandoffKeyException("No handoff key path was configured");
		}
		if (Files.isSymbolicLink(path)) {
			throw new HandoffKeyException(
					"The handoff key " + path + " is a symbolic link");
		}
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			throw new HandoffKeyException(
					"The handoff key " + path + " is not a regular file");
		}
		byte[] material;
		try {
			material = Files.readAllBytes(path);
		} catch (IOException failure) {
			throw new HandoffKeyException(
					"The handoff key " + path + " could not be read", failure);
		}
		try {
			Set<PosixFilePermission> mode = Files.getPosixFilePermissions(
					path, LinkOption.NOFOLLOW_LINKS);
			if (!REQUIRED_MODE.equals(mode)) {
				throw new HandoffKeyException("The handoff key " + path
						+ " must be mode 0600; it is " + render(mode));
			}
		} catch (UnsupportedOperationException notPosix) {
			throw new HandoffKeyException("The handoff key " + path
					+ " is on a file system that cannot express POSIX mode 0600",
					notPosix);
		} catch (IOException failure) {
			throw new HandoffKeyException("The mode of the handoff key " + path
					+ " could not be read", failure);
		}
		if (material.length < MINIMUM_LENGTH) {
			throw new HandoffKeyException("The handoff key " + path + " is "
					+ material.length + " bytes; at least " + MINIMUM_LENGTH
					+ " are required");
		}
		if (placeholder(material)) {
			throw new HandoffKeyException("The handoff key " + path
					+ " looks like a placeholder rather than random material");
		}
		return new HandoffKey(material);
	}

	/** Wraps already-validated material, for tests and for key generation. */
	public static HandoffKey of(byte[] material) throws HandoffKeyException {
		if (material == null || material.length < MINIMUM_LENGTH) {
			throw new HandoffKeyException(
					"A handoff key needs at least " + MINIMUM_LENGTH + " bytes");
		}
		if (placeholder(material)) {
			throw new HandoffKeyException(
					"A handoff key must not be a placeholder value");
		}
		return new HandoffKey(material.clone());
	}

	/** A defensive copy of the key material. */
	public byte[] material() {
		return material.clone();
	}

	/**
	 * All-identical bytes, or entirely printable ASCII. Real 32-byte random
	 * material is neither, and both are what a generated or hand-typed
	 * placeholder looks like.
	 */
	private static boolean placeholder(byte[] material) {
		boolean uniform = true;
		boolean printable = true;
		for (byte value : material) {
			if (value != material[0]) {
				uniform = false;
			}
			int unsigned = value & 0xFF;
			if (unsigned < 0x20 || unsigned > 0x7E) {
				printable = false;
			}
		}
		return uniform || printable;
	}

	private static String render(Set<PosixFilePermission> mode) {
		return Arrays.stream(PosixFilePermission.values())
				.map(permission -> mode.contains(permission) ? "1" : "0")
				.reduce("", String::concat);
	}

	@Override
	public String toString() {
		return "HandoffKey[" + material.length + " bytes]";
	}
}
