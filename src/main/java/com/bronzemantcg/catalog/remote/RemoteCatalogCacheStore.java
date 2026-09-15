package com.bronzemantcg.catalog.remote;

import com.bronzemantcg.ownership.CardEntityKind;
import com.bronzemantcg.ownership.CardIdentity;
import com.bronzemantcg.ownership.ImmutableCardIdentityCatalog;
import com.bronzemantcg.util.CacheFiles;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.RuneLite;

/** Persists the compact, validated identity projection of the public live catalogue. */
@Singleton
public final class RemoteCatalogCacheStore
{
	private static final int SCHEMA_VERSION = 1;
	private static final int MAX_CACHE_BYTES = 4 * 1024 * 1024;
	private static final String CACHE_FILE = "live-catalogue-v1.json";

	private final Gson gson;
	private final Path cacheFile;

	@Inject
	public RemoteCatalogCacheStore(Gson gson)
	{
		this(gson, RuneLite.RUNELITE_DIR.toPath().resolve("bronzeman-tcg")
			.resolve("catalogue").resolve(CACHE_FILE));
	}

	RemoteCatalogCacheStore(Gson gson, Path cacheFile)
	{
		if (gson == null || cacheFile == null)
		{
			throw new IllegalArgumentException("gson and cacheFile are required");
		}
		this.gson = gson;
		this.cacheFile = cacheFile;
	}

	public CachedCatalog load() throws IOException, CatalogValidationException
	{
		if (!Files.exists(cacheFile))
		{
			return null;
		}
		long size = Files.size(cacheFile);
		if (size <= 0 || size > MAX_CACHE_BYTES)
		{
			throw new CatalogValidationException("catalogue cache has an invalid size");
		}
		CacheDto dto;
		try
		{
			dto = gson.fromJson(Files.readString(cacheFile, StandardCharsets.UTF_8), CacheDto.class);
		}
		catch (JsonParseException | IllegalStateException ex)
		{
			throw new CatalogValidationException("catalogue cache JSON is malformed", ex);
		}
		if (dto == null || dto.schemaVersion != SCHEMA_VERSION || dto.entries == null
			|| dto.entries.isEmpty() || !validVersion(dto.version)
			|| !validEtag(dto.etag) || dto.savedAtEpochMillis <= 0)
		{
			throw new CatalogValidationException("catalogue cache envelope is invalid");
		}
		List<ImmutableCardIdentityCatalog.Entry> entries = new ArrayList<>();
		for (int index = 0; index < dto.entries.size(); index++)
		{
			EntryDto entry = dto.entries.get(index);
			if (entry == null || entry.kind == null || blank(entry.cardName)
				|| entry.entityIds == null || entry.entityIds.isEmpty()
				|| entry.entityNames == null || entry.entityNames.isEmpty())
			{
				throw new CatalogValidationException("catalogue cache entry is invalid at " + index);
			}
			Set<Integer> entityIds = strictIds(entry.entityIds, "entity", index);
			Set<Integer> guardedIds = strictIds(entry.ownedNameRequiredEntityIds,
				"guarded", index);
			if (!entityIds.containsAll(guardedIds))
			{
				throw new CatalogValidationException(
					"catalogue cache guarded ID is unknown at " + index);
			}
			Set<String> entityNames = strictNames(entry.entityNames, "entity", index);
			Set<String> legacyNames = strictNames(entry.legacyCardNames, "legacy", index);
			CardIdentity identity = new CardIdentity(entry.kind, entry.cardName,
				legacyNames, entityIds, guardedIds);
			entries.add(new ImmutableCardIdentityCatalog.Entry(identity, entityNames));
		}
		OsrsTcgCatalogSnapshot snapshot = new OsrsTcgCatalogSnapshot(entries);
		if (snapshot.getAmbiguousIdCount(CardEntityKind.ITEM) != 0
			|| snapshot.getAmbiguousIdCount(CardEntityKind.NPC) != 0)
		{
			throw new CatalogValidationException(
				"catalogue cache contains ambiguous entity IDs");
		}
		return new CachedCatalog(snapshot, dto.version.trim(), trimToNull(dto.etag),
			dto.savedAtEpochMillis);
	}

