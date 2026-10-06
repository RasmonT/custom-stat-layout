/*
 * Copyright (c) 2026, ImTheC4
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.imthec4.customstatlayout;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

/**
 * Side panel: the address an overlay should read, with a copy button, so a streamer
 * never has to dig the bound port out of the client log - the second client of a
 * two-account stream ends up on 5031, not 5030. Also the Discord invite.
 *
 * Only ever touched on the Swing thread; the plugin hands updates over with invokeLater.
 */
class CustomStatLayoutPanel extends PluginPanel
{
	static final String DISCORD_URL = "https://discord.gg/XgxjhyznbZ";
	/** Discord's brand colour and its darker hover shade, as in the other plugins' panels. */
	private static final Color DISCORD = new Color(0x5865F2);
	private static final Color DISCORD_HOVER = new Color(0x4752C4);
	private static final Color OK = new Color(0x3DD68C);
	private static final Color WARN = new Color(0xFFB347);
	private static final Color CARD = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color CARD_HOVER = ColorScheme.DARK_GRAY_HOVER_COLOR;

	private final JTextField address = new JTextField();
	private final JLabel copy = new JLabel("Copy address", SwingConstants.CENTER);
	private boolean canCopy;
	private final JLabel serving = new JLabel();
	private final JLabel game = new JLabel();
	private final JLabel discord = new JLabel("Join the Discord", SwingConstants.CENTER);

	CustomStatLayoutPanel()
	{
		super(false);
		setLayout(new BorderLayout(0, 10));
		setBorder(new EmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("Custom Stat Layout");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);

		// Fixed html width so the text wraps inside the sidebar instead of being clipped
		JLabel hint = new JLabel("<html><body style='width:165px'>Your overlay reads the game values"
			+ " from this address. Paste it into the overlay's settings.</body></html>");
		hint.setFont(FontManager.getRunescapeSmallFont());
		hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		address.setEditable(false);
		address.setFont(FontManager.getRunescapeSmallFont());
		address.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		address.setForeground(Color.WHITE);
		address.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));

		copy.setOpaque(true);
		copy.setBackground(CARD);
		copy.setForeground(Color.WHITE);
		copy.setFont(FontManager.getRunescapeBoldFont());
		copy.setBorder(new EmptyBorder(6, 0, 6, 0));
		copy.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		copy.setToolTipText("Copy the address to the clipboard");
		copy.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (canCopy)
				{
					Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(address.getText()), null);
					copy.setText("Copied");
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (canCopy)
				{
					copy.setBackground(CARD_HOVER);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				copy.setBackground(CARD);
			}
		});

		serving.setFont(FontManager.getRunescapeSmallFont());
		game.setFont(FontManager.getRunescapeSmallFont());

		// Stacked top to bottom at natural heights, each item full width
		JPanel top = new JPanel();
		top.setOpaque(false);
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		for (JComponent c : new JComponent[]{title, hint, address, copy, serving, game})
		{
			c.setAlignmentX(Component.LEFT_ALIGNMENT);
			if (c == address || c == copy)
			{
				c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
			}
			top.add(c);
			top.add(Box.createVerticalStrut(c == title || c == copy ? 10 : 6));
		}
		add(top, BorderLayout.NORTH);

		// Opens the browser only when clicked; the plugin itself never contacts Discord
		discord.setOpaque(true);
		discord.setBackground(DISCORD);
		discord.setForeground(Color.WHITE);
		discord.setFont(FontManager.getRunescapeBoldFont());
		discord.setBorder(new EmptyBorder(7, 0, 7, 0));
		discord.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		discord.setToolTipText("Questions, ideas or a bug? Opens " + DISCORD_URL + " in your browser");
		discord.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				LinkBrowser.browse(DISCORD_URL);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				discord.setBackground(DISCORD_HOVER);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				discord.setBackground(DISCORD);
			}
		});
		add(discord, BorderLayout.SOUTH);

		setServer(-1, -1, -1);
		setLoggedIn(false);
	}

	/**
	 * @param port the port actually bound, or -1 if none could be opened
	 * @param from first port that was tried
	 * @param to last port that was tried
	 */
	void setServer(int port, int from, int to)
	{
		copy.setText("Copy address");
		if (port > 0)
		{
			address.setText("http://127.0.0.1:" + port + "/stats.json");
			address.setCaretPosition(0);
			canCopy = true;
			copy.setForeground(Color.WHITE);
			serving.setText(port == from ? "Serving on port " + port
				: "Serving on port " + port + " (" + from + " was taken)");
			serving.setForeground(OK);
		}
		else
		{
			address.setText("not serving");
			canCopy = false;
			copy.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
			serving.setText(from > 0 ? "No free port between " + from + " and " + to : "Starting...");
			serving.setForeground(WARN);
		}
	}

	void setLoggedIn(boolean loggedIn)
	{
		game.setText(loggedIn ? "Logged in: values are live" : "At the login screen: no values yet");
		game.setForeground(loggedIn ? OK : ColorScheme.LIGHT_GRAY_COLOR);
	}
}
