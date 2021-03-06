/*
 * Copyright 2020 FormDev Software GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.formdev.flatlaf.extras;

import java.awt.*;
import java.awt.event.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Predicate;
import java.util.prefs.Preferences;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.icons.FlatAbstractIcon;
import com.formdev.flatlaf.ui.FlatBorder;
import com.formdev.flatlaf.ui.FlatEmptyBorder;
import com.formdev.flatlaf.ui.FlatLineBorder;
import com.formdev.flatlaf.ui.FlatMarginBorder;
import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.GrayFilter;
import com.formdev.flatlaf.util.HSLColor;
import com.formdev.flatlaf.util.ScaledEmptyBorder;
import com.formdev.flatlaf.util.UIScale;

/**
 * A simple UI defaults inspector that shows a window with all UI defaults used
 * in current look and feel.
 * <p>
 * To use it in an application install it with:
 * <pre>
 * FlatUIDefaultsInspector.install( "ctrl shift alt Y" );
 * </pre>
 * This can be done e.g. in the main() method and allows enabling (and disabling)
 * the UI defaults inspector with the given keystroke.
 *
 * @author Karl Tauber
 */
public class FlatUIDefaultsInspector
{
	private static final int KEY_MODIFIERS_MASK = InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK;

	private static FlatUIDefaultsInspector inspector;

	private final String title;
	private final PropertyChangeListener lafListener = this::lafChanged;
	private final PropertyChangeListener lafDefaultsListener = this::lafDefaultsChanged;
	private boolean refreshPending;

	/**
	 * Installs a key listener into the application that allows enabling and disabling
	 * the UI inspector with the given keystroke (e.g. "ctrl shift alt Y").
	 */
	public static void install( String activationKeys ) {
		KeyStroke keyStroke = KeyStroke.getKeyStroke( activationKeys );
		Toolkit.getDefaultToolkit().addAWTEventListener( e -> {
			if( e.getID() == KeyEvent.KEY_RELEASED &&
				((KeyEvent)e).getKeyCode() == keyStroke.getKeyCode() &&
				(((KeyEvent)e).getModifiersEx() & KEY_MODIFIERS_MASK) == (keyStroke.getModifiers() & KEY_MODIFIERS_MASK)  )
			{
				show();
			}
		}, AWTEvent.KEY_EVENT_MASK );
	}

	public static void show() {
		if( inspector != null ) {
			inspector.ensureOnScreen();
			inspector.frame.toFront();
			return;
		}

		inspector = new FlatUIDefaultsInspector();
		inspector.frame.setVisible( true );
	}

	public static void hide() {
		if( inspector != null )
			inspector.frame.dispose();
	}

	private FlatUIDefaultsInspector() {
		initComponents();

		title = frame.getTitle();
		updateWindowTitle();

		panel.setBorder( new ScaledEmptyBorder( 10, 10, 10, 10 ) );
		filterPanel.setBorder( new ScaledEmptyBorder( 0, 0, 10, 0 ) );

		// initialize filter
		filterField.getDocument().addDocumentListener( new DocumentListener() {
			@Override
			public void removeUpdate( DocumentEvent e ) {
				filterChanged();
			}
			@Override
			public void insertUpdate( DocumentEvent e ) {
				filterChanged();
			}
			@Override
			public void changedUpdate( DocumentEvent e ) {
				filterChanged();
			}
		} );
		delegateKey( KeyEvent.VK_UP, "unitScrollUp" );
		delegateKey( KeyEvent.VK_DOWN, "unitScrollDown" );
		delegateKey( KeyEvent.VK_PAGE_UP, "scrollUp" );
		delegateKey( KeyEvent.VK_PAGE_DOWN, "scrollDown" );

		// initialize table
		table.setModel( new ItemsTableModel( getUIDefaultsItems() ) );
		table.setDefaultRenderer( String.class, new KeyRenderer() );
		table.setDefaultRenderer( Item.class, new ValueRenderer() );
		table.getRowSorter().setSortKeys( Collections.singletonList(
			new RowSorter.SortKey( 0, SortOrder.ASCENDING ) ) );

		// restore window bounds
		Preferences prefs = getPrefs();
		int x = prefs.getInt( "x", -1 );
		int y = prefs.getInt( "y", -1 );
		int width = prefs.getInt( "width", UIScale.scale( 600 ) );
		int height = prefs.getInt( "height", UIScale.scale( 800 ) );
		frame.setSize( width, height );
		if( x != -1 && y != -1 ) {
			frame.setLocation( x, y );
			ensureOnScreen();
		} else
			frame.setLocationRelativeTo( null );

		// restore column widths
		TableColumnModel columnModel = table.getColumnModel();
		columnModel.getColumn( 0 ).setPreferredWidth( prefs.getInt( "column1width", 100 ) );
		columnModel.getColumn( 1 ).setPreferredWidth( prefs.getInt( "column2width", 100 ) );

		// restore filter
		String filter = prefs.get( "filter", "" );
		String valueType = prefs.get( "valueType", null );
		if( filter != null && !filter.isEmpty() )
			filterField.setText( filter );
		if( valueType != null )
			valueTypeField.setSelectedItem( valueType );

		UIManager.addPropertyChangeListener( lafListener );
		UIManager.getDefaults().addPropertyChangeListener( lafDefaultsListener );

		// register F5 key to refresh
		((JComponent)frame.getContentPane()).registerKeyboardAction(
			e -> refresh(),
			KeyStroke.getKeyStroke( KeyEvent.VK_F5, 0, false ),
			JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT );

		// register ESC key to close frame
		((JComponent)frame.getContentPane()).registerKeyboardAction(
			e -> frame.dispose(),
			KeyStroke.getKeyStroke( KeyEvent.VK_ESCAPE, 0, false ),
			JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT );
	}

