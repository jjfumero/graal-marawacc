/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
 */
package com.oracle.graal.virtual.phases.ea;

import java.lang.reflect.*;
import java.util.*;

import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;

/**
 * An {@link EffectList} can be used to maintain a list of {@link Effect}s and backtrack to a
 * previous state by truncating the list. It can also maintain a level for each effect, which helps
 * in creating a string representation for the list.
 */
public class EffectList implements Iterable<EffectList.Effect> {

    public abstract static class Effect {

        public boolean isVisible() {
            return true;
        }

        @Override
        public String toString() {
            StringBuilder str = new StringBuilder();
            for (Field field : getClass().getDeclaredFields()) {
                String name = field.getName();
                if (name.contains("$")) {
                    name = name.substring(name.indexOf('$') + 1);
                }
                if (!Modifier.isStatic(field.getModifiers()) && !name.equals("0")) {
                    try {
                        field.setAccessible(true);
                        str.append(str.length() > 0 ? ", " : "").append(name).append("=").append(format(field.get(this)));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            return name() + " [" + str + "]";
        }

        private static String format(Object object) {
            if (object != null && Object[].class.isAssignableFrom(object.getClass())) {
                return Arrays.toString((Object[]) object);
            }
            return "" + object;
        }

        public abstract String name();

        public abstract void apply(StructuredGraph graph, ArrayList<Node> obsoleteNodes);
    }

    private Effect[] effects = new Effect[16];
    private int[] level = new int[16];
    private int size;
    private int currentLevel;

    public void add(Effect effect) {
        if (effects.length == size) {
            effects = Arrays.copyOf(effects, effects.length * 2);
            level = Arrays.copyOf(level, effects.length);
        }
        level[size] = currentLevel;
        effects[size++] = effect;
    }

    public void addAll(Collection<? extends Effect> list) {
        int length = effects.length;
        if (size + list.size() > length) {
            while (size + list.size() > length) {
                length *= 2;
            }
            effects = Arrays.copyOf(effects, length);
            level = Arrays.copyOf(level, effects.length);
        }
        for (Effect effect : list) {
            level[size] = currentLevel;
            effects[size++] = effect;
        }
    }

    public void addAll(EffectList list) {
        int length = effects.length;
        if (size + list.size > length) {
            while (size + list.size > length) {
                length *= 2;
            }
            effects = Arrays.copyOf(effects, length);
            level = Arrays.copyOf(level, effects.length);
        }
        for (Effect effect : list) {
            level[size] = currentLevel;
            effects[size++] = effect;
        }
    }

    public int checkpoint() {
        return size;
    }

    public int size() {
        return size;
    }

    public void backtrack(int checkpoint) {
        assert checkpoint <= size;
        size = checkpoint;
    }

    @Override
    public Iterator<Effect> iterator() {
        return new Iterator<Effect>() {

            int index;
            final int listSize = EffectList.this.size;

            @Override
            public boolean hasNext() {
                return index < listSize;
            }

            @Override
            public Effect next() {
                return effects[index++];
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    public void incLevel() {
        currentLevel++;
    }

    public void decLevel() {
        currentLevel--;
    }

    public Effect get(int index) {
        if (index >= size) {
            throw new IndexOutOfBoundsException();
        }
        return effects[index];
    }

    public int levelAt(int index) {
        if (index >= size) {
            throw new IndexOutOfBoundsException();
        }
        return level[index];
    }

    public void clear() {
        size = 0;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder();
        for (int i = 0; i < size(); i++) {
            Effect effect = get(i);
            if (effect.isVisible()) {
                for (int i2 = 0; i2 < levelAt(i); i2++) {
                    str.append("    ");
                }
                str.append(effect).toString();
            }
        }
        return str.toString();
    }
}
