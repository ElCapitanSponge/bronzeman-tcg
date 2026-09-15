package com.bronzemantcg.panel;

import com.bronzemantcg.util.CacheFiles;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.RuneLite;

/** Versioned profile-keyed persistence for the two bounded Recent Unlocks histories. */
@Singleton
public final class RecentUnlocksCacheStore
{
	private static final int SCHEMA_VERSION = 1;
	private static final int MAX_CACHE_BYTES = 256 * 1024;
	static final int MAX_RECENT = 200;
	private static final int MAX_NAME_LENGTH = 256;

	private final Gson gson;
	private final Path cacheDirectory;

	@Inject
	public RecentUnlocksCacheStore(Gson gson)
	{
		this(gson, RuneLite.RUNELITE_DIR.toPath().resolve("bronzeman-tcg")
			.resolve("recent-unlocks"));
	}

	RecentUnlocksCacheStore(Gson gson, Path cacheDirectory)
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
			throw new IOException("Recent Unlocks cache has an invalid size");
		}
		CacheDto dto;
		try
		{
			dto = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), CacheDto.class);
		}
		catch (JsonParseException | IllegalStateException ex)
		{
			throw new IOException("Recent Unlocks cache JSON is malformed", ex);
		}
		if (dto == null || dto.schemaVersion != SCHEMA_VERSION)
		{
			throw new IOException("Recent Unlocks cache envelope is invalid");
		}
		return new Record(fromDtos(dto.personal, false), fromDtos(dto.shared, true));
	}

	public void save(String profileKey, List<RecentUnlocksTracker.Unlock> personal,
		List<RecentUnlocksTracker.Unlock> shared) throws IOException
	{
		List<RecentUnlocksTracker.Unlock> validPersonal = validateHistory(personal, false);
		List<RecentUnlocksTracker.Unlock> validShared = validateHistory(shared, true);
		CacheDto dto = new CacheDto();
		dto.schemaVersion = SCHEMA_VERSION;
		dto.personal = toDtos(validPersonal);
		dto.shared = toDtos(validShared);
		byte[] bytes = gson.toJson(dto).getBytes(StandardCharsets.UTF_8);
		if (bytes.length > MAX_CACHE_BYTES)
		{
			throw new IOException("Recent Unlocks cache exceeds size limit");
		}
		CacheFiles.writeAtomically(fileForProfile(profileKey), bytes);
	}

	Path fileForProfile(String profileKey)
	{
		return cacheDirectory.resolve(CacheFiles.sha256Hex(profileKey) + ".json");
	}

	static List<RecentUnlocksTracker.Unlock> validateHistory(
		List<RecentUnlocksTracker.Unlock> source, boolean shared) throws IOException
	{
		if (source == null || source.size() > MAX_RECENT)
		{
			throw new IOException("Recent Unlocks history has an invalid size");
		}
		List<RecentUnlocksTracker.Unlock> result = new ArrayList<>(source.size());
		for (RecentUnlocksTracker.Unlock unlock : source)
		{
			String name = unlock == null || unlock.name == null
				? "" : unlock.name.trim().toLowerCase(Locale.ROOT);
			if (name.isEmpty() || unlock.name.length() > MAX_NAME_LENGTH
				|| name.chars().anyMatch(Character::isISOControl) || unlock.time < 0
				|| unlock.shared != shared)
			{
				throw new IOException("Recent Unlocks history contains an invalid entry");
			}
			result.add(new RecentUnlocksTracker.Unlock(name, unlock.time, shared));
		}
		return Collections.unmodifiableList(result);
	}

	private List<RecentUnlocksTracker.Unlock> fromDtos(List<UnlockDto> source,
		boolean shared) throws IOException
	{
		if (source == null)
		{
			throw new IOException("Recent Unlocks cache is missing a history");
		}
		List<RecentUnlocksTracker.Unlock> result = new ArrayList<>(source.size());
		for (UnlockDto dto : source)
		{
			if (dto == null)
			{
				throw new IOException("Recent Unlocks cache contains an invalid entry");
			}
			result.add(new RecentUnlocksTracker.Unlock(dto.name, dto.time, shared));
		}
		return validateHistory(result, shared);
	}

	private static List<UnlockDto> toDtos(List<RecentUnlocksTracker.Unlock> source)
	{
		List<UnlockDto> result = new ArrayList<>(source.size());
		for (RecentUnlocksTracker.Unlock unlock : source)
		{
			UnlockDto dto = new UnlockDto();
			dto.name = unlock.name;
			dto.time = unlock.time;
			result.add(dto);
		}
		return result;
	}

	public static final class Record
	{
		private final List<RecentUnlocksTracker.Unlock> personal;
		private final List<RecentUnlocksTracker.Unlock> shared;

		private Record(List<RecentUnlocksTracker.Unlock> personal,
			List<RecentUnlocksTracker.Unlock> shared)
		{
			this.personal = personal;
			this.shared = shared;
		}

		public List<RecentUnlocksTracker.Unlock> getPersonal()
		{
			return personal;
		}

		public List<RecentUnlocksTracker.Unlock> getShared()
		{
			return shared;
		}
	}

	@SuppressWarnings("unused")
	private static final class CacheDto
	{
		private int schemaVersion;
		private List<UnlockDto> personal;
		private List<UnlockDto> shared;
	}

	@SuppressWarnings("unused")
	private static final class UnlockDto
	{
		private String name;
		private long time;
	}
}