	private void delegateKey( int keyCode, String actionKey ) {
		KeyStroke keyStroke = KeyStroke.getKeyStroke( keyCode, 0 );
		String actionMapKey = "delegate-" + actionKey;

		filterField.getInputMap().put( keyStroke, actionMapKey );
		filterField.getActionMap().put( actionMapKey, new AbstractAction() {
			@Override
			public void actionPerformed( ActionEvent e ) {
				Action action = scrollPane.getActionMap().get( actionKey );
				if( action != null ) {
					action.actionPerformed( new ActionEvent( scrollPane,
						e.getID(), actionKey, e.getWhen(), e.getModifiers() ) );
				}
			}
		} );
	}

	private void ensureOnScreen() {
		Rectangle frameBounds = frame.getBounds();
		boolean onScreen = false;
		for( GraphicsDevice screen : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices() ) {
			GraphicsConfiguration gc = screen.getDefaultConfiguration();
			Rectangle screenBounds = FlatUIUtils.subtractInsets( gc.getBounds(),
				Toolkit.getDefaultToolkit().getScreenInsets( gc ) );
			if( frameBounds.intersects( screenBounds ) ) {
				onScreen = true;
				break;
			}
		}

		if( !onScreen )
			frame.setLocationRelativeTo( null );
	}

	void lafChanged( PropertyChangeEvent e ) {
		if( "lookAndFeel".equals( e.getPropertyName() ) )
			refresh();
	}

	void lafDefaultsChanged( PropertyChangeEvent e ) {
		if( refreshPending )
			return;

		refreshPending = true;
		EventQueue.invokeLater( () -> {
			refresh();
			refreshPending = false;
		} );
	}

	void refresh() {
		ItemsTableModel model = (ItemsTableModel) table.getModel();
		model.setItems( getUIDefaultsItems() );

		updateWindowTitle();
	}

	private Item[] getUIDefaultsItems() {
		UIDefaults defaults = UIManager.getDefaults();
		UIDefaults lafDefaults = UIManager.getLookAndFeelDefaults();

		Set<Entry<Object, Object>> defaultsSet = defaults.entrySet();
		ArrayList<Item> items = new ArrayList<>( defaultsSet.size() );
		HashSet<Object> keys = new HashSet<>( defaultsSet.size() );
		for( Entry<Object,Object> e : defaultsSet ) {
			Object key = e.getKey();

			// ignore non-string keys
			if( !(key instanceof String) )
				continue;

			// ignore values of type Class
			Object value = defaults.get( key );
			if( value instanceof Class )
				continue;

			// avoid duplicate keys if UIManager.put(key,value) was used to override a LaF value
			if( !keys.add( key ) )
				continue;

			// check whether key was overridden using UIManager.put(key,value)
			Object lafValue = null;
			if( defaults.containsKey( key ) )
				lafValue = lafDefaults.get( key );

			// add item
			items.add( new Item( String.valueOf( key ), value, lafValue ) );
		}

		return items.toArray( new Item[items.size()] );
	}

