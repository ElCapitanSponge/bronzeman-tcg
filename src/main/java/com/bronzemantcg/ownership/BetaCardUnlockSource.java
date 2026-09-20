package com.bronzemantcg.ownership;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Reviewed v1 parent unlocks derived from the active profile's Beta-name cache. */
public interface BetaCardUnlockSource
{
	View getBetaCardUnlocks();

	final class View
	{
		private final long revision;
		private final Map<CardEntityKind, Set<String>> parentNamesLowerCase;
		private final Set<String> allParentNamesLowerCase;

		public View(long revision, Set<String> itemParentNamesLowerCase,
			Set<String> npcParentNamesLowerCase)
		{
			this.revision = revision;
			Map<CardEntityKind, Set<String>> names = new EnumMap<>(CardEntityKind.class);
			names.put(CardEntityKind.ITEM, freeze(itemParentNamesLowerCase));
			names.put(CardEntityKind.NPC, freeze(npcParentNamesLowerCase));
			parentNamesLowerCase = Collections.unmodifiableMap(names);
			Set<String> all = new LinkedHashSet<>(names.get(CardEntityKind.ITEM));
			all.addAll(names.get(CardEntityKind.NPC));
			allParentNamesLowerCase = Collections.unmodifiableSet(all);
		}

		public long getRevision()
		{
			return revision;
		}

		public Set<String> getParentNamesLowerCase()
		{
			return allParentNamesLowerCase;
		}

		public Set<String> getParentNamesLowerCase(CardEntityKind kind)
		{
			return parentNamesLowerCase.getOrDefault(kind, Collections.emptySet());
		}

		private static Set<String> freeze(Set<String> names)
		{
			return names == null || names.isEmpty()
				? Collections.emptySet()
				: Collections.unmodifiableSet(new LinkedHashSet<>(names));
		}
	}
}
