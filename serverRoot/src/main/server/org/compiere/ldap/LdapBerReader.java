package org.compiere.ldap;

import java.nio.charset.StandardCharsets;

final class LdapBerReader
{
	private static final int BOOLEAN_TAG = 0x01;
	private static final int INTEGER_TAG = 0x02;
	private static final int OCTET_STRING_TAG = 0x04;
	private static final int ENUMERATION_TAG = 0x0A;

	private final byte[] data;
	private final int limit;
	private int position;

	LdapBerReader(byte[] data, int offset, int length) throws LdapBerException
	{
		if (data == null)
			throw new LdapBerException("BER input is required");
		if (offset < 0 || length < 0 || offset > data.length || offset + length > data.length)
			throw new LdapBerException("BER input exceeds packet bounds");
		this.data = data;
		this.position = offset;
		this.limit = offset + length;
	}

	LdapBerReader readSequence(int expectedTag) throws LdapBerException
	{
		Element element = readElement();
		if (element.getTag() != expectedTag)
			throw new LdapBerException("Expected tag " + expectedTag + " but found " + element.getTag());
		return element.getReader();
	}

	Element readElement() throws LdapBerException
	{
		int tag = readTag();
		int length = readLength();
		return new Element(tag, readSlice(length));
	}

	int readInteger() throws LdapBerException
	{
		return readIntegerWithTag(INTEGER_TAG);
	}

	int readEnumeration() throws LdapBerException
	{
		return readIntegerWithTag(ENUMERATION_TAG);
	}

	boolean readBoolean() throws LdapBerException
	{
		int tag = readTag();
		if (tag != BOOLEAN_TAG)
			throw new LdapBerException("Expected boolean tag " + BOOLEAN_TAG + " but found " + tag);
		int length = readLength();
		if (length != 1)
			throw new LdapBerException("Boolean values must be exactly one byte");
		ensureAvailable(length, "boolean");
		return data[position++] != 0;
	}

	String readString() throws LdapBerException
	{
		return readString(OCTET_STRING_TAG);
	}

	String readString(int expectedTag) throws LdapBerException
	{
		int tag = readTag();
		if (tag != expectedTag)
			throw new LdapBerException("Expected string tag " + expectedTag + " but found " + tag);
		int length = readLength();
		ensureAvailable(length, "string");
		String value = new String(data, position, length, StandardCharsets.UTF_8);
		position += length;
		return value;
	}

	String readRawString()
	{
		String value = new String(data, position, limit - position, StandardCharsets.UTF_8);
		position = limit;
		return value;
	}

	int peekTag() throws LdapBerException
	{
		ensureAvailable(1, "tag");
		return data[position] & 0xFF;
	}

	boolean hasRemaining()
	{
		return position < limit;
	}

	private int readIntegerWithTag(int expectedTag) throws LdapBerException
	{
		int tag = readTag();
		if (tag != expectedTag)
			throw new LdapBerException("Expected integer tag " + expectedTag + " but found " + tag);
		int length = readLength();
		if (length <= 0 || length > Integer.BYTES)
			throw new LdapBerException("Unsupported integer length " + length);
		ensureAvailable(length, "integer");
		int value = (data[position] & 0x80) == 0 ? 0 : -1;
		for (int i = 0; i < length; i++)
		{
			value = (value << 8) | (data[position++] & 0xFF);
		}
		return value;
	}

	private int readTag() throws LdapBerException
	{
		ensureAvailable(1, "tag");
		return data[position++] & 0xFF;
	}

	private int readLength() throws LdapBerException
	{
		ensureAvailable(1, "length");
		int length = data[position++] & 0xFF;
		if ((length & 0x80) == 0)
			return length;

		int lengthBytes = length & 0x7F;
		if (lengthBytes == 0)
			throw new LdapBerException("Indefinite BER lengths are not supported");
		if (lengthBytes > Integer.BYTES)
			throw new LdapBerException("BER length uses too many bytes: " + lengthBytes);

		ensureAvailable(lengthBytes, "length");
		length = 0;
		for (int i = 0; i < lengthBytes; i++)
		{
			length = (length << 8) | (data[position++] & 0xFF);
		}
		return length;
	}

	private LdapBerReader readSlice(int length) throws LdapBerException
	{
		ensureAvailable(length, "value");
		LdapBerReader reader = new LdapBerReader(data, position, length);
		position += length;
		return reader;
	}

	private void ensureAvailable(int length, String component) throws LdapBerException
	{
		if (length < 0 || limit - position < length)
			throw new LdapBerException("Truncated BER " + component);
	}

	static final class Element
	{
		private final int tag;
		private final LdapBerReader reader;

		Element(int tag, LdapBerReader reader)
		{
			this.tag = tag;
			this.reader = reader;
		}

		int getTag()
		{
			return tag;
		}

		LdapBerReader getReader()
		{
			return reader;
		}
	}
}