	private void updateWindowTitle() {
		frame.setTitle( title + "  -  " + UIManager.getLookAndFeel().getName() );
	}

	private void saveWindowBounds() {
		Preferences prefs = getPrefs();
		prefs.putInt( "x", frame.getX() );
		prefs.putInt( "y", frame.getY() );
		prefs.putInt( "width", frame.getWidth() );
		prefs.putInt( "height", frame.getHeight() );

		TableColumnModel columnModel = table.getColumnModel();
		prefs.putInt( "column1width", columnModel.getColumn( 0 ).getWidth() );
		prefs.putInt( "column2width", columnModel.getColumn( 1 ).getWidth() );
	}

	private Preferences getPrefs() {
		return Preferences.userRoot().node( "flatlaf-uidefaults-inspector" );
	}

	private void windowClosed() {
		UIManager.removePropertyChangeListener( lafListener );
		UIManager.getDefaults().removePropertyChangeListener( lafDefaultsListener );

		inspector = null;
	}

	private void filterChanged() {
		String filter = filterField.getText().trim();
		String valueType = (String) valueTypeField.getSelectedItem();

		// split filter string on space characters
		String[] filters = filter.split( " +" );
		for( int i = 0; i < filters.length; i++ )
			filters[i] = filters[i].toLowerCase( Locale.ENGLISH );

		ItemsTableModel model = (ItemsTableModel) table.getModel();
		model.setFilter( item -> {
			if( valueType != null &&
				!valueType.equals( "(any)" ) &&
				!valueType.equals( typeOfValue( item.value ) ) )
			  return false;

			String lkey = item.key.toLowerCase( Locale.ENGLISH );
			String lvalue = item.getValueAsString().toLowerCase( Locale.ENGLISH );
			for( String f : filters ) {
				if( lkey.contains( f ) || lvalue.contains( f ) )
					return true;
			}
			return false;
		} );

		Preferences prefs = getPrefs();
		prefs.put( "filter", filter );
		prefs.put( "valueType", valueType );
	}

	private String typeOfValue( Object value ) {
		if( value instanceof Boolean )
			return "Boolean";
		if( value instanceof Border )
			return "Border";
		if( value instanceof Color )
			return "Color";
		if( value instanceof Dimension )
			return "Dimension";
		if( value instanceof Float )
			return "Float";
		if( value instanceof Font )
			return "Font";
		if( value instanceof Icon )
			return "Icon";
		if( value instanceof Insets )
			return "Insets";
		if( value instanceof Integer )
			return "Integer";
		if( value instanceof String )
			return "String";
		return "(other)";
	}