	public void save(OsrsTcgCatalogSnapshot snapshot, String version, String etag,
		long savedAtEpochMillis) throws IOException
	{
		if (snapshot == null || !validVersion(version) || !validEtag(etag)
			|| savedAtEpochMillis <= 0)
		{
			throw new IllegalArgumentException("catalogue cache envelope is invalid");
		}
		CacheDto dto = new CacheDto();
		dto.schemaVersion = SCHEMA_VERSION;
		dto.version = version.trim();
		dto.etag = trimToNull(etag);
		dto.savedAtEpochMillis = savedAtEpochMillis;
		dto.entries = new ArrayList<>();
		for (ImmutableCardIdentityCatalog.Entry source : snapshot.getEntries())
		{
			CardIdentity identity = source.getIdentity();
			EntryDto entry = new EntryDto();
			entry.kind = identity.getKind();
			entry.cardName = identity.getCardName();
			entry.legacyCardNames = new ArrayList<>(identity.getLegacyCardNames());
			entry.entityNames = new ArrayList<>(source.getEntityNames());
			entry.entityIds = new ArrayList<>(identity.getEntityIds());
			entry.ownedNameRequiredEntityIds =
				new ArrayList<>(identity.getOwnedNameRequiredEntityIds());
			dto.entries.add(entry);
		}
		byte[] bytes = gson.toJson(dto).getBytes(StandardCharsets.UTF_8);
		if (bytes.length > MAX_CACHE_BYTES)
		{
			throw new IOException("compact catalogue cache exceeds size limit");
		}
		CacheFiles.writeAtomically(cacheFile, bytes);
	}

	Path getCacheFile()
	{
		return cacheFile;
	}

	private static Set<Integer> strictIds(List<Integer> values, String label, int index)
		throws CatalogValidationException
	{
		Set<Integer> result = new LinkedHashSet<>();
		if (values == null)
		{
			return result;
		}
		for (Integer value : values)
		{
			if (value == null || value < 0 || !result.add(value))
			{
				throw new CatalogValidationException("catalogue cache " + label
					+ " ID is invalid at " + index);
			}
		}
		return result;
	}

	private static Set<String> strictNames(List<String> values, String label, int index)
		throws CatalogValidationException
	{
		Set<String> result = new LinkedHashSet<>();
		if (values == null)
		{
			return result;
		}
		for (String value : values)
		{
			if (blank(value) || !result.add(value.trim()))
			{
				throw new CatalogValidationException("catalogue cache " + label
					+ " name is invalid at " + index);
			}
		}
		return result;
	}

	private static boolean blank(String value)
	{
		return value == null || value.trim().isEmpty();
	}

	private static String trimToNull(String value)
	{
		return blank(value) ? null : value.trim();
	}

	private static boolean validVersion(String value)
	{
		return !blank(value) && value.trim().length() <= 256
			&& value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
	}

	private static boolean validEtag(String value)
	{
		if (blank(value))
		{
			return true;
		}
		String trimmed = value.trim();
		int quote = trimmed.startsWith("W/\"") ? 2 : 0;
		return trimmed.length() > quote + 1 && trimmed.length() <= 256
			&& trimmed.charAt(quote) == '"'
			&& trimmed.endsWith("\"")
			&& trimmed.substring(quote + 1, trimmed.length() - 1).indexOf('"') < 0
			&& trimmed.indexOf('\r') < 0 && trimmed.indexOf('\n') < 0;
	}

	public static final class CachedCatalog
	{
		private final OsrsTcgCatalogSnapshot snapshot;
		private final String version;
		private final String etag;
		private final long savedAtEpochMillis;

		private CachedCatalog(OsrsTcgCatalogSnapshot snapshot, String version, String etag,
			long savedAtEpochMillis)
		{
			this.snapshot = snapshot;
			this.version = version;
			this.etag = etag;
			this.savedAtEpochMillis = savedAtEpochMillis;
		}

		public OsrsTcgCatalogSnapshot getSnapshot()
		{
			return snapshot;
		}

		public String getVersion()
		{
			return version;
		}

		public String getEtag()
		{
			return etag;
		}

		public long getSavedAtEpochMillis()
		{
			return savedAtEpochMillis;
		}
	}

	@SuppressWarnings("unused")
	private static final class CacheDto
	{
		private int schemaVersion;
		private String version;
		private String etag;
		private long savedAtEpochMillis;
		private List<EntryDto> entries;
	}

	@SuppressWarnings({"unused", "MismatchedQueryAndUpdateOfCollection"})
	private static final class EntryDto
	{
		private CardEntityKind kind;
		private String cardName;
		private List<String> legacyCardNames;
		private List<String> entityNames;
		private List<Integer> entityIds;
		private List<Integer> ownedNameRequiredEntityIds;
	}
}
