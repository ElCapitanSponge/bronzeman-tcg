package com.bronzemantcg.panel;

import com.bronzemantcg.util.CacheFiles;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RecentUnlocksCacheStoreTest
{
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void storesBothHistoriesUnderOnlyAHashedProfileFilename() throws Exception
	{
		Path directory = temporaryFolder.newFolder().toPath();
		RecentUnlocksCacheStore store = new RecentUnlocksCacheStore(new Gson(), directory);
		String profile = "rsprofile.player-name";

		store.save(profile,
			List.of(new RecentUnlocksTracker.Unlock(" Dragon Axe ", 20L)),
			List.of(new RecentUnlocksTracker.Unlock("Rune Axe", 10L, true)));

		Path expected = directory.resolve(CacheFiles.sha256Hex(profile) + ".json");
		assertTrue(Files.exists(expected));
		assertFalse(expected.getFileName().toString().contains("player"));
		String json = Files.readString(expected, StandardCharsets.UTF_8);
		assertTrue(json.contains("\"schemaVersion\":1"));
		assertFalse(json.contains(profile));

		RecentUnlocksCacheStore.Record loaded = store.load(profile);
		assertHistory(loaded.getPersonal(), "dragon axe", 20L, false);
		assertHistory(loaded.getShared(), "rune axe", 10L, true);
	}

	@Test
	public void profileFilesAreIsolatedAndAtomicReplacementLeavesNoTemporaryFile() throws Exception
	{
		Path directory = temporaryFolder.newFolder().toPath();
		RecentUnlocksCacheStore store = new RecentUnlocksCacheStore(new Gson(), directory);
		store.save("profile-a", List.of(
			new RecentUnlocksTracker.Unlock("dragon axe", 20L)), Collections.emptyList());
		store.save("profile-b", List.of(
			new RecentUnlocksTracker.Unlock("rune axe", 10L)), Collections.emptyList());
		store.save("profile-a", List.of(
			new RecentUnlocksTracker.Unlock("abyssal whip", 30L)), Collections.emptyList());

		assertFalse(store.fileForProfile("profile-a").equals(store.fileForProfile("profile-b")));
		assertHistory(store.load("profile-a").getPersonal(), "abyssal whip", 30L, false);
		assertHistory(store.load("profile-b").getPersonal(), "rune axe", 10L, false);
		try (java.util.stream.Stream<Path> files = Files.list(directory))
		{
			assertEquals(2L, files.count());
		}
	}

	@Test
	public void rejectsMalformedOversizedAndIncompleteCacheFiles() throws Exception
	{
		RecentUnlocksCacheStore store = new RecentUnlocksCacheStore(new Gson(),
			temporaryFolder.newFolder().toPath());
		Files.createDirectories(store.fileForProfile("profile").getParent());

		Files.writeString(store.fileForProfile("profile"), "{broken", StandardCharsets.UTF_8);
		assertThrows(Exception.class, () -> store.load("profile"));

		Files.writeString(store.fileForProfile("profile"),
			"{\"schemaVersion\":1,\"personal\":[]}", StandardCharsets.UTF_8);
		assertThrows(Exception.class, () -> store.load("profile"));

		Files.write(store.fileForProfile("profile"), new byte[256 * 1024 + 1]);
		assertThrows(Exception.class, () -> store.load("profile"));
	}

	@Test
	public void rejectsInvalidOrOversizedHistoriesBeforeWriting() throws Exception
	{
		RecentUnlocksCacheStore store = new RecentUnlocksCacheStore(new Gson(),
			temporaryFolder.newFolder().toPath());
		assertThrows(Exception.class, () -> store.save("null-name",
			List.of(new RecentUnlocksTracker.Unlock(null, 1L)), Collections.emptyList()));
		assertThrows(Exception.class, () -> store.save("control-name",
			List.of(new RecentUnlocksTracker.Unlock("bad\nname", 1L)), Collections.emptyList()));
		assertThrows(Exception.class, () -> store.save("negative-time",
			List.of(new RecentUnlocksTracker.Unlock("dragon axe", -1L)), Collections.emptyList()));
		assertThrows(Exception.class, () -> store.save("wrong-kind",
			List.of(new RecentUnlocksTracker.Unlock("dragon axe", 1L, true)),
			Collections.emptyList()));

		List<RecentUnlocksTracker.Unlock> tooMany = new ArrayList<>();
		for (int i = 0; i <= RecentUnlocksCacheStore.MAX_RECENT; i++)
		{
			tooMany.add(new RecentUnlocksTracker.Unlock("card " + i, i));
		}
		assertThrows(Exception.class, () -> store.save("too-many", tooMany,
			Collections.emptyList()));
		assertFalse(Files.exists(store.fileForProfile("too-many")));
	}

	private static void assertHistory(List<RecentUnlocksTracker.Unlock> history,
		String name, long time, boolean shared)
	{
		assertEquals(1, history.size());
		assertEquals(name, history.get(0).name);
		assertEquals(time, history.get(0).time);
		assertEquals(shared, history.get(0).shared);
	}
}
