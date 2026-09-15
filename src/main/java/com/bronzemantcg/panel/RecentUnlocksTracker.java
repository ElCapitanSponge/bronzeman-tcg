package com.bronzemantcg.panel;

import com.bronzemantcg.BronzemanTcgConfig;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Records first-time card unlocks for the panel. The first readable collection per
 * profile is a silent baseline, so existing players do not receive a history flood.
 * Later additions are kept newest-first, independent of card pulls (duplicates do not
 * change the owned-name set), and persisted in a profile-hashed local cache.
 */
@Slf4j
@Singleton
public class RecentUnlocksTracker
{
	private static final String KEY = "recentUnlocks";
	private static final String SHARED_KEY = "recentSharedUnlocks";
	private static final int MAX_RECENT = RecentUnlocksCacheStore.MAX_RECENT;
	private final ProfileAccess profileAccess;
	private final Gson gson;
	private final RecentUnlocksCacheStore cacheStore;
	private List<Unlock> recent = new ArrayList<>();
	private List<Unlock> sharedRecent = new ArrayList<>();
	private Set<String> baseline;
	private Set<String> sharedBaseline;
	private Set<String> sharedSeen = new HashSet<>();
	private String loadedProfile;
	private boolean cleanPersonalLegacy;
	private boolean cleanSharedLegacy;

	@Inject
	public RecentUnlocksTracker(ConfigManager configManager, Gson gson,
		RecentUnlocksCacheStore cacheStore)
	{
		this(new ConfigManagerProfileAccess(configManager), gson, cacheStore);
	}

	RecentUnlocksTracker(ProfileAccess profileAccess, Gson gson,
		RecentUnlocksCacheStore cacheStore)
	{
		this.profileAccess = profileAccess;
		this.gson = gson;
		this.cacheStore = cacheStore;
	}

	/** Reload persisted history and await the current profile's first readable collection. */
	public synchronized void reload()
	{
		baseline = null;
		sharedBaseline = null;
		sharedSeen = new HashSet<>();
		recent = new ArrayList<>();
		sharedRecent = new ArrayList<>();
		cleanPersonalLegacy = false;
		cleanSharedLegacy = false;
		loadedProfile = profileAccess.currentProfileKey();
		if (loadedProfile == null || loadedProfile.trim().isEmpty())
		{
			return;
		}

		RecentUnlocksCacheStore.Record cached = loadCache(loadedProfile);
		List<Unlock> cachedPersonal = cached == null
			? Collections.emptyList() : cached.getPersonal();
		List<Unlock> cachedShared = cached == null
			? Collections.emptyList() : cached.getShared();

		LegacyHistory personalLegacy = loadLegacy(loadedProfile, KEY, false);
		LegacyHistory sharedLegacy = loadLegacy(loadedProfile, SHARED_KEY, true);
		if (personalLegacy.invalid || sharedLegacy.invalid)
		{
			recent = mergeHistory(cachedPersonal, personalLegacy.invalid
				? Collections.emptyList() : personalLegacy.history, false);
			sharedRecent = mergeHistory(cachedShared, sharedLegacy.invalid
				? Collections.emptyList() : sharedLegacy.history, true);
			rebuildSharedSeen();
			return;
		}
		cleanPersonalLegacy = personalLegacy.present;
		cleanSharedLegacy = sharedLegacy.present;
		recent = mergeHistory(cachedPersonal, personalLegacy.history, false);
		sharedRecent = mergeHistory(cachedShared, sharedLegacy.history, true);
		if (personalLegacy.present || sharedLegacy.present)
		{
			persist();
		}
		rebuildSharedSeen();
	}

	private RecentUnlocksCacheStore.Record loadCache(String profile)
	{
		try
		{
			return cacheStore.load(profile);
		}
		catch (IOException | RuntimeException ex)
		{
			log.warn("Could not load the local Recent Unlocks cache", ex);
			return null;
		}
	}

