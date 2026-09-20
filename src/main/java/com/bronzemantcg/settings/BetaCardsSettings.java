package com.bronzemantcg.settings;

import com.bronzemantcg.BronzemanTcgConfig;
import com.bronzemantcg.ownership.BetaCardCacheService;
import java.awt.Component;
import java.awt.Dimension;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;

/** Explicit lookup controls and status for the profile-scoped Beta ownership cache. */
public final class BetaCardsSettings
{
	private static final DateTimeFormatter SAVED_AT_FORMAT =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

	private final BetaCardCacheService cacheService;
	private final BronzemanTcgConfig config;
	private final Runnable changed;
	private final JPanel panel = new JPanel();

	public BetaCardsSettings(BetaCardCacheService cacheService, BronzemanTcgConfig config,
		Runnable changed)
	{
		this.cacheService = cacheService;
		this.config = config;
		this.changed = changed;
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setOpaque(false);
		refresh();
	}

	public JPanel component()
	{
		refresh();
		return panel;
	}

	private void refresh()
	{
		panel.removeAll();
		BetaCardCacheService.State state = cacheService.getState();
		status("Cached Beta names: " + state.getCachedCount());
		status("Last refresh: " + (state.getSavedAt() == null
			? "Never" : SAVED_AT_FORMAT.format(state.getSavedAt())));
		if (state.getDisplayName() != null)
		{
			status("Player: " + state.getDisplayName() + " · revision " + state.getRevision());
		}
		if (state.getMessage() != null && !state.getMessage().isEmpty())
		{
			status("<html><body style='width:190px'>" + escapeHtml(state.getMessage())
				+ "</body></html>");
		}
		if (state.getStatus() == BetaCardCacheService.Status.NO_CACHE
			&& !config.allowBetaCardLookup())
		{
			status("<html><body style='width:190px'>Enable <b>Allow Beta player lookup</b> "
				+ "in RuneLite's Bronzeman TCG plugin settings, then refresh from OSRS TCG."
				+ "</body></html>");
		}
		panel.add(Box.createVerticalStrut(4));
		boolean refreshing = state.getStatus() == BetaCardCacheService.Status.REFRESHING;
		boolean hasProfile = state.getStatus() != BetaCardCacheService.Status.NO_PROFILE;
		button(refreshing ? "Refreshing Beta Cards..." : "Refresh Beta Cards",
			cacheService::refresh,
			!refreshing && hasProfile && config.allowBetaCardLookup());
		button("Clear Cached Beta Cards", this::confirmClear,
			!refreshing && hasProfile && state.getSavedAt() != null);
		panel.revalidate();
		panel.repaint();
	}

	private void status(String text)
	{
		JLabel label = new JLabel(text);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		panel.add(label);
	}

	private void button(String title, Runnable action, boolean enabled)
	{
		JButton button = new JButton(title);
		button.setAlignmentX(Component.LEFT_ALIGNMENT);
		button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		button.setEnabled(enabled);
		button.addActionListener(event ->
		{
			action.run();
			changed.run();
			refresh();
		});
		panel.add(button);
	}

	private void confirmClear()
	{
		String text = "Clear Bronzeman's cached Beta cards for the active profile?\n"
			+ "This removes Beta-derived parent unlocks until another explicit refresh.\n"
			+ "Current OSRS TCG PluginMessage ownership and shared unlocks are not changed.";
		if (JOptionPane.showConfirmDialog(panel, text, "Clear Cached Beta Cards?",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION)
		{
			cacheService.clear();
		}
	}

	static String escapeHtml(String value)
	{
		return value.replace("&", "&amp;").replace("<", "&lt;")
			.replace(">", "&gt;").replace("\"", "&quot;");
	}
}
