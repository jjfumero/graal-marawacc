/*
 * Copyright (c) 2014, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.lir;

import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.*;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.lir.gen.*;

/**
 * A FrameMapBuilder that records allocation.
 */
public class DelayedFrameMapBuilder implements FrameMapBuilder {

    @FunctionalInterface
    public interface FrameMapFactory {
        FrameMap newFrameMap(FrameMapBuilder frameMapBuilder);
    }

    private final RegisterConfig registerConfig;
    private final CodeCacheProvider codeCache;
    private final FrameMapFactory factory;
    private final List<TrackedVirtualStackSlot> stackSlots;
    private final List<CallingConvention> calls;

    public DelayedFrameMapBuilder(FrameMapFactory factory, CodeCacheProvider codeCache, RegisterConfig registerConfig) {
        this.registerConfig = registerConfig == null ? codeCache.getRegisterConfig() : registerConfig;
        this.codeCache = codeCache;
        this.factory = factory;
        this.stackSlots = new ArrayList<>();
        this.calls = new ArrayList<>();
    }

    private Set<VirtualStackSlot> freedSlots;

    public VirtualStackSlot allocateSpillSlot(LIRKind kind) {
        if (freedSlots != null) {
            for (Iterator<VirtualStackSlot> iter = freedSlots.iterator(); iter.hasNext();) {
                VirtualStackSlot s = iter.next();
                if (s.getLIRKind().equals(kind)) {
                    iter.remove();
                    if (freedSlots.isEmpty()) {
                        freedSlots = null;
                    }
                    return s;
                }
            }
        }
        SimpleVirtualStackSlot slot = new SimpleVirtualStackSlot(kind);
        stackSlots.add(slot);
        return slot;
    }

    public abstract class TrackedVirtualStackSlot extends VirtualStackSlot {
        /**
         *
         */
        private static final long serialVersionUID = 408446797222290182L;

        public TrackedVirtualStackSlot(LIRKind lirKind) {
            super(lirKind);
        }

        public abstract StackSlot transform(FrameMap frameMap);
    }

    private class SimpleVirtualStackSlot extends TrackedVirtualStackSlot {

        private static final long serialVersionUID = 7654295701165421750L;

        public SimpleVirtualStackSlot(LIRKind lirKind) {
            super(lirKind);
        }

        @Override
        public StackSlot transform(FrameMap frameMap) {
            int size = frameMap.spillSlotSize(getLIRKind());
            frameMap.spillSize = NumUtil.roundUp(frameMap.spillSize + size, size);
            return frameMap.allocateNewSpillSlot(getLIRKind(), 0);
        }

    }

    private class VirtualStackSlotRange extends TrackedVirtualStackSlot {

        private static final long serialVersionUID = 5152592950118317121L;
        private final BitSet objects;
        private final int slots;

        public VirtualStackSlotRange(int slots, BitSet objects) {
            super(LIRKind.reference(Kind.Object));
            this.slots = slots;
            this.objects = (BitSet) objects.clone();
        }

        @Override
        public StackSlot transform(FrameMap frameMap) {
            frameMap.spillSize += (slots * frameMap.getTarget().wordSize);

            if (!objects.isEmpty()) {
                assert objects.length() <= slots;
                StackSlot result = null;
                for (int slotIndex = 0; slotIndex < slots; slotIndex++) {
                    StackSlot objectSlot = null;
                    if (objects.get(slotIndex)) {
                        objectSlot = frameMap.allocateNewSpillSlot(LIRKind.reference(Kind.Object), slotIndex * frameMap.getTarget().wordSize);
                        frameMap.addObjectStackSlot(objectSlot);
                    }
                    if (slotIndex == 0) {
                        if (objectSlot != null) {
                            result = objectSlot;
                        } else {
                            result = frameMap.allocateNewSpillSlot(LIRKind.value(frameMap.getTarget().wordKind), 0);
                        }
                    }
                }
                assert result != null;
                return result;

            } else {
                return frameMap.allocateNewSpillSlot(LIRKind.value(frameMap.getTarget().wordKind), 0);
            }
        }

    }

    public VirtualStackSlot allocateStackSlots(int slots, BitSet objects, List<VirtualStackSlot> outObjectStackSlots) {
        if (slots == 0) {
            return null;
        }
        if (outObjectStackSlots != null) {
            throw GraalInternalError.unimplemented();
        }
        VirtualStackSlotRange slot = new VirtualStackSlotRange(slots, objects);
        stackSlots.add(slot);
        return slot;
    }

    public RegisterConfig getRegisterConfig() {
        return registerConfig;
    }

    public CodeCacheProvider getCodeCache() {
        return codeCache;
    }

    public void freeSpillSlot(VirtualStackSlot slot) {
        if (freedSlots == null) {
            freedSlots = new HashSet<>();
        }
        freedSlots.add(slot);
    }

    public void callsMethod(CallingConvention cc) {
        calls.add(cc);
    }

    public FrameMap buildFrameMap(LIRGenerationResult res) {
        FrameMap frameMap = factory.newFrameMap(this);
        HashMap<VirtualStackSlot, StackSlot> mapping = new HashMap<>();
        // fill
        mapStackSlots(frameMap, mapping);
        for (CallingConvention cc : calls) {
            frameMap.callsMethod(cc);
        }
        // end fill
        if (freedSlots != null) {
            // If the freed slots cover the complete spill area (except for the return
            // address slot), then the spill size is reset to its initial value.
            // Without this, frameNeedsAllocating() would never return true.
            int total = 0;
            for (VirtualStackSlot s : freedSlots) {
                total += frameMap.getTarget().getSizeInBytes(s.getKind());
            }
            if (total == frameMap.spillSize - frameMap.initialSpillSize) {
                // reset spill area size
                frameMap.spillSize = frameMap.initialSpillSize;
            }
            freedSlots = null;
        }
        frameMap.finish();
        return frameMap;
    }

    protected void mapStackSlots(FrameMap frameMap, HashMap<VirtualStackSlot, StackSlot> mapping) {
        for (TrackedVirtualStackSlot virtualSlot : stackSlots) {
            StackSlot slot = virtualSlot.transform(frameMap);
            mapping.put(virtualSlot, slot);
        }
    }

}