	private LegacyHistory loadLegacy(String profile, String key, boolean shared)
	{
		try
		{
			String raw = profileAccess.loadConfiguration(profile, key);
			if (raw == null || raw.isEmpty())
			{
				return LegacyHistory.missing();
			}
			Unlock[] loaded = gson.fromJson(raw, Unlock[].class);
			List<Unlock> history = loaded == null
				? Collections.emptyList() : Arrays.asList(loaded);
			return LegacyHistory.present(
				RecentUnlocksCacheStore.validateHistory(history, shared));
		}
		catch (IOException | RuntimeException ex)
		{
			log.warn("Could not migrate a stored Recent Unlocks history; it was kept", ex);
			return LegacyHistory.invalid();
		}
	}

	/**
	 * The API is authoritative over the config fallback. When it first arrives, take its
	 * complete collection as the baseline too, rather than mistaking a stale fallback
	 * snapshot for a batch of new unlocks.
	 */
	public synchronized void resetBaseline()
	{
		baseline = null;
	}

	/**
	 * @return true only when this observation records one or more genuinely new cards.
	 */
	public synchronized boolean update(Set<String> owned, boolean stateAvailable)
	{
		if (!stateAvailable)
		{
			return false;
		}
		if (baseline == null)
		{
			baseline = new HashSet<>(owned);
			return false;
		}

		List<String> added = owned.stream()
			.filter(name -> !baseline.contains(name))
			.sorted()
			.collect(Collectors.toList());
		baseline = new HashSet<>(owned);
		if (added.isEmpty())
		{
			return false;
		}

		long now = System.currentTimeMillis();
		for (int i = added.size() - 1; i >= 0; i--)
		{
			recent.add(0, new Unlock(added.get(i), now));
		}
		while (recent.size() > MAX_RECENT)
		{
			recent.remove(recent.size() - 1);
		}
		persist();
		return true;
	}

	public synchronized List<Unlock> getRecent()
	{
		return Collections.unmodifiableList(new ArrayList<>(recent));
	}

	/**
	 * Observe the current effective shared set. The first payload is a silent baseline; later,
	 * genuinely unseen additions are persisted newest-first. Cards withdrawn and re-offered in
	 * the same session are not presented as new again.
	 */
	public synchronized boolean updateShared(Set<String> shared)
	{
		if (sharedBaseline == null)
		{
			sharedBaseline = new HashSet<>(shared);
			sharedSeen.addAll(shared);
			return false;
		}

		List<String> added = newSharedNames(shared, sharedBaseline, sharedSeen);
		sharedBaseline = new HashSet<>(shared);
		sharedSeen.addAll(shared);
		if (added.isEmpty())
		{
			return false;
		}

		long now = System.currentTimeMillis();
		for (int i = added.size() - 1; i >= 0; i--)
		{
			sharedRecent.add(0, new Unlock(added.get(i), now, true));
		}
		while (sharedRecent.size() > MAX_RECENT)
		{
			sharedRecent.remove(sharedRecent.size() - 1);
		}
		persist();
		return true;
	}

	private void persist()
	{
		String currentProfile = profileAccess.currentProfileKey();
		if (loadedProfile == null || !Objects.equals(loadedProfile, currentProfile))
		{
			return;
		}
		try
		{
			cacheStore.save(loadedProfile, recent, sharedRecent);
			RecentUnlocksCacheStore.Record verified = cacheStore.load(loadedProfile);
			if (verified == null || !sameHistory(recent, verified.getPersonal())
				|| !sameHistory(sharedRecent, verified.getShared()))
			{
				throw new IOException("Recent Unlocks cache verification failed");
			}
			if (!Objects.equals(loadedProfile, profileAccess.currentProfileKey()))
			{
				return;
			}
			cleanupLegacyValues();
		}
		catch (IOException | RuntimeException ex)
		{
			log.warn("Could not save the local Recent Unlocks cache or finish migration", ex);
		}
	}

	private void rebuildSharedSeen()
	{
		sharedSeen.clear();
		for (Unlock unlock : sharedRecent)
		{
			sharedSeen.add(unlock.name);
		}
	}

