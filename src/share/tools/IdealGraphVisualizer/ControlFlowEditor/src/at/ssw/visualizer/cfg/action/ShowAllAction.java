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
package at.ssw.visualizer.cfg.action;

import at.ssw.visualizer.cfg.graph.CfgScene;
import org.openide.util.HelpCtx;


/**
 * Adjusts the Zoom factor of the Scene to the bounds of Scroll panel 
 * to get a clean view on the whole graph.
 *
 */
public class ShowAllAction extends AbstractCfgEditorAction {
    
    @Override
    public void performAction() {    
        CfgScene tc = getEditor();
        if (tc != null) {
            CfgScene scene = tc;  
            scene.zoomScene();        

        }        
    }
    
    @Override
    public HelpCtx getHelpCtx() {
        return HelpCtx.DEFAULT_HELP;
    }
    
    @Override
    public String getName() {
        return "Fit Scene to Window";
    }
    
    @Override
    protected String iconResource() {
        return "at/ssw/visualizer/cfg/icons/autosize.gif";    
    }
          
}   
