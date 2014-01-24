/*
 * Copyright (c) 2012, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package com.oracle.truffle.api.nodes;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.*;

/**
 * A root node is a node with a method to execute it given only a frame as a parameter. Therefore, a
 * root node can be used to create a call target using
 * {@link TruffleRuntime#createCallTarget(RootNode)}.
 */
public abstract class RootNode extends Node {

    private CallTarget callTarget;
    private final FrameDescriptor frameDescriptor;

    protected RootNode() {
        this(null, null);
    }

    protected RootNode(SourceSection sourceSection) {
        this(sourceSection, null);
    }

    protected RootNode(SourceSection sourceSection, FrameDescriptor frameDescriptor) {
        super(sourceSection);
        if (frameDescriptor == null) {
            this.frameDescriptor = new FrameDescriptor();
        } else {
            this.frameDescriptor = frameDescriptor;
        }
    }

    /**
     * Creates a copy of the current {@link RootNode} for use as inlined AST. The default
     * implementation copies this {@link RootNode} and all its children recursively. It is
     * recommended to override this method to provide an implementation that copies an uninitialized
     * version of this AST. An uninitialized version of an AST was usually never executed which
     * means that it has not yet collected any profiling feedback. Please note that changes in the
     * behavior of this method might also require changes in {@link #getInlineNodeCount()}.
     * 
     * @see RootNode#getInlineNodeCount()
     * @see RootNode#isInlinable()
     * 
     * @return the copied RootNode for inlining
     * @throws UnsupportedOperationException if {@link #isInlinable()} returns false
     */
    public RootNode inline() {
        if (!isInlinable()) {
            throw new UnsupportedOperationException("Inlining is not enabled.");
        }
        return NodeUtil.cloneNode(this);
    }

    /**
     * Returns the number of nodes that would be returned if {@link #inline()} would get invoked.
     * This node count may be used for the calculation of a smart inlining heuristic.
     * 
     * @see RootNode#inline()
     * @see RootNode#isInlinable()
     * 
     * @return the number of nodes that will get inlined
     * @throws UnsupportedOperationException if {@link #isInlinable()} returns false
     */
    public int getInlineNodeCount() {
        if (!isInlinable()) {
            throw new UnsupportedOperationException("Inlining is not enabled.");
        }
        return NodeUtil.countNodes(this);
    }

    /**
     * Returns true if this RootNode can be inlined. If this method returns true proper
     * implementations of {@link #inline()} and {@link #getInlineNodeCount()} must be provided.
     * Returns true by default.
     * 
     * @see RootNode#inline()
     * @see RootNode#getInlineNodeCount()
     * 
     * @return true if this RootNode can be inlined
     */
    public boolean isInlinable() {
        return true;
    }

    /**
     * Executes this function using the specified frame and returns the result value.
     * 
     * @param frame the frame of the currently executing guest language method
     * @return the value of the execution
     */
    public abstract Object execute(VirtualFrame frame);

    public CallTarget getCallTarget() {
        return callTarget;
    }

    public final FrameDescriptor getFrameDescriptor() {
        return frameDescriptor;
    }

    public void setCallTarget(CallTarget callTarget) {
        this.callTarget = callTarget;
    }
}
