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
 *
 */

package com.oracle.graal.visualizer.logviewer.model.io;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;

public class SeekableFileReader extends Reader {
	
	public static final int DEFAULT_GAP = 1024; // in bytes

	private final FileReader reader;
	private SeekableFile seekableFile;
	private ProgressMonitor monitor;
	private long filelen;
	private int gap;
	
	public SeekableFileReader(File file) throws IOException {
		super(file);
		reader = new FileReader(file);
		seekableFile = new SeekableFile(file);
	}
	
	public SeekableFileReader(File file, ProgressMonitor monitor, int gap) throws IOException {
		this(file);
		this.monitor = monitor;
		this.filelen = file.length();
		this.gap = gap;
	}
	
	public SeekableFileReader(File file, ProgressMonitor monitor) throws IOException {
		this(file, monitor, DEFAULT_GAP);
	}
	
	public SeekableFile getSeekableFile() {
		return seekableFile;
	}

    @Override
	public void close() throws IOException {
		reader.close();		
	}
	
	private long bytes = 0;
	private boolean skipLF = false;
	private long off;
	private long lastGapped = 0;
	private boolean finished = false;

	@Override
	public int read(char[] cbuf, int offset, int length) throws IOException {
		
		int l = reader.read(cbuf, offset, length);
		
		if(l == -1 && !finished) {
			seekableFile.addLine(off, (int)(bytes-off));
			finished = true;
			if(monitor != null) {
				monitor.worked(1);
				monitor.finished();
			}
		}
		
		for(int i = 0; i < l; ++i) {
			Character ch = new Character(cbuf[i]);
			int chlen = ch.toString().getBytes(reader.getEncoding()).length;
				
			if(skipLF && ch != '\n') {
				seekableFile.addLine(off, (int)(bytes-off-chlen));
				off = bytes;
				skipLF = false;
			}
			
			bytes += chlen;
			
			if(monitor != null) {
				if(bytes > lastGapped+gap) {
					lastGapped = bytes;
					monitor.worked(((float)bytes)/((float)filelen));
				}
			}
			
			if(ch == '\r') {
				skipLF = true;
			}
			if(ch == '\n') {
				seekableFile.addLine(off, (int)(bytes-off-(skipLF ? 2*chlen : chlen)));
				off = bytes;
				skipLF = false;
			}
		}
		
		return l;
	}
	
	
}
