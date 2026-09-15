package com.bronzemantcg.panel;

import java.awt.Dimension;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BronzemanTcgPanelSearchLayoutTest
{
	@Test
	public void resultHeightCollapsesAndStopsGrowingAfterTenRows()
	{
		JPanel results = new JPanel();
		assertEquals(0, BronzemanTcgPanel.visibleSearchResultsHeight(results, 10));

		addRows(results, 3, 20);
		assertEquals(60, BronzemanTcgPanel.visibleSearchResultsHeight(results, 10));

		addRows(results, 7, 20);
		assertEquals(200, BronzemanTcgPanel.visibleSearchResultsHeight(results, 10));

		addRows(results, 2, 20);
		assertEquals(200, BronzemanTcgPanel.visibleSearchResultsHeight(results, 10));
	}

	@Test
	public void invalidVisibleRowLimitCollapsesResults()
	{
		JPanel results = new JPanel();
		addRows(results, 1, 20);

		assertEquals(0, BronzemanTcgPanel.visibleSearchResultsHeight(results, 0));
		assertEquals(0, BronzemanTcgPanel.visibleSearchResultsHeight(results, -1));
		assertEquals(0, BronzemanTcgPanel.visibleSearchResultsHeight(null, 10));
	}

	@Test
	public void resultPaneUsesBoundedVerticalScrollingOnly()
	{
		JPanel results = new JPanel();
		results.setLayout(new BoxLayout(results, BoxLayout.Y_AXIS));
		addRows(results, 12, 20);
		JScrollPane scroll = new BronzemanTcgPanel.SearchResultsScrollPane(results, 10);
		scroll.setBorder(null);

		assertEquals(200, scroll.getPreferredSize().height);
		assertEquals(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			scroll.getVerticalScrollBarPolicy());
		assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
			scroll.getHorizontalScrollBarPolicy());
	}

	private static void addRows(JPanel results, int count, int height)
	{
		for (int i = 0; i < count; i++)
		{
			JPanel row = new JPanel();
			row.setPreferredSize(new Dimension(100, height));
			results.add(row);
		}
	}
}
