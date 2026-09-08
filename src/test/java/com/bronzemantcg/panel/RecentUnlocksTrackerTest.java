package com.bronzemantcg.panel;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RecentUnlocksTrackerTest
{
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void recentDiffIgnoresBaselineSeenAndReconnects()
	{
		Set<String> baseline = set("dragon axe");
		Set<String> seen = set("dragon axe", "rune axe");
		assertEquals(Collections.singletonList("abyssal whip"),
			RecentUnlocksTracker.newSharedNames(
				set("dragon axe", "rune axe", "abyssal whip"), baseline, seen));
		assertTrue(RecentUnlocksTracker.newSharedNames(
			set("dragon axe", "rune axe"), Collections.emptySet(), seen).isEmpty());
	}

	@Test
	public void reloadMigratesBothLegacyHistoriesThenRemovesTheirKeys() throws Exception
	{
		Gson gson = new Gson();
		TestProfileAccess profiles = new TestProfileAccess("profile-one");
		profiles.put("recentUnlocks", gson.toJson(List.of(
			new RecentUnlocksTracker.Unlock("dragon axe", 20L))));
		profiles.put("recentSharedUnlocks", gson.toJson(List.of(
			new RecentUnlocksTracker.Unlock("rune axe", 10L, true))));
		RecentUnlocksCacheStore store = store(gson);
		RecentUnlocksTracker tracker = new RecentUnlocksTracker(profiles, gson, store);

		tracker.reload();

		assertNull(profiles.get("recentUnlocks"));
		assertNull(profiles.get("recentSharedUnlocks"));
		assertTrue(Files.exists(store.fileForProfile("profile-one")));
		assertHistory(tracker.getRecent(), "dragon axe", 20L, false);
		assertHistory(tracker.getSharedRecent(), "rune axe", 10L, true);

		RecentUnlocksTracker reloaded = new RecentUnlocksTracker(profiles, gson, store);
		reloaded.reload();
		assertHistory(reloaded.getRecent(), "dragon axe", 20L, false);
		assertHistory(reloaded.getSharedRecent(), "rune axe", 10L, true);
	}

	@Test
	public void migrationMergesByNormalizedNameAndKeepsNewestTimestamp() throws Exception
	{
		Gson gson = new Gson();
		TestProfileAccess profiles = new TestProfileAccess("profile-one");
		RecentUnlocksCacheStore store = store(gson);
		store.save("profile-one", List.of(
			new RecentUnlocksTracker.Unlock("dragon axe", 20L),
			new RecentUnlocksTracker.Unlock("rune axe", 5L)), Collections.emptyList());
		profiles.put("recentUnlocks", gson.toJson(List.of(
			new RecentUnlocksTracker.Unlock(" Dragon Axe ", 30L),
			new RecentUnlocksTracker.Unlock("abyssal whip", 10L))));
		RecentUnlocksTracker tracker = new RecentUnlocksTracker(profiles, gson, store);

		tracker.reload();

		List<RecentUnlocksTracker.Unlock> merged = tracker.getRecent();
		assertEquals(3, merged.size());
		assertUnlock(merged.get(0), "dragon axe", 30L, false);
		assertUnlock(merged.get(1), "abyssal whip", 10L, false);
		assertUnlock(merged.get(2), "rune axe", 5L, false);
		assertNull(profiles.get("recentUnlocks"));
		assertTrue(RecentUnlocksTracker.sameHistory(
			merged, store.load("profile-one").getPersonal()));
	}

	@Test
	public void invalidLegacyHistoryKeepsBothLegacyValuesAndExistingCache() throws Exception
	{
		Gson gson = new Gson();
		TestProfileAccess profiles = new TestProfileAccess("profile-one");
		String invalidPersonal = "{broken";
		String validShared = gson.toJson(List.of(
			new RecentUnlocksTracker.Unlock("rune axe", 10L, true)));
		profiles.put("recentUnlocks", invalidPersonal);
		profiles.put("recentSharedUnlocks", validShared);
		RecentUnlocksCacheStore store = store(gson);
		store.save("profile-one", List.of(
			new RecentUnlocksTracker.Unlock("dragon axe", 20L)), Collections.emptyList());
		RecentUnlocksTracker tracker = new RecentUnlocksTracker(profiles, gson, store);

		tracker.reload();

		assertEquals(invalidPersonal, profiles.get("recentUnlocks"));
		assertEquals(validShared, profiles.get("recentSharedUnlocks"));
		assertHistory(tracker.getRecent(), "dragon axe", 20L, false);
		assertHistory(tracker.getSharedRecent(), "rune axe", 10L, true);
		assertHistory(store.load("profile-one").getPersonal(), "dragon axe", 20L, false);
		assertTrue(store.load("profile-one").getShared().isEmpty());
	}

	@Test
	public void failedCacheWriteKeepsTheValidLegacyValue() throws Exception
	{
		Gson gson = new Gson();
		TestProfileAccess profiles = new TestProfileAccess("profile-one");
		String legacy = gson.toJson(List.of(
			new RecentUnlocksTracker.Unlock("dragon axe", 20L)));
		profiles.put("recentUnlocks", legacy);
		Path nonDirectory = temporaryFolder.newFile().toPath();
		RecentUnlocksTracker tracker = new RecentUnlocksTracker(profiles, gson,
			new RecentUnlocksCacheStore(gson, nonDirectory));

		tracker.reload();

		assertEquals(legacy, profiles.get("recentUnlocks"));
		assertHistory(tracker.getRecent(), "dragon axe", 20L, false);
	}

	@Test
	public void profileChangeAfterReadbackKeepsLegacyValue() throws Exception
	{
		Gson gson = new Gson();
		TestProfileAccess profiles = new TestProfileAccess("profile-one");
		String legacy = gson.toJson(List.of(
			new RecentUnlocksTracker.Unlock("dragon axe", 20L)));
		profiles.put("recentUnlocks", legacy);
		profiles.switchProfileOnCurrentCall(3, "profile-two");
		RecentUnlocksCacheStore store = store(gson);
		RecentUnlocksTracker tracker = new RecentUnlocksTracker(profiles, gson, store);

		tracker.reload();

		assertEquals("profile-two", profiles.currentProfileKey());
		assertEquals(legacy, profiles.get("profile-one", "recentUnlocks"));
		assertTrue(Files.exists(store.fileForProfile("profile-one")));
	}

	@Test
	public void laterUnlocksWriteOnlyToTheLocalCache() throws Exception
	{
		Gson gson = new Gson();
		TestProfileAccess profiles = new TestProfileAccess("profile-one");
		RecentUnlocksCacheStore store = store(gson);
		RecentUnlocksTracker tracker = new RecentUnlocksTracker(profiles, gson, store);
		tracker.reload();
		tracker.update(set("dragon axe"), true);

		assertTrue(tracker.update(set("dragon axe", "rune axe"), true));

		assertNull(profiles.get("recentUnlocks"));
		assertHistory(store.load("profile-one").getPersonal(), "rune axe",
			tracker.getRecent().get(0).time, false);
	}

	@Test
	public void profileAtoBtoAKeepsHistoriesIsolated() throws Exception
	{
		Gson gson = new Gson();
		TestProfileAccess profiles = new TestProfileAccess("profile-a");
		RecentUnlocksCacheStore store = store(gson);
		RecentUnlocksTracker tracker = new RecentUnlocksTracker(profiles, gson, store);

		tracker.reload();
		tracker.update(set("dragon axe"), true);
		assertTrue(tracker.update(set("dragon axe", "rune axe"), true));

		profiles.setProfile("profile-b");
		tracker.reload();
		tracker.update(set("abyssal whip"), true);
		assertTrue(tracker.update(set("abyssal whip", "dragon scimitar"), true));
		assertEquals("dragon scimitar", tracker.getRecent().get(0).name);

		profiles.setProfile("profile-a");
		tracker.reload();
		assertEquals("rune axe", tracker.getRecent().get(0).name);
		assertFalse(store.fileForProfile("profile-a").equals(store.fileForProfile("profile-b")));
	}

	private RecentUnlocksCacheStore store(Gson gson) throws Exception
	{
		return new RecentUnlocksCacheStore(gson, temporaryFolder.newFolder().toPath());
	}

	private static void assertHistory(List<RecentUnlocksTracker.Unlock> history,
		String name, long time, boolean shared)
	{
		assertEquals(1, history.size());
		assertUnlock(history.get(0), name, time, shared);
	}

	private static void assertUnlock(RecentUnlocksTracker.Unlock unlock,
		String name, long time, boolean shared)
	{
		assertEquals(name, unlock.name);
		assertEquals(time, unlock.time);
		assertEquals(shared, unlock.shared);
	}

	private static Set<String> set(String... values)
	{
		return new HashSet<>(Arrays.asList(values));
	}

	private static final class TestProfileAccess implements RecentUnlocksTracker.ProfileAccess
	{
		private final Map<String, Map<String, String>> values = new HashMap<>();
		private String profile;
		private int currentCalls;
		private int switchOnCall = -1;
		private String switchTo;

		private TestProfileAccess(String profile)
		{
			this.profile = profile;
		}

		@Override
		public String currentProfileKey()
		{
			currentCalls++;
			if (currentCalls == switchOnCall)
			{
				profile = switchTo;
			}
			return profile;
		}

		@Override
		public String loadConfiguration(String activeProfile, String key)
		{
			return values.getOrDefault(activeProfile, Collections.emptyMap()).get(key);
		}

		@Override
		public void unsetConfiguration(String activeProfile, String key)
		{
			values.computeIfAbsent(activeProfile, ignored -> new HashMap<>()).remove(key);
		}

		private void put(String key, String value)
		{
			values.computeIfAbsent(profile, ignored -> new HashMap<>()).put(key, value);
		}

		private String get(String key)
		{
			return loadConfiguration(profile, key);
		}

		private String get(String activeProfile, String key)
		{
			return loadConfiguration(activeProfile, key);
		}

		private void setProfile(String profile)
		{
			this.profile = profile;
			currentCalls = 0;
			switchOnCall = -1;
			switchTo = null;
		}

		private void switchProfileOnCurrentCall(int call, String profile)
		{
			switchOnCall = call;
			switchTo = profile;
		}
	}
}