	private void cleanupLegacyValues()
	{
		if (cleanPersonalLegacy)
		{
			profileAccess.unsetConfiguration(loadedProfile, KEY);
			cleanPersonalLegacy = false;
		}
		if (cleanSharedLegacy)
		{
			profileAccess.unsetConfiguration(loadedProfile, SHARED_KEY);
			cleanSharedLegacy = false;
		}
	}

	static List<Unlock> mergeHistory(List<Unlock> cached, List<Unlock> legacy,
		boolean shared)
	{
		Map<String, Unlock> newestByName = new LinkedHashMap<>();
		mergeInto(newestByName, cached, shared);
		mergeInto(newestByName, legacy, shared);
		List<Unlock> merged = new ArrayList<>(newestByName.values());
		merged.sort(Comparator.comparingLong((Unlock unlock) -> unlock.time).reversed()
			.thenComparing(unlock -> unlock.name));
		if (merged.size() > MAX_RECENT)
		{
			merged = new ArrayList<>(merged.subList(0, MAX_RECENT));
		}
		return merged;
	}

	private static void mergeInto(Map<String, Unlock> newestByName, List<Unlock> history,
		boolean shared)
	{
		for (Unlock unlock : history)
		{
			String name = unlock.name.trim().toLowerCase(java.util.Locale.ROOT);
			Unlock current = newestByName.get(name);
			if (current == null || unlock.time > current.time)
			{
				newestByName.put(name, new Unlock(name, unlock.time, shared));
			}
		}
	}

	static boolean sameHistory(List<Unlock> left, List<Unlock> right)
	{
		if (left.size() != right.size())
		{
			return false;
		}
		for (int i = 0; i < left.size(); i++)
		{
			Unlock a = left.get(i);
			Unlock b = right.get(i);
			if (!a.name.equals(b.name) || a.time != b.time || a.shared != b.shared)
			{
				return false;
			}
		}
		return true;
	}

	static List<String> newSharedNames(Set<String> shared, Set<String> baseline, Set<String> seen)
	{
		return shared.stream()
			.filter(name -> !baseline.contains(name) && !seen.contains(name))
			.sorted()
			.collect(Collectors.toList());
	}

	public synchronized List<Unlock> getSharedRecent()
	{
		return Collections.unmodifiableList(new ArrayList<>(sharedRecent));
	}

	/** One locally observed unlock. The name is normalized just like TcgCollectionReader. */
	public static class Unlock
	{
		final String name;
		final long time;
		final boolean shared;

		Unlock(String name, long time)
		{
			this(name, time, false);
		}

		Unlock(String name, long time, boolean shared)
		{
			this.name = name;
			this.time = time;
			this.shared = shared;
		}
	}

	interface ProfileAccess
	{
		String currentProfileKey();

		String loadConfiguration(String profile, String key);

		void unsetConfiguration(String profile, String key);
	}

	private static final class ConfigManagerProfileAccess implements ProfileAccess
	{
		private final ConfigManager configManager;

		private ConfigManagerProfileAccess(ConfigManager configManager)
		{
			this.configManager = configManager;
		}

		@Override
		public String currentProfileKey()
		{
			return configManager.getRSProfileKey();
		}

		@Override
		public String loadConfiguration(String profile, String key)
		{
			return configManager.getConfiguration(BronzemanTcgConfig.GROUP, profile, key);
		}

		@Override
		public void unsetConfiguration(String profile, String key)
		{
			configManager.unsetConfiguration(BronzemanTcgConfig.GROUP, profile, key);
		}
	}

	private static final class LegacyHistory
	{
		private final boolean present;
		private final boolean invalid;
		private final List<Unlock> history;

		private LegacyHistory(boolean present, boolean invalid, List<Unlock> history)
		{
			this.present = present;
			this.invalid = invalid;
			this.history = history;
		}

		private static LegacyHistory missing()
		{
			return new LegacyHistory(false, false, Collections.emptyList());
		}

		private static LegacyHistory present(List<Unlock> history)
		{
			return new LegacyHistory(true, false, history);
		}

		private static LegacyHistory invalid()
		{
			return new LegacyHistory(true, true, Collections.emptyList());
		}
	}
}
