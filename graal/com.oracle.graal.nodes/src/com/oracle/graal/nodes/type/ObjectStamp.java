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
package com.oracle.graal.nodes.type;

import java.lang.reflect.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.nodes.*;

public class ObjectStamp extends Stamp {

    private final ResolvedJavaType type;
    private final boolean exactType;
    private final boolean nonNull;
    private final boolean alwaysNull;

    public ObjectStamp(ResolvedJavaType type, boolean exactType, boolean nonNull, boolean alwaysNull) {
        super(Kind.Object);
        this.type = type;
        this.exactType = exactType;
        this.nonNull = nonNull;
        this.alwaysNull = alwaysNull;
    }

    @Override
    public ResolvedJavaType javaType(MetaAccessProvider metaAccess) {
        if (type != null) {
            return type;
        }
        return metaAccess.lookupJavaType(Object.class);
    }

    public boolean nonNull() {
        return nonNull;
    }

    public boolean alwaysNull() {
        return alwaysNull;
    }

    public ResolvedJavaType type() {
        return type;
    }

    public boolean isExactType() {
        return exactType;
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder();
        str.append(kind().getTypeChar());
        str.append(nonNull ? "!" : "").append(exactType ? "#" : "").append(' ').append(type == null ? "-" : type.getName()).append(alwaysNull ? " NULL" : "");
        return str.toString();
    }

    @Override
    public Stamp meet(Stamp otherStamp) {
        if (this == otherStamp) {
            return this;
        }
        if (otherStamp instanceof IllegalStamp) {
            return otherStamp.meet(this);
        }
        if (!(otherStamp instanceof ObjectStamp)) {
            return StampFactory.illegal();
        }
        ObjectStamp other = (ObjectStamp) otherStamp;
        ResolvedJavaType meetType;
        boolean meetExactType;
        boolean meetNonNull;
        boolean meetAlwaysNull;
        if (other.alwaysNull) {
            meetType = type();
            meetExactType = exactType;
            meetNonNull = false;
            meetAlwaysNull = alwaysNull;
        } else if (alwaysNull) {
            meetType = other.type();
            meetExactType = other.exactType;
            meetNonNull = false;
            meetAlwaysNull = other.alwaysNull;
        } else {
            meetType = meetTypes(type(), other.type());
            meetExactType = meetType == type && meetType == other.type && exactType && other.exactType;
            meetNonNull = nonNull && other.nonNull;
            meetAlwaysNull = false;
        }

        if (meetType == type && meetExactType == exactType && meetNonNull == nonNull && meetAlwaysNull == alwaysNull) {
            return this;
        } else if (meetType == other.type && meetExactType == other.exactType && meetNonNull == other.nonNull && meetAlwaysNull == other.alwaysNull) {
            return other;
        } else {
            return new ObjectStamp(meetType, meetExactType, meetNonNull, meetAlwaysNull);
        }
    }

    @Override
    public Stamp join(Stamp otherStamp) {
        return join0(otherStamp, false);
    }

    public Stamp castTo(ObjectStamp to) {
        return join0(to, true);
    }

    public Stamp join0(Stamp otherStamp, boolean castToOther) {
        if (this == otherStamp) {
            return this;
        }
        if (otherStamp instanceof IllegalStamp) {
            return otherStamp.join(this);
        }
        if (!(otherStamp instanceof ObjectStamp)) {
            return StampFactory.illegal();
        }
        ObjectStamp other = (ObjectStamp) otherStamp;
        ResolvedJavaType joinType;
        boolean joinAlwaysNull = alwaysNull || other.alwaysNull;
        boolean joinNonNull = nonNull || other.nonNull;
        if (joinAlwaysNull && joinNonNull) {
            return StampFactory.illegal();
        }
        boolean joinExactType = exactType || other.exactType;
        if (type == other.type) {
            joinType = type;
        } else if (type == null && other.type == null) {
            joinType = null;
            if (joinExactType) {
                return StampFactory.illegal();
            }
        } else if (type == null) {
            joinType = other.type;
        } else if (other.type == null) {
            joinType = type;
        } else {
            // both types are != null and different
            if (type.isAssignableFrom(other.type)) {
                joinType = other.type;
                if (exactType) {
                    joinAlwaysNull = true;
                }
            } else if (other.type.isAssignableFrom(type)) {
                joinType = type;
                if (other.exactType) {
                    joinAlwaysNull = true;
                }
            } else {
                if (castToOther) {
                    joinType = other.type;
                    joinExactType = other.exactType;
                } else {
                    joinType = null;
                    if (joinExactType || (!type.isInterface() && !other.type.isInterface())) {
                        joinAlwaysNull = true;
                    }
                }
            }
        }
        if (joinAlwaysNull) {
            if (joinNonNull) {
                return StampFactory.illegal();
            }
            joinExactType = false;
            joinType = null;
        } else if (joinExactType && Modifier.isAbstract(joinType.getModifiers()) && !joinType.isArray()) {
            return StampFactory.illegal();
        }
        if (joinType == type && joinExactType == exactType && joinNonNull == nonNull && joinAlwaysNull == alwaysNull) {
            return this;
        } else if (joinType == other.type && joinExactType == other.exactType && joinNonNull == other.nonNull && joinAlwaysNull == other.alwaysNull) {
            return other;
        } else {
            return new ObjectStamp(joinType, joinExactType, joinNonNull, joinAlwaysNull);
        }
    }

    private static ResolvedJavaType meetTypes(ResolvedJavaType a, ResolvedJavaType b) {
        if (a == b) {
            return a;
        } else if (a == null || b == null) {
            return null;
        } else {
            return a.findLeastCommonAncestor(b);
        }
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (exactType ? 1231 : 1237);
        result = prime * result + (nonNull ? 1231 : 1237);
        result = prime * result + (alwaysNull ? 1231 : 1237);
        result = prime * result + ((type == null) ? 0 : type.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        ObjectStamp other = (ObjectStamp) obj;
        if (exactType != other.exactType || nonNull != other.nonNull || alwaysNull != other.alwaysNull) {
            return false;
        }
        if (type == null) {
            if (other.type != null) {
                return false;
            }
        } else if (!type.equals(other.type)) {
            return false;
        }
        return true;
    }

    public static boolean isObjectAlwaysNull(ValueNode node) {
        return isObjectAlwaysNull(node.stamp());
    }

    public static boolean isObjectAlwaysNull(Stamp stamp) {
        return (stamp instanceof ObjectStamp && ((ObjectStamp) stamp).alwaysNull());
    }

    public static boolean isObjectNonNull(ValueNode node) {
        return isObjectNonNull(node.stamp());
    }

    public static boolean isObjectNonNull(Stamp stamp) {
        return (stamp instanceof ObjectStamp && ((ObjectStamp) stamp).nonNull());
    }

    public static ResolvedJavaType typeOrNull(ValueNode node) {
        return typeOrNull(node.stamp());
    }

    public static ResolvedJavaType typeOrNull(Stamp stamp) {
        if (stamp instanceof ObjectStamp) {
            return ((ObjectStamp) stamp).type();
        }
        return null;
    }

    public static boolean isExactType(ValueNode node) {
        return isExactType(node.stamp());
    }

    public static boolean isExactType(Stamp stamp) {
        if (stamp instanceof ObjectStamp) {
            return ((ObjectStamp) stamp).isExactType();
        }
        return false;
    }
}
