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
 */
package com.oracle.graal.hotspot;

import java.nio.*;
import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.code.CompilationResult.CodeAnnotation;
import com.oracle.graal.api.code.CompilationResult.CodeComment;
import com.oracle.graal.api.code.CompilationResult.Data;
import com.oracle.graal.api.code.CompilationResult.DataPatch;
import com.oracle.graal.api.code.CompilationResult.ExceptionHandler;
import com.oracle.graal.api.code.CompilationResult.JumpTable;
import com.oracle.graal.api.code.CompilationResult.Mark;
import com.oracle.graal.api.code.CompilationResult.Site;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.*;

/**
 * A {@link CompilationResult} with additional HotSpot-specific information required for installing
 * the code in HotSpot's code cache.
 */
public abstract class HotSpotCompiledCode extends CompilerObject {

    private static final long serialVersionUID = 7807321392203253218L;
    public final CompilationResult comp;

    public final Site[] sites;
    public final ExceptionHandler[] exceptionHandlers;
    public final Comment[] comments;

    public final DataSection dataSection;

    public static final class HotSpotData extends Data {

        public int offset;
        public Constant constant;

        public HotSpotData(int offset) {
            super(0, 0);
            this.offset = offset;
        }

        @Override
        public void emit(ByteBuffer buffer) {
        }
    }

    public static final class DataSection {

        public final int sectionAlignment;
        public final byte[] data;
        public final HotSpotData[] patches;

        public DataSection(Site[] sites) {
            int size = 0;
            int patchCount = 0;
            List<DataPatch> externalDataList = new ArrayList<>();
            for (Site site : sites) {
                if (site instanceof DataPatch) {
                    DataPatch dataPatch = (DataPatch) site;
                    if (dataPatch.externalData != null) {
                        Data d = dataPatch.externalData;
                        size = NumUtil.roundUp(size, d.alignment);
                        size += d.size;
                        externalDataList.add(dataPatch);
                        if (dataPatch.getConstant() != null && dataPatch.getConstant().getKind() == Kind.Object) {
                            patchCount++;
                        }
                    }
                }
            }

            data = new byte[size];
            patches = new HotSpotData[patchCount];
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());
            int index = 0;
            int patchIndex = 0;
            int alignment = 0;
            for (DataPatch dataPatch : externalDataList) {
                Data d = dataPatch.externalData;

                alignment = Math.max(alignment, d.alignment);
                index = NumUtil.roundUp(index, d.alignment);
                buffer.position(index);

                HotSpotData hsData = new HotSpotData(index);
                if (dataPatch.getConstant() != null && dataPatch.getConstant().getKind() == Kind.Object) {
                    hsData.constant = dataPatch.getConstant();
                    patches[patchIndex++] = hsData;
                }
                dataPatch.externalData = hsData;

                index += d.size;
                d.emit(buffer);
            }

            this.sectionAlignment = alignment;
        }
    }

    public static class Comment {

        public final String text;
        public final int pcOffset;

        public Comment(int pcOffset, String text) {
            this.text = text;
            this.pcOffset = pcOffset;
        }
    }

    public HotSpotCompiledCode(CompilationResult compResult) {
        this.comp = compResult;
        sites = getSortedSites(compResult);
        dataSection = new DataSection(sites);
        if (compResult.getExceptionHandlers().isEmpty()) {
            exceptionHandlers = null;
        } else {
            exceptionHandlers = compResult.getExceptionHandlers().toArray(new ExceptionHandler[compResult.getExceptionHandlers().size()]);
        }
        List<CodeAnnotation> annotations = compResult.getAnnotations();
        comments = new Comment[annotations.size()];
        if (!annotations.isEmpty()) {
            for (int i = 0; i < comments.length; i++) {
                CodeAnnotation annotation = annotations.get(i);
                String text;
                if (annotation instanceof CodeComment) {
                    CodeComment codeComment = (CodeComment) annotation;
                    text = codeComment.value;
                } else if (annotation instanceof JumpTable) {
                    JumpTable jumpTable = (JumpTable) annotation;
                    text = "JumpTable [" + jumpTable.low + " .. " + jumpTable.high + "]";
                } else {
                    text = annotation.toString();
                }
                comments[i] = new Comment(annotation.position, text);
            }
        }
    }

    static class SiteComparator implements Comparator<Site> {

        public int compare(Site s1, Site s2) {
            if (s1.pcOffset == s2.pcOffset && (s1 instanceof Mark ^ s2 instanceof Mark)) {
                return s1 instanceof Mark ? -1 : 1;
            }
            return s1.pcOffset - s2.pcOffset;
        }
    }

    private static Site[] getSortedSites(CompilationResult target) {
        List<?>[] lists = new List<?>[]{target.getInfopoints(), target.getDataReferences(), target.getMarks()};
        int count = 0;
        for (List<?> list : lists) {
            count += list.size();
        }
        Site[] result = new Site[count];
        int pos = 0;
        for (List<?> list : lists) {
            for (Object elem : list) {
                result[pos++] = (Site) elem;
            }
        }
        Arrays.sort(result, new SiteComparator());
        return result;
    }
}