	private void initComponents() {
		// JFormDesigner - Component initialization - DO NOT MODIFY  //GEN-BEGIN:initComponents
		frame = new JFrame();
		panel = new JPanel();
		filterPanel = new JPanel();
		flterLabel = new JLabel();
		filterField = new JTextField();
		valueTypeLabel = new JLabel();
		valueTypeField = new JComboBox<>();
		scrollPane = new JScrollPane();
		table = new JTable();

		//======== frame ========
		{
			frame.setTitle("UI Defaults Inspector");
			frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
			frame.addWindowListener(new WindowAdapter() {
				@Override
				public void windowClosed(WindowEvent e) {
					FlatUIDefaultsInspector.this.windowClosed();
				}
				@Override
				public void windowClosing(WindowEvent e) {
					saveWindowBounds();
				}
				@Override
				public void windowDeactivated(WindowEvent e) {
					saveWindowBounds();
				}
			});
			Container frameContentPane = frame.getContentPane();
			frameContentPane.setLayout(new BorderLayout());

			//======== panel ========
			{
				panel.setLayout(new BorderLayout());

				//======== filterPanel ========
				{
					filterPanel.setLayout(new GridBagLayout());
					((GridBagLayout)filterPanel.getLayout()).columnWidths = new int[] {0, 0, 0, 0, 0};
					((GridBagLayout)filterPanel.getLayout()).rowHeights = new int[] {0, 0};
					((GridBagLayout)filterPanel.getLayout()).columnWeights = new double[] {0.0, 1.0, 0.0, 0.0, 1.0E-4};
					((GridBagLayout)filterPanel.getLayout()).rowWeights = new double[] {0.0, 1.0E-4};

					//---- flterLabel ----
					flterLabel.setText("Filter:");
					flterLabel.setLabelFor(filterField);
					flterLabel.setDisplayedMnemonic('F');
					filterPanel.add(flterLabel, new GridBagConstraints(0, 0, 1, 1, 0.0, 0.0,
						GridBagConstraints.CENTER, GridBagConstraints.BOTH,
						new Insets(0, 0, 0, 10), 0, 0));

					//---- filterField ----
					filterField.putClientProperty("JTextField.placeholderText", "enter one or more filter strings, separated by space characters");
					filterPanel.add(filterField, new GridBagConstraints(1, 0, 1, 1, 0.0, 0.0,
						GridBagConstraints.CENTER, GridBagConstraints.BOTH,
						new Insets(0, 0, 0, 10), 0, 0));

					//---- valueTypeLabel ----
					valueTypeLabel.setText("Value Type:");
					valueTypeLabel.setLabelFor(valueTypeField);
					valueTypeLabel.setDisplayedMnemonic('T');
					filterPanel.add(valueTypeLabel, new GridBagConstraints(2, 0, 1, 1, 0.0, 0.0,
						GridBagConstraints.CENTER, GridBagConstraints.BOTH,
						new Insets(0, 0, 0, 10), 0, 0));

					//---- valueTypeField ----
					valueTypeField.setModel(new DefaultComboBoxModel<>(new String[] {
						"(any)",
						"Boolean",
						"Border",
						"Color",
						"Dimension",
						"Float",
						"Font",
						"Icon",
						"Insets",
						"Integer",
						"String",
						"(other)"
					}));
					valueTypeField.addActionListener(e -> filterChanged());
					filterPanel.add(valueTypeField, new GridBagConstraints(3, 0, 1, 1, 0.0, 0.0,
						GridBagConstraints.CENTER, GridBagConstraints.BOTH,
						new Insets(0, 0, 0, 0), 0, 0));
				}
				panel.add(filterPanel, BorderLayout.NORTH);

				//======== scrollPane ========
				{

					//---- table ----
					table.setAutoCreateRowSorter(true);
					scrollPane.setViewportView(table);
				}
				panel.add(scrollPane, BorderLayout.CENTER);
			}
			frameContentPane.add(panel, BorderLayout.CENTER);
		}
		// JFormDesigner - End of component initialization  //GEN-END:initComponents
	}

	// JFormDesigner - Variables declaration - DO NOT MODIFY  //GEN-BEGIN:variables
	private JFrame frame;
	private JPanel panel;
	private JPanel filterPanel;
	private JLabel flterLabel;
	private JTextField filterField;
	private JLabel valueTypeLabel;
	private JComboBox<String> valueTypeField;
	private JScrollPane scrollPane;
	private JTable table;
	// JFormDesigner - End of variables declaration  //GEN-END:variables

	//---- class Item ---------------------------------------------------------

	private static class Item {
		final String key;
		final Object value;
		final Object lafValue;

		private String valueStr;

		Item( String key, Object value, Object lafValue ) {
			this.key = key;
			this.value = value;
			this.lafValue = lafValue;
		}

		String getValueAsString() {
			if( valueStr == null )
				valueStr = valueAsString( value );
			return valueStr;
		}

