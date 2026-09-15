package com.bronzemantcg.panel.collection;

import com.bronzemantcg.ownership.CardEntityKind;
import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PanelCollectionLayoutTest
{
	private static final Set<String> FINALIZED_HISTORICAL_NAMES = Set.of(
		"Blighted bind sack", "Blighted snare sack", "Blighted wave sack",
		"Brewer's folly", "Church lectern", "Cook's letter", "Dead person",
		"Dwarf cake", "Gnome cake", "Goblin cake", "Raisins", "Vyvin's wine",
		"Emissary Forebearer (unused)", "Golem (unused NPC)");

	@Test
	public void rejectsInvalidCategoryReferencesAsAnEmptyLayout()
	{
		PanelCollectionLayout layout = new PanelCollectionLayout(
			new Gson(), "/panel/malformed_collection_layout.json");

		assertTrue(layout.getSections().isEmpty());
		assertTrue(layout.getCollectionPlacements().isEmpty());
		assertTrue(layout.getBetaCollectionCards().isEmpty());
	}

	@Test
	public void rejectsNullJsonRowsAsAnEmptyLayout()
	{
		PanelCollectionLayout layout = new PanelCollectionLayout(
			new Gson(), "/panel/null_collection_placement_layout.json");

		assertTrue(layout.getSections().isEmpty());
		assertTrue(layout.getCollectionPlacements().isEmpty());
		assertTrue(layout.getBetaCollectionCards().isEmpty());
	}

	@Test
	public void loadsSlimGeneratedProductionLayout()
	{
		PanelCollectionLayout layout = new PanelCollectionLayout(new Gson());

		assertEquals("sha256:062cfd93a66d2b8268c45cd58ae68c63cad4b2dbe9049f8d9cb4626f6b677e77",
			layout.getOrganiserFingerprint());
		assertEquals("73EB7023F008A64737C20AD9E78F81F57025EA25AD9786E3A0AB2A82214C58D6",
			layout.getOrganiserProjectSha256());
		assertEquals(24, layout.getSections().size());
		assertEquals(4992, layout.getCollectionPlacements().size());
		assertEquals(3601, layout.getCollectionPlacements().stream()
			.filter(card -> card.getKind() == CardEntityKind.ITEM).count());
		assertEquals(1391, layout.getCollectionPlacements().stream()
			.filter(card -> card.getKind() == CardEntityKind.NPC).count());
		assertFalse(layout.getCollectionPlacements().stream()
			.anyMatch(card -> card.getCategoryIds().isEmpty()));
		assertEquals(5576, layout.getBetaCollectionCards().size());
		assertEquals(6376, layout.getBetaCollectionCards().stream()
			.mapToInt(card -> card.getVariants().size()).sum());
		assertEquals(1089, layout.getBetaCollectionCards().stream()
			.filter(PanelCollectionLayout.BetaCollectionCard::isBetaOnly).count());

		placement(layout, CardEntityKind.NPC, "Akkha");
		placement(layout, CardEntityKind.NPC, "The Wardens");
		placement(layout, CardEntityKind.ITEM, "Helm of Neitiznot");
		PanelCollectionLayout.BetaCollectionCard betaAkkha = betaParent(layout, "Akkha");
		variant(betaAkkha, "Akkha's Shadow");
		assertTrue(betaParent(layout, "Akkha's Phantom").isBetaOnly());
	}

	@Test
	public void fishChunksUsesReviewedPlacementAndFinalizedEntityId()
	{
		PanelCollectionLayout layout = new PanelCollectionLayout(new Gson());
		PanelCollectionLayout.BetaCollectionCard fish = betaParent(layout, "Fish chunks");
		assertEquals(placement(layout, CardEntityKind.ITEM, "Fish chunks").getCategoryIds(),
			fish.getCategoryIds());
		assertFalse(fish.isBetaOnly());
		assertEquals("Fish chunks", fish.getVariants().get(0).getName());
		assertEquals(Set.of(22818), fish.getVariants().get(0).getEntityIds());
		assertTrue(layout.isBetaVariantNameUnique("fish chunks"));
		assertTrue(new PanelCollectionOwnership(layout).isBetaVariantOwnedByNames(
			fish.getVariants().get(0), Set.of("fish chunks")));
	}

	@Test
	public void finalizedHistoricalRowsHaveExactNamesKindsAndVisiblePlacements()
	{
		PanelCollectionLayout layout = new PanelCollectionLayout(new Gson());
		Map<CardEntityKind, List<String>> expected = Map.of(
			CardEntityKind.ITEM, List.of("Blighted bind sack", "Blighted snare sack",
				"Blighted wave sack", "Brewer's folly", "Church lectern", "Cook's letter",
				"Dead person", "Dwarf cake", "Gnome cake", "Goblin cake", "Raisins", "Vyvin's wine"),
			CardEntityKind.NPC, List.of("Emissary Forebearer (unused)", "Golem (unused NPC)"));
		expected.forEach((kind, names) -> names.forEach(name ->
		{
			PanelCollectionLayout.BetaCollectionCard card = betaParent(layout, name);
			String category = "category-needs-review-beta-only-"
				+ (kind == CardEntityKind.ITEM ? "items" : "npcs");
			assertEquals(kind, card.getKind());
			assertEquals(Set.of(category), card.getCategoryIds());
			assertTrue(card.isBetaOnly());
			assertTrue(card.isVisible());
			assertEquals(1, card.getVariants().size());
			assertEquals(name, card.getVariants().get(0).getName());
			assertEquals(kind, card.getVariants().get(0).getKind());
			assertTrue(card.getVariants().get(0).getEntityIds().isEmpty());
			assertTrue(layout.isBetaVariantNameUnique(name));
			assertTrue(layout.getSections().stream().filter(PanelCollectionLayout.Section::isVisible)
				.flatMap(section -> section.getCategories().stream())
				.anyMatch(row -> row.isVisible() && row.getId().equals(category)));
		}));
	}

	@Test
	public void finalizedRowsRemainAlphabeticalWithinEachReviewedCategory()
	{
		PanelCollectionLayout layout = new PanelCollectionLayout(new Gson());
		Map<String, Integer> totals = Map.of("items", 430, "npcs", 45);
		totals.forEach((suffix, total) ->
		{
			List<String> names = layout.getBetaCollectionCards().stream()
				.filter(card -> card.getCategoryIds().contains("category-needs-review-beta-only-" + suffix))
				.map(PanelCollectionLayout.BetaCollectionCard::getParentName).collect(Collectors.toList());
			assertEquals(total.intValue(), names.size());
			layout.getBetaCollectionCards().stream()
				.filter(card -> FINALIZED_HISTORICAL_NAMES.contains(card.getParentName())
					&& card.getCategoryIds().contains(
						"category-needs-review-beta-only-" + suffix))
				.forEach(card ->
				{
					int index = names.indexOf(card.getParentName());
					assertTrue(index == 0 || String.CASE_INSENSITIVE_ORDER.compare(
						names.get(index - 1), card.getParentName()) < 0);
					assertTrue(index == names.size() - 1
						|| String.CASE_INSENSITIVE_ORDER.compare(
							card.getParentName(), names.get(index + 1)) < 0);
				});
		});
		assertEquals(layout.getBetaCollectionCards().size(), layout.getBetaCollectionCards().stream()
			.map(PanelCollectionLayout.BetaCollectionCard::getKey).distinct().count());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void collectionPlacementsContainNoBundledV1IdentityData()
	{
		Map<String, Object> raw = new Gson().fromJson(new InputStreamReader(
			Objects.requireNonNull(getClass().getResourceAsStream(
				"/panel/collection_layout.json")), StandardCharsets.UTF_8), Map.class);
		List<Map<String, Object>> placements =
			(List<Map<String, Object>>) raw.get("collectionPlacements");

		assertNotNull(placements);
		assertFalse(placements.isEmpty());
		assertTrue(placements.stream().noneMatch(row -> row.containsKey("entityIds")));
		assertTrue(placements.stream().noneMatch(row -> row.containsKey("acceptedNames")));
		assertFalse(raw.containsKey("collectionCards"));
	}

	@Test
	public void betaVariantUniquenessPreservesHistoricalIdentitySafety()
	{
		PanelCollectionLayout layout = fixture();
		PanelCollectionOwnership ownership = new PanelCollectionOwnership(layout);
		PanelCollectionLayout.BetaVariant item = betaVariant(
			layout, CardEntityKind.ITEM, "Manta ray", "Manta ray");
		PanelCollectionLayout.BetaVariant npc = betaVariant(
			layout, CardEntityKind.NPC, "Manta ray", "Manta ray");

		assertFalse(layout.isBetaVariantNameUnique("Manta ray"));
		assertFalse(ownership.isBetaVariantOwnedByNames(item, Set.of("manta ray")));
		assertFalse(ownership.isBetaVariantOwnedByNames(npc, Set.of("manta ray")));
		assertFalse(layout.isBetaEntityIdUnique(CardEntityKind.NPC, 14706));
	}

	private static PanelCollectionLayout fixture()
	{
		return new PanelCollectionLayout(new Gson(), "/panel/test_collection_layout.json");
	}

	private static PanelCollectionLayout.CollectionPlacement placement(
		PanelCollectionLayout layout, CardEntityKind kind, String name)
	{
		PanelCollectionLayout.CollectionPlacement result = layout.getCollectionPlacements().stream()
			.filter(card -> card.getKind() == kind && card.getCardName().equals(name))
			.findFirst().orElse(null);
		assertNotNull(result);
		return result;
	}

	private static PanelCollectionLayout.BetaCollectionCard betaParent(
		PanelCollectionLayout layout, String name)
	{
		PanelCollectionLayout.BetaCollectionCard result = layout.getBetaCollectionCards().stream()
			.filter(card -> card.getParentName().equals(name)).findFirst().orElse(null);
		assertNotNull(result);
		return result;
	}

	private static PanelCollectionLayout.BetaVariant variant(
		PanelCollectionLayout.BetaCollectionCard card, String name)
	{
		PanelCollectionLayout.BetaVariant result = card.getVariants().stream()
			.filter(value -> value.getName().equals(name)).findFirst().orElse(null);
		assertNotNull(result);
		return result;
	}

	private static PanelCollectionLayout.BetaVariant betaVariant(
		PanelCollectionLayout layout, CardEntityKind kind, String parentName, String variantName)
	{
		PanelCollectionLayout.BetaCollectionCard parent = layout.getBetaCollectionCards().stream()
			.filter(card -> card.getKind() == kind && card.getParentName().equals(parentName))
			.findFirst().orElse(null);
		assertNotNull(parent);
		return variant(parent, variantName);
	}
}
