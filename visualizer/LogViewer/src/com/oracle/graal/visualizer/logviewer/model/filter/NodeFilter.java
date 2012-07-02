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

package com.oracle.graal.visualizer.logviewer.model.filter;

import com.oracle.graal.visualizer.logviewer.model.LogLine;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class NodeFilter implements Filter {

	private Pattern p;
	private Integer nodeNum;
	private boolean active = true;
	
	@Override
	public void setConstraints(Object ... constraints) {
        this.nodeNum = null;
        this.p = null;
		for(Object constraint : constraints) {
			setConstraint(constraint);
		}
	}
	
	private void setConstraint(Object constraint) {
		if(constraint instanceof String) {
		
			if(((String)constraint).trim().length() > 0) {
				this.p = Pattern.compile((String)constraint);
			}
			else {
				this.p = null;
			}
		}
		else if(constraint instanceof Integer) {
			this.nodeNum = (Integer)constraint;
		}
		
	}

	@Override
	public boolean keep(LogLine line) {
		if(p == null && nodeNum == null) return true; 
		
		if(line.getNode() == null) return false;
		
		boolean keep = false;
		
		if(p != null) {
			Matcher matcher = p.matcher(line.getNode().getName());
			keep = keep || matcher.find();
		}
		if(nodeNum != null) {
			keep = keep || nodeNum.equals(line.getNode().getNumber());
		}
		
		return keep;		
	}

	@Override
	public void setActive(boolean active) {
		this.active = active;
	}

	@Override
	public boolean isActive() {
		return active;
	}

}