		static String valueAsString( Object value ) {
			if( value instanceof Color ) {
				Color color = (Color) value;
				HSLColor hslColor = new HSLColor( color );
				if( color.getAlpha() == 255 ) {
					return String.format( "%s    rgb(%d, %d, %d)    hsl(%d, %d, %d)",
						color2hex( color ),
						color.getRed(), color.getGreen(), color.getBlue(),
						(int) hslColor.getHue(), (int) hslColor.getSaturation(),
						(int) hslColor.getLuminance() );
				} else {
					return String.format( "%s   rgba(%d, %d, %d, %d)    hsla(%d, %d, %d, %d)",
						color2hex( color ),
						color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha(),
						(int) hslColor.getHue(), (int) hslColor.getSaturation(),
						(int) hslColor.getLuminance(), (int) (hslColor.getAlpha() * 100) );
				}
			} else if( value instanceof Insets ) {
				Insets insets = (Insets) value;
				return insets.top + "," + insets.left + "," + insets.bottom + "," + insets.right;
			} else if( value instanceof Dimension ) {
				Dimension dim = (Dimension) value;
				return dim.width + "," + dim.height;
			} else if( value instanceof Font ) {
				Font font = (Font) value;
				String s = font.getFamily() + " " + font.getSize();
				if( font.isBold() )
					s += " bold";
				if( font.isItalic() )
					s += " italic";
				return s;
			} else if( value instanceof Icon ) {
				Icon icon = (Icon) value;
				return icon.getIconWidth() + "x" + icon.getIconHeight() + "   " + icon.getClass().getName();
			} else if( value instanceof Border ) {
				Border border = (Border) value;
				if( border instanceof FlatLineBorder ) {
					FlatLineBorder lineBorder = (FlatLineBorder) border;
					return valueAsString( lineBorder.getUnscaledBorderInsets() )
						+ "  " + Item.color2hex( lineBorder.getLineColor() )
						+ "  " + lineBorder.getLineThickness()
						+ "    " + border.getClass().getName();
				} else if( border instanceof EmptyBorder ) {
					Insets insets = (border instanceof FlatEmptyBorder)
						? ((FlatEmptyBorder)border).getUnscaledBorderInsets()
						: ((EmptyBorder)border).getBorderInsets();
					return valueAsString( insets ) + "    " + border.getClass().getName();
				} else if( border instanceof FlatBorder || border instanceof FlatMarginBorder )
					return border.getClass().getName();
				else
					return String.valueOf( value );
			} else if( value instanceof GrayFilter ) {
				GrayFilter grayFilter = (GrayFilter) value;
				return grayFilter.getBrightness() + "," + grayFilter.getContrast()
					+ " " + grayFilter.getAlpha() + "    " + grayFilter.getClass().getName();
			} else if( value instanceof ActionMap ) {
				ActionMap actionMap = (ActionMap) value;
				return "ActionMap (" + actionMap.size() + ")";
			} else if( value instanceof InputMap ) {
				InputMap inputMap = (InputMap) value;
				return "InputMap (" + inputMap.size() + ")";
			} else if( value instanceof Object[] )
				return Arrays.toString( (Object[]) value );
			else if( value instanceof int[] )
				return Arrays.toString( (int[]) value );
			else
				return String.valueOf( value );
		}

		private static String color2hex( Color color ) {
			int rgb = color.getRGB();
			boolean hasAlpha = color.getAlpha() != 255;

			boolean useShortFormat =
				(rgb & 0xf0000000) == (rgb & 0xf000000) << 4 &&
				(rgb & 0xf00000) == (rgb & 0xf0000) << 4 &&
				(rgb & 0xf000) == (rgb & 0xf00) << 4 &&
				(rgb & 0xf0) == (rgb & 0xf) << 4;

			if( useShortFormat ) {
				int srgb = ((rgb & 0xf0000) >> 8) | ((rgb & 0xf00) >> 4) | (rgb & 0xf);
				return String.format( hasAlpha ? "#%03X%X" : "#%03X", srgb, (rgb >> 24) & 0xf );
			} else
				return String.format( hasAlpha ? "#%06X%02X" : "#%06X", rgb & 0xffffff, (rgb >> 24) & 0xff );
		}

		// used for sorting by value
		@Override
		public String toString() {
			return getValueAsString();
		}
	}

	//---- class ItemsTableModel ----------------------------------------------

	private static class ItemsTableModel
		extends AbstractTableModel
	{
		private Item[] allItems;
		private Item[] items;
		private Predicate<Item> filter;

		ItemsTableModel( Item[] items ) {
			this.allItems = this.items = items;
		}

		void setItems( Item[] items ) {
			this.allItems = this.items = items;
			setFilter( filter );
		}

		void setFilter( Predicate<Item> filter ) {
			this.filter = filter;

			if( filter != null ) {
				ArrayList<Item> list = new ArrayList<>( allItems.length );
				for( Item item : allItems ) {
					if( filter.test( item ) )
						list.add( item );
				}
				items = list.toArray( new Item[list.size()] );
			} else
				items = allItems;

			fireTableDataChanged();
		}

		@Override
		public int getRowCount() {
			return items.length;
		}

		@Override
		public int getColumnCount() {
			return 2;
		}

		@Override
		public String getColumnName( int columnIndex ) {
			switch( columnIndex ) {
				case 0: return "Name";
				case 1: return "Value";
			}
			return super.getColumnName( columnIndex );
		}

		@Override
		public Class<?> getColumnClass( int columnIndex ) {
			switch( columnIndex ) {
				case 0: return String.class;
				case 1: return Item.class;
			}
			return super.getColumnClass( columnIndex );
		}

		@Override
		public Object getValueAt( int rowIndex, int columnIndex ) {
			Item item = items[rowIndex];
			switch( columnIndex ) {
				case 0: return item.key;
				case 1: return item;
			}
			return null;
		}
	}

