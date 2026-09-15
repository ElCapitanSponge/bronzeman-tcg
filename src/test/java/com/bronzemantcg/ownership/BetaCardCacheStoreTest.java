package com.bronzemantcg.ownership;

import com.bronzemantcg.util.CacheFiles;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BetaCardCacheStoreTest
{
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void storesOnlyValidatedDataUnderAHashedProfileFilename() throws Exception
	{
		Path directory = temporaryFolder.newFolder().toPath();
		BetaCardCacheStore store = new BetaCardCacheStore(new Gson(), directory);
		String profile = "profile-key-with-player-name";

		store.save(profile, "TzTokKickCat", 5, 1234L,
			List.of("Water rune pack", "Coin pouch"));

		Path expected = directory.resolve(CacheFiles.sha256Hex(profile) + ".json");
		assertTrue(Files.exists(expected));
		assertFalse(expected.getFileName().toString().contains("player"));
		assertFalse(Files.readString(expected, StandardCharsets.UTF_8).contains(profile));
		BetaCardCacheStore.Record loaded = store.load(profile);
		assertEquals("TzTokKickCat", loaded.getDisplayName());
		assertEquals(5, loaded.getRevision());
		assertEquals(1234L, loaded.getSavedAtEpochMillis());
		assertEquals(List.of("Water rune pack", "Coin pouch"), loaded.getCardNames());
	}

	@Test
	public void validEmptyBetaCollectionRoundTrips() throws Exception
	{
		BetaCardCacheStore store = new BetaCardCacheStore(new Gson(),
			temporaryFolder.newFolder().toPath());

		store.save("profile", "Felmeme", 56, 1234L, Collections.emptyList());

		assertTrue(store.load("profile").getCardNames().isEmpty());
	}

	@Test
	public void invalidReplacementCannotOverwriteAValidCache() throws Exception
	{
		BetaCardCacheStore store = new BetaCardCacheStore(new Gson(),
			temporaryFolder.newFolder().toPath());
		store.save("profile", "Player", 1, 1L, List.of("Water rune"));

		assertThrows(Exception.class,
			() -> store.save("profile", "Player", 2, 2L,
				List.of("Water rune", "water RUNE")));

		assertEquals(List.of("Water rune"), store.load("profile").getCardNames());
		store.delete("profile");
		assertNull(store.load("profile"));
	}
}
