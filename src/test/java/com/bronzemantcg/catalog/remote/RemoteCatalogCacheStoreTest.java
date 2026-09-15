package com.bronzemantcg.catalog.remote;

import com.bronzemantcg.ownership.ActiveCardIdentityCatalog;
import com.bronzemantcg.ownership.BundledCardIdentityCatalog;
import com.bronzemantcg.ownership.CardEntityKind;
import com.bronzemantcg.ownership.CardIdentity;
import com.bronzemantcg.ownership.CardOwnershipService;
import com.bronzemantcg.ownership.CardResolver;
import com.bronzemantcg.ownership.ImmutableCardIdentityCatalog;
import com.bronzemantcg.ownership.TcgOwnershipSnapshot;
import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RemoteCatalogCacheStoreTest
{
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void roundTripsOnlyTheValidatedIdentitySnapshotAndEtag() throws Exception
	{
		Gson gson = new Gson();
		OsrsTcgCatalogSnapshot snapshot = fixture(gson);
		Path file = temporaryFolder.newFolder().toPath().resolve("catalogue.json");
		RemoteCatalogCacheStore store = new RemoteCatalogCacheStore(gson, file);

		store.save(snapshot, "sha256:old", "\"sha256:old\"", 1000L);
		store.save(snapshot, "sha256:test", "\"sha256:test\"", 1234L);
		RemoteCatalogCacheStore.CachedCatalog loaded = store.load();

		assertEquals("sha256:test", loaded.getVersion());
		assertEquals("\"sha256:test\"", loaded.getEtag());
		assertEquals(1234L, loaded.getSavedAtEpochMillis());
		assertEquals(snapshot.size(), loaded.getSnapshot().size());
		assertEquals("Water rune", loaded.getSnapshot()
			.findById(CardEntityKind.ITEM, 12730).get(0).getCardName());
		String cacheJson = Files.readString(file, StandardCharsets.UTF_8);
		assertFalse(cacheJson.contains("imagePath"));
		assertFalse(cacheJson.contains("examine"));
		assertTrue(cacheJson.contains("ownedNameRequiredEntityIds"));
	}

	@Test
	public void rejectsMalformedCacheWithoutCreatingAReplacement() throws Exception
	{
		Path file = temporaryFolder.newFolder().toPath().resolve("catalogue.json");
		Files.writeString(file, "{broken", StandardCharsets.UTF_8);
		RemoteCatalogCacheStore store = new RemoteCatalogCacheStore(new Gson(), file);

		assertThrows(CatalogValidationException.class, store::load);
		assertEquals("{broken", Files.readString(file, StandardCharsets.UTF_8));
	}

	@Test
	public void rejectsCachedEntityIdAssignedToTwoParents() throws Exception
	{
		Gson gson = new Gson();
		CardIdentity first = new CardIdentity(CardEntityKind.ITEM, "First",
			Set.of(42));
		CardIdentity second = new CardIdentity(CardEntityKind.ITEM, "Second",
			Set.of(42));
		OsrsTcgCatalogSnapshot ambiguous = new OsrsTcgCatalogSnapshot(List.of(
			new ImmutableCardIdentityCatalog.Entry(first, Set.of("First")),
			new ImmutableCardIdentityCatalog.Entry(second, Set.of("Second"))));
		Path file = temporaryFolder.newFolder().toPath().resolve("catalogue.json");
		RemoteCatalogCacheStore store = new RemoteCatalogCacheStore(gson, file);
		store.save(ambiguous, "sha256:ambiguous", null, 1234L);

		assertThrows(CatalogValidationException.class, store::load);
	}

	@Test
	public void cachedTeakIdentityPreservesMainParentAndVariantOwnership() throws Exception
	{
		Gson gson = new Gson();
		CardIdentity teak = new CardIdentity(CardEntityKind.ITEM, "Teak logs",
			Collections.emptySet(), Set.of(6333, 6211));
		OsrsTcgCatalogSnapshot snapshot = new OsrsTcgCatalogSnapshot(List.of(
			new ImmutableCardIdentityCatalog.Entry(teak,
				Set.of("Teak logs", "Teak pyre logs"))));
		Path file = temporaryFolder.newFolder().toPath().resolve("catalogue.json");
		RemoteCatalogCacheStore store = new RemoteCatalogCacheStore(gson, file);
		store.save(snapshot, "sha256:teak", "\"sha256:teak\"", 1234L);
		RemoteCatalogCacheStore.CachedCatalog loaded = store.load();

		ActiveCardIdentityCatalog active = new ActiveCardIdentityCatalog(
			new BundledCardIdentityCatalog(gson));
		active.activate(loaded.getSnapshot(), loaded.getSnapshot().getEntries(),
			loaded.getVersion());
		CardOwnershipService ownership = new CardOwnershipService(new CardResolver(active));
		TcgOwnershipSnapshot empty = TcgOwnershipSnapshot.fromApi(
			Collections.emptyList(), Collections.emptyList(),
			Collections.emptyList(), null);

		assertStatus(CardOwnershipService.Status.LOCKED,
			ownership.decide(CardEntityKind.ITEM, 6333, "Teak logs", empty, null, null));
		assertStatus(CardOwnershipService.Status.LOCKED,
			ownership.decide(CardEntityKind.ITEM, 6211, "Teak pyre logs",
				empty, null, null));

		TcgOwnershipSnapshot parentOwned = TcgOwnershipSnapshot.fromApi(
			Collections.emptyList(), Collections.singletonList(6333),
			Collections.emptyList(), null);
		assertStatus(CardOwnershipService.Status.OWNED,
			ownership.decide(CardEntityKind.ITEM, 6211, "Teak pyre logs",
				parentOwned, null, null));

		TcgOwnershipSnapshot variantOwned = TcgOwnershipSnapshot.fromApi(
			Collections.emptyList(), Collections.singletonList(6211),
			Collections.emptyList(), null);
		assertStatus(CardOwnershipService.Status.OWNED,
			ownership.decide(CardEntityKind.ITEM, 6333, "Teak logs",
				variantOwned, null, null));
	}

	private static void assertStatus(CardOwnershipService.Status expected,
		CardOwnershipService.Decision decision)
	{
		assertEquals(expected, decision.getStatus());
	}

	private static OsrsTcgCatalogSnapshot fixture(Gson gson) throws Exception
	{
		try (InputStreamReader reader = new InputStreamReader(
			RemoteCatalogCacheStoreTest.class.getResourceAsStream(
				"/osrs-tcg-live-catalog-fixture.json"), StandardCharsets.UTF_8))
		{
			return new OsrsTcgCatalogParser(gson).parse(reader);
		}
	}
}