	//---- class Renderer -----------------------------------------------------

	private static class Renderer
		extends DefaultTableCellRenderer
	{
		protected boolean selected;
		protected boolean first;

		protected void init( JTable table, String key, boolean selected, int row ) {
			this.selected = selected;

			first = false;
			if( row > 0 ) {
				String previousKey = (String) table.getValueAt( row - 1, 0 );
				int dot = key.indexOf( '.' );
				if( dot > 0 ) {
					String prefix = key.substring( 0, dot + 1 );
					first = !previousKey.startsWith( prefix );
				} else
					first = previousKey.indexOf( '.' ) > 0;
			}
		}

		protected void paintSeparator( Graphics g ) {
			if( first && !selected ) {
				g.setColor( FlatLaf.isLafDark() ? Color.gray : Color.lightGray );
				g.fillRect( 0, 0, getWidth() - 1, 1 );
			}
		}

		protected String layoutLabel( FontMetrics fm, String text, Rectangle textR ) {
			int width = getWidth();
			int height = getHeight();
			Insets insets = getInsets();

			Rectangle viewR = new Rectangle( insets.left, insets.top,
				width - (insets.left + insets.right),
				height - (insets.top + insets.bottom) );
			Rectangle iconR = new Rectangle();

			return SwingUtilities.layoutCompoundLabel( this, fm, text, null,
				getVerticalAlignment(), getHorizontalAlignment(),
				getVerticalTextPosition(), getHorizontalTextPosition(),
				viewR, iconR, textR, getIconTextGap() );
		}
	}

	//---- class KeyRenderer --------------------------------------------------

	private static class KeyRenderer
		extends Renderer
	{
		private String key;
		private boolean isOverridden;
		private Icon overriddenIcon;

		@Override
		public Component getTableCellRendererComponent( JTable table, Object value,
			boolean isSelected, boolean hasFocus, int row, int column )
		{
			key = (String) value;
			init( table, key, isSelected, row );

			Item item = (Item) table.getValueAt( row, 1 );
			isOverridden = (item.lafValue != null);

			// set tooltip
			String toolTipText = key;
			if( isOverridden )
				toolTipText += "    \n\nLaF UI default value was overridden with UIManager.put(key,value).";
			setToolTipText( toolTipText );

			return super.getTableCellRendererComponent( table, value, isSelected, hasFocus, row, column );
		}

		@Override
		protected void paintComponent( Graphics g ) {
			g.setColor( getBackground() );
			g.fillRect( 0, 0, getWidth(), getHeight() );

			FontMetrics fm = getFontMetrics( getFont() );
			Rectangle textR = new Rectangle();
			String clippedText = layoutLabel( fm, key, textR );
			int x = textR.x;
			int y = textR.y + fm.getAscent();

			int dot = key.indexOf( '.' );
			if( dot > 0 && !selected ) {
				g.setColor( UIManager.getColor( "Label.disabledForeground" ) );

				if( dot >= clippedText.length() )
					FlatUIUtils.drawString( this, g, clippedText, x, y );
				else {
					String prefix = clippedText.substring( 0, dot + 1 );
					String subkey = clippedText.substring( dot + 1 );

					FlatUIUtils.drawString( this, g, prefix, x, y );

					g.setColor( getForeground() );
					FlatUIUtils.drawString( this, g, subkey, x + fm.stringWidth( prefix ), y );
				}
			} else {
				g.setColor( getForeground() );
				FlatUIUtils.drawString( this, g, clippedText, x, y );
			}

			if( isOverridden ) {
				if( overriddenIcon == null ) {
					overriddenIcon = new FlatAbstractIcon( 16, 16, null ) {
						@Override
						protected void paintIcon( Component c, Graphics2D g2 ) {
							g2.setColor( FlatUIUtils.getUIColor( "Actions.Red", Color.red ) );
							g2.setStroke( new BasicStroke( 2f ) );
							g2.draw( FlatUIUtils.createPath( false, 3,10, 8,5, 13,10 ) );
						}
					};
				}

				overriddenIcon.paintIcon( this, g,
					getWidth() - overriddenIcon.getIconWidth(),
					(getHeight() - overriddenIcon.getIconHeight()) / 2 );
			}

			paintSeparator( g );
		}
	}

