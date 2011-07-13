/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */
package com.sun.hotspot.igv.graal.filters;

import com.sun.hotspot.igv.graph.Diagram;
import com.sun.hotspot.igv.graph.Figure;
import java.awt.Color;
import java.awt.LinearGradientPaint;
import java.awt.PaintContext;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.Raster;
import java.util.List;

/**
 * Filter that colors nodes using a customizable color gradient, based on how
 * a numeric property is located in a specified interval.
 * 
 * @author Peter Hofer
 */
public class GraalGradientColorFilter {

    public enum Mode {
        LINEAR,
        LOGARITHMIC
    };

    private String propertyName = "probability";
    private float minValue = 0;
    private float maxValue = 500;
    private float[] fractions = {0, 0.5f, 1};
    private Color[] colors = {Color.BLUE, Color.YELLOW, Color.RED};
    private int shadeCount = 8;
    private Mode mode = Mode.LOGARITHMIC;

    public void apply(Diagram d) {
        Rectangle bounds = new Rectangle(shadeCount, 1);
        LinearGradientPaint lgp = new LinearGradientPaint(bounds.x, bounds.y, bounds.width, bounds.y, fractions, colors);
        PaintContext context = lgp.createContext(null, bounds, bounds.getBounds2D(), AffineTransform.getTranslateInstance(0, 0), new RenderingHints(null));
        Raster raster = context.getRaster(bounds.x, bounds.y, bounds.width, bounds.height);
        int[] rgb = raster.getPixels(bounds.x, bounds.y, bounds.width, bounds.height, (int[]) null);
        Color[] shades = new Color[rgb.length / 3];
        for (int i = 0; i < shades.length; ++i) {
            shades[i] = new Color(rgb[i * 3], rgb[i * 3 + 1], rgb[i * 3 + 2]);
        }

        List<Figure> figures = d.getFigures();
        for (Figure f : figures) {
            String property = f.getProperties().get(propertyName);
            if (property != null) {
                try {
                    float value = Float.parseFloat(property);

                    Color nodeColor;
                    if (value <= minValue) {
                        nodeColor = colors[0];
                    } else if (value >= maxValue) {
                        nodeColor = colors[colors.length - 1];
                    } else {
                        double normalized = value - minValue;
                        double interval = maxValue - minValue;
                        int index;
                        // Use Math.ceil() to make values above zero distinguishable from zero
                        if (mode == Mode.LOGARITHMIC) {
                            index = (int) Math.ceil(shades.length * Math.log(1 + normalized) / Math.log(1 + interval));
                        } else if (mode == Mode.LINEAR) {
                            index = (int) Math.ceil(shades.length * normalized / interval);
                        } else {
                            throw new RuntimeException("Unknown mode");
                        }
                        nodeColor = shades[index];
                    }
                    f.setColor(nodeColor);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public String getPropertyName() {
        return propertyName;
    }

    public void setPropertyName(String propertyName) {
        this.propertyName = propertyName;
    }

    public float getMinValue() {
        return minValue;
    }

    public void setMinValue(float minValue) {
        this.minValue = minValue;
    }

    public float getMaxValue() {
        return maxValue;
    }

    public void setMaxValue(float maxValue) {
        this.maxValue = maxValue;
    }

    public float[] getFractions() {
        return fractions;
    }

    public void setFractions(float[] fractions) {
        this.fractions = fractions;
    }

    public Color[] getColors() {
        return colors;
    }

    public void setColors(Color[] colors) {
        this.colors = colors;
    }

    public int getShadeCount() {
        return shadeCount;
    }

    public void setShadeCount(int steps) {
        this.shadeCount = steps;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }
}
