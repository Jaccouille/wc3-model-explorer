package com.hiveworkshop.ui;

import com.hiveworkshop.model.TeamColorOptions;

import javax.swing.*;
import java.awt.*;
import java.util.function.IntFunction;

final class TeamColorComboRenderer extends DefaultListCellRenderer {
    private static final int SWATCH_SIZE = 12;

    private final JComboBox<String> comboBox;
    private final IntFunction<int[]> rgbResolver;

    TeamColorComboRenderer(JComboBox<String> comboBox, IntFunction<int[]> rgbResolver) {
        this.comboBox = comboBox;
        this.rgbResolver = rgbResolver;
        setIconTextGap(8);
    }

    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                  boolean isSelected, boolean cellHasFocus) {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
        int colorIndex = index >= 0 ? index : comboBox.getSelectedIndex();
        int[] rgb = rgbResolver.apply(TeamColorOptions.clampIndex(colorIndex));
        setIcon(new ColorSwatchIcon(new Color(rgb[0], rgb[1], rgb[2])));
        return this;
    }

    private static final class ColorSwatchIcon implements Icon {
        private final Color color;

        private ColorSwatchIcon(Color color) {
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            g.setColor(color);
            g.fillRect(x, y, SWATCH_SIZE, SWATCH_SIZE);
            g.setColor(new Color(0, 0, 0, 160));
            g.drawRect(x, y, SWATCH_SIZE - 1, SWATCH_SIZE - 1);
        }

        @Override
        public int getIconWidth() {
            return SWATCH_SIZE;
        }

        @Override
        public int getIconHeight() {
            return SWATCH_SIZE;
        }
    }
}
