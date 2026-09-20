package com.bronzemantcg.catalog;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QuestNpcIndexTest
{
	@Test
	public void genericNpcNamesDoNotBecomeGlobalFailOpenExemptions()
	{
		assertFalse(QuestNpcIndex.isIndexableNpcName("Guard"));
		assertFalse(QuestNpcIndex.isIndexableNpcName(" guard "));
		assertTrue(QuestNpcIndex.isIndexableNpcName("General Bentnoze"));
	}
}
