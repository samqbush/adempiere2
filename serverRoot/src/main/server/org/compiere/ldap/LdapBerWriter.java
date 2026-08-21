package org.compiere.ldap;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

final class LdapBerWriter
{
	private final ByteArrayOutputStream output = new ByteArrayOutputStream();
	private final Deque<Frame> frames = new ArrayDeque<Frame>();

	void beginSeq(int tag)
	{
		frames.push(new Frame(tag));
	}

	void endSeq()
	{
		if (frames.isEmpty())
			throw new IllegalStateException("No BER sequence is open");

		Frame frame = frames.pop();
		writeEncoded(frame.tag, frame.payload.toByteArray());
	}

	void encodeInt(int value)
	{
		encodeInt(value, 0x02);
	}

	void encodeInt(int value, int tag)
	{
		writeEncoded(tag, intToBytes(value));
	}

	void encodeString(String value, boolean ignoredUseUtf8)
	{
		encodeString(value, 0x04, ignoredUseUtf8);
	}

	void encodeString(String value, int tag, boolean ignoredUseUtf8)
	{
		writeEncoded(tag, (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
	}

	byte[] getTrimmedBuf()
	{
		if (!frames.isEmpty())
			throw new IllegalStateException("BER sequence stack is not balanced");
		return output.toByteArray();
	}

	private void writeEncoded(int tag, byte[] value)
	{
		ByteArrayOutputStream target = frames.isEmpty() ? output : frames.peek().payload;
		target.write(tag);
		writeLength(target, value.length);
		target.write(value, 0, value.length);
	}

	private void writeLength(ByteArrayOutputStream target, int length)
	{
		if (length < 0)
			throw new IllegalArgumentException("BER length must be non-negative");
		if (length < 0x80)
		{
			target.write(length);
			return;
		}

		int lengthBytes = 0;
		int value = length;
		byte[] buffer = new byte[Integer.BYTES];
		while (value > 0)
		{
			buffer[buffer.length - 1 - lengthBytes] = (byte) (value & 0xFF);
			value >>= 8;
			lengthBytes++;
		}
		target.write(0x80 | lengthBytes);
		target.write(buffer, buffer.length - lengthBytes, lengthBytes);
	}

	private byte[] intToBytes(int value)
	{
		byte[] bytes = new byte[] {
			(byte) (value >> 24),
			(byte) (value >> 16),
			(byte) (value >> 8),
			(byte) value
		};
		int index = 0;
		if (value >= 0)
		{
			while (index < bytes.length - 1
				&& bytes[index] == 0
				&& (bytes[index + 1] & 0x80) == 0)
			{
				index++;
			}
		}
		else
		{
			while (index < bytes.length - 1
				&& bytes[index] == (byte) 0xFF
				&& (bytes[index + 1] & 0x80) == 0x80)
			{
				index++;
			}
		}

		byte[] encoded = new byte[bytes.length - index];
		System.arraycopy(bytes, index, encoded, 0, encoded.length);
		return encoded;
	}

	private static final class Frame
	{
		private final int tag;
		private final ByteArrayOutputStream payload = new ByteArrayOutputStream();

		private Frame(int tag)
		{
			this.tag = tag;
		}
	}
}
