/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.truffle;

import java.util.concurrent.atomic.*;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.impl.*;
import com.oracle.truffle.api.nodes.*;
import com.oracle.truffle.api.nodes.NodeInfo.Kind;

/**
 * Call target that is optimized by Graal upon surpassing a specific invocation threshold.
 */
abstract class OptimizedCallNode extends DefaultCallNode {

    protected int callCount;

    private OptimizedCallNode(OptimizedCallTarget target) {
        super(target);
    }

    @Override
    public final boolean isInlinable() {
        return true;
    }

    @Override
    public final boolean isSplittable() {
        return getCallTarget().getRootNode().isSplittable();
    }

    @Override
    public final OptimizedCallTarget getCallTarget() {
        return (OptimizedCallTarget) super.getCallTarget();
    }

    public final int getCallCount() {
        return callCount;
    }

    public TruffleInliningProfile createInliningProfile(OptimizedCallTarget target) {
        return new OptimizedCallNodeProfile(target, this);
    }

    @Override
    public OptimizedCallTarget getSplitCallTarget() {
        return null;
    }

    protected OptimizedCallNode inlineImpl() {
        if (getParent() == null) {
            throw new IllegalStateException("CallNode must be adopted before it is split.");
        }

        return replace(new InlinedOptimizedCallNode(getCallTarget(), getSplitCallTarget(), getExecutedCallTarget().getRootNode(), callCount));
    }

    public final OptimizedCallTarget getExecutedCallTarget() {
        return getSplitCallTarget() != null ? getSplitCallTarget() : getCallTarget();
    }

    public static OptimizedCallNode create(OptimizedCallTarget target) {
        return new DefaultOptimizedCallNode(target);
    }

    private static final class DefaultOptimizedCallNode extends OptimizedCallNode {

        private boolean splitTried;

        DefaultOptimizedCallNode(OptimizedCallTarget target) {
            super(target);
        }

        @Override
        public Object call(PackedFrame caller, Arguments arguments) {
            if (CompilerDirectives.inInterpreter()) {
                callCount++;
                if (!splitTried) {
                    return trySplit(caller, arguments);
                }
            }
            return callTarget.call(caller, arguments);
        }

        private Object trySplit(PackedFrame caller, Arguments arguments) {
            int effectiveCallCount = callCount;
            // we try splitting for the first two invocations
            if (effectiveCallCount <= 3) {
                if (isSplittable() && shouldSplit()) {
                    return splitImpl(true).call(caller, arguments);
                }
                if (effectiveCallCount == 3) {
                    splitTried = true;
                }
            }
            return callTarget.call(caller, arguments);
        }

        private boolean shouldSplit() {
            if (!TruffleCompilerOptions.TruffleSplittingEnabled.getValue()) {
                return false;
            }

            int nodeCount = NodeUtil.countNodes(getCallTarget().getRootNode(), null, false);

            // max one child call and callCount > 2 and kind of small number of nodes
            if (callCount > 2 && isCallMethod()) {
                if (nodeCount <= 100) {
                    return true;
                }
            }

            if (nodeCount > TruffleCompilerOptions.TruffleSplittingMaxCalleeSize.getValue()) {
                return false;
            }
            return countPolymorphic() > 1 || countGeneric() > 0;
        }

        private boolean isCallMethod() {
            final AtomicInteger count = new AtomicInteger(0);
            getExecutedCallTarget().getRootNode().accept(new NodeVisitor() {

                public boolean visit(Node node) {
                    if (node instanceof CallNode) {
                        return count.incrementAndGet() > 1;
                    }
                    return true;
                }
            });
            return count.get() <= 1;
        }

        private int countPolymorphic() {
            return NodeUtil.countNodes(getCallTarget().getRootNode(), null, Kind.POLYMORPHIC, true);
        }

        private int countGeneric() {
            return NodeUtil.countNodes(getCallTarget().getRootNode(), null, Kind.GENERIC, true);
        }

        @Override
        public boolean isInlined() {
            return false;
        }

        @Override
        public boolean split() {
            if (!isSplittable()) {
                // split is only allowed once and if the root node supports it
                return false;
            }
            if (getParent() == null) {
                throw new IllegalStateException("CallNode must be adopted before it is split.");
            }
            splitImpl(false);
            return true;
        }

        private OptimizedCallNode splitImpl(boolean heuristic) {
            RootNode splittedRoot = getCallTarget().getRootNode().split();
            OptimizedCallTarget splitCallTarget = (OptimizedCallTarget) Truffle.getRuntime().createCallTarget(splittedRoot);
            splitCallTarget.setSplitSource(getCallTarget());
            if (heuristic) {
                OptimizedCallTarget.logSplit(this, getCallTarget(), splitCallTarget);
            }
            return replace(new SplitOptimizedCallNode(getCallTarget(), splitCallTarget, callCount));
        }

        @Override
        public void inline() {
            inlineImpl();
        }

        @Override
        public OptimizedCallTarget getSplitCallTarget() {
            return null;
        }

    }

    private static final class InlinedOptimizedCallNode extends OptimizedCallNode {

        private final RootNode inlinedRoot;
        private final OptimizedCallTarget splittedTarget;

        public InlinedOptimizedCallNode(OptimizedCallTarget target, OptimizedCallTarget splittedTarget, RootNode inlinedRoot, int callCount) {
            super(target);
            this.inlinedRoot = inlinedRoot;
            this.splittedTarget = splittedTarget;
            this.callCount = callCount;
            installParentInlinedCall();
        }

        @Override
        public Object call(PackedFrame caller, Arguments arguments) {
            if (CompilerDirectives.inInterpreter()) {
                callCount++;
            }
            return inlinedRoot.execute(Truffle.getRuntime().createVirtualFrame(caller, arguments, inlinedRoot.getFrameDescriptor()));
        }

        @Override
        public void inline() {
        }

        @Override
        public boolean split() {
            return false;
        }

        @Override
        public boolean isInlined() {
            return true;
        }

        @Override
        public RootNode getInlinedRoot() {
            return inlinedRoot;
        }

        @Override
        public OptimizedCallTarget getSplitCallTarget() {
            return splittedTarget;
        }
    }

    private static class SplitOptimizedCallNode extends OptimizedCallNode {

        private final OptimizedCallTarget splittedTarget;

        public SplitOptimizedCallNode(OptimizedCallTarget target, OptimizedCallTarget splittedTarget, int callCount) {
            super(target);
            this.callCount = callCount;
            this.splittedTarget = splittedTarget;
        }

        @Override
        public Object call(PackedFrame caller, Arguments arguments) {
            if (CompilerDirectives.inInterpreter()) {
                callCount++;
            }
            return splittedTarget.call(caller, arguments);
        }

        @Override
        public boolean isInlined() {
            return false;
        }

        @Override
        public final boolean split() {
            return false;
        }

        @Override
        public void inline() {
            inlineImpl();
        }

        @Override
        public final OptimizedCallTarget getSplitCallTarget() {
            return splittedTarget;
        }

    }

}
