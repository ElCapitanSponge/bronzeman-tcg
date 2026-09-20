package com.bronzemantcg.restriction;

import com.bronzemantcg.ownership.BundledCardIdentityCatalog;
import com.bronzemantcg.ownership.BetaCardUnlockSource;
import com.bronzemantcg.ownership.CardOwnershipService;
import com.bronzemantcg.ownership.CardResolver;
import com.bronzemantcg.ownership.TcgOwnershipSnapshot;
import com.google.gson.Gson;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** Shared mutable decision sources for focused tests; never part of the plugin JAR. */
public final class RestrictionDecisionTestSupport
{
	private RestrictionDecisionTestSupport()
	{
	}

	public static Harness harness()
	{
		return harness(new CardOwnershipService(new CardResolver(
			new BundledCardIdentityCatalog(new Gson()))));
	}

	public static Harness harness(CardOwnershipService ownershipService)
	{
		return harness(ownershipService, () -> 0L);
	}

	public static Harness harness(CardOwnershipService ownershipService,
		LongSupplier catalogRevision)
	{
		MutableSources sources = new MutableSources();
		MutableBetaUnlockSource betaUnlocks = new MutableBetaUnlockSource();
		RestrictionDecisionService service =
			new RestrictionDecisionService(sources, ownershipService, betaUnlocks,
				catalogRevision);
		return new Harness(sources, betaUnlocks, service);
	}

	public static final class Harness
	{
		private final MutableSources sources;
		private final MutableBetaUnlockSource betaUnlocks;
		private final RestrictionDecisionService service;

		private Harness(MutableSources sources, MutableBetaUnlockSource betaUnlocks,
			RestrictionDecisionService service)
		{
			this.sources = sources;
			this.betaUnlocks = betaUnlocks;
			this.service = service;
		}

		public RestrictionDecisionService getService()
		{
			return service;
		}

		public Harness ownership(TcgOwnershipSnapshot ownership)
		{
			sources.ownership = ownership;
			return this;
		}

		public Harness stateAvailable(boolean available)
		{
			sources.stateAvailable = available;
			return this;
		}

		public Harness acceptShared(boolean accept)
		{
			sources.acceptShared = accept;
			return this;
		}

		public Harness shared(Set<String> shared)
		{
			sources.shared = shared;
			return this;
		}

		public Harness betaItems(Set<String> parentNames)
		{
			betaUnlocks.items = parentNames;
			betaUnlocks.update();
			return this;
		}

		public Harness betaNpcs(Set<String> parentNames)
		{
			betaUnlocks.npcs = parentNames;
			betaUnlocks.update();
			return this;
		}

		public Harness configuredExempt(Set<String> exempt)
		{
			sources.configuredExempt = exempt;
			return this;
		}

		public Harness coinsExempt(boolean exempt)
		{
			sources.coinsExempt = exempt;
			return this;
		}

		public Harness tutorialProgress(int progress)
		{
			sources.tutorialProgress = progress;
			return this;
		}

		public Harness lmsState(int state)
		{
			sources.lmsState = state;
			return this;
		}

		public Harness mapRegions(int... regions)
		{
			sources.mapRegions = regions;
			return this;
		}

		public Harness itemName(int id, String name)
		{
			sources.itemNames.put(id, name);
			return this;
		}

		public Harness canonicalItemId(int runtimeId, int canonicalId)
		{
			sources.canonicalItemIds.put(runtimeId, canonicalId);
			return this;
		}

		public int getItemNameCalls()
		{
			return sources.itemNameCalls;
		}
	}

	private static final class MutableBetaUnlockSource implements BetaCardUnlockSource
	{
		private long revision;
		private Set<String> items = Collections.emptySet();
		private Set<String> npcs = Collections.emptySet();
		private View view = new View(0L, items, npcs);

		@Override
		public View getBetaCardUnlocks()
		{
			return view;
		}

		private void update()
		{
			view = new View(++revision, items, npcs);
		}
	}

	private static final class MutableSources implements RestrictionDecisionService.Sources
	{
		private TcgOwnershipSnapshot ownership = TcgOwnershipSnapshot.namesOnly(
			Collections.emptySet());
		private boolean stateAvailable = true;
		private boolean acceptShared = true;
		private Set<String> shared = Collections.emptySet();
		private Set<String> configuredExempt = Collections.emptySet();
		private boolean coinsExempt;
		private int tutorialProgress;
		private int lmsState;
		private int[] mapRegions;
		private final Map<Integer, String> itemNames = new HashMap<>();
		private final Map<Integer, Integer> canonicalItemIds = new HashMap<>();
		private int itemNameCalls;

		@Override
		public TcgOwnershipSnapshot getOwnershipSnapshot()
		{
			return ownership;
		}

		@Override
		public boolean isOwnershipStateAvailable()
		{
			return stateAvailable;
		}

		@Override
		public boolean acceptSharedUnlocks()
		{
			return acceptShared;
		}

		@Override
		public Set<String> getSharedCardNames()
		{
			return shared;
		}

		@Override
		public Set<String> getConfiguredExemptCardNames()
		{
			return configuredExempt;
		}

		@Override
		public boolean isCoinsExempt()
		{
			return coinsExempt;
		}

		@Override
		public int getTutorialProgress()
		{
			return tutorialProgress;
		}

		@Override
		public int getLmsState()
		{
			return lmsState;
		}

		@Override
		public int[] getMapRegions()
		{
			return mapRegions;
		}

		@Override
		public int canonicalizeItemId(int itemId)
		{
			return canonicalItemIds.getOrDefault(itemId, itemId);
		}

		@Override
		public String getItemName(int itemId)
		{
			itemNameCalls++;
			return itemNames.get(itemId);
		}
	}
}