	//---- class ValueRenderer ------------------------------------------------

	private static class ValueRenderer
		extends Renderer
	{
		private Item item;

		@Override
		public Component getTableCellRendererComponent( JTable table, Object value,
			boolean isSelected, boolean hasFocus, int row, int column )
		{
			item = (Item) value;
			init( table, item.key, isSelected, row );

			// reset background, foreground and icon
			if( !(item.value instanceof Color) ) {
				setBackground( null );
				setForeground( null );
			}
			if( !(item.value instanceof Icon) )
				setIcon( null );

			// value to string
			value = item.getValueAsString();

			super.getTableCellRendererComponent( table, value, isSelected, hasFocus, row, column );

			if( item.value instanceof Color ) {
				Color color = (Color) item.value;
				boolean isDark = new HSLColor( color ).getLuminance() < 70;
				setBackground( color );
				setForeground( isDark ? Color.white : Color.black );
			} else if( item.value instanceof Icon ) {
				Icon icon = (Icon) item.value;
				setIcon( new SafeIcon( icon ) );
			}

			// set tooltip
			String toolTipText = String.valueOf( item.value );
			if( item.lafValue != null ) {
				toolTipText += "    \n\nLaF UI default value was overridden with UIManager.put(key,value):\n    "
					+ Item.valueAsString( item.lafValue ) + "\n    " + String.valueOf( item.lafValue );
			}
			setToolTipText( toolTipText );

			return this;
		}

		@Override
		protected void paintComponent( Graphics g ) {
			if( item.value instanceof Color ) {
				// fill background
				g.setColor( getBackground() );
				g.fillRect( 0, 0, getWidth(), getHeight() );

				// layout text
				FontMetrics fm = getFontMetrics( getFont() );
				String text = getText();
				Rectangle textR = new Rectangle();
				layoutLabel( fm, text, textR );
				int x = textR.x;
				int y = textR.y + fm.getAscent();

				g.setColor( getForeground() );

				// paint rgb() and hsl() horizontally aligned
				int rgbIndex = text.indexOf( "rgb" );
				int hslIndex = text.indexOf( "hsl" );
				if( rgbIndex > 0 && hslIndex > rgbIndex ) {
					String hexText = text.substring( 0, rgbIndex );
					String rgbText = text.substring( rgbIndex, hslIndex );
					String hslText = text.substring( hslIndex );
					int hexWidth = Math.max( fm.stringWidth( hexText ), fm.stringWidth( "#DDDDDD    " ) );
					int rgbWidth = Math.max( fm.stringWidth( rgbText ), fm.stringWidth( "rgb(444, 444, 444)    " ) );
					FlatUIUtils.drawString( this, g, hexText, x, y );
					FlatUIUtils.drawString( this, g, rgbText, x + hexWidth, y );
					FlatUIUtils.drawString( this, g, hslText, x + hexWidth + rgbWidth, y );
				} else
					FlatUIUtils.drawString( this, g, text, x, y );
			} else
				super.paintComponent( g );

			paintSeparator( g );
		}
	}

	//---- class SafeIcon -----------------------------------------------------

	private static class SafeIcon
		implements Icon
	{
		private final Icon icon;

		SafeIcon( Icon icon ) {
			this.icon = icon;
		}

		@Override
		public void paintIcon( Component c, Graphics g, int x, int y ) {
			int width = getIconWidth();
			int height = getIconHeight();

			try {
				g.setColor( UIManager.getColor( "Panel.background" ) );
				g.fillRect( x, y, width, height );

				icon.paintIcon( c, g, x, y );
			} catch( Exception ex ) {
				g.setColor( Color.red );
				g.drawRect( x, y, width - 1, height - 1 );
			}
		}

		@Override
		public int getIconWidth() {
			return icon.getIconWidth();
		}

		@Override
		public int getIconHeight() {
			return icon.getIconHeight();
		}
	}
}
