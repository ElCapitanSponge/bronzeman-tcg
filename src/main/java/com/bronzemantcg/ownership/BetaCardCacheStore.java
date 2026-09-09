package com.bronzemantcg.ownership;

import com.bronzemantcg.util.CacheFiles;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.RuneLite;

/** Versioned profile-keyed disk persistence for validated Beta-name classifications. */
@Singleton
public final class BetaCardCacheStore
{
	private static final int SCHEMA_VERSION = 1;
	private static final int MAX_CACHE_BYTES = 1024 * 1024;
	private static final int MAX_CARD_NAMES = 10_000;

	private final Gson gson;
	private final Path cacheDirectory;

	@Inject
	public BetaCardCacheStore(Gson gson)
	{
		this(gson, RuneLite.RUNELITE_DIR.toPath().resolve("bronzeman-tcg")
			.resolve("beta-cards"));
	}

	BetaCardCacheStore(Gson gson, Path cacheDirectory)
	{
		this.gson = gson;
		this.cacheDirectory = cacheDirectory;
	}

	public Record load(String profileKey) throws IOException
	{
		Path file = fileForProfile(profileKey);
		if (!Files.exists(file))
		{
			return null;
		}
		long size = Files.size(file);
		if (size <= 0 || size > MAX_CACHE_BYTES)
		{
			throw new IOException("Beta cache has an invalid size");
		}
		CacheDto dto;
		try
		{
			dto = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), CacheDto.class);
		}
		catch (JsonParseException | IllegalStateException ex)
		{
			throw new IOException("Beta cache JSON is malformed", ex);
		}
		if (dto == null || dto.schemaVersion != SCHEMA_VERSION
			|| dto.savedAtEpochMillis <= 0 || dto.revision < 0
			|| blank(dto.displayName))
		{
			throw new IOException("Beta cache envelope is invalid");
		}
		return new Record(dto.displayName.trim(), dto.revision, dto.savedAtEpochMillis,
			validateNames(dto.cardNames));
	}

	public void save(String profileKey, String displayName, long revision,
		long savedAtEpochMillis, List<String> cardNames) throws IOException
	{
		if (blank(profileKey) || blank(displayName) || revision < 0 || savedAtEpochMillis <= 0)
		{
			throw new IllegalArgumentException("profile, player, revision and saved time are required");
		}
		CacheDto dto = new CacheDto();
		dto.schemaVersion = SCHEMA_VERSION;
		dto.displayName = displayName.trim();
		dto.revision = revision;
		dto.savedAtEpochMillis = savedAtEpochMillis;
		dto.cardNames = validateNames(cardNames);
		byte[] bytes = gson.toJson(dto).getBytes(StandardCharsets.UTF_8);
		if (bytes.length > MAX_CACHE_BYTES)
		{
			throw new IOException("Beta cache exceeds size limit");
		}
		CacheFiles.writeAtomically(fileForProfile(profileKey), bytes);
	}

	public void delete(String profileKey) throws IOException
	{
		Files.deleteIfExists(fileForProfile(profileKey));
	}

	Path fileForProfile(String profileKey)
	{
		return cacheDirectory.resolve(CacheFiles.sha256Hex(profileKey) + ".json");
	}

	private static List<String> validateNames(List<String> source) throws IOException
	{
		if (source == null || source.size() > MAX_CARD_NAMES)
		{
			throw new IOException("Beta cache card names are missing or invalid");
		}
		List<String> names = new ArrayList<>(source.size());
		Set<String> normalized = new HashSet<>();
		for (String name : source)
		{
			if (blank(name) || name.length() > 256)
			{
				throw new IOException("Beta cache contains an invalid card name");
			}
			String trimmed = name.trim();
			if (!normalized.add(trimmed.toLowerCase(Locale.ROOT)))
			{
				throw new IOException("Beta cache contains duplicate card names");
			}
			names.add(trimmed);
		}
		return List.copyOf(names);
	}

	private static boolean blank(String value)
	{
		return value == null || value.trim().isEmpty();
	}

	public static final class Record
	{
		private final String displayName;
		private final long revision;
		private final long savedAtEpochMillis;
		private final List<String> cardNames;

		private Record(String displayName, long revision, long savedAtEpochMillis,
			List<String> cardNames)
		{
			this.displayName = displayName;
			this.revision = revision;
			this.savedAtEpochMillis = savedAtEpochMillis;
			this.cardNames = cardNames;
		}

		public String getDisplayName()
		{
			return displayName;
		}

		public long getRevision()
		{
			return revision;
		}

		public long getSavedAtEpochMillis()
		{
			return savedAtEpochMillis;
		}

		public List<String> getCardNames()
		{
			return cardNames;
		}
	}

	@SuppressWarnings("unused")
	private static final class CacheDto
	{
		private int schemaVersion;
		private String displayName;
		private long revision;
		private long savedAtEpochMillis;
		private List<String> cardNames;
	}
}
