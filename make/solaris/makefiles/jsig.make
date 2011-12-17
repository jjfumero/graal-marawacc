#
# Copyright (c) 2005, 2011, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.
#  
#

# Rules to build signal interposition library, used by vm.make

# libjsig[_g].so: signal interposition library
JSIG      = jsig
LIBJSIG   = lib$(JSIG).so

JSIG_G    = $(JSIG)$(G_SUFFIX)
LIBJSIG_G = lib$(JSIG_G).so

LIBJSIG_DEBUGINFO   = lib$(JSIG).debuginfo
LIBJSIG_G_DEBUGINFO = lib$(JSIG_G).debuginfo

JSIGSRCDIR = $(GAMMADIR)/src/os/$(Platform_os_family)/vm

DEST_JSIG           = $(JDK_LIBDIR)/$(LIBJSIG)
DEST_JSIG_DEBUGINFO = $(JDK_LIBDIR)/$(LIBJSIG_DEBUGINFO)

LIBJSIG_MAPFILE = $(MAKEFILES_DIR)/mapfile-vers-jsig

LFLAGS_JSIG += $(MAPFLAG:FILENAME=$(LIBJSIG_MAPFILE))

ifdef USE_GCC
LFLAGS_JSIG += -D_REENTRANT
else
LFLAGS_JSIG += -mt -xnolib
endif

$(LIBJSIG): $(JSIGSRCDIR)/jsig.c $(LIBJSIG_MAPFILE)
	@echo Making signal interposition lib...
	$(QUIETLY) $(CC) $(SYMFLAG) $(ARCHFLAG) $(SHARED_FLAG) $(PICFLAG) \
                         $(LFLAGS_JSIG) -o $@ $< -ldl
	[ -f $(LIBJSIG_G) ] || { ln -s $@ $(LIBJSIG_G); }
ifneq ($(OBJCOPY),)
	$(QUIETLY) $(OBJCOPY) --only-keep-debug $@ $(LIBJSIG_DEBUGINFO)
	$(QUIETLY) $(OBJCOPY) --add-gnu-debuglink=$(LIBJSIG_DEBUGINFO) $@
  ifeq ($(STRIP_POLICY),all_strip)
	$(QUIETLY) $(STRIP) $@
  else
    ifeq ($(STRIP_POLICY),min_strip)
	$(QUIETLY) $(STRIP) -x $@
    # implied else here is no stripping at all
    endif
  endif
	[ -f $(LIBJSIG_G_DEBUGINFO) ] || { ln -s $(LIBJSIG_DEBUGINFO) $(LIBJSIG_G_DEBUGINFO); }
endif

install_jsig: $(LIBJSIG)
	@echo "Copying $(LIBJSIG) to $(DEST_JSIG)"
	$(QUIETLY) test -f $(LIBJSIG_DEBUGINFO) && \
	    cp -f $(LIBJSIG_DEBUGINFO) $(DEST_JSIG_DEBUGINFO)
	$(QUIETLY) cp -f $(LIBJSIG) $(DEST_JSIG) && echo "Done"

.PHONY: install_jsig
