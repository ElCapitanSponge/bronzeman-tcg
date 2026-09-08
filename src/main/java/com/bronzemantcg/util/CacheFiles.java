package com.bronzemantcg.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Small shared primitives for private, versioned Bronzeman cache files. */
public final class CacheFiles
{
	private CacheFiles()
	{
	}

	public static void writeAtomically(Path target, byte[] bytes) throws IOException
	{
		if (target == null || bytes == null)
		{
			throw new IllegalArgumentException("target and bytes are required");
		}
		Path parent = target.toAbsolutePath().getParent();
		if (parent == null)
		{
			throw new IOException("cache target has no parent directory");
		}
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
		boolean moved = false;
		try
		{
			try (FileChannel channel = FileChannel.open(temporary,
				StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))
			{
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining())
				{
					channel.write(buffer);
				}
				channel.force(true);
			}
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
			moved = true;
		}
		finally
		{
			if (!moved)
			{
				Files.deleteIfExists(temporary);
			}
		}
	}

	public static String sha256Hex(String value)
	{
		if (value == null || value.isEmpty())
		{
			throw new IllegalArgumentException("value is required");
		}
		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder(digest.length * 2);
			for (byte valueByte : digest)
			{
				result.append(String.format("%02x", valueByte & 0xff));
			}
			return result.toString();
		}
		catch (NoSuchAlgorithmException ex)
		{
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}
}
