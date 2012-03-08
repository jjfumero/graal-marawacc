/*
 * Copyright (c) 2009, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.alloc.util;

import static com.oracle.max.cri.ci.CiValueUtil.*;
import static com.oracle.graal.alloc.util.LocationUtil.*;

import java.util.*;

import com.oracle.max.cri.ci.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.lir.*;

public abstract class MoveResolver {
    private final LIR lir;
    private final FrameMap frameMap;
    private final int[] registersBlocked;
    private final Map<CiValue, Integer> valuesBlocked;
    private final List<CiValue> mappingFrom;
    private final List<Location> mappingTo;
    private final LIRInsertionBuffer insertionBuffer;
    private int insertPos;

    public MoveResolver(LIR lir, FrameMap frameMap) {
        this.lir = lir;
        this.frameMap = frameMap;

        registersBlocked = new int[frameMap.target.arch.registers.length];
        valuesBlocked = new HashMap<>();

        mappingFrom = new ArrayList<>();
        mappingTo = new ArrayList<>();
        insertionBuffer = new LIRInsertionBuffer();
        insertPos = -1;

        assert checkEmpty();
    }

    public void init(List<LIRInstruction> newInsertList, int newInsertPos) {
        assert checkEmpty();

        if (insertionBuffer.lirList() != newInsertList) {
            // Block changed, so append insertionBuffer because it is bound to a specific block
            finish();
            insertionBuffer.init(newInsertList);
        }
        insertPos = newInsertPos;

        assert checkValid();
    }

    public void add(CiValue from, Location to) {
        assert checkValid();
        assert isLocation(from) || isConstant(from);
        assert from != to;

        Debug.log("mr    add mapping from %s to %s", from, to);
        mappingFrom.add(from);
        mappingTo.add(to);

        assert checkValid();
    }

    public boolean hasMappings() {
        return mappingFrom.size() > 0;
    }

    public void resolve() {
        assert checkValid();

        if (mappingFrom.size() == 1) {
            // If there is only one mapping, it is trivial that this mapping is safe to resolve.
            Debug.log("mr    resolve  mappings: %d", mappingFrom.size());
            insertMove(mappingFrom.get(0), mappingTo.get(0));
            mappingFrom.remove(0);
            mappingTo.remove(0);
        } else if (mappingFrom.size() > 1) {
            Debug.log("mr    resolve  mappings: %d", mappingFrom.size());
            doResolve();
        }
        insertPos = -1;

        assert checkEmpty();
    }

    public void finish() {
        assert checkEmpty();

        if (insertionBuffer.initialized()) {
            insertionBuffer.finish();
        }

        assert !insertionBuffer.initialized() : "must be uninitialized now";
        assert checkEmpty();
    }


    private void doResolve() {
        // Block all registers and stack slots that are used as inputs of a move.
        // When a register is blocked, no move to this register is emitted.
        // This is necessary for detecting cycles in moves.
        for (CiValue from : mappingFrom) {
            block(from);
        }

        while (mappingFrom.size() > 0) {
            boolean processed = false;
            for (int i = mappingFrom.size() - 1; i >= 0; i--) {
                CiValue from = mappingFrom.get(i);
                Location to = mappingTo.get(i);

                if (safeToProcessMove(from, to)) {
                    insertMove(from, to);
                    unblock(from);
                    mappingFrom.remove(i);
                    mappingTo.remove(i);
                    processed = true;
                }
            }

            if (!processed) {
                // No move could be processed because there is a cycle in the move list
                // (e.g., r1 -> r2, r2 -> r1), so one location must be spilled.
                spill();
            }
        }
    }

    private void spill() {
        Location spillCandidate = null;
        int exchangeCandidate = -1;
        int exchangeOther = -1;

        for (int i = mappingFrom.size() - 1; i >= 0; i--) {
            CiValue from = mappingFrom.get(i);
            Location to = mappingTo.get(i);
            assert !safeToProcessMove(from, to) : "would not be in this code otherwise";

            if (isConstant(from)) {
                continue;
            }
            CiValue fromLoc = asLocation(from).location;

            // Check if we can insert an exchange to save us from spilling.
            if (isRegister(fromLoc) && isRegister(to) && asRegister(fromLoc) != asRegister(to) && blockedCount(to) == 1) {
                for (int j = mappingFrom.size() - 1; j >= 0; j--) {
                    CiValue possibleOther = mappingFrom.get(j);
                    if (isLocation(possibleOther)) {
                        if (asLocation(possibleOther).location == to.location) {
                            assert exchangeCandidate == -1 : "must not find twice because of blocked check above";
                            exchangeCandidate = i;
                            exchangeOther = j;
                        } else if (i != j && asLocation(possibleOther).location == fromLoc) {
                            // From is read multiple times, so exchange would be too complicated.
                            exchangeCandidate = -1;
                            break;
                        }
                    }
                }
            }

            if (exchangeCandidate != -1) {
                // Already found a result, no need to search further
                break;
            }
            if (spillCandidate == null || isStackSlot(spillCandidate.location)) {
                // this interval cannot be processed now because target is not free
                spillCandidate = asLocation(from);
            }
        }

        if (exchangeCandidate != -1) {
            Location from = asLocation(mappingFrom.get(exchangeCandidate));
            Location to = mappingTo.get(exchangeCandidate);
            Location other = asLocation(mappingFrom.get(exchangeOther));

            Location newOther = new Location(other.variable, from.variable);
            mappingFrom.set(exchangeOther, newOther);

            insertExchange(newOther, to);
            unblock(to);
            mappingFrom.remove(exchangeCandidate);
            mappingTo.remove(exchangeCandidate);

        } else {
            assert spillCandidate != null : "no location for spilling found";

            Location spillLocation = new Location(spillCandidate.variable, frameMap.allocateSpillSlot(spillCandidate.kind));
            insertMove(spillCandidate, spillLocation);

            for (int i = mappingFrom.size() - 1; i >= 0; i--) {
                if (mappingFrom.get(i) == spillCandidate) {
                    mappingFrom.set(i, spillLocation);
                    unblock(spillCandidate);
                    block(spillLocation);
                }
            }
            assert blockedCount(spillCandidate) == 0 : "register must be unblocked after spilling";
        }
    }

    private void block(CiValue value) {
        if (isLocation(value)) {
            CiValue location = asLocation(value).location;
            if (isRegister(location)) {
                registersBlocked[asRegister(location).number]++;
            } else {
                Integer count = valuesBlocked.get(location);
                valuesBlocked.put(location, count == null ? 1 : count + 1);
            }
        }
    }

    private void unblock(CiValue value) {
        if (isLocation(value)) {
            assert blockedCount(asLocation(value)) > 0;
            CiValue location = asLocation(value).location;
            if (isRegister(location)) {
                registersBlocked[asRegister(location).number]--;
            } else {
                Integer count = valuesBlocked.remove(location);
                if (count > 1) {
                    valuesBlocked.put(location, count - 1);
                }
            }
        }
    }

    private int blockedCount(Location value) {
        CiValue location = asLocation(value).location;
        if (isRegister(location)) {
            return registersBlocked[asRegister(location).number];
        } else {
            Integer count = valuesBlocked.get(location);
            return count == null ? 0 : count;
        }
    }

    private boolean safeToProcessMove(CiValue from, Location to) {
        int count = blockedCount(to);
        return count == 0 || (count == 1 && isLocation(from) && asLocation(from).location == to.location);
    }

    private void insertExchange(Location from, Location to) {
        Debug.log("mr      XCHG %s, %s", from, to);
        // TODO (cwimmer) create XCHG instruction and use it here
        insertionBuffer.append(insertPos, null);
        throw GraalInternalError.unimplemented();
    }

    private void insertMove(CiValue src, Location dst) {
        if (isStackSlot(dst.location) && isLocation(src) && isStackSlot(asLocation(src).location)) {
            // Move between two stack slots. We need a temporary registers. If the allocator can give
            // us a free register, we need two moves: src->scratch, scratch->dst
            // If the allocator cannot give us a free register (it returns a Location in this case),
            // we need to spill the scratch register first, so we need four moves in total.

            CiValue scratch = scratchRegister(dst.variable);

            Location scratchSaved = null;
            CiValue scratchRegister = scratch;
            if (isLocation(scratch)) {
                scratchSaved = new Location(asLocation(scratch).variable, frameMap.allocateSpillSlot(scratch.kind));
                insertMove(scratch, scratchSaved);
                scratchRegister = asLocation(scratch).location;
            }
            assert isRegister(scratchRegister);

            Location scratchLocation = new Location(dst.variable, scratchRegister);
            insertMove(src, scratchLocation);
            insertMove(scratchLocation, dst);

            if (scratchSaved != null) {
                insertMove(scratchSaved, asLocation(scratch));
            }

        } else {
            Debug.log("mr      MOV %s -> %s", src, dst);
            insertionBuffer.append(insertPos, lir.spillMoveFactory.createMove(dst,  src));
        }
    }

    /**
     * Provides a register that can be used by the move resolver. If the returned value is a
     * {@link CiRegisterValue}, the register can be overwritten without precautions. If the
     * returned value is a {@link Location}, it needs to be spilled and rescued itself.
     */
    protected abstract CiValue scratchRegister(Variable spilled);

    private boolean checkEmpty() {
        assert insertPos == -1;
        assert mappingFrom.size() == 0 && mappingTo.size() == 0;
        for (int registerBlocked : registersBlocked) {
            assert registerBlocked == 0;
        }
        assert valuesBlocked.size() == 0;
        return true;
    }

    private boolean checkValid() {
        assert insertPos != -1;
        for (int registerBlocked : registersBlocked) {
            assert registerBlocked == 0;
        }
        assert mappingFrom.size() == mappingTo.size();
        assert insertionBuffer.initialized() && insertPos != -1;

        for (int i = 0; i < mappingTo.size(); i++) {
            CiValue from = mappingFrom.get(i);
            Location to = mappingTo.get(i);

            assert from.kind.stackKind() == to.kind;

            for (int j = i + 1; j < mappingTo.size(); j++) {
                Location otherTo = mappingTo.get(j);
                assert to != otherTo && to.variable != otherTo.variable && to.location != otherTo.location : "Cannot write to same location twice";
            }
        }
        return true;
    }
}
